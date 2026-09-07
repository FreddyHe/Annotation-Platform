from pathlib import Path
from typing import Any, Dict, List, Tuple
from xml.etree import ElementTree

from PIL import Image


def format_predictions(payload: Dict[str, Any]) -> Dict[str, Any]:
    fused: List[Dict[str, Any]] = payload.get("fused_predictions") or []
    if fused:
        labeling_target = _labeling_target(payload)
        allowed_labels = _allowed_labels(payload)
        model_version = str(payload.get("model_version") or f"HYBRID_TEACHER_QUALITY/{payload.get('job_id') or 'unknown'}")
        predictions_by_image: Dict[str, Dict[str, Any]] = {}
        warnings = []
        for index, prediction in enumerate(fused):
            image_id = Path(str(prediction.get("image_id") or prediction.get("imageName") or "")).name
            raw_label = str(prediction.get("label") or "")
            label = _normalize_prediction_label(raw_label, allowed_labels)
            bbox = prediction.get("bbox_xyxy") or prediction.get("bbox") or []
            score = float(prediction.get("final_score") or prediction.get("score") or 0.0)
            if not image_id or len(bbox) < 4:
                warnings.append({"code": "INVALID_FUSED_PREDICTION", "index": index})
                continue
            if not label:
                warnings.append({"code": "INVALID_LABEL_FOR_CONFIG", "index": index, "label": raw_label, "image_id": image_id})
                continue
            size = _image_size(prediction, payload)
            if not size:
                warnings.append({"code": "IMAGE_SIZE_MISSING", "image_id": image_id})
                continue
            width, height = size
            result = _rectangle_result(
                image_id=image_id,
                label=label,
                bbox=[float(value) for value in bbox[:4]],
                image_width=width,
                image_height=height,
                score=score,
                index=len(predictions_by_image.get(image_id, {}).get("results", [])),
                labeling_target=labeling_target,
            )
            image_prediction = predictions_by_image.setdefault(
                image_id,
                {"image_name": image_id, "results": [], "scores": []},
            )
            image_prediction["results"].append(result)
            image_prediction["scores"].append(score)

        predictions = []
        for item in predictions_by_image.values():
            scores = item.pop("scores", [])
            item["avg_score"] = round(sum(scores) / len(scores), 6) if scores else 0.0
            item["model_version"] = model_version
            predictions.append(item)

        task_id_by_image = payload.get("task_id_by_image") or payload.get("taskIdByImage") or {}
        bulk_payload = []
        for item in predictions:
            task_id = task_id_by_image.get(item["image_name"])
            if task_id is not None:
                bulk_payload.append(
                    {
                        "task": task_id,
                        "result": item["results"],
                        "model_version": item["model_version"],
                        "score": item["avg_score"],
                    }
                )

        return {
            "success": True,
            "available": True,
            "status": "FORMAT_COMPLETED",
            "external_api_used": False,
            "job_id": payload.get("job_id"),
            "prediction_count": len(predictions),
            "result_count": sum(len(item["results"]) for item in predictions),
            "predictions": predictions,
            "bulk_payload": bulk_payload,
            "bulk_payload_ready": bool(bulk_payload),
            "warnings": warnings,
        }

    return {
        "success": True,
        "available": True,
        "status": "DRY_RUN_EMPTY_LABEL_STUDIO_PREDICTIONS" if not fused else "FORMATTER_PLACEHOLDER_READY",
        "external_api_used": False,
        "job_id": payload.get("job_id"),
        "prediction_count": 0,
        "predictions": [],
        "message": "Label Studio prediction formatter 已接入；Stage 6 会根据真实 fused prediction 输出 LS 预标注。",
    }


def _rectangle_result(
    image_id: str,
    label: str,
    bbox: List[float],
    image_width: int,
    image_height: int,
    score: float,
    index: int,
    labeling_target: Dict[str, str],
) -> Dict[str, Any]:
    x1, y1, x2, y2 = bbox
    x = _clamp_pct(x1 / image_width * 100.0)
    y = _clamp_pct(y1 / image_height * 100.0)
    width = _clamp_pct((x2 - x1) / image_width * 100.0)
    height = _clamp_pct((y2 - y1) / image_height * 100.0)
    return {
        "original_width": image_width,
        "original_height": image_height,
        "image_rotation": 0,
        "value": {
            "x": x,
            "y": y,
            "width": width,
            "height": height,
            "rotation": 0,
            "rectanglelabels": [label],
        },
        "id": f"{Path(image_id).stem}_{index}",
        "from_name": labeling_target["from_name"],
        "to_name": labeling_target["to_name"],
        "type": labeling_target["result_type"],
        "score": score,
    }


