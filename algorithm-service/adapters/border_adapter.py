import os
import socket
from pathlib import Path
from typing import Any, Dict, List
from urllib.parse import urljoin, urlparse

import requests
import yaml


REGISTRY_PATH = Path("/root/autodl-fs/Annotation-Platform/config/border_model_registry.yaml")
DEFAULT_TIMEOUT_SECONDS = 30


def status() -> Dict[str, Any]:
    base_url = _base_url()
    parsed = urlparse(base_url) if base_url else None
    credentials_loaded = bool(os.getenv("BORDER_API_APP_KEY")) and bool(os.getenv("BORDER_API_APP_SECRET"))
    tcp_available = _tcp_available(parsed.hostname, parsed.port or _default_port(parsed.scheme)) if parsed else False
    available = bool(base_url and credentials_loaded and tcp_available)
    reason = None
    if not base_url:
        reason = "BORDER_API_BASE_URL is not configured."
    elif not credentials_loaded:
        reason = "BORDER_API_APP_KEY or BORDER_API_APP_SECRET is not configured."
    elif not tcp_available:
        reason = f"third-party API is not reachable from this container: {parsed.netloc}"
    return {
        "model_id": "border-online-api",
        "name": "第三方厂商在线 API",
        "version": _catalog().get("version") or "border-online",
        "available": available,
        "status": "AVAILABLE" if available else "UNAVAILABLE",
        "endpoint": _public_endpoint(),
        "external_api_used": True,
        "local": False,
        "credentials_loaded": credentials_loaded,
        "base_url_configured": bool(base_url),
        "api_host": parsed.netloc if parsed else None,
        "catalog_path": str(REGISTRY_PATH),
        "reason": reason,
    }


def load_capabilities() -> Dict[str, Any]:
    catalog = _catalog()
    runtime = status()
    raw_models = _expanded_models(catalog)
    capabilities = [_normalize_model(item, catalog, runtime) for item in raw_models]
    return {
        "available": runtime.get("available"),
        "status": runtime.get("status"),
        "provider": catalog.get("provider") or "border-online-api",
        "version": catalog.get("version") or "border-online",
        "catalog_path": str(REGISTRY_PATH),
        "capabilities": capabilities,
        "displayed_count": len(capabilities),
        "runtime_status": runtime,
    }


def match_capabilities(labels: List[str], available_only: bool = True) -> List[Dict[str, Any]]:
    normalized_labels = [_normalize(label) for label in labels if label]
    matches: List[Dict[str, Any]] = []
    for item in load_capabilities().get("capabilities") or []:
        if (item.get("resource") or {}).get("routing_enabled") is False:
            continue
        if available_only and not item.get("available"):
            continue
        score = 0
        for label in normalized_labels:
            for alias in _aliases_for_match(item):
                normalized_alias = _normalize(alias)
                if not normalized_alias:
                    continue
                if label == normalized_alias:
                    score = max(score, 1000 + len(normalized_alias))
                elif label in normalized_alias or normalized_alias in label:
                    score = max(score, 500 + min(len(label), len(normalized_alias)))
        if score > 0:
            matched = dict(item)
            matched["_match_score"] = score
            matches.append(matched)
    return sorted(matches, key=lambda model: model.get("_match_score", 0), reverse=True)


