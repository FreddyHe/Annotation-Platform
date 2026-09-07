from pathlib import Path
from typing import Any, Dict, List
import socket

import httpx
from PIL import Image


DINO_URL = "http://127.0.0.1:5003"


def status() -> Dict[str, Any]:
    available = _tcp_available("127.0.0.1", 5003)
    return {
        "model_id": "grounding-dino",
        "name": "Grounding DINO",
        "available": available,
        "status": "AVAILABLE" if available else "UNAVAILABLE",
        "local": True,
        "role": "open_vocabulary_proposal_teacher",
        "endpoint": f"{DINO_URL}/predict",
        "reason": None if available else "DINO service is not listening on 127.0.0.1:5003",
    }


def predict_image(
    image_path: str,
    labels: List[str],
    job_id: Any = None,
    route_id: Any = None,
    box_threshold: float = 0.25,
    text_threshold: float = 0.20,
) -> Dict[str, Any]:
    if not _tcp_available("127.0.0.1", 5003):
        return _unavailable("DINO service is not listening on 127.0.0.1:5003")
    path = Path(image_path)
    if not path.exists():
        return _unavailable(f"image not found: {image_path}")
    if not labels:
        return _unavailable("labels are required for Grounding DINO adapter")

    prompt = " . ".join(labels) + " ."
    with Image.open(path) as image:
        width, height = image.size

    try:
        with path.open("rb") as file_obj:
            response = httpx.post(
                f"{DINO_URL}/predict",
                files={"image": (path.name, file_obj, "application/octet-stream")},
                data={
                    "text_prompt": prompt,
                    "box_threshold": str(box_threshold),
                    "text_threshold": str(text_threshold),
                },
                timeout=120.0,
            )
        if response.status_code >= 400:
            return _unavailable(f"DINO service returned HTTP {response.status_code}: {response.text[:200]}")
        body = response.json()
        candidates = []
        for detection in body.get("detections") or []:
            box = detection.get("box") or []
            if len(box) != 4:
                continue
            x1, y1, x2, y2 = _cxcywh_to_xyxy(box, width, height)
            label = str(detection.get("label") or "").strip() or _best_label_from_prompt(labels)
            candidates.append(
                {
                    "image_id": path.name,
                    "label": label,
                    "bbox_xyxy": [x1, y1, x2, y2],
                    "score": float(detection.get("logit_score") or detection.get("score") or 0.0),
                    "source_model": "grounding-dino",
                    "model_version": "existing-local-service",
                    "prompt": prompt,
                    "raw_output": detection,
                    "metadata": {"route_id": route_id, "job_id": job_id, "image_path": str(path)},
                }
            )
        candidates = _postprocess_candidates(candidates, width, height)
        return {
            "success": True,
            "available": True,
            "status": "COMPLETED",
            "source_model": "grounding-dino",
            "candidate_count": len(candidates),
            "candidates": candidates,
            "raw_output": body,
        }
    except Exception as exc:
        return _unavailable(f"DINO adapter failed: {exc}")


def _postprocess_candidates(
    candidates: List[Dict[str, Any]],
    image_width: int,
    image_height: int,
    max_area_ratio: float = 0.85,
    iou_threshold: float = 0.55,
) -> List[Dict[str, Any]]:
    """Match the platform adapter to the validated standalone DINO workflow."""
    image_area = float(image_width * image_height)
    valid: List[Dict[str, Any]] = []
    for candidate in candidates:
        box = candidate.get("bbox_xyxy") or []
        if len(box) != 4:
            continue
        x1, y1, x2, y2 = [float(value) for value in box]
        box_area = max(0.0, x2 - x1) * max(0.0, y2 - y1)
        if image_area and box_area / image_area >= max_area_ratio:
            continue
        valid.append(candidate)

    kept: List[Dict[str, Any]] = []
    for candidate in sorted(valid, key=lambda item: float(item.get("score") or 0.0), reverse=True):
        if all(_box_iou(candidate["bbox_xyxy"], item["bbox_xyxy"]) < iou_threshold for item in kept):
            kept.append(candidate)
    return kept


def _box_iou(left: List[float], right: List[float]) -> float:
    x1 = max(float(left[0]), float(right[0]))
    y1 = max(float(left[1]), float(right[1]))
    x2 = min(float(left[2]), float(right[2]))
    y2 = min(float(left[3]), float(right[3]))
    intersection = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    left_area = max(0.0, float(left[2]) - float(left[0])) * max(0.0, float(left[3]) - float(left[1]))
    right_area = max(0.0, float(right[2]) - float(right[0])) * max(0.0, float(right[3]) - float(right[1]))
    union = left_area + right_area - intersection
    return intersection / union if union > 0 else 0.0


def _cxcywh_to_xyxy(box: List[float], width: int, height: int) -> List[float]:
    cx, cy, w, h = [float(value) for value in box]
    x1 = max(0.0, (cx - w / 2.0) * width)
    y1 = max(0.0, (cy - h / 2.0) * height)
    x2 = min(float(width), (cx + w / 2.0) * width)
    y2 = min(float(height), (cy + h / 2.0) * height)
    return [round(x1, 2), round(y1, 2), round(x2, 2), round(y2, 2)]


def _best_label_from_prompt(labels: List[str]) -> str:
    return labels[0] if labels else "object"


def _tcp_available(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=1.0):
            return True
    except OSError:
        return False


def _unavailable(reason: str) -> Dict[str, Any]:
    return {
        "success": True,
        "available": False,
        "status": "UNAVAILABLE",
        "source_model": "grounding-dino",
        "reason": reason,
        "candidates": [],
    }
