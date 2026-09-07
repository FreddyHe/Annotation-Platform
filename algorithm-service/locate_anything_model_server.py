#!/usr/bin/env python3
"""Local HTTP service for NVIDIA LocateAnything-3B.

This process is intentionally separate from the main algorithm-service so the
large VLM grounding model can be started, stopped, and diagnosed independently.
"""

from __future__ import annotations

import os
import re
import sys
import threading
import time
import json
from pathlib import Path
from typing import Any, Dict, List, Optional

import torch
from fastapi import FastAPI, HTTPException
from PIL import Image
from pydantic import BaseModel, Field


PROJECT_ROOT = Path("/root/autodl-fs/Annotation-Platform")
EAGLE_EMBODIED_ROOT = Path(os.getenv("LOCATE_ANYTHING_CODE_ROOT", "/root/autodl-fs/external/Eagle/Embodied"))
MODEL_PATH = Path(os.getenv("LOCATE_ANYTHING_MODEL_PATH", "/root/autodl-fs/models/LocateAnything-3B"))
MODEL_ID = os.getenv("LOCATE_ANYTHING_MODEL_ID", "local-locate-anything-3b")
MODEL_VERSION = os.getenv("LOCATE_ANYTHING_MODEL_VERSION", "nvidia/LocateAnything-3B")
DEVICE = os.getenv("LOCATE_ANYTHING_DEVICE", "cuda")
DTYPE_NAME = os.getenv("LOCATE_ANYTHING_DTYPE", "bfloat16").lower()
GENERATION_MODE = os.getenv("LOCATE_ANYTHING_GENERATION_MODE", "hybrid")
MAX_NEW_TOKENS = int(os.getenv("LOCATE_ANYTHING_MAX_NEW_TOKENS", "512"))
TEMPERATURE = float(os.getenv("LOCATE_ANYTHING_TEMPERATURE", "0.1"))
MAX_IMAGE_SIDE = int(os.getenv("LOCATE_ANYTHING_MAX_IMAGE_SIDE", "1280"))

if str(EAGLE_EMBODIED_ROOT) not in sys.path:
    sys.path.insert(0, str(EAGLE_EMBODIED_ROOT))

try:
    from locateanything_worker import LocateAnythingWorker
except Exception as exc:  # pragma: no cover - reported through /healthz
    LocateAnythingWorker = None  # type: ignore[assignment]
    IMPORT_ERROR = str(exc)
else:
    IMPORT_ERROR = None


class PredictRequest(BaseModel):
    image_path: str
    labels: List[str] = Field(default_factory=list)
    label: Optional[str] = None
    mode: str = "detect"
    job_id: Optional[Any] = None
    route_id: Optional[Any] = None
    max_new_tokens: Optional[int] = None
    generation_mode: Optional[str] = None
    temperature: Optional[float] = None


class ServerState:
    def __init__(self) -> None:
        self.worker: Optional[Any] = None
        self.loaded_at: Optional[float] = None
        self.load_error: Optional[str] = None
        self.lock = threading.Lock()

    def health(self) -> Dict[str, Any]:
        model_files_ready = (MODEL_PATH / "config.json").exists() and (MODEL_PATH / "model.safetensors.index.json").exists()
        required_weight_files = _required_weight_files()
        weight_files_ready = bool(required_weight_files) and all((MODEL_PATH / file_name).exists() for file_name in required_weight_files)
        return {
            "success": True,
            "model_id": MODEL_ID,
            "model_version": MODEL_VERSION,
            "status": "AVAILABLE" if self.worker is not None else "READY_TO_LOAD",
            "available": self.worker is not None or (IMPORT_ERROR is None and model_files_ready and weight_files_ready),
            "loaded": self.worker is not None,
            "loaded_at": self.loaded_at,
            "load_error": self.load_error,
            "import_error": IMPORT_ERROR,
            "code_root": str(EAGLE_EMBODIED_ROOT),
            "model_path": str(MODEL_PATH),
            "model_files_ready": model_files_ready,
            "weight_files_ready": weight_files_ready,
            "required_weight_files": required_weight_files,
            "device": DEVICE,
            "dtype": DTYPE_NAME,
            "external_api_used": False,
        }

    def load(self) -> Any:
        if self.worker is not None:
            return self.worker
        with self.lock:
            if self.worker is not None:
                return self.worker
            if IMPORT_ERROR:
                self.load_error = IMPORT_ERROR
                raise RuntimeError(f"LocateAnything worker import failed: {IMPORT_ERROR}")
            if LocateAnythingWorker is None:
                self.load_error = "LocateAnythingWorker import failed"
                raise RuntimeError(self.load_error)
            if not MODEL_PATH.exists():
                self.load_error = f"model path does not exist: {MODEL_PATH}"
                raise RuntimeError(self.load_error)
            dtype = _dtype(DTYPE_NAME)
            start = time.time()
            self.worker = LocateAnythingWorker(str(MODEL_PATH), device=DEVICE, dtype=dtype)
            self.loaded_at = time.time()
            self.load_error = None
            return self.worker