def predict_image(
    image_path: str,
    model_id: str,
    labels: List[str] | None = None,
    job_id: Any = None,
    route_id: Any = None,
    conf: float | None = None,
) -> Dict[str, Any]:
    capability = _capability_by_model_id(model_id)
    if not capability:
        return _unavailable(model_id, "model_id is not configured in border_model_registry.yaml")
    if not capability.get("available"):
        return _unavailable(model_id, capability.get("unavailable_reason") or "third-party model is unavailable")

    path = Path(image_path)
    if not path.exists() or not path.is_file():
        return _unavailable(model_id, f"image not found: {image_path}")

    api_path = ((capability.get("resource") or {}).get("api_path") or "").strip()
    if not api_path:
        return _unavailable(model_id, "api_path is not configured; import the Apifox predict path into border_model_registry.yaml")

    request_config = (capability.get("resource") or {}).get("request") or {}
    file_field = request_config.get("file_field") or os.getenv("BORDER_API_FILE_FIELD", "file")
    label_field = request_config.get("label_field") or os.getenv("BORDER_API_LABEL_FIELD", "labels")
    timeout = int(os.getenv("BORDER_API_TIMEOUT_SECONDS", str(DEFAULT_TIMEOUT_SECONDS)))
    data: Dict[str, str] = {}
    if labels:
        data[label_field] = ",".join(labels)
    if conf is not None:
        data["conf"] = str(conf)
    for key, value in (request_config.get("data") or {}).items():
        data[str(key)] = str(value)

    try:
        with path.open("rb") as file_obj:
            response = requests.post(
                _vendor_url(api_path),
                headers=_auth_headers(),
                files={file_field: (path.name, file_obj, "application/octet-stream")},
                data=data,
                timeout=timeout,
            )
        if response.status_code >= 400:
            return _unavailable(model_id, f"third-party API returned HTTP {response.status_code}: {response.text[:300]}")
        body = response.json()
        candidates = _candidates_from_body(
            body=body,
            image_path=path,
            capability=capability,
            model_id=model_id,
            labels=labels or [],
            job_id=job_id,
            route_id=route_id,
        )
        return {
            "success": True,
            "available": True,
            "status": "COMPLETED",
            "source_model": model_id,
            "candidate_count": len(candidates),
            "candidates": candidates,
            "raw_output": body,
            "metadata": {"external_api_used": True, "api_path": api_path},
        }
    except Exception as exc:
        return _unavailable(model_id, f"third-party adapter failed: {exc}")


def _catalog() -> Dict[str, Any]:
    if not REGISTRY_PATH.exists():
        return {"provider": "border-online-api", "version": "missing-catalog", "models": []}
    with REGISTRY_PATH.open("r", encoding="utf-8") as file_obj:
        data = yaml.safe_load(file_obj) or {}
    if not isinstance(data, dict):
        return {"provider": "border-online-api", "version": "invalid-catalog", "models": []}
    data["models"] = [item for item in data.get("models") or [] if isinstance(item, dict)]
    return data


def _expanded_models(catalog: Dict[str, Any]) -> List[Dict[str, Any]]:
    expanded: List[Dict[str, Any]] = []
    for item in catalog.get("models") or []:
        classes = _string_list(item.get("classes"))
        if not item.get("expand_classes_as_models"):
            expanded.append(item)
            continue

        if item.get("include_aggregate_model", True) is not False:
            aggregate = dict(item)
            aggregate["domain_tags"] = _dedupe(_string_list(aggregate.get("domain_tags")) + ["aggregate"])
            aggregate["routing_enabled"] = False
            expanded.append(aggregate)

        prefix = str(item.get("class_model_id_prefix") or item.get("model_id") or "border-online").strip()
        for class_name in classes:
            class_item = dict(item)
            class_item["model_id"] = f"{prefix}-{_model_id_part(class_name)}"
            class_item["name"] = class_name
            class_item["classes"] = [class_name]
            class_item["aliases"] = _dedupe([class_name] + _class_aliases(class_name) + ["三方厂商", "第三方厂商", "在线检测"])
            class_item["domain_tags"] = _dedupe(_string_list(item.get("domain_tags")) + ["single_capability"])
            class_item["parent_model_id"] = item.get("model_id")
            class_item["claim_boundary"] = item.get("claim_boundary")
            class_item["expand_classes_as_models"] = False
            expanded.append(class_item)
    return expanded


