from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import List, Optional, Dict, Any
from loguru import logger


router = APIRouter()


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str
    models: Dict[str, bool]


@router.get("/health", response_model=HealthResponse)
async def health_check():
    return HealthResponse(
        status="healthy",
        service="Annotation Algorithm Service",
        version="1.0.0",
        models={
            "dino": False,
            "vlm": False,
            "yolo": False
        }
    )


@router.get("/health/detailed")
async def detailed_health_check():
    return {
        "status": "healthy",
        "service": "Annotation Algorithm Service",
        "version": "1.0.0",
        "endpoints": {
            "dino": "/api/v1/algo/dino/detect",
            "vlm": "/api/v1/algo/vlm/clean",
            "yolo": "/api/v1/algo/yolo/detect"
        },
        "models": {
            "dino": {
                "loaded": False,
                "path": "models/groundingdino_swint_ogc.pth"
            },
            "vlm": {
                "loaded": False,
                "path": "models/Qwen-VL-Chat"
            },
            "yolo": {
                "loaded": False,
                "path": "models/yolov8n.pt"
            }
        }
    }