app = FastAPI(title="LocateAnything Local Service", version="1.0.0")
state = ServerState()


@app.get("/healthz")
def healthz() -> Dict[str, Any]:
    return state.health()


@app.get("/readyz")
def readyz() -> Dict[str, Any]:
    health = state.health()
    if not health.get("available"):
        raise HTTPException(status_code=503, detail=health)
    return health


@app.post("/predict")
def predict(request: PredictRequest) -> Dict[str, Any]:
    image_path = Path(request.image_path)
    if not image_path.exists():
        raise HTTPException(status_code=404, detail={"message": "image file not found", "image_path": request.image_path})
    labels = _labels(request)
    if not labels:
        raise HTTPException(status_code=400, detail={"message": "labels must not be empty"})

    worker = state.load()
    image = Image.open(image_path).convert("RGB")
    width, height = image.size
    inference_image, scale_x, scale_y = _resize_for_inference(image)
    inference_width, inference_height = inference_image.size
    kwargs = {
        "generation_mode": request.generation_mode or GENERATION_MODE,
        "max_new_tokens": request.max_new_tokens or MAX_NEW_TOKENS,
        "temperature": TEMPERATURE if request.temperature is None else request.temperature,
        "verbose": False,
    }

    start = time.time()
    if request.mode == "ground_single":
        response = worker.ground_single(inference_image, labels[0], **kwargs)
    elif request.mode == "ground_multi":
        response = worker.ground_multi(inference_image, labels[0], **kwargs)
    else:
        response = worker.detect(inference_image, labels, **kwargs)
    elapsed_ms = int((time.time() - start) * 1000)

    answer = str(response.get("answer") or "")
    labeled_boxes = _parse_labeled_boxes(answer, inference_width, inference_height, labels)
    if not labeled_boxes:
        labeled_boxes = [
            {"label": labels[0], **box}
            for box in LocateAnythingWorker.parse_boxes(answer, inference_width, inference_height)
        ]

    candidates = []
    for box in labeled_boxes:
        x1 = float(box["x1"]) * scale_x
        y1 = float(box["y1"]) * scale_y
        x2 = float(box["x2"]) * scale_x
        y2 = float(box["y2"]) * scale_y
        candidates.append(
            {
                "image_id": image_path.name,
                "label": box["label"],
                "bbox_xyxy": [
                    round(max(0.0, min(x1, float(width))), 2),
                    round(max(0.0, min(y1, float(height))), 2),
                    round(max(0.0, min(x2, float(width))), 2),
                    round(max(0.0, min(y2, float(height))), 2),
                ],
                "score": 0.65,
                "source_model": MODEL_ID,
                "model_version": MODEL_VERSION,
                "prompt": _prompt_preview(request.mode, labels),
                "raw_output": {
                    "answer": answer,
                    "mode": request.mode,
                    "labels": labels,
                    "elapsed_ms": elapsed_ms,
                    "inference_width": inference_width,
                    "inference_height": inference_height,
                    "scale_x": scale_x,
                    "scale_y": scale_y,
                },
                "metadata": {"route_id": request.route_id, "job_id": request.job_id},
            }
        )

    return {
        "success": True,
        "available": True,
        "status": "COMPLETED",
        "model_id": MODEL_ID,
        "model_version": MODEL_VERSION,
        "external_api_used": False,
        "image_path": str(image_path),
        "image_width": width,
        "image_height": height,
        "labels": labels,
        "answer": answer,
        "candidate_count": len(candidates),
        "candidates": candidates,
        "elapsed_ms": elapsed_ms,
    }


