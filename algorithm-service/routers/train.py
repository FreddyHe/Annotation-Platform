"""
YOLO 训练路由
提供 YOLO 模型训练的异步任务接口
"""
import asyncio
import csv
import json
import logging
import os
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, Any, List, Optional
from uuid import uuid4

from fastapi import APIRouter, BackgroundTasks, HTTPException
from pydantic import BaseModel, Field

from services.task_manager import task_manager, TaskStatus
from services.resource_locks import gpu_lock

logger = logging.getLogger(__name__)

# 添加 YOLO 训练脚本路径
TRAINING_SCRIPT_PATH = "/root/autodl-fs/web_biaozhupingtai/scripts/train_yolo.py"
TRAINING_OUTPUT_BASE = "/root/autodl-fs/Annotation-Platform/training_runs"
CONDA_ENV = "xingmu_yolo"

router = APIRouter(prefix="/algo/train", tags=["Training"])


def _to_float(value):
    if value is None:
        return None
    try:
        if hasattr(value, "detach"):
            value = value.detach().cpu()
        if hasattr(value, "tolist"):
            value = value.tolist()
        if isinstance(value, (list, tuple)):
            return [_to_float(item) for item in value]
        return float(value)
    except Exception:
        return None


def _loss_metrics_from_trainer(trainer, current_epoch: int) -> Dict[str, Any]:
    metrics: Dict[str, Any] = {"current_epoch": current_epoch}
    raw_losses = _to_float(getattr(trainer, "tloss", None))
    if raw_losses is None:
        raw_losses = _to_float(getattr(trainer, "loss_items", None))
    if raw_losses is None:
        return metrics
    if not isinstance(raw_losses, list):
        raw_losses = [raw_losses]

    names = list(getattr(trainer, "loss_names", []) or [])
    normalized = []
    for name in names:
        clean = str(name).replace("train/", "").replace("_loss", "")
        normalized.append(clean)
    if not normalized:
        normalized = ["box", "cls", "dfl"]

    loss_sum = 0.0
    has_loss = False
    for idx, value in enumerate(raw_losses):
        if value is None:
            continue
        key = normalized[idx] if idx < len(normalized) else f"loss_{idx}"
        metrics[f"{key}_loss"] = value
        loss_sum += value
        has_loss = True
    if has_loss:
        metrics["train_loss"] = loss_sum
    return metrics


def _metrics_from_results_csv(results_csv: Path) -> Dict[str, Any]:
    if not results_csv.exists():
        return {}
    try:
        with open(results_csv, newline='', encoding='utf-8') as f:
            rows = list(csv.DictReader(f))
        if not rows:
            return {}
        row = {k.strip(): v for k, v in rows[-1].items()}

        def as_float(key: str):
            value = row.get(key)
            if value is None or value == "":
                return None
            try:
                return float(value)
            except ValueError:
                return None

        metrics: Dict[str, Any] = {}
        epoch = as_float("epoch")
        if epoch is not None:
            metrics["current_epoch"] = int(epoch)

        box_loss = as_float("train/box_loss")
        cls_loss = as_float("train/cls_loss")
        dfl_loss = as_float("train/dfl_loss")
        val_box_loss = as_float("val/box_loss")
        val_cls_loss = as_float("val/cls_loss")
        val_dfl_loss = as_float("val/dfl_loss")

        if box_loss is not None:
            metrics["box_loss"] = box_loss
        if cls_loss is not None:
            metrics["cls_loss"] = cls_loss
        if dfl_loss is not None:
            metrics["dfl_loss"] = dfl_loss

        train_losses = [value for value in [box_loss, cls_loss, dfl_loss] if value is not None]
        val_losses = [value for value in [val_box_loss, val_cls_loss, val_dfl_loss] if value is not None]
        if train_losses:
            metrics["train_loss"] = sum(train_losses)
        if val_losses:
            metrics["val_loss"] = sum(val_losses)

        return metrics
    except Exception as exc:
        logger.debug(f"Failed to parse live training metrics from {results_csv}: {exc}")
        return {}


