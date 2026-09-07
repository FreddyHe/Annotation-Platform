from collections import defaultdict
import re
from typing import Any, Dict, List


def merge_candidates(payload: Dict[str, Any]) -> Dict[str, Any]:
    candidates: List[Dict[str, Any]] = payload.get("candidates") or []
    allowed_labels = _allowed_labels(payload)
    candidates, label_warnings = _normalize_candidates(candidates, allowed_labels)
    if candidates:
        iou_threshold = float(payload.get("iou_threshold") or 0.55)
        fused = _nms_score_merge(candidates, iou_threshold=iou_threshold)
        return {
            "success": True,
            "available": True,
            "status": "FUSION_COMPLETED",
            "external_api_used": False,
            "candidate_count": len(candidates),
            "fused_count": len(fused),
            "fused_predictions": fused,
            "fusion_method": "nms_score_merge",
            "wbf_available": False,
            "allowed_labels": allowed_labels,
            "dropped_candidate_count": len(label_warnings),
            "warnings": label_warnings[:100],
            "message": "使用内置 NMS + score merge 完成质量优先融合；WBF 接口保留但未新增依赖。",
        }
    return {
        "success": True,
        "available": True,
        "status": "DRY_RUN_EMPTY_FUSION" if not candidates else "NMS_PLACEHOLDER_READY",
        "external_api_used": False,
        "candidate_count": len(candidates),
        "fused_predictions": [],
        "allowed_labels": allowed_labels,
        "dropped_candidate_count": len(label_warnings),
        "warnings": label_warnings[:100],
        "message": "未提供 candidates，融合保持 dry-run，不伪造预测结果。",
    }


def _allowed_labels(payload: Dict[str, Any]) -> List[str]:
    labels = payload.get("allowed_labels") or payload.get("labels") or []
    if not isinstance(labels, list):
        return []
    result: List[str] = []
    for label in labels:
        text = str(label or "").strip()
        if text and text not in result:
            result.append(text)
    return result


def _normalize_candidates(candidates: List[Dict[str, Any]], allowed_labels: List[str]) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    if not allowed_labels:
        return candidates, []
    normalized: List[Dict[str, Any]] = []
    warnings: List[Dict[str, Any]] = []
    for index, candidate in enumerate(candidates):
        raw_label = str(candidate.get("label") or "").strip()
        label = _normalize_label(raw_label, allowed_labels, candidate)
        if not label:
            warnings.append(
                {
                    "code": "INVALID_LABEL_FOR_PROJECT",
                    "index": index,
                    "raw_label": raw_label,
                    "source_model": candidate.get("source_model"),
                    "image_id": candidate.get("image_id"),
                }
            )
            continue
        item = dict(candidate)
        item["label"] = label
        if raw_label != label:
            metadata = dict(item.get("metadata") or {})
            metadata["raw_label_before_fusion_normalization"] = raw_label
            item["metadata"] = metadata
        normalized.append(item)
    return normalized, warnings


def _normalize_label(raw_label: str, allowed_labels: List[str], candidate: Dict[str, Any] | None = None) -> str | None:
    if not raw_label:
        return None
    raw = raw_label.strip()
    source_model = str((candidate or {}).get("source_model") or "").lower()
    if "[unk]" in raw.lower() and "grounding-dino" in source_model:
        return None
    normalized_raw = _normalize_text(raw)
    alias_pairs = _label_alias_pairs(allowed_labels)

    for label, _, normalized in alias_pairs:
        if normalized_raw == normalized:
            return label

    legal_hits = [
        label for label, _, normalized in sorted(alias_pairs, key=lambda item: len(item[2]), reverse=True)
        if normalized and len(normalized) >= 2 and (normalized in normalized_raw or normalized_raw in normalized)
    ]
    if len(legal_hits) == 1:
        return legal_hits[0]
    if len(legal_hits) > 1:
        return None

    if _has_any(normalized_raw, ["reflectivevest", "safetyvest", "vest", "fan", "guang", "反光", "光"]):
        label = _first_allowed(allowed_labels, ["反光衣", "反光", "vest"])
        if label:
            return label
    if _has_any(normalized_raw, ["lanyard", "rope", "lifeline", "登高", "安全绳", "高绳", "绳", "高"]):
        label = _first_allowed(allowed_labels, ["安全登高绳", "登高", "安全绳", "绳"])
        if label:
            return label
    if _has_any(normalized_raw, ["helmet", "hardhat", "hat", "安全帽", "头盔", "帽"]):
        label = _first_allowed(allowed_labels, ["安全帽", "helmet", "帽"])
        if label:
            return label
    return None


def _normalize_text(value: str) -> str:
    text = str(value or "").lower()
    text = text.replace("[unk]", "").replace("unk", "")
    return re.sub(r"[\s_./,，。:：;；#<>|\\-]+", "", text)