def _allowed_labels(payload: Dict[str, Any]) -> List[str]:
    explicit = payload.get("labels") or payload.get("allowed_labels") or payload.get("allowedLabels") or []
    labels = [str(label).strip() for label in explicit if str(label).strip()]
    if labels:
        return labels

    label_config = str(payload.get("label_config") or "")
    if not label_config:
        return []
    try:
        root = ElementTree.fromstring(label_config)
        values = []
        for element in root.iter():
            if element.tag == "Label":
                value = element.attrib.get("value")
                if value:
                    values.append(value.strip())
        return values
    except ElementTree.ParseError:
        return []


def _normalize_prediction_label(raw_label: str, allowed_labels: List[str]) -> str | None:
    label = str(raw_label or "").strip()
    if not label:
        return None
    if not allowed_labels:
        return label
    if label in allowed_labels:
        return label
    if "," in label or "，" in label:
        return None

    normalized_label = _normalize_text(label)
    for allowed in allowed_labels:
        normalized_allowed = _normalize_text(allowed)
        if normalized_allowed and (normalized_allowed in normalized_label or normalized_label in normalized_allowed):
            return allowed

    heuristics = [
        (("反光", "reflect", "vest", "背心", "光"), ("反光", "衣", "背心")),
        (("helmet", "hat", "安全帽", "帽"), ("帽",)),
        (("rope", "lanyard", "绳", "登高", "高"), ("绳", "登高")),
    ]
    for needles, allowed_needles in heuristics:
        if any(needle in normalized_label for needle in needles):
            for allowed in allowed_labels:
                normalized_allowed = _normalize_text(allowed)
                if any(needle in normalized_allowed for needle in allowed_needles):
                    return allowed
    return None


def _normalize_text(value: str) -> str:
    return "".join(ch for ch in str(value or "").lower() if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")


def _labeling_target(payload: Dict[str, Any]) -> Dict[str, str]:
    parsed = _parse_label_config(str(payload.get("label_config") or ""))
    return {
        "from_name": str(payload.get("from_name") or parsed.get("from_name") or "label"),
        "to_name": str(payload.get("to_name") or parsed.get("to_name") or "image"),
        "result_type": str(payload.get("result_type") or parsed.get("result_type") or "rectanglelabels"),
    }


def _parse_label_config(label_config: str) -> Dict[str, str]:
    if not label_config.strip():
        return {}
    try:
        root = ElementTree.fromstring(label_config)
    except ElementTree.ParseError:
        return {}
    for element in root.iter():
        if element.tag == "RectangleLabels":
            return {
                "from_name": element.attrib.get("name", ""),
                "to_name": element.attrib.get("toName", ""),
                "result_type": "rectanglelabels",
            }
    return {}


def _image_size(prediction: Dict[str, Any], payload: Dict[str, Any]) -> Tuple[int, int] | None:
    width = prediction.get("image_width") or prediction.get("original_width") or payload.get("image_width")
    height = prediction.get("image_height") or prediction.get("original_height") or payload.get("image_height")
    if width and height:
        return int(width), int(height)
    image_path = _image_path_from_prediction(prediction)
    if image_path and Path(image_path).exists():
        try:
            with Image.open(image_path) as image:
                return image.width, image.height
        except Exception:
            return None
    return None


def _image_path_from_prediction(prediction: Dict[str, Any]) -> str | None:
    metadata = prediction.get("metadata") or {}
    if metadata.get("image_path"):
        return str(metadata["image_path"])
    for candidate in prediction.get("raw_candidates") or []:
        candidate_metadata = candidate.get("metadata") or {}
        if candidate_metadata.get("image_path"):
            return str(candidate_metadata["image_path"])
    return None


def _clamp_pct(value: float) -> float:
    return round(max(0.0, min(100.0, value)), 6)