def _normalize_model(item: Dict[str, Any], catalog: Dict[str, Any], runtime: Dict[str, Any]) -> Dict[str, Any]:
    model_id = str(item.get("model_id") or item.get("modelId") or "").strip()
    name = str(item.get("name") or model_id or "第三方厂商模型").strip()
    classes = _string_list(item.get("classes"))
    aliases = _dedupe(_string_list(item.get("aliases")) + [name] + classes)
    api_path = _api_path(item, catalog)
    enabled = item.get("enabled", True) is not False
    available = bool(enabled and runtime.get("available") and api_path)
    unavailable_reason = None
    if not enabled:
        unavailable_reason = "model is disabled in border_model_registry.yaml"
    elif not runtime.get("available"):
        unavailable_reason = runtime.get("reason") or "third-party API is unavailable"
    elif not api_path:
        unavailable_reason = "api_path is empty; fill it from the Apifox document before routing"

    resource = {
        "provider": catalog.get("provider") or "border-online-api",
        "external_api_used": True,
        "api_path": api_path,
        "claim_boundary": item.get("claim_boundary"),
        "parent_model_id": item.get("parent_model_id"),
        "routing_enabled": item.get("routing_enabled", True) is not False,
        "request": item.get("request") or {},
        "runtime_status": {
            "status": runtime.get("status"),
            "available": runtime.get("available"),
            "reason": runtime.get("reason"),
            "api_host": runtime.get("api_host"),
            "credentials_loaded": runtime.get("credentials_loaded"),
        },
    }
    return {
        "model_id": model_id,
        "name": name,
        "version": item.get("version") or catalog.get("version") or "border-online",
        "model_type": item.get("model_type") or "BORDER_VENDOR",
        "task_type": item.get("task_type") or "image_detection",
        "domain_tags": _dedupe(_string_list(item.get("domain_tags")) + ["third_party_vendor", "external_api"]),
        "classes": classes,
        "aliases": aliases,
        "endpoint": item.get("endpoint") or _public_endpoint(),
        "status": "AVAILABLE" if available else "UNAVAILABLE",
        "available": available,
        "resource": resource,
        "license_info": item.get("license_info") or "third-party online API",
        "unavailable_reason": unavailable_reason,
        "source": "border_model_registry.yaml",
        "frontend_visible": True,
    }


def _capability_by_model_id(model_id: str) -> Dict[str, Any] | None:
    for item in load_capabilities().get("capabilities") or []:
        if item.get("model_id") == model_id:
            return item
    return None


def _api_path(item: Dict[str, Any], catalog: Dict[str, Any]) -> str:
    return str(
        item.get("api_path")
        or item.get("apiPath")
        or catalog.get("default_api_path")
        or os.getenv("BORDER_API_PREDICT_PATH")
        or ""
    ).strip()


def _candidates_from_body(
    body: Dict[str, Any],
    image_path: Path,
    capability: Dict[str, Any],
    model_id: str,
    labels: List[str],
    job_id: Any,
    route_id: Any,
) -> List[Dict[str, Any]]:
    raw_items = _first_list(body, ["detections", "objects", "results", "data.detections", "data.objects", "data.results"])
    candidates = []
    for raw in raw_items:
        if not isinstance(raw, dict):
            continue
        bbox = raw.get("bbox") or raw.get("box") or raw.get("xyxy") or raw.get("rect") or []
        if isinstance(bbox, dict):
            bbox = _bbox_from_dict(bbox)
        if not isinstance(bbox, list) or len(bbox) < 4:
            continue
        candidates.append(
            {
                "image_id": image_path.name,
                "label": _candidate_label(raw, labels, capability),
                "bbox_xyxy": [float(value) for value in bbox[:4]],
                "score": float(raw.get("confidence") or raw.get("score") or raw.get("prob") or 0.0),
                "source_model": model_id,
                "model_version": capability.get("version"),
                "prompt": ",".join(labels) if labels else capability.get("name"),
                "raw_output": raw,
                "metadata": {
                    "route_id": route_id,
                    "job_id": job_id,
                    "image_path": str(image_path),
                    "external_api_used": True,
                    "provider": "border-online-api",
                },
            }
        )
    return candidates


def _first_list(body: Dict[str, Any], paths: List[str]) -> List[Any]:
    for path in paths:
        value: Any = body
        for part in path.split("."):
            if not isinstance(value, dict):
                value = None
                break
            value = value.get(part)
        if isinstance(value, list):
            return value
    return []