def _resize_for_inference(image: Image.Image) -> tuple[Image.Image, float, float]:
    width, height = image.size
    longest = max(width, height)
    if MAX_IMAGE_SIDE <= 0 or longest <= MAX_IMAGE_SIDE:
        return image, 1.0, 1.0
    ratio = MAX_IMAGE_SIDE / float(longest)
    target_width = max(1, int(round(width * ratio)))
    target_height = max(1, int(round(height * ratio)))
    resized = image.resize((target_width, target_height), Image.Resampling.LANCZOS)
    return resized, width / float(target_width), height / float(target_height)


def _labels(request: PredictRequest) -> List[str]:
    values: List[str] = []
    if request.label:
        values.append(request.label)
    values.extend(request.labels or [])
    return [value.strip() for value in values if value and value.strip()]


def _prompt_preview(mode: str, labels: List[str]) -> str:
    if mode == "ground_single":
        return f"Locate a single instance that matches the following description: {labels[0]}."
    if mode == "ground_multi":
        return f"Locate all the instances that match the following description: {labels[0]}."
    return "Locate all the instances that matches the following description: " + "</c>".join(labels) + "."


def _parse_labeled_boxes(answer: str, width: int, height: int, labels: List[str]) -> List[Dict[str, Any]]:
    labeled_boxes: List[Dict[str, Any]] = []
    current_label: Optional[str] = None
    token_pattern = re.compile(r"<ref>(.*?)</ref>|<box>(.*?)</box>", flags=re.IGNORECASE | re.DOTALL)
    for match in token_pattern.finditer(answer or ""):
        ref_text, box_text = match.groups()
        if ref_text is not None:
            current_label = _match_allowed_label(ref_text, labels)
            continue
        if box_text is None or current_label is None:
            continue
        coords = _parse_box_numbers(box_text)
        if coords is None:
            continue
        x1, y1, x2, y2 = coords
        labeled_boxes.append(
            {
                "label": current_label,
                "x1": max(0.0, min(1000.0, x1)) / 1000.0 * width,
                "y1": max(0.0, min(1000.0, y1)) / 1000.0 * height,
                "x2": max(0.0, min(1000.0, x2)) / 1000.0 * width,
                "y2": max(0.0, min(1000.0, y2)) / 1000.0 * height,
            }
        )
    return labeled_boxes


def _parse_box_numbers(box_text: str) -> Optional[List[float]]:
    numbers = re.findall(r"<\s*([0-9.]+)\s*>", box_text or "")
    if len(numbers) < 4:
        numbers = re.findall(r"[0-9.]+", box_text or "")
    if len(numbers) < 4:
        return None
    values = [float(value) for value in numbers[:4]]
    x1, y1, x2, y2 = values
    if x2 < x1:
        x1, x2 = x2, x1
    if y2 < y1:
        y1, y2 = y2, y1
    return [x1, y1, x2, y2]


def _match_allowed_label(ref_text: str, labels: List[str]) -> Optional[str]:
    normalized_ref = _normalize(ref_text)
    if not normalized_ref or normalized_ref in {"#", "##", "###", "!", "#!"}:
        return None
    for label in labels:
        normalized_label = _normalize(label)
        if normalized_label and (normalized_label in normalized_ref or normalized_ref in normalized_label):
            return label

    heuristics = [
        (("反光", "reflect", "vest", "背心"), ("反光", "衣", "背心")),
        (("helmet", "hat", "安全帽", "帽"), ("帽",)),
        (("rope", "lanyard", "绳", "登高", "safetyline"), ("绳", "登高")),
    ]
    for needles, label_needles in heuristics:
        if any(needle in normalized_ref for needle in needles):
            for label in labels:
                normalized_label = _normalize(label)
                if any(needle in normalized_label for needle in label_needles):
                    return label
    return None


def _normalize(value: str) -> str:
    return re.sub(r"[\s_./,，。:：;；#<>|\\\\-]+", "", str(value or "").lower())


def _dtype(name: str) -> torch.dtype:
    if name in {"bf16", "bfloat16"}:
        return torch.bfloat16
    if name in {"fp16", "float16", "half"}:
        return torch.float16
    return torch.float32


def _required_weight_files() -> List[str]:
    index_path = MODEL_PATH / "model.safetensors.index.json"
    if not index_path.exists():
        return []
    try:
        payload = json.loads(index_path.read_text())
        return sorted({str(value) for value in (payload.get("weight_map") or {}).values() if value})
    except Exception:
        return []


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("LOCATE_ANYTHING_PORT", "5010")))
