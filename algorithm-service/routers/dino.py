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
DINO_CONCURRENCY = int(os.getenv("DINO_CONCURRENCY", "4"))


def resolve_image_path(image_path: str) -> str:
    if os.path.isabs(image_path):
        return image_path
    return os.path.join(settings.UPLOAD_BASE_PATH, image_path)


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


async def call_dino_service_single(
    client: httpx.AsyncClient,
    image_path: str,
    labels: List[str],
    box_threshold: float = 0.3,
    text_threshold: float = 0.25
) -> Dict[str, Any]:
    """
    单图调用本地 DINO 服务，复用外部 httpx client。
    """
    dino_url = "http://127.0.0.1:5003/predict"
    resolved_image_path = resolve_image_path(image_path)

    if not os.path.exists(resolved_image_path):
        logger.error(f"Image file not found: {resolved_image_path}")
        return {
            "image_path": image_path,
            "detections": [],
            "labels": labels,
            "error": f"File not found: {resolved_image_path}",
        }

    try:
        with open(resolved_image_path, "rb") as f:
            image_data = f.read()

        text_prompt = " . ".join(labels)
        if not text_prompt.endswith("."):
            text_prompt += "."

        logger.info(
            f"Calling DINO for {os.path.basename(resolved_image_path)} with prompt: {text_prompt}, "
            f"thresholds: box={box_threshold}, text={text_threshold}"
        )

        response = await client.post(
            dino_url,
            files={"image": (os.path.basename(resolved_image_path), image_data, "image/jpeg")},
            data={
                "text_prompt": text_prompt,
                "box_threshold": str(box_threshold),
                "text_threshold": str(text_threshold),
            },
        )

        if response.status_code != 200:
            logger.error(f"DINO service error: {response.status_code} - {response.text}")
            return {
                "image_path": image_path,
                "detections": [],
                "labels": labels,
                "error": f"DINO service error: {response.status_code}",
            }

        data = response.json()
        detections = data.get("detections", [])

        with Image.open(resolved_image_path) as img:
            img_w, img_h = img.size

        converted_detections = []
        for det in detections:
            box = det.get("box", [])
            if len(box) == 4:
                cx, cy, w, h = box
                x_min = (cx - w / 2) * img_w
                y_min = (cy - h / 2) * img_h
                abs_w = w * img_w
                abs_h = h * img_h

                converted_detections.append({
                    "bbox": [x_min, y_min, abs_w, abs_h],
                    "label": det.get("label", "unknown"),
                    "score": det.get("logit_score", det.get("score", 0.0)),
                })

        return {
            "image_path": image_path,
            "detections": converted_detections,
            "labels": labels,
        }
    except Exception as e:
        logger.error(f"Error calling DINO service for {image_path}: {e}")
        return {
            "image_path": image_path,
            "detections": [],
            "labels": labels,
            "error": str(e),
        }


async def call_dino_service(
    image_paths: List[str],
    labels: List[str],
    box_threshold: float = 0.3,
    text_threshold: float = 0.25,
) -> List[Dict[str, Any]]:
    async with httpx.AsyncClient(timeout=60.0, trust_env=False) as client:
        return [
            await call_dino_service_single(client, image_path, labels, box_threshold, text_threshold)
            for image_path in image_paths
        ]


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
        failed_images = 0
        last_error = None
        processed_counter = 0
        counter_lock = asyncio.Lock()
        semaphore = asyncio.Semaphore(DINO_CONCURRENCY)

        async with httpx.AsyncClient(timeout=60.0, trust_env=False) as client:
            async def worker(image_path: str):
                nonlocal failed_images, last_error, processed_counter
                if await task_manager.is_task_cancelled(task_id):
                    return

                async with semaphore:
                    if await task_manager.is_task_cancelled(task_id):
                        return
                    result = await call_dino_service_single(
                        client,
                        image_path,
                        labels,
                        box_threshold,
                        text_threshold,
                    )

                async with counter_lock:
                    if result.get("error"):
                        failed_images += 1
                        last_error = result["error"]
                    await task_manager.add_task_result(task_id, result)
                    processed_counter += 1
                    await task_manager.update_task_progress(task_id, processed_counter)

            await asyncio.gather(*(worker(image_path) for image_path in image_paths))

        if await task_manager.is_task_cancelled(task_id):
            logger.info(f"Task {task_id}: Cancelled by user")
            await task_manager.set_task_cancelled(task_id)
            return
        
        if total_images > 0 and failed_images == total_images:
            message = f"DINO service failed for all images: {last_error or 'unknown error'}"
            await task_manager.set_task_failed(task_id, message)
            logger.error(f"Task {task_id}: {message}")
            return

        await task_manager.set_task_completed(task_id)
        logger.info(f"Task {task_id}: DINO detection completed (failed={failed_images}/{total_images})")
        
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
        return {
            "success": True,
            "message": f"Task {task_id} is already {task.status.value}"
        }
    
    await task_manager.set_task_cancelled(task_id)
    logger.info(f"Task {task_id}: Cancelled by user")
    
    return {
        "success": True,
        "message": f"Task {task_id} cancelled successfully"
    }
