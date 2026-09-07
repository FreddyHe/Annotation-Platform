import os
from pathlib import Path
from typing import Any, Dict, List
import re

import requests
from PIL import Image


DEFAULT_BASE_URL = "http://127.0.0.1:5010"
DEFAULT_MODEL_VERSION = "nvidia/LocateAnything-3B"


def status() -> Dict[str, Any]:
    base_url = os.getenv("LOCATE_ANYTHING_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    endpoint = f"{base_url}/predict"
    try:
        response = requests.get(f"{base_url}/healthz", timeout=3)
        if response.status_code == 200:
            payload = response.json()
            available = bool(payload.get("available"))
            return {
                "model_id": "local-locate-anything-3b",
                "name": "LocateAnything-3B Local",
                "version": payload.get("model_version") or DEFAULT_MODEL_VERSION,
                "available": available,
                "status": "AVAILABLE" if available else payload.get("status", "UNAVAILABLE"),
                "local": True,
                "requires_gpu": True,
                "external_api_used": False,
                "endpoint": endpoint,
                "service_base_url": base_url,
                "resource": payload,
                "reason": None if available else payload.get("load_error") or payload.get("import_error") or "本地 LocateAnything 服务未就绪。",
                "next_action": None if available else "检查 5010 服务日志、权重文件和 GPU 显存。",
            }
        return {
            "model_id": "local-locate-anything-3b",
            "name": "LocateAnything-3B Local",
            "available": False,
            "status": "UNAVAILABLE",
            "local": True,
            "requires_gpu": True,
            "external_api_used": False,
            "endpoint": endpoint,
            "reason": f"本地 LocateAnything 服务 HTTP {response.status_code}。",
            "next_action": "检查 5010 服务日志和端口。",
        }
    except Exception as exc:
        reason = f"本地 LocateAnything GPU 服务尚未配置或不可达：{exc}"
    return {
        "model_id": "local-locate-anything-3b",
        "name": "LocateAnything-3B Local",
        "available": False,
        "status": "UNAVAILABLE",
        "local": True,
        "requires_gpu": True,
        "external_api_used": False,
        "endpoint": endpoint,
        "reason": reason,
        "next_action": "启动本地 LocateAnything-3B 服务后再启用该清洗 teacher。",
    }


def predict_image(
    image_path: str,
    labels: List[str],
    job_id: Any = None,
    route_id: Any = None,
    mode: str = "detect",
) -> Dict[str, Any]:
    base_url = os.getenv("LOCATE_ANYTHING_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    if not labels:
        return {
            "success": True,
            "available": False,
            "status": "NO_LABELS",
            "source_model": "local-locate-anything-3b",
            "reason": "LocateAnything requires at least one label.",
            "candidates": [],
        }
    if not image_path or not Path(image_path).exists():
        return {
            "success": True,
            "available": False,
            "status": "IMAGE_NOT_FOUND",
            "source_model": "local-locate-anything-3b",
            "reason": f"image path not found: {image_path}",
            "candidates": [],
        }
    try:
        response = requests.post(
            f"{base_url}/predict",
            json={
                "image_path": image_path,
                "labels": labels,
                "mode": mode,
                "job_id": job_id,
                "route_id": route_id,
            },
            timeout=int(os.getenv("LOCATE_ANYTHING_TIMEOUT_SECONDS", "180")),
        )
        if response.status_code == 200:
            payload = response.json()
            payload["source_model"] = "local-locate-anything-3b"
            return _filter_implausible_full_frame_candidates(payload, image_path)
        return {
            "success": False,
            "available": False,
            "status": "SERVICE_ERROR",
            "source_model": "local-locate-anything-3b",
            "reason": f"LocateAnything service HTTP {response.status_code}: {response.text[:500]}",
            "candidates": [],
        }
    except Exception as exc:
        return {
            "success": False,
            "available": False,
            "status": "SERVICE_UNREACHABLE",
            "source_model": "local-locate-anything-3b",
            "reason": f"LocateAnything service unreachable: {exc}",
            "candidates": [],
        }


def parse_output(
    text: str,
    label: str,
    image_id: str,
    image_width: int,
    image_height: int,
    job_id: Any = None,
    route_id: Any = None,
) -> Dict[str, Any]:
    raw = text or ""
    if re.search(r"<box>\s*none\s*</box>", raw, flags=re.IGNORECASE):
        return {
            "success": True,
            "available": True,
            "status": "NO_OBJECT",
            "candidates": [],
            "raw_output": raw,
        }

    boxes = re.findall(
        r"<box>\s*<x1>\s*([0-9.]+)\s*</x1>\s*<y1>\s*([0-9.]+)\s*</y1>\s*<x2>\s*([0-9.]+)\s*</x2>\s*<y2>\s*([0-9.]+)\s*</y2>\s*</box>",
        raw,
        flags=re.IGNORECASE,
    )
    if not boxes:
        boxes = re.findall(
            r"<box>\s*([0-9.]+)\s*,\s*([0-9.]+)\s*,\s*([0-9.]+)\s*,\s*([0-9.]+)\s*</box>",
            raw,
            flags=re.IGNORECASE,
        )
    if not boxes:
        return {
            "success": True,
            "available": True,
            "status": "PARSE_ERROR",
            "reason": "LocateAnything output did not contain a parseable box or none marker",
            "candidates": [],
            "raw_output": raw,
        }

    candidates: List[Dict[str, Any]] = []
    for box in boxes:
        coords = [float(value) for value in box]
        bbox = _scale_1000_box(coords, image_width, image_height)
        candidates.append(
            {
                "image_id": image_id,
                "label": label,
                "bbox_xyxy": bbox,
                "score": 0.5,
                "source_model": "local-locate-anything-3b",
                "model_version": "pending-local-gpu",
                "prompt": label,
                "raw_output": {"text": raw, "box_1000": coords},
                "metadata": {"route_id": route_id, "job_id": job_id},
            }
        )
    return {
        "success": True,
        "available": True,
        "status": "PARSED",
        "candidate_count": len(candidates),
        "candidates": candidates,
        "raw_output": raw,
    }


def _scale_1000_box(coords: List[float], image_width: int, image_height: int) -> List[float]:
    x1, y1, x2, y2 = coords
    return [
        round(max(0.0, min(1000.0, x1)) / 1000.0 * image_width, 2),
        round(max(0.0, min(1000.0, y1)) / 1000.0 * image_height, 2),
        round(max(0.0, min(1000.0, x2)) / 1000.0 * image_width, 2),
        round(max(0.0, min(1000.0, y2)) / 1000.0 * image_height, 2),
    ]


def _filter_implausible_full_frame_candidates(payload: Dict[str, Any], image_path: str) -> Dict[str, Any]:
    candidates = payload.get("candidates") or []
    if not isinstance(candidates, list) or not candidates:
        return payload
    try:
        with Image.open(image_path) as image:
            image_width, image_height = image.size
    except Exception:
        return payload
    if image_width <= 0 or image_height <= 0:
        return payload

    try:
        max_area_ratio = float(os.getenv("LOCATE_ANYTHING_MAX_BOX_AREA_RATIO", "0.90"))
    except (TypeError, ValueError):
        max_area_ratio = 0.90
    max_area_ratio = max(0.5, min(1.0, max_area_ratio))
    edge_margin_x = image_width * 0.02
    edge_margin_y = image_height * 0.02
    image_area = float(image_width * image_height)
    kept: List[Dict[str, Any]] = []
    dropped: List[Dict[str, Any]] = []

    for candidate in candidates:
        box = candidate.get("bbox_xyxy") or candidate.get("bbox") or []
        if not isinstance(box, list) or len(box) < 4:
            kept.append(candidate)
            continue
        try:
            x1, y1, x2, y2 = [float(value) for value in box[:4]]
        except (TypeError, ValueError):
            kept.append(candidate)
            continue
        area_ratio = max(0.0, x2 - x1) * max(0.0, y2 - y1) / image_area
        touches_all_edges = (
            x1 <= edge_margin_x
            and y1 <= edge_margin_y
            and x2 >= image_width - edge_margin_x
            and y2 >= image_height - edge_margin_y
        )
        if area_ratio >= max_area_ratio and touches_all_edges:
            dropped.append({"label": candidate.get("label"), "bbox_xyxy": box[:4], "area_ratio": round(area_ratio, 6)})
            continue
        kept.append(candidate)

    if not dropped:
        return payload
    result = dict(payload)
    result["candidates"] = kept
    result["candidate_count"] = len(kept)
    metadata = dict(result.get("metadata") or {})
    metadata["full_frame_filter"] = {
        "dropped_count": len(dropped),
        "max_area_ratio": max_area_ratio,
        "dropped": dropped,
    }
    result["metadata"] = metadata
    if not kept:
        result["status"] = "NO_OBJECT_AFTER_FULL_FRAME_FILTER"
    return result
