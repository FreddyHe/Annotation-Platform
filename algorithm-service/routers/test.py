"""
YOLO 测试路由
提供 YOLO 模型推理测试的异步任务接口
"""
import asyncio
import base64
import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Dict, Any, List, Optional
from uuid import uuid4

from fastapi import APIRouter, BackgroundTasks, HTTPException, UploadFile, File
from pydantic import BaseModel, Field

from services.task_manager import task_manager, TaskStatus

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/algo/test", tags=["Testing"])


class YOLOTestRequest(BaseModel):
    """YOLO 测试请求"""
    model_path: str = Field(..., description="模型权重路径")
    image_paths: List[str] = Field(..., description="测试图片路径列表")
    conf_threshold: float = Field(0.25, ge=0.0, le=1.0, description="置信度阈值")
    iou_threshold: float = Field(0.45, ge=0.0, le=1.0, description="IOU阈值")
    device: str = Field("0", description="GPU设备")


class YOLOTestResponse(BaseModel):
    """YOLO 测试响应"""
    task_id: str
    status: str
    message: str


class DetectionResult(BaseModel):
    """检测结果"""
    image_path: str
    detections: List[Dict[str, Any]]
    total_detections: int


async def run_yolo_inference_task(
    task_id: str,
    model_path: str,
    image_paths: List[str],
    conf_threshold: float,
    iou_threshold: float,
    device: str
):
    """后台执行 YOLO 推理任务"""
    try:
        # 设置任务为运行中
        await task_manager.set_task_running(task_id)
        
        # 验证模型路径
        model_file = Path(model_path)
        if not model_file.exists():
            raise FileNotFoundError(f"Model file not found: {model_path}")
        
        # 加载模型
        logger.info(f"Task {task_id}: Loading YOLO model from {model_path}")
        
        # 设置环境变量
        import os
        os.environ['CUDA_VISIBLE_DEVICES'] = device
        
        # 导入 YOLO
        from ultralytics import YOLO
        
        model = YOLO(model_path)
        
        # 推理结果
        all_results = []
        total_images = len(image_paths)
        processed_images = 0
        
        for image_path in image_paths:
            try:
                # 验证图片路径
                img_file = Path(image_path)
                if not img_file.exists():
                    logger.warning(f"Task {task_id}: Image not found: {image_path}")
                    continue
                
                # 更新进度
                processed_images += 1
                progress = int((processed_images / total_images) * 100)
                await task_manager.update_task_progress(task_id, progress)
                
                # 推理
                results = model(
                    str(img_file),
                    conf=conf_threshold,
                    iou=iou_threshold,
                    device=device
                )
                
                # 解析结果
                detections = []
                for result in results:
                    boxes = result.boxes
                    if boxes is not None:
                        for box in boxes:
                            # 获取边界框坐标
                            x1, y1, x2, y2 = box.xyxy[0].tolist()
                            conf = box.conf[0].item()
                            cls_id = int(box.cls[0].item())
                            label = model.names[cls_id]
                            
                            detections.append({
                                "label": label,
                                "confidence": float(conf),
                                "bbox": {
                                    "x1": float(x1),
                                    "y1": float(y1),
                                    "x2": float(x2),
                                    "y2": float(y2)
                                },
                                "class_id": cls_id
                            })
                
                all_results.append({
                    "image_path": image_path,
                    "detections": detections,
                    "total_detections": len(detections)
                })
                
                logger.info(f"Task {task_id}: Processed {image_path}, found {len(detections)} detections")
                
            except Exception as e:
                logger.error(f"Task {task_id}: Failed to process {image_path}: {e}")
                all_results.append({
                    "image_path": image_path,
                    "error": str(e),
                    "detections": [],
                    "total_detections": 0
                })
        
        # 保存结果
        await task_manager.add_task_result(task_id, {
            "model_path": model_path,
            "conf_threshold": conf_threshold,
            "iou_threshold": iou_threshold,
            "results": all_results,
            "total_images": total_images,
            "completed_at": datetime.now().isoformat()
        })
        
        await task_manager.set_task_completed(task_id)
        
        logger.info(f"Task {task_id}: YOLO inference completed, processed {total_images} images")
        
    except Exception as e:
        logger.error(f"Task {task_id}: YOLO inference failed - {e}", exc_info=True)
        await task_manager.set_task_failed(task_id, str(e))


