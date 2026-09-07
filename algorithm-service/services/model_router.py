from typing import Any, Dict, List

from adapters import locate_anything_adapter
from schemas.auto_label_to_model import HYBRID_TEACHER_QUALITY, QUALITY_FIRST

GROUNDING_DINO_MODEL_ID = "grounding-dino"
LOCATE_ANYTHING_MODEL_ID = "local-locate-anything-3b"
ALLOWED_MODEL_IDS = [LOCATE_ANYTHING_MODEL_ID, GROUNDING_DINO_MODEL_ID]


def preview_route(payload: Dict[str, Any]) -> Dict[str, Any]:
    label_schema = payload.get("label_schema") or (payload.get("requirement") or {}).get("label_schema") or []
    labels = [
        str(item.get("display_name") or item.get("canonical_name") or "").strip()
        for item in label_schema
        if isinstance(item, dict)
    ]
    labels = [label for label in labels if label]
    if not labels:
        return {
            "success": False,
            "available": False,
            "status": "NEED_USER_LABEL_CONFIRMATION",
            "reason": "模型路由需要先确认 label_schema。",
            "priority_mode": QUALITY_FIRST,
            "strategy": HYBRID_TEACHER_QUALITY,
        }

    locate_status = locate_anything_adapter.status()
    locate_available = bool(locate_status.get("available"))
    unavailable = [] if locate_available else [LOCATE_ANYTHING_MODEL_ID]
    reason = (
        "按当前路由策略仅使用 LocateAnything 与 Grounding DINO；"
        "LocateAnything 负责开放词汇主候选，Grounding DINO 按空候选兜底和周期抽样补充。"
        if locate_available else
        "按当前路由策略仅使用 LocateAnything 与 Grounding DINO；"
        "LocateAnything 当前不可用，运行时会保留不可用报告并由 Grounding DINO 继续出候选。"
    )
    return {
        "success": True,
        "available": True,
        "dataset_id": payload.get("dataset_id"),
        "primary_model_id": LOCATE_ANYTHING_MODEL_ID,
        "auxiliary_model_ids": [GROUNDING_DINO_MODEL_ID],
        "strategy": HYBRID_TEACHER_QUALITY,
        "priority_mode": QUALITY_FIRST,
        "reason": reason,
        "score": {
            "labels": labels,
            "route_policy": "locate_anything_grounding_dino_only",
            "allowed_model_ids": ALLOWED_MODEL_IDS,
            "auxiliary_policy": "primary_empty_or_sampled",
            "auxiliary_sample_interval": 25,
            "disabled_model_families": ["xingmu_model", "border_vendor", "local-qwen3-vl-4b", "yolo-world"],
            "locate_anything_status": locate_status,
            "local_quality_models_unavailable": unavailable,
        },
    }
