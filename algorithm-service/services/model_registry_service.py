from typing import Any, Dict, List, Optional

from adapters import border_adapter, grounding_dino_adapter, local_vlm_adapter, locate_anything_adapter, xingmu_adapter, yolo_world_adapter


def sync_registry() -> Dict[str, Any]:
    models = list_models()
    return {
        "success": True,
        "available": True,
        "status": "SYNCED_FROM_LOCAL_MANIFESTS",
        "total": len(models),
        "xingmu_displayed_count": len([m for m in models if m.get("model_type") == "XINGMU_SCENARIO"]),
        "hidden_xingmu_excluded": xingmu_adapter.load_displayed_capabilities().get("hidden_excluded", []),
        "border_vendor_count": len([m for m in models if m.get("model_type") == "BORDER_VENDOR"]),
        "items": models,
        "adapter_status": adapter_status(),
        "xingmu_model_groups": xingmu_adapter.model_groups_registry(),
    }


def list_models(model_type: Optional[str] = None) -> List[Dict[str, Any]]:
    xingmu_catalog = xingmu_adapter.load_displayed_capabilities()
    models = list(xingmu_catalog.get("capabilities") or [])
    models.extend(border_adapter.load_capabilities().get("capabilities") or [])
    models.extend(
        [
            _status_to_registry(grounding_dino_adapter.status(), "GROUNDING_DINO", "open_vocabulary_detection"),
            _status_to_registry(locate_anything_adapter.status(), "LOCAL_GROUNDING_LMM", "language_grounding"),
            _status_to_registry(local_vlm_adapter.status(), "LOCAL_VLM", "semantic_verification"),
            _status_to_registry(yolo_world_adapter.status(), "YOLO_WORLD_PLACEHOLDER", "open_vocabulary_detection"),
        ]
    )
    if model_type:
        models = [model for model in models if model.get("model_type") == model_type]
    return models


def get_model(model_id: str) -> Optional[Dict[str, Any]]:
    for model in list_models():
        if model.get("model_id") == model_id:
            return model
    return None


def adapter_status() -> Dict[str, Any]:
    return {
        "grounding_dino": grounding_dino_adapter.status(),
        "xingmu": xingmu_adapter.service_status(),
        "border_vendor": border_adapter.status(),
        "locate_anything": locate_anything_adapter.status(),
        "local_vlm": local_vlm_adapter.status(),
        "yolo_world": yolo_world_adapter.status(),
    }


def xingmu_model_groups() -> List[Dict[str, Any]]:
    return xingmu_adapter.model_groups_registry()


def _status_to_registry(status: Dict[str, Any], model_type: str, task_type: str) -> Dict[str, Any]:
    return {
        "model_id": status.get("model_id"),
        "name": status.get("name"),
        "version": status.get("version") or status.get("status", "").lower(),
        "model_type": model_type,
        "task_type": task_type,
        "domain_tags": [model_type.lower()],
        "classes": status.get("classes") or [],
        "aliases": status.get("aliases") or [status.get("name")],
        "endpoint": status.get("endpoint"),
        "status": status.get("status"),
        "available": status.get("available", False),
        "resource": {key: value for key, value in status.items() if key not in {"model_id", "name", "status", "available"}},
        "unavailable_reason": None if status.get("available", False) else status.get("reason"),
    }
