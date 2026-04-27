from pathlib import Path
import threading
from typing import Any, Dict, List

import torch
from fastapi import APIRouter, HTTPException
from loguru import logger
from pydantic import BaseModel, Field
from services.resource_locks import gpu_lock

router = APIRouter(prefix="/algo/edge-inference", tags=["EdgeInference"])
_model_cache: Dict[str, Dict[str, Any]] = {}
_model_cache_lock = threading.Lock()


class EdgeInferenceRequest(BaseModel):
    model_path: str = Field(..., description="YOLO model path")
    image_paths: List[str] = Field(..., description="Image paths")
    conf_threshold: float = Field(0.3, ge=0.0, le=1.0)
    iou_threshold: float = Field(0.45, ge=0.0, le=1.0)
    device: str = Field("0")


def _parse_result(model, image_path: str, result) -> Dict[str, Any]:
    detections = []
    confidences = []
    boxes = result.boxes
    if boxes is not None:
        for box in boxes:
            x1, y1, x2, y2 = box.xyxy[0].tolist()
            conf = float(box.conf[0].item())
            cls_id = int(box.cls[0].item())
            detections.append({
                "label": model.names.get(cls_id, str(cls_id)),
                "class_id": cls_id,
                "confidence": conf,
                "bbox": {
                    "x1": float(x1),
                    "y1": float(y1),
                    "x2": float(x2),
                    "y2": float(y2)
                }
            })
            confidences.append(conf)

    return {
        "image_path": image_path,
        "file_name": Path(image_path).name,
        "detections": detections,
        "total_detections": len(detections),
        "avg_confidence": sum(confidences) / len(confidences) if confidences else 0.0
    }


def _get_cached_model(model_file: Path):
    from ultralytics import YOLO

    key = str(model_file.resolve())
    mtime = model_file.stat().st_mtime
    with _model_cache_lock:
        cached = _model_cache.get(key)
        if cached and cached.get("mtime") == mtime:
            return cached["model"], True
        logger.info(f"Loading edge inference model: {key}")
        model = YOLO(str(model_file))
        _model_cache[key] = {"mtime": mtime, "model": model}
        return model, False


@router.post("/batch")
async def batch_edge_inference(request: EdgeInferenceRequest):
    model_file = Path(request.model_path)
    if not model_file.exists():
        raise HTTPException(status_code=400, detail=f"Model file not found: {request.model_path}")

    valid_images = [path for path in request.image_paths if Path(path).exists()]
    if not valid_images:
        raise HTTPException(status_code=400, detail="No valid images found")

    device = request.device
    if device != "cpu" and not torch.cuda.is_available():
        logger.warning("CUDA unavailable for edge inference, falling back to CPU")
        device = "cpu"

    try:
        model, cache_hit = _get_cached_model(model_file)
        logger.info(
            f"Edge inference started: images={len(valid_images)}, model={model_file}, "
            f"cache_hit={cache_hit}, device={device}"
        )
        results = []
        with gpu_lock:
            for image_path in valid_images:
                prediction = model(
                    image_path,
                    conf=request.conf_threshold,
                    iou=request.iou_threshold,
                    device=device,
                    verbose=False
                )[0]
                results.append(_parse_result(model, image_path, prediction))

        return {
            "success": True,
            "model_path": request.model_path,
            "count": len(results),
            "cache_hit": cache_hit,
            "results": results
        }
    except Exception as exc:
        logger.error(f"Edge inference failed: {exc}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(exc))