class YOLOTrainRequest(BaseModel):
    """YOLO 训练请求"""
    project_id: int = Field(..., description="项目ID")
    dataset_path: str = Field(..., description="数据集路径")
    epochs: int = Field(100, ge=10, le=500, description="训练轮数")
    batch_size: int = Field(16, ge=1, le=64, description="批次大小")
    image_size: int = Field(640, ge=320, le=1280, description="图片尺寸")
    model_type: str = Field("yolov8n.pt", description="预训练模型类型")
    device: str = Field("0", description="GPU设备")


class YOLOTrainResponse(BaseModel):
    """YOLO 训练响应"""
    task_id: str
    status: str
    message: str


def ensure_training_output_dir(project_id: int) -> Path:
    """确保训练输出目录存在"""
    output_dir = Path(TRAINING_OUTPUT_BASE) / f"project_{project_id}"
    output_dir.mkdir(parents=True, exist_ok=True)
    return output_dir


def _do_yolo_training_sync(
    task_id: str,
    project_id: int,
    dataset_path: str,
    epochs: int,
    batch_size: int,
    image_size: int,
    model_type: str,
    device: str,
    loop
) -> dict:
    """在线程中同步执行真实 YOLO 训练"""
    from ultralytics import YOLO

    output_dir = ensure_training_output_dir(project_id)
    run_name = f"run_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
    run_dir = output_dir / run_name
    log_file = output_dir / f"{run_name}_training.log"
    results_csv = run_dir / "results.csv"

    data_yaml = str(Path(dataset_path) / "data.yaml")

    # 确保 model_type 以 .pt 结尾
    if not model_type.endswith('.pt'):
        model_type = model_type + '.pt'

    # 自动检测设备：如果请求 GPU 但不可用，回退到 CPU
    import torch
    if device != "cpu" and not torch.cuda.is_available():
        logger.warning(f"Task {task_id}: CUDA not available, falling back to CPU")
        device = "cpu"

    logger.info(f"Task {task_id}: Loading model {model_type}, device={device}")
    model = YOLO(model_type)

    # 注册 epoch 结束回调，实时更新进度
    def on_train_epoch_end(trainer):
        current_epoch = trainer.epoch + 1
        try:
            future = asyncio.run_coroutine_threadsafe(
                task_manager.update_task_progress(
                    task_id,
                    current_epoch,
                    _loss_metrics_from_trainer(trainer, current_epoch)
                ),
                loop
            )
            future.result(timeout=5)
        except Exception:
            pass

    def on_fit_epoch_end(trainer):
        current_epoch = trainer.epoch + 1
        metrics = _loss_metrics_from_trainer(trainer, current_epoch)
        trainer_metrics = getattr(trainer, "metrics", None) or {}
        for key, value in trainer_metrics.items():
            numeric = _to_float(value)
            if numeric is not None:
                metrics[str(key)] = numeric
        try:
            future = asyncio.run_coroutine_threadsafe(
                task_manager.update_task_progress(task_id, current_epoch, metrics),
                loop
            )
            future.result(timeout=5)
        except Exception:
            pass

    model.add_callback("on_train_epoch_end", on_train_epoch_end)
    model.add_callback("on_fit_epoch_end", on_fit_epoch_end)

    logger.info(f"Task {task_id}: Starting YOLO training - data={data_yaml}, "
                f"epochs={epochs}, batch={batch_size}, imgsz={image_size}, device={device}")
    try:
        future = asyncio.run_coroutine_threadsafe(
            task_manager.update_task_progress(
                task_id,
                0,
                {
                    "run_dir": str(run_dir),
                    "results_csv": str(results_csv),
                    "current_epoch": 0
                }
            ),
            loop
        )
        future.result(timeout=5)
    except Exception:
        pass

    # 真实训练
    with gpu_lock:
        results = model.train(
            data=data_yaml,
            epochs=epochs,
            batch=batch_size,
            imgsz=image_size,
            device=device,
            project=str(output_dir),
            name=run_name,
            exist_ok=True,
            verbose=True,
        )

    # 提取输出路径
    best_pt = run_dir / "weights" / "best.pt"
    last_pt = run_dir / "weights" / "last.pt"

    metrics = {}
    if results and hasattr(results, 'results_dict'):
        metrics = dict(results.results_dict)

    if results_csv.exists():
        try:
            with open(results_csv, newline='', encoding='utf-8') as f:
                rows = list(csv.DictReader(f))
                if rows:
                    last_row = {k.strip(): v for k, v in rows[-1].items()}

                    def as_float(key):
                        value = last_row.get(key)
                        if value is None or value == "":
                            return None
                        try:
                            return float(value)
                        except ValueError:
                            return None

                    train_losses = [
                        as_float("train/box_loss"),
                        as_float("train/cls_loss"),
                        as_float("train/dfl_loss"),
                    ]
                    val_losses = [
                        as_float("val/box_loss"),
                        as_float("val/cls_loss"),
                        as_float("val/dfl_loss"),
                    ]
                    train_losses = [v for v in train_losses if v is not None]
                    val_losses = [v for v in val_losses if v is not None]

                    if train_losses:
                        metrics["train/loss"] = sum(train_losses)
                    if val_losses:
                        metrics["val/loss"] = sum(val_losses)
                    for key in [
                        "metrics/precision(B)",
                        "metrics/recall(B)",
                        "metrics/mAP50(B)",
                        "metrics/mAP50-95(B)",
                    ]:
                        value = as_float(key)
                        if value is not None:
                            metrics[key] = value
        except Exception as e:
            logger.warning(f"Task {task_id}: Failed to parse results.csv metrics: {e}")

    # 写入摘要日志
    with open(log_file, 'w', encoding='utf-8') as f:
        f.write(f"YOLO Training Log - {run_name}\n")
        f.write(f"Epochs: {epochs}\n")
        f.write(f"Batch Size: {batch_size}\n")
        f.write(f"Image Size: {image_size}\n")
        f.write(f"Model: {model_type}\n")
        f.write(f"Device: {device}\n\n")
        if results and hasattr(results, 'results_dict'):
            f.write("=== Metrics ===\n")
            for k, v in results.results_dict.items():
                f.write(f"  {k}: {v}\n")
        f.write(f"\nTraining completed successfully at {datetime.now().isoformat()}\n")

    return {
        "run_name": run_name,
        "output_dir": str(output_dir),
        "best_model_path": str(best_pt),
        "last_model_path": str(last_pt),
        "log_file": str(log_file),
        "results_csv": str(results_csv),
        "metrics": metrics,
        "completed_at": datetime.now().isoformat(),
    }