def _bbox_from_dict(value: Dict[str, Any]) -> List[float]:
    if all(key in value for key in ("x1", "y1", "x2", "y2")):
        return [value["x1"], value["y1"], value["x2"], value["y2"]]
    if all(key in value for key in ("left", "top", "right", "bottom")):
        return [value["left"], value["top"], value["right"], value["bottom"]]
    if all(key in value for key in ("x", "y", "width", "height")):
        x, y, width, height = value["x"], value["y"], value["width"], value["height"]
        return [x, y, float(x) + float(width), float(y) + float(height)]
    return []


def _candidate_label(raw: Dict[str, Any], labels: List[str], capability: Dict[str, Any]) -> str:
    text = str(raw.get("label") or raw.get("class") or raw.get("class_name") or raw.get("name") or "").strip()
    if text:
        for label in labels:
            if _normalize(label) == _normalize(text) or _normalize(label) in _normalize(text) or _normalize(text) in _normalize(label):
                return label
        return text
    return labels[0] if labels else (capability.get("classes") or [capability.get("name")])[0]


def _auth_headers() -> Dict[str, str]:
    app_key = os.getenv("BORDER_API_APP_KEY", "")
    app_secret = os.getenv("BORDER_API_APP_SECRET", "")
    return {
        "appKey": app_key,
        "appSecret": app_secret,
        "X-App-Key": app_key,
        "X-App-Secret": app_secret,
    }


def _base_url() -> str:
    return (os.getenv("BORDER_API_BASE_URL") or "").strip().rstrip("/")


def _vendor_url(api_path: str) -> str:
    return urljoin(_base_url() + "/", api_path.lstrip("/"))


def _public_endpoint() -> str:
    return "/internal/adapters/border/predict"


def _tcp_available(host: str | None, port: int | None) -> bool:
    if not host or not port:
        return False
    try:
        with socket.create_connection((host, port), timeout=float(os.getenv("BORDER_API_CONNECT_TIMEOUT_SECONDS", "2"))):
            return True
    except OSError:
        return False


def _default_port(scheme: str | None) -> int:
    return 443 if scheme == "https" else 80


def _aliases_for_match(item: Dict[str, Any]) -> List[str]:
    return _dedupe([item.get("name")] + _string_list(item.get("classes")) + _string_list(item.get("aliases")))


def _model_id_part(value: str) -> str:
    text = _normalize(value)
    return text or "unknown"


def _class_aliases(class_name: str) -> List[str]:
    normalized = _normalize(class_name)
    aliases = {
        "安全帽": ["helmet", "hardhat", "戴安全帽"],
        "安全帽未佩戴": ["未戴安全帽", "没戴安全帽", "no helmet", "without helmet"],
        "反光衣": ["reflective vest", "safety vest", "hi-vis vest", "穿反光衣"],
        "未穿反光衣": ["没穿反光衣", "no safety vest", "without reflective vest", "no vest"],
        "人形": ["person", "human", "行人"],
        "红外人形": ["infrared person", "thermal person", "红外行人"],
        "车辆": ["vehicle", "car", "汽车"],
        "红外车辆": ["infrared vehicle", "thermal vehicle"],
        "船只": ["boat", "ship"],
        "红外船只": ["infrared boat", "thermal boat"],
        "烟火": ["smoke fire", "fire smoke", "明火", "烟雾"],
        "红外烟火": ["infrared fire", "thermal fire"],
        "通用文字ocr识别": ["OCR", "文字识别", "text recognition"],
    }
    return aliases.get(normalized, [])


def _string_list(value: Any) -> List[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if item is not None and str(item).strip()]


def _dedupe(values: List[Any]) -> List[str]:
    seen = set()
    result = []
    for value in values:
        text = str(value or "").strip()
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result


def _normalize(value: Any) -> str:
    return "".join(ch for ch in str(value or "").lower() if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")


def _unavailable(model_id: str, reason: str) -> Dict[str, Any]:
    return {
        "success": True,
        "available": False,
        "status": "UNAVAILABLE",
        "source_model": model_id,
        "reason": reason,
        "candidates": [],
    }
