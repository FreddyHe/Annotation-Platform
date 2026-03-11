from fastapi import APIRouter, HTTPException, BackgroundTasks
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from loguru import logger
import uuid
import asyncio
import httpx
import io
import os
from PIL import Image

from services import task_manager
from config import settings


router = APIRouter()


class DinoDetectRequest(BaseModel):
    project_id: int = Field(..., description="Project ID")
    image_paths: List[str] = Field(..., description="List of image paths")
    labels: List[str] = Field(..., description="List of labels to detect")
    box_threshold: float = Field(default=0.3, ge=0.0, le=1.0, description="Box threshold")
    text_threshold: float = Field(default=0.25, ge=0.0, le=1.0, description="Text threshold")
    task_id: Optional[str] = Field(default=None, description="Task ID for tracking")


class DinoDetectResponse(BaseModel):
    success: bool
    message: str
    task_id: str
    status: str
    total_images: int
    processed_images: int
    results: Optional[List[Dict[str, Any]]] = None


async def call_dino_service(
    image_paths: List[str], 
    labels: List[str],
    box_threshold: float = 0.3,
    text_threshold: float = 0.25
) -> List[Dict[str, Any]]:
    """
    调用本地 DINO 服务 (http://127.0.0.1:5002/predict)
    
    Args:
        image_paths: 图片路径列表（绝对路径）
        labels: 标签列表
        box_threshold: Box threshold (default: 0.3)
        text_threshold: Text threshold (default: 0.25)
        
    Returns:
        检测结果列表
    """
    results = []
    dino_url = "http://127.0.0.1:5003/predict"
    
    for image_path in image_paths:
        try:
            # 检查图片文件是否存在
            if not os.path.exists(image_path):
                logger.error(f"Image file not found: {image_path}")
                results.append({
                    "image_path": image_path,
                    "detections": [],
                    "labels": labels,
                    "error": f"File not found: {image_path}"
                })
                continue
            
            with open(image_path, 'rb') as f:
                image_data = f.read()
            
            text_prompt = " . ".join(labels)
            if not text_prompt.endswith("."):
                text_prompt += "."
            
            logger.info(f"Calling DINO for {image_path.split('/')[-1]} with prompt: {text_prompt}, thresholds: box={box_threshold}, text={text_threshold}")
            
            async with httpx.AsyncClient(timeout=60.0) as client:
                response = await client.post(
                    dino_url,
                    files={'image': (image_path.split('/')[-1], image_data, 'image/jpeg')},
                    data={
                        'text_prompt': text_prompt,
                        'box_threshold': str(box_threshold),
                        'text_threshold': str(text_threshold)
                    }
                )
                
                if response.status_code == 200:
                    data = response.json()
                    detections = data.get('detections', [])
                    
                    with Image.open(image_path) as img:
                        img_w, img_h = img.size
                    
                    converted_detections = []
                    for det in detections:
                        box = det.get('box', [])
                        if len(box) == 4:
                            cx, cy, w, h = box
                            x_min = (cx - w / 2) * img_w
                            y_min = (cy - h / 2) * img_h
                            abs_w = w * img_w
                            abs_h = h * img_h
                            
                            converted_detections.append({
                                "bbox": [x_min, y_min, abs_w, abs_h],
                                "label": det.get('label', 'unknown'),
                                "score": det.get('logit_score', det.get('score', 0.0))
                            })
                    
                    results.append({
                        "image_path": image_path,
                        "detections": converted_detections,
                        "labels": labels
                    })
                else:
                    logger.error(f"DINO service error: {response.status_code} - {response.text}")
                    results.append({
                        "image_path": image_path,
                        "detections": [],
                        "labels": labels,
                        "error": f"DINO service error: {response.status_code}"
                    })
                    
        except Exception as e:
            logger.error(f"Error calling DINO service for {image_path}: {e}")
            results.append({
                "image_path": image_path,
                "detections": [],
                "labels": labels,
                "error": str(e)
            })
    
    return results


async def run_dino_detection_task(
    task_id: str,
    project_id: int,
    image_paths: List[str],
    labels: List[str],
    box_threshold: float,
    text_threshold: float
):
    """后台执行 DINO 检测任务 - 真实实现"""
    try:
        await task_manager.set_task_running(task_id)
        
        total_images = len(image_paths)
        
        for idx, image_path in enumerate(image_paths):
            if await task_manager.is_task_cancelled(task_id):
                logger.info(f"Task {task_id}: Cancelled by user")
                await task_manager.set_task_cancelled(task_id)
                return
            
            try:
                detections = await call_dino_service([image_path], labels)
                
                if detections:
                    result = {
                        "image_path": image_path,
                        "detections": detections[0].get("detections", []),
                        "labels": labels
                    }
                else:
                    result = {
                        "image_path": image_path,
                        "detections": [],
                        "labels": labels
                    }
                
                await task_manager.add_task_result(task_id, result)
                await task_manager.update_task_progress(task_id, idx + 1)
                
            except Exception as e:
                logger.error(f"Task {task_id}: Failed to process {image_path} - {e}")
        
        await task_manager.set_task_completed(task_id)
        logger.info(f"Task {task_id}: DINO detection completed successfully")
        
    except Exception as e:
        logger.error(f"Task {task_id}: DINO detection failed - {e}", exc_info=True)
        await task_manager.set_task_failed(task_id, str(e))


@router.post("/algo/dino/detect", response_model=DinoDetectResponse)
async def run_dino_detection(request: DinoDetectRequest, background_tasks: BackgroundTasks):
    """运行 DINO 检测（异步后台任务）"""
    logger.info(f"Received DINO detection request: project_id={request.project_id}, images={len(request.image_paths)}")
    
    task_id = request.task_id or str(uuid.uuid4())
    
    task = await task_manager.create_task(
        task_id=task_id,
        task_type="DINO_DETECTION",
        project_id=request.project_id,
        total_images=len(request.image_paths),
        parameters={
            "labels": request.labels,
            "box_threshold": request.box_threshold,
            "text_threshold": request.text_threshold
        }
    )
    
    background_tasks.add_task(
        run_dino_detection_task,
        task_id,
        request.project_id,
        request.image_paths,
        request.labels,
        request.box_threshold,
        request.text_threshold
    )
    
    logger.info(f"Task {task_id}: DINO detection task started in background")
    
    return DinoDetectResponse(
        success=True,
        message="DINO detection task started successfully",
        task_id=task_id,
        status="RUNNING",
        total_images=len(request.image_paths),
        processed_images=0,
        results=None
    )


@router.get("/algo/dino/status/{task_id}")
async def get_dino_task_status(task_id: str):
    """查询 DINO 任务状态"""
    task = await task_manager.get_task(task_id)
    
    if task is None:
        raise HTTPException(status_code=404, detail=f"Task {task_id} not found")
    
    return task.to_dict()


@router.get("/algo/dino/results/{task_id}")
async def get_dino_task_results(task_id: str):
    """获取 DINO 任务结果"""
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


@router.post("/algo/dino/cancel/{task_id}")
async def cancel_dino_task(task_id: str):
    """取消 DINO 任务"""
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