async def run_yolo_training_task(
    task_id: str,
    project_id: int,
    dataset_path: str,
    epochs: int,
    batch_size: int,
    image_size: int,
    model_type: str,
    device: str
):
    """后台执行真实 YOLO 训练任务"""
    try:
        await task_manager.set_task_running(task_id)

        loop = asyncio.get_event_loop()

        result = await asyncio.to_thread(
            _do_yolo_training_sync,
            task_id, project_id, dataset_path,
            epochs, batch_size, image_size,
            model_type, device, loop
        )

        await task_manager.add_task_result(task_id, result)
        await task_manager.set_task_completed(task_id)

        logger.info(f"Task {task_id}: YOLO training completed successfully "
                     f"- best model: {result['best_model_path']}")

    except Exception as e:
        logger.error(f"Task {task_id}: YOLO training failed - {e}", exc_info=True)
        await task_manager.set_task_failed(task_id, str(e))


@router.post("/yolo", response_model=YOLOTrainResponse)
async def start_yolo_training(
    request: YOLOTrainRequest,
    background_tasks: BackgroundTasks
):
    """
    启动 YOLO 训练任务
    
    - **project_id**: 项目ID
    - **dataset_path**: 数据集路径（包含 data.yaml）
    - **epochs**: 训练轮数
    - **batch_size**: 批次大小
    - **image_size**: 图片尺寸
    - **model_type**: 预训练模型类型（yolov8n.pt, yolov8s.pt, etc.）
    - **device**: GPU设备
    """
    try:
        # 验证数据集路径
        dataset_path = Path(request.dataset_path)
        if not dataset_path.exists():
            raise HTTPException(status_code=400, detail=f"Dataset path not found: {request.dataset_path}")
        
        data_yaml = dataset_path / "data.yaml"
        if not data_yaml.exists():
            raise HTTPException(status_code=400, detail=f"data.yaml not found in dataset path")
        
        # 生成任务ID
        task_id = str(uuid4())
        
        # 创建任务
        await task_manager.create_task(
            task_id=task_id,
            task_type="YOLO_TRAINING",
            project_id=request.project_id,
            total_images=request.epochs,  # 用 epochs 作为进度分母
            parameters={
                "dataset_path": request.dataset_path,
                "epochs": request.epochs,
                "batch_size": request.batch_size,
                "image_size": request.image_size,
                "model_type": request.model_type,
                "device": request.device
            }
        )
        
        # 添加后台任务
        background_tasks.add_task(
            run_yolo_training_task,
            task_id,
            request.project_id,
            request.dataset_path,
            request.epochs,
            request.batch_size,
            request.image_size,
            request.model_type,
            request.device
        )
        
        return YOLOTrainResponse(
            task_id=task_id,
            status="RUNNING",
            message="YOLO training task started successfully"
        )
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to start YOLO training: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/status/{task_id}")
async def get_training_status(task_id: str):
    """
    获取训练任务状态
    
    - **task_id**: 任务ID
    """
    task = await task_manager.get_task(task_id)
    
    if not task:
        raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")

    status = task.to_dict()
    results_csv_value = task.metrics.get("results_csv")
    if results_csv_value:
        csv_metrics = _metrics_from_results_csv(Path(results_csv_value))
        if csv_metrics:
            task.metrics.update(csv_metrics)
            csv_epoch = int(csv_metrics.get("current_epoch") or task.processed_images)
            if csv_epoch > task.processed_images:
                task.processed_images = csv_epoch
            status = task.to_dict()

    return status


