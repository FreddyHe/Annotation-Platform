from fastapi import APIRouter, HTTPException, BackgroundTasks
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from loguru import logger
import uuid
import asyncio

from services import task_manager, model_service
from config import settings


router = APIRouter()


class YoloDetectRequest(BaseModel):
    project_id: int = Field(..., description="Project ID")
    image_paths: List[str] = Field(..., description="List of image paths")
    model_size: str = Field(default="n", description="Model size: n, s, m, l, x")
    confidence_threshold: float = Field(default=0.5, ge=0.0, le=1.0, description="Confidence threshold")
    iou_threshold: float = Field(default=0.45, ge=0.0, le=1.0, description="IOU threshold for NMS")
    task_id: Optional[str] = Field(default=None, description="Task ID for tracking")


class YoloDetectResponse(BaseModel):
    success: bool
    message: str
    task_id: str
    status: str
    total_images: int
    processed_images: int
    results: Optional[List[Dict[str, Any]]] = None


async def run_yolo_detection_task(
    task_id: str,
    project_id: int,
    image_paths: List[str],
    model_size: str,
    confidence_threshold: float,
    iou_threshold: float
):
    """后台执行 YOLO 检测任务"""
    try:
        # 设置任务为运行中
        await task_manager.set_task_running(task_id)
        
        # 确保模型已加载
        if model_service.yolo_model is None:
            model_service.load_yolo_model(
                model_path=settings.YOLO_MODEL_PATH
            )
        
        total_images = len(image_paths)
        
        # 遍历图片进行检测
        for idx, image_path in enumerate(image_paths):
            # 检查任务是否已取消
            if await task_manager.is_task_cancelled(task_id):
                logger.info(f"Task {task_id}: Cancelled by user")
                await task_manager.set_task_cancelled(task_id)
                return
            
            try:
                # 运行检测
                detections = model_service.run_yolo_detection(
                    image_path=image_path,
                    confidence_threshold=confidence_threshold,
                    iou_threshold=iou_threshold
                )
                
                # 保存结果
                result = {
                    "image_path": image_path,
                    "detections": detections
                }
                await task_manager.add_task_result(task_id, result)
                
                # 更新进度
                await task_manager.update_task_progress(task_id, idx + 1)
                
            except Exception as e:
                logger.error(f"Task {task_id}: Failed to process {image_path} - {e}")
                # 继续处理下一张图片，不中断整个任务
        
        # 设置任务为已完成
        await task_manager.set_task_completed(task_id)
        logger.info(f"Task {task_id}: YOLO detection completed successfully")
        
    except Exception as e:
        logger.error(f"Task {task_id}: YOLO detection failed - {e}", exc_info=True)
        await task_manager.set_task_failed(task_id, str(e))


@router.post("/algo/yolo/detect", response_model=YoloDetectResponse)
async def run_yolo_detection(request: YoloDetectRequest, background_tasks: BackgroundTasks):
    """运行 YOLO 检测（异步后台任务）"""
    logger.info(f"Received YOLO detection request: project_id={request.project_id}, images={len(request.image_paths)}")
    
    # 生成任务 ID
    task_id = request.task_id or str(uuid.uuid4())
    
    # 创建任务
    task = await task_manager.create_task(
        task_id=task_id,
        task_type="YOLO_DETECTION",
        project_id=request.project_id,
        total_images=len(request.image_paths),
        parameters={
            "model_size": request.model_size,
            "confidence_threshold": request.confidence_threshold,
            "iou_threshold": request.iou_threshold
        }
    )
    
    # 添加后台任务
    background_tasks.add_task(
        run_yolo_detection_task,
        task_id,
        request.project_id,
        request.image_paths,
        request.model_size,
        request.confidence_threshold,
        request.iou_threshold
    )
    
    logger.info(f"Task {task_id}: YOLO detection task started in background")
    
    return YoloDetectResponse(
        success=True,
        message="YOLO detection task started successfully",
        task_id=task_id,
        status="RUNNING",
        total_images=len(request.image_paths),
        processed_images=0,
        results=None
    )


@router.get("/algo/yolo/status/{task_id}")
async def get_yolo_task_status(task_id: str):
    """查询 YOLO 任务状态"""
    task = await task_manager.get_task(task_id)
    
    if task is None:
        raise HTTPException(status_code=404, detail=f"Task {task_id} not found")
    
    return task.to_dict()


@router.get("/algo/yolo/results/{task_id}")
async def get_yolo_task_results(task_id: str):
    """获取 YOLO 任务结果"""
    task = await task_manager.get_task(task_id)
    
    if task is None:
        raise HTTPException(status_code=404, detail=f"Task {task_id} not found")
    
    if task.status.value != "completed":
        raise HTTPException(
            status_code=400,
            detail=f"Task {task_id} is not completed yet. Current status: {task.status.value}"
        )
    
    results = await task_manager.get_task_results(task_id)
    
    return {
        "success": True,
        "task_id": task_id,
        "status": task.status.value,
        "total_images": task.total_images,
        "processed_images": task.processed_images,
        "results": results or []
    }


@router.post("/algo/yolo/cancel/{task_id}")
async def cancel_yolo_task(task_id: str):
    """取消 YOLO 任务"""
    task = await task_manager.get_task(task_id)
    
    if task is None:
        raise HTTPException(status_code=404, detail=f"Task {task_id} not found")
    
    if task.status.value in ["completed", "failed", "cancelled"]:
        raise HTTPException(
            status_code=400,
            detail=f"Cannot cancel task {task_id}. Current status: {task.status.value}"
        )
    
    await task_manager.set_task_cancelled(task_id)
    logger.info(f"Task {task_id}: Cancelled by user")
    
    return {
        "success": True,
        "message": f"Task {task_id} cancelled successfully"
    }
