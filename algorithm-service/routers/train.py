"""
YOLO 训练路由
提供 YOLO 模型训练的异步任务接口
"""
import asyncio
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

logger = logging.getLogger(__name__)

# 添加 YOLO 训练脚本路径
TRAINING_SCRIPT_PATH = "/root/autodl-fs/web_biaozhupingtai/scripts/train_yolo.py"
TRAINING_OUTPUT_BASE = "/root/autodl-fs/Annotation-Platform/training_runs"
CONDA_ENV = "xingmu_yolo"

router = APIRouter(prefix="/algo/train", tags=["Training"])


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
    """后台执行 YOLO 训练任务 - MOCK 版本"""
    try:
        # 设置任务为运行中
        await task_manager.set_task_running(task_id)
        
        # 模拟耗时
        await asyncio.sleep(3)
        
        # 确保输出目录存在
        output_dir = ensure_training_output_dir(project_id)
        run_name = f"run_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
        
        # MOCK: 创建假的训练输出文件
        run_dir = output_dir / run_name
        run_dir.mkdir(parents=True, exist_ok=True)
        
        weights_dir = run_dir / "weights"
        weights_dir.mkdir(parents=True, exist_ok=True)
        
        # 创建假的模型文件
        (weights_dir / "best.pt").touch()
        (weights_dir / "last.pt").touch()
        
        # 创建假的日志文件
        log_file = output_dir / f"{run_name}_training.log"
        with open(log_file, 'w', encoding='utf-8') as f:
            f.write(f"YOLO Training Log - {run_name}\n")
            f.write(f"Epochs: {epochs}\n")
            f.write(f"Batch Size: {batch_size}\n")
            f.write(f"Image Size: {image_size}\n")
            f.write(f"Model: {model_type}\n")
            f.write(f"Device: {device}\n")
            f.write(f"Training completed successfully\n")
        
        # 保存训练结果
        result = {
            "run_name": run_name,
            "output_dir": str(output_dir),
            "best_model_path": str(weights_dir / "best.pt"),
            "last_model_path": str(weights_dir / "last.pt"),
            "log_file": str(log_file),
            "completed_at": datetime.now().isoformat()
        }
        
        await task_manager.add_task_result(task_id, result)
        await task_manager.set_task_completed(task_id)
        
        logger.info(f"Task {task_id}: YOLO training completed successfully")
        
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
            total_images=0,  # 训练任务不统计图片数
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
    
    return task.to_dict()


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
        raise HTTPException(status_code=404, detail="Log file not found")
    
    log_file = Path(log_file_path)
    if not log_file.exists():
        raise HTTPException(status_code=404, detail="Log file does not exist")
    
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
        raise HTTPException(status_code=400, detail="Task is not running")
    
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
