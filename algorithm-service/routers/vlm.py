from fastapi import APIRouter, HTTPException, BackgroundTasks
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from loguru import logger
import uuid
import asyncio

from services import task_manager, model_service
from config import settings


router = APIRouter()


class VlmCleanRequest(BaseModel):
    project_id: int = Field(..., description="Project ID")
    image_paths: List[str] = Field(..., description="List of image paths to clean")
    model: str = Field(default="Qwen3-VL-4B-Instruct", description="VLM model name")
    max_tokens: int = Field(default=4096, ge=1, le=8192, description="Maximum tokens")
    min_dim: int = Field(default=10, ge=1, description="Minimum dimension for filtering")
    task_id: Optional[str] = Field(default=None, description="Task ID for tracking")


class VlmCleanResponse(BaseModel):
    success: bool
    message: str
    task_id: str
    status: str
    total_images: int
    processed_images: int
    results: Optional[List[Dict[str, Any]]] = None


async def run_vlm_cleaning_task(
    task_id: str,
    project_id: int,
    image_paths: List[str],
    model: str,
    max_tokens: int,
    min_dim: int
):
    """后台执行 VLM 清洗任务 - MOCK 版本"""
    try:
        # 设置任务为运行中
        await task_manager.set_task_running(task_id)
        
        # 模拟耗时
        await asyncio.sleep(3)
        
        total_images = len(image_paths)
        
        # 遍历图片进行清洗
        for idx, image_path in enumerate(image_paths):
            # 检查任务是否已取消
            if await task_manager.is_task_cancelled(task_id):
                logger.info(f"Task {task_id}: Cancelled by user")
                await task_manager.set_task_cancelled(task_id)
                return
            
            try:
                # MOCK: 直接返回假数据
                result = {
                    "image_path": image_path,
                    "original_labels": ["person", "car", "small_box"],
                    "cleaned_labels": ["person", "car"],
                    "removed_labels": ["small_box"],
                    "reason": f"Small bounding box below minimum dimension threshold ({min_dim}x{min_dim})"
                }
                await task_manager.add_task_result(task_id, result)
                
                # 更新进度
                await task_manager.update_task_progress(task_id, idx + 1)
                
            except Exception as e:
                logger.error(f"Task {task_id}: Failed to process {image_path} - {e}")
                # 继续处理下一张图片，不中断整个任务
        
        # 设置任务为已完成
        await task_manager.set_task_completed(task_id)
        logger.info(f"Task {task_id}: VLM cleaning completed successfully")
        
    except Exception as e:
        logger.error(f"Task {task_id}: VLM cleaning failed - {e}", exc_info=True)
        await task_manager.set_task_failed(task_id, str(e))


async def run_vlm_cleaning_with_detections_task(
    task_id: str,
    project_id: int,
    detections: List[Dict[str, Any]],
    model: str,
    max_tokens: int,
    min_dim: int,
    label_definitions: Dict[str, str]
):
    """后台执行 VLM 清洗任务（带检测结果）"""
    try:
        # 设置任务为运行中
        await task_manager.set_task_running(task_id)
        
        # 确保模型已加载
        if model_service.vlm_client is None:
            model_service.init_vlm_client(
                vlm_url="http://localhost:5008/v1",
                vlm_model=model
            )
        
        total_detections = len(detections)
        processed_count = 0
        
        # 遍历检测结果进行清洗
        for detection in detections:
            # 检查任务是否已取消
            if await task_manager.is_task_cancelled(task_id):
                logger.info(f"Task {task_id}: Cancelled by user")
                await task_manager.set_task_cancelled(task_id)
                return
            
            try:
                image_path = detection["image_path"]
                bbox = detection["bbox_absolute"]
                label_name = detection["label"]
                label_definition = label_definitions.get(label_name, "")
                
                # 运行 VLM 清洗
                vlm_result = model_service.run_vlm_cleaning(
                    image_path=image_path,
                    bbox=bbox,
                    label_name=label_name,
                    label_definition=label_definition,
                    min_dim=min_dim
                )
                
                # 保存结果
                result = {
                    "image_path": image_path,
                    "original_labels": [label_name],
                    "cleaned_labels": [label_name] if vlm_result["decision"] == "keep" else [],
                    "removed_labels": [] if vlm_result["decision"] == "keep" else [label_name],
                    "reason": vlm_result["reason"]
                }
                await task_manager.add_task_result(task_id, result)
                
                # 更新进度
                processed_count += 1
                await task_manager.update_task_progress(task_id, processed_count)
                
            except Exception as e:
                logger.error(f"Task {task_id}: Failed to process detection - {e}")
                # 继续处理下一个检测，不中断整个任务
        
        # 设置任务为已完成
        await task_manager.set_task_completed(task_id)
        logger.info(f"Task {task_id}: VLM cleaning completed successfully")
        
    except Exception as e:
        logger.error(f"Task {task_id}: VLM cleaning failed - {e}", exc_info=True)
        await task_manager.set_task_failed(task_id, str(e))


@router.post("/algo/vlm/clean/mock", response_model=VlmCleanResponse)
async def run_vlm_cleaning(request: VlmCleanRequest, background_tasks: BackgroundTasks):
    """运行 VLM 清洗（异步后台任务）"""
    logger.info(f"Received VLM cleaning request: project_id={request.project_id}, images={len(request.image_paths)}")
    
    # 生成任务 ID
    task_id = request.task_id or str(uuid.uuid4())
    
    # 创建任务
    task = await task_manager.create_task(
        task_id=task_id,
        task_type="VLM_CLEANING",
        project_id=request.project_id,
        total_images=len(request.image_paths),
        parameters={
            "model": request.model,
            "max_tokens": request.max_tokens,
            "min_dim": request.min_dim
        }
    )
    
    # 添加后台任务
    background_tasks.add_task(
        run_vlm_cleaning_task,
        task_id,
        request.project_id,
        request.image_paths,
        request.model,
        request.max_tokens,
        request.min_dim
    )
    
    logger.info(f"Task {task_id}: VLM cleaning task started in background")
    
    return VlmCleanResponse(
        success=True,
        message="VLM cleaning task started successfully",
        task_id=task_id,
        status="RUNNING",
        total_images=len(request.image_paths),
        processed_images=0,
        results=None
    )


@router.get("/algo/vlm/status/{task_id}")
async def get_vlm_task_status(task_id: str):
    """查询 VLM 任务状态"""
    task = await task_manager.get_task(task_id)
    
    if task is None:
        raise HTTPException(status_code=404, detail=f"Task {task_id} not found")
    
    return task.to_dict()


@router.get("/algo/vlm/results/{task_id}")
async def get_vlm_task_results(task_id: str):
    """获取 VLM 任务结果"""
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


@router.post("/algo/vlm/cancel/{task_id}")
async def cancel_vlm_task(task_id: str):
    """取消 VLM 任务"""
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