@router.get("/log/{task_id}")
async def get_training_log(task_id: str, lines: int = 100):
    """
    获取训练日志
    
    - **task_id**: 任务ID
    - **lines**: 返回的日志行数（默认100）
    """
    task = await task_manager.get_task(task_id)
    
    if not task:
        raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
    
    # 从任务结果中获取日志文件路径
    results = await task_manager.get_task_results(task_id)
    log_file_path = None
    
    for result in results:
        if "log_file" in result:
            log_file_path = result["log_file"]
            break
    
    if not log_file_path:
        return {
            "task_id": task_id,
            "log_lines": [],
            "total_lines": 0
        }
    
    log_file = Path(log_file_path)
    if not log_file.exists():
        return {
            "task_id": task_id,
            "log_lines": [],
            "total_lines": 0
        }
    
    # 读取最后 N 行日志
    try:
        with open(log_file, 'r', encoding='utf-8', errors='ignore') as f:
            all_lines = f.readlines()
            recent_lines = all_lines[-lines:] if len(all_lines) > lines else all_lines
        
        return {
            "task_id": task_id,
            "log_lines": [line.strip() for line in recent_lines],
            "total_lines": len(all_lines)
        }
    except Exception as e:
        logger.error(f"Failed to read log file: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Failed to read log file: {str(e)}")


@router.post("/cancel/{task_id}")
async def cancel_training(task_id: str):
    """
    取消训练任务
    
    - **task_id**: 任务ID
    """
    task = await task_manager.get_task(task_id)
    
    if not task:
        raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
    
    if task.status != TaskStatus.RUNNING:
        return {
            "task_id": task_id,
            "status": "CANCELLED",
            "message": f"Task is already {task.status.value}"
        }
    
    # 设置任务为已取消
    await task_manager.set_task_cancelled(task_id)
    
    # TODO: 实际终止训练进程
    # 这里需要保存训练进程的 PID，然后发送 SIGTERM 信号
    
    return {
        "task_id": task_id,
        "status": "CANCELLED",
        "message": "Training task cancelled"
    }


@router.get("/results/{task_id}")
async def get_training_results(task_id: str):
    """
    获取训练结果
    
    - **task_id**: 任务ID
    """
    task = await task_manager.get_task(task_id)
    
    if not task:
        raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
    
    if task.status != TaskStatus.COMPLETED:
        raise HTTPException(status_code=400, detail="Training task is not completed")
    
    results = await task_manager.get_task_results(task_id)
    
    return {
        "task_id": task_id,
        "results": results
    }