@router.post("/yolo", response_model=YOLOTestResponse)
async def start_yolo_test(
    request: YOLOTestRequest,
    background_tasks: BackgroundTasks
):
    """
    启动 YOLO 测试任务
    
    - **model_path**: 模型权重路径（best.pt 或 last.pt）
    - **image_paths**: 测试图片路径列表
    - **conf_threshold**: 置信度阈值
    - **iou_threshold**: IOU阈值
    - **device**: GPU设备
    """
    try:
        # 验证模型路径
        model_file = Path(request.model_path)
        if not model_file.exists():
            raise HTTPException(status_code=400, detail=f"Model file not found: {request.model_path}")
        
        # 验证图片路径
        valid_image_paths = []
        for img_path in request.image_paths:
            img_file = Path(img_path)
            if img_file.exists():
                valid_image_paths.append(img_path)
            else:
                logger.warning(f"Image not found: {img_path}")
        
        if not valid_image_paths:
            raise HTTPException(status_code=400, detail="No valid images found")
        
        # 生成任务ID
        task_id = str(uuid4())
        
        # 创建任务
        await task_manager.create_task(
            task_id=task_id,
            task_type="YOLO_TESTING",
            project_id=0,  # 测试任务不关联项目
            total_images=len(valid_image_paths),
            parameters={
                "model_path": request.model_path,
                "conf_threshold": request.conf_threshold,
                "iou_threshold": request.iou_threshold,
                "device": request.device
            }
        )
        
        # 添加后台任务
        background_tasks.add_task(
            run_yolo_inference_task,
            task_id,
            request.model_path,
            valid_image_paths,
            request.conf_threshold,
            request.iou_threshold,
            request.device
        )
        
        return YOLOTestResponse(
            task_id=task_id,
            status="RUNNING",
            message="YOLO testing task started successfully"
        )
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to start YOLO testing: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/yolo/upload", response_model=YOLOTestResponse)
async def start_yolo_test_with_upload(
    model_path: str,
    conf_threshold: float = 0.25,
    iou_threshold: float = 0.45,
    device: str = "0",
    files: List[UploadFile] = File(...),
    background_tasks: BackgroundTasks = BackgroundTasks()
):
    """
    启动 YOLO 测试任务（上传图片）
    
    - **model_path**: 模型权重路径
    - **conf_threshold**: 置信度阈值
    - **iou_threshold**: IOU阈值
    - **device**: GPU设备
    - **files**: 上传的图片文件
    """
    try:
        # 验证模型路径
        model_file = Path(model_path)
        if not model_file.exists():
            raise HTTPException(status_code=400, detail=f"Model file not found: {model_path}")
        
        # 保存上传的图片
        upload_dir = Path("/root/autodl-fs/Annotation-Platform/temp_uploads")
        upload_dir.mkdir(parents=True, exist_ok=True)
        
        image_paths = []
        for file in files:
            # 生成唯一文件名
            file_ext = Path(file.filename).suffix
            unique_filename = f"{uuid4()}{file_ext}"
            file_path = upload_dir / unique_filename
            
            # 保存文件
            with open(file_path, "wb") as buffer:
                content = await file.read()
                buffer.write(content)
            
            image_paths.append(str(file_path))
        
        if not image_paths:
            raise HTTPException(status_code=400, detail="No images uploaded")
        
        # 生成任务ID
        task_id = str(uuid4())
        
        # 创建任务
        await task_manager.create_task(
            task_id=task_id,
            task_type="YOLO_TESTING",
            project_id=0,
            total_images=len(image_paths),
            parameters={
                "model_path": model_path,
                "conf_threshold": conf_threshold,
                "iou_threshold": iou_threshold,
                "device": device
            }
        )
        
        # 添加后台任务
        background_tasks.add_task(
            run_yolo_inference_task,
            task_id,
            model_path,
            image_paths,
            conf_threshold,
            iou_threshold,
            device
        )
        
        return YOLOTestResponse(
            task_id=task_id,
            status="RUNNING",
            message="YOLO testing task started successfully"
        )
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to start YOLO testing: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/status/{task_id}")
async def get_test_status(task_id: str):
    """
    获取测试任务状态
    
    - **task_id**: 任务ID
    """
    task = await task_manager.get_task(task_id)
    
    if not task:
        raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
    
    return task.to_dict()


@router.get("/results/{task_id}")
async def get_test_results(task_id: str):
    """
    获取测试结果
    
    - **task_id**: 任务ID
    """
    task = await task_manager.get_task(task_id)
    
    if not task:
        raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
    
    if task.status != TaskStatus.COMPLETED:
        raise HTTPException(status_code=400, detail="Testing task is not completed")
    
    results = await task_manager.get_task_results(task_id)
    
    return {
        "task_id": task_id,
        "results": results
    }


@router.get("/results/{task_id}/image/{image_index}")
async def get_test_result_for_image(task_id: str, image_index: int):
    """
    获取指定图片的测试结果
    
    - **task_id**: 任务ID
    - **image_index**: 图片索引
    """
    task = await task_manager.get_task(task_id)
    
    if not task:
        raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
    
    if task.status != TaskStatus.COMPLETED:
        raise HTTPException(status_code=400, detail="Testing task is not completed")
    
    results = await task_manager.get_task_results(task_id)
    
    if not results:
        raise HTTPException(status_code=404, detail="No results found")
    
    # 获取第一个结果
    result_data = results[0]
    all_results = result_data.get("results", [])
    
    if image_index < 0 or image_index >= len(all_results):
        raise HTTPException(status_code=400, detail="Invalid image index")
    
    return all_results[image_index]


@router.post("/cancel/{task_id}")
async def cancel_test(task_id: str):
    """
    取消测试任务
    
    - **task_id**: 任务ID
    """
    task = await task_manager.get_task(task_id)
    
    if not task:
        raise HTTPException(status_code=404, detail=f"Task not found: {task_id}")
    
    if task.status != TaskStatus.RUNNING:
        raise HTTPException(status_code=400, detail="Task is not running")
    
    # 设置任务为已取消
    await task_manager.set_task_cancelled(task_id)
    
    return {
        "task_id": task_id,
        "status": "CANCELLED",
        "message": "Testing task cancelled"
    }
