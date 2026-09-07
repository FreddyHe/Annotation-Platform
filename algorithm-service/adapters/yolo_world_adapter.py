from typing import Any, Dict


def status() -> Dict[str, Any]:
    return {
        "model_id": "yolo-world-placeholder",
        "name": "YOLO-World Placeholder",
        "available": False,
        "status": "UNAVAILABLE",
        "local": True,
        "optional": True,
        "external_api_used": False,
        "reason": "YOLO-World 本轮仅预留 adapter，不作为主线硬依赖。",
    }