def _has_any(text: str, needles: List[str]) -> bool:
    return any(_normalize_text(needle) in text for needle in needles if needle)


def _first_allowed(allowed_labels: List[str], needles: List[str]) -> str | None:
    for needle in needles:
        normalized_needle = _normalize_text(needle)
        for label in allowed_labels:
            if normalized_needle and normalized_needle in _normalize_text(label):
                return label
    return None


def _label_alias_pairs(labels: List[str]) -> List[tuple[str, str, str]]:
    pairs: List[tuple[str, str, str]] = []
    seen = set()
    for label in labels:
        for alias in [label, *_traffic_aliases(label)]:
            normalized = _normalize_text(alias)
            key = (label, normalized)
            if normalized and key not in seen:
                pairs.append((label, alias, normalized))
                seen.add(key)
    return pairs


def _traffic_aliases(label: str) -> List[str]:
    normalized = _normalize_text(label)
    aliases = {
        "人": ["person", "pedestrian", "people", "human"],
        "人形": ["person", "pedestrian", "people", "human"],
        "车": ["车辆", "汽车", "机动车", "car", "vehicle", "automobile", "van", "truck"],
        "车辆": ["车", "汽车", "机动车", "car", "vehicle", "automobile", "van", "truck"],
        "自行车": ["单车", "脚踏车", "bicycle", "bike"],
        "电动车": ["电瓶车", "电动自行车", "电动摩托车", "electric bicycle", "e-bike", "ebike", "electric scooter", "electric motorcycle"],
        "摩托车": ["摩托", "机车", "motorcycle", "motorbike", "scooter"],
    }
    return aliases.get(normalized, [])


def _nms_score_merge(candidates: List[Dict[str, Any]], iou_threshold: float) -> List[Dict[str, Any]]:
    grouped: Dict[tuple, List[Dict[str, Any]]] = defaultdict(list)
    for candidate in candidates:
        key = (candidate.get("image_id"), candidate.get("label"))
        grouped[key].append(candidate)

    fused = []
    for (image_id, label), items in grouped.items():
        remaining = sorted(items, key=lambda item: float(item.get("score") or 0), reverse=True)
        while remaining:
            head = remaining.pop(0)
            cluster = [head]
            survivors = []
            for item in remaining:
                if _iou(_bbox(head), _bbox(item)) >= iou_threshold:
                    cluster.append(item)
                else:
                    survivors.append(item)
            remaining = survivors
            fused.append(_merge_cluster(image_id, label, cluster))
    return fused


def _merge_cluster(image_id: str, label: str, cluster: List[Dict[str, Any]]) -> Dict[str, Any]:
    total_weight = sum(max(float(item.get("score") or 0), 0.001) for item in cluster)
    merged_box = [0.0, 0.0, 0.0, 0.0]
    for item in cluster:
        weight = max(float(item.get("score") or 0), 0.001) / total_weight
        box = _bbox(item)
        for index in range(4):
            merged_box[index] += box[index] * weight
    scores = [float(item.get("score") or 0) for item in cluster]
    source_models = sorted({str(item.get("source_model") or "unknown") for item in cluster})
    final_score = max(scores) if scores else 0.0
    review_priority = _review_priority(final_score, source_models, cluster)
    return {
        "image_id": image_id,
        "label": label,
        "bbox_xyxy": [round(value, 2) for value in merged_box],
        "final_score": round(final_score, 6),
        "source_models": source_models,
        "fusion_reason": _fusion_reason(cluster, source_models, final_score),
        "review_priority": review_priority,
        "candidate_count": len(cluster),
        "raw_candidates": cluster,
    }


def _review_priority(final_score: float, source_models: List[str], cluster: List[Dict[str, Any]]) -> str:
    if final_score < 0.45:
        return "HIGH"
    if len(source_models) == 1 and len(cluster) == 1:
        return "MEDIUM"
    return "LOW"


def _fusion_reason(cluster: List[Dict[str, Any]], source_models: List[str], final_score: float) -> str:
    if len(cluster) == 1:
        return "single_candidate_manual_review_recommended" if final_score < 0.45 else "single_candidate_kept"
    return f"merged_{len(cluster)}_candidates_from_{len(source_models)}_models"


def _bbox(candidate: Dict[str, Any]) -> List[float]:
    box = candidate.get("bbox_xyxy") or candidate.get("bbox") or [0, 0, 0, 0]
    return [float(value) for value in box[:4]]


def _iou(a: List[float], b: List[float]) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    inter_x1 = max(ax1, bx1)
    inter_y1 = max(ay1, by1)
    inter_x2 = min(ax2, bx2)
    inter_y2 = min(ay2, by2)
    inter_w = max(0.0, inter_x2 - inter_x1)
    inter_h = max(0.0, inter_y2 - inter_y1)
    inter_area = inter_w * inter_h
    area_a = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
    area_b = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
    union = area_a + area_b - inter_area
    return inter_area / union if union > 0 else 0.0
