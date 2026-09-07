from pathlib import Path
from typing import Any, Dict, List
import re
import socket
import sys

import httpx
from loguru import logger


XINGMU_ROOT = Path("/root/autodl-fs/xingmu_model")
XINGMU_SERVICE_URL = "http://127.0.0.1:9000"
XINGMU_REGISTRY_PATH = Path("/root/autodl-fs/Annotation-Platform/config/xingmu_model_registry.yaml")
XINGMU_DEPLOY_DIR = XINGMU_ROOT / "deploy"
XINGMU_DEPLOY_CURRENT_REGISTRY_PATH = XINGMU_DEPLOY_DIR / "current" / "model_registry.yaml"
_LOCAL_YOLO_CACHE: Dict[str, Any] = {}
HIDDEN_FALLBACK = {
    "小吃车",
    "违规摆摊",
    "非法垂钓",
    "违停区域占用",
    "高速应急车道占用",
    "行人闯入高速公路",
    "林场违建识别",
    "非法偷盗识别",
}

DISPLAYED_EXCEL_NO = {
    "人形": 1,
    "红外人形": 2,
    "车辆": 3,
    "红外车辆": 4,
    "人群聚集": 5,
    "船只": 6,
    "红外船只": 7,
    "通用文字OCR识别": 8,
    "烟火": 9,
    "红外烟火": 10,
    "安全帽": 11,
    "安全帽未佩戴": 12,
    "工程车辆": 13,
    "挖掘机": 14,
    "渣土车": 15,
    "垃圾包": 16,
    "建筑垃圾乱堆": 19,
    "井盖": 20,
    "太阳能板": 21,
    "水面漂浮物": 23,
    "水面油污": 24,
    "水面垃圾": 25,
    "水面植物": 26,
    "涉水识别": 27,
    "东西焚烧": 28,
    "交通拥堵": 29,
    "路面标线": 30,
    "路面破损": 31,
    "路面杂物": 32,
    "路面积水": 33,
    "非机动车道占用": 34,
    "近景车牌号码识别": 36,
}


def canonical_name(label: str) -> str:
    text = (label or "").strip().lower()
    text = re.sub(r"[^a-zA-Z0-9\u4e00-\u9fa5]+", "_", text)
    return text.strip("_") or "unknown"


def load_displayed_capabilities(root: Path = XINGMU_ROOT) -> Dict[str, Any]:
    module_dir = root / "platform" / "inference_service"
    if not module_dir.exists():
        return _fallback_catalog("xingmu inference_service directory not found")

    inserted = False
    try:
        module_dir_str = str(module_dir)
        if module_dir_str not in sys.path:
            sys.path.insert(0, module_dir_str)
            inserted = True
        from capability_catalog import CAPABILITY_MAP, HIDDEN_FRONTEND_ALGORITHMS, build_capability_catalog

        catalog = build_capability_catalog(root)
        capabilities = []
        for item in catalog.get("capabilities", []):
            algorithm = item.get("algorithm") or item.get("name") or ""
            if algorithm in HIDDEN_FRONTEND_ALGORITHMS:
                continue
            capabilities.append(_normalize_capability(item))
        if not capabilities:
            capabilities = _capabilities_from_capability_map(CAPABILITY_MAP, HIDDEN_FRONTEND_ALGORITHMS)
        deploy_catalog = _deployment_registry_catalog(root)
        capabilities = _merge_capabilities(capabilities, deploy_catalog.get("capabilities") or [])
        hidden = sorted(HIDDEN_FRONTEND_ALGORITHMS)
        return {
            "available": True,
            "root": str(root),
            "capabilities": capabilities,
            "displayed_count": len(capabilities),
            "hidden_excluded": hidden,
            "coverage_summary": catalog.get("coverage_summary") or {},
            "deploy_registry": deploy_catalog.get("registry"),
            "deploy_version": deploy_catalog.get("version"),
        }
    except Exception as exc:
        logger.warning(f"load xingmu capability catalog failed: {exc}")
        return _fallback_catalog(str(exc))
    finally:
        if inserted:
            try:
                sys.path.remove(str(module_dir))
            except ValueError:
                pass


def service_status() -> Dict[str, Any]:
    available = _tcp_available("127.0.0.1", 9000)
    result = {
        "model_id": "xingmu-model-service",
        "name": "xingmu_model inference_service",
        "available": available,
        "status": "AVAILABLE" if available else "UNAVAILABLE",
        "endpoint": XINGMU_SERVICE_URL,
        "local": True,
    }
    if not available:
        result["reason"] = "xingmu inference_service is not listening on 127.0.0.1:9000"
        return result
    try:
        response = httpx.get(f"{XINGMU_SERVICE_URL}/healthz", timeout=5.0)
        result["status_code"] = response.status_code
        result["health"] = response.json() if response.headers.get("content-type", "").startswith("application/json") else response.text
        result["available"] = response.status_code < 400
        result["status"] = "AVAILABLE" if result["available"] else "UNAVAILABLE"
    except Exception as exc:
        result["available"] = False
        result["status"] = "UNAVAILABLE"
        result["reason"] = f"xingmu health check failed: {exc}"
    return result


def predict_image(
    image_path: str,
    model_id: str,
    label: str | None = None,
    job_id: Any = None,
    route_id: Any = None,
    conf: float | None = None,
) -> Dict[str, Any]:
    status = service_status()
    if not status.get("available"):
        return {
            "success": True,
            "available": False,
            "status": "UNAVAILABLE",
            "source_model": model_id,
            "reason": status.get("reason") or "xingmu service unavailable",
            "candidates": [],
        }
    capability = _capability_by_model_id(model_id)
    if not capability:
        return {
            "success": True,
            "available": False,
            "status": "UNAVAILABLE",
            "source_model": model_id,
            "reason": "model_id is not in the 32 displayed xingmu capabilities",
            "candidates": [],
        }
    path = Path(image_path)
    if not path.exists():
        return {
            "success": True,
            "available": False,
            "status": "UNAVAILABLE",
            "source_model": model_id,
            "reason": f"image not found: {image_path}",
            "candidates": [],
        }
    endpoint = capability.get("endpoint")
    model_group = capability.get("model_group")
    if endpoint == "/api/model-demo/event-video-replay":
        sample_id = capability.get("sample_id")
        if not sample_id:
            return {
                "success": True,
                "available": True,
                "status": "COMPLETED_NO_IMAGE_CANDIDATES",
                "source_model": model_id,
                "candidate_count": 0,
                "reason": "event sample endpoint is available but no sample_id is configured for this capability",
                "candidates": [],
                "metadata": {"endpoint": endpoint, "task_type": capability.get("task_type")},
            }
        try:
            response = httpx.post(
                f"{XINGMU_SERVICE_URL}{endpoint}",
                data={"sample_id": sample_id},
                timeout=60.0,
            )
            if response.status_code >= 400:
                return _unavailable_response(model_id, f"xingmu event endpoint returned HTTP {response.status_code}: {response.text[:200]}")
            return {
                "success": True,
                "available": True,
                "status": "COMPLETED_NO_IMAGE_CANDIDATES",
                "source_model": model_id,
                "candidate_count": 0,
                "reason": "event sample endpoint completed; it does not emit current-image boxes",
                "candidates": [],
                "raw_output": response.json(),
                "metadata": {"endpoint": endpoint, "sample_id": sample_id, "task_type": capability.get("task_type")},
            }
        except Exception as exc:
            return _unavailable_response(model_id, f"xingmu event adapter failed: {exc}")

    if endpoint != "/api/model-demo/predict":
        return _predict_single_image_endpoint(
            image_path=path,
            endpoint=endpoint,
            capability=capability,
            model_id=model_id,
            label=label,
            job_id=job_id,
            route_id=route_id,
            conf=conf,
        )

    if not model_group:
        return {
            "success": True,
            "available": False,
            "status": "UNAVAILABLE",
            "source_model": model_id,
            "reason": f"xingmu model_group is missing for endpoint {endpoint}",
            "candidates": [],
        }
    local_weight = _capability_weight_path(capability)
    if local_weight:
        local_result = _predict_local_yolo_weight(
            weight_path=local_weight,
            image_path=path,
            capability=capability,
            model_id=model_id,
            label=label,
            job_id=job_id,
            route_id=route_id,
            conf=conf,
        )
        if local_result.get("status") == "COMPLETED":
            return local_result

    data = {"model_group": model_group, "backend": "pt"}
    class_filter = _class_filter(capability, label)
    if class_filter:
        data["classes"] = class_filter
    if conf is not None:
        data["conf"] = str(conf)
    try:
        with path.open("rb") as file_obj:
            response = httpx.post(
                f"{XINGMU_SERVICE_URL}{endpoint}",
                files={"file": (path.name, file_obj, "application/octet-stream")},
                data=data,
                timeout=120.0,
            )
        if response.status_code >= 400:
            return {
                "success": True,
                "available": False,
                "status": "UNAVAILABLE",
                "source_model": model_id,
                "reason": f"xingmu service returned HTTP {response.status_code}: {response.text[:200]}",
                "candidates": [],
            }
        body = response.json()
        candidates = _candidates_from_detections(
            body.get("detections") or [],
            image_path=path,
            capability=capability,
            model_id=model_id,
            label=label,
            job_id=job_id,
            route_id=route_id,
            metadata={"model_group": model_group, "endpoint": endpoint},
        )
        return {
            "success": True,
            "available": True,
            "status": "COMPLETED",
            "source_model": model_id,
            "candidate_count": len(candidates),
            "candidates": candidates,
            "raw_output": body,
        }
    except Exception as exc:
        return _unavailable_response(model_id, f"xingmu adapter failed: {exc}")


def _predict_single_image_endpoint(
    image_path: Path,
    endpoint: str | None,
    capability: Dict[str, Any],
    model_id: str,
    label: str | None = None,
    job_id: Any = None,
    route_id: Any = None,
    conf: float | None = None,
) -> Dict[str, Any]:
    if not endpoint:
        return _unavailable_response(model_id, "xingmu endpoint is not configured")
    local_weight = _capability_weight_path(capability)
    if local_weight:
        local_result = _predict_local_yolo_weight(
            weight_path=local_weight,
            image_path=image_path,
            capability=capability,
            model_id=model_id,
            label=label,
            job_id=job_id,
            route_id=route_id,
            conf=conf,
        )
        if local_result.get("status") == "COMPLETED":
            return local_result
    try:
        data: Dict[str, str] = {}
        if conf is not None:
            data["conf"] = str(conf)
        with image_path.open("rb") as file_obj:
            response = httpx.post(
                f"{XINGMU_SERVICE_URL}{endpoint}",
                files={"file": (image_path.name, file_obj, "application/octet-stream")},
                data=data,
                timeout=180.0,
            )
        if response.status_code >= 400:
            return _unavailable_response(model_id, f"xingmu endpoint {endpoint} returned HTTP {response.status_code}: {response.text[:200]}")
        body = response.json()
        candidates = _candidates_from_detections(
            body.get("detections") or [],
            image_path=image_path,
            capability=capability,
            model_id=model_id,
            label=label,
            job_id=job_id,
            route_id=route_id,
            metadata={"endpoint": endpoint, "task_type": capability.get("task_type")},
        )
        candidates.extend(
            _candidates_from_ocr_lines(
                body.get("lines") or [],
                image_path=image_path,
                capability=capability,
                model_id=model_id,
                label=label,
                job_id=job_id,
                route_id=route_id,
                metadata={"endpoint": endpoint, "task_type": capability.get("task_type")},
            )
        )
        return {
            "success": True,
            "available": True,
            "status": "COMPLETED",
            "source_model": model_id,
            "candidate_count": len(candidates),
            "candidates": candidates,
            "raw_output": body,
            "metadata": {"endpoint": endpoint, "task_type": capability.get("task_type")},
        }
    except Exception as exc:
        return _unavailable_response(model_id, f"xingmu endpoint adapter failed: {exc}")


def _candidates_from_detections(
    detections: List[Dict[str, Any]],
    image_path: Path,
    capability: Dict[str, Any],
    model_id: str,
    label: str | None,
    job_id: Any,
    route_id: Any,
    metadata: Dict[str, Any] | None = None,
) -> List[Dict[str, Any]]:
    candidates = []
    for detection in detections:
        bbox = detection.get("bbox") or detection.get("xyxy") or []
        if len(bbox) < 4:
            continue
        candidates.append(
            {
                "image_id": image_path.name,
                "label": _candidate_label(detection, label, capability),
                "bbox_xyxy": [float(value) for value in bbox[:4]],
                "score": float(detection.get("confidence") or detection.get("score") or 0.0),
                "source_model": model_id,
                "model_version": capability.get("version"),
                "prompt": label or capability.get("name"),
                "raw_output": detection,
                "metadata": {
                    **(metadata or {}),
                    "route_id": route_id,
                    "job_id": job_id,
                    "image_path": str(image_path),
                },
            }
        )
    return candidates


def _candidates_from_ocr_lines(
    lines: List[Dict[str, Any]],
    image_path: Path,
    capability: Dict[str, Any],
    model_id: str,
    label: str | None,
    job_id: Any,
    route_id: Any,
    metadata: Dict[str, Any] | None = None,
) -> List[Dict[str, Any]]:
    candidates = []
    for line in lines:
        bbox = _ocr_line_bbox(line)
        if len(bbox) < 4:
            continue
        text = str(line.get("text") or "").strip()
        candidates.append(
            {
                "image_id": image_path.name,
                "label": label or capability.get("name") or "文字",
                "bbox_xyxy": bbox,
                "score": float(line.get("confidence") or line.get("score") or 0.0),
                "source_model": model_id,
                "model_version": capability.get("version"),
                "prompt": label or capability.get("name"),
                "raw_output": line,
                "metadata": {
                    **(metadata or {}),
                    "route_id": route_id,
                    "job_id": job_id,
                    "image_path": str(image_path),
                    "recognized_text": text,
                },
            }
        )
    return candidates


def _candidate_label(detection: Dict[str, Any], label: str | None, capability: Dict[str, Any]) -> str:
    raw = str(detection.get("class_label") or detection.get("class_name") or detection.get("class_key") or "")
    capability_name = str(capability.get("name") or "")
    if _is_no_helmet_text(raw) or _is_no_helmet_text(capability_name):
        return "安全帽未佩戴"
    if _is_no_reflective_vest_text(raw) or _is_no_reflective_vest_text(capability_name):
        return "没穿反光衣"
    if _is_reflective_vest_text(raw) or _is_reflective_vest_text(capability_name):
        return "反光衣"
    if label and _alias_match(_normalize_for_match(raw), label):
        return label
    return raw or label or capability_name or "目标"


def _class_filter(capability: Dict[str, Any], label: str | None) -> str:
    classes = [str(item) for item in (capability.get("classes") or []) if item]
    capability_name = str(capability.get("name") or "")
    if classes and classes != [capability_name]:
        return ",".join(classes)
    return ""


def _is_no_helmet_text(value: str) -> bool:
    normalized = _normalize_for_match(value)
    return (
        "未戴安全帽" in normalized
        or "没戴安全帽" in normalized
        or "安全帽未佩戴" in normalized
        or "nohelmet" in normalized
        or "nohardhat" in normalized
    )


def _is_no_reflective_vest_text(value: str) -> bool:
    normalized = _normalize_for_match(value)
    return (
        "没穿反光衣" in normalized
        or "未穿反光衣" in normalized
        or "未穿戴反光衣" in normalized
        or "没穿安全背心" in normalized
        or "未穿安全背心" in normalized
        or "nosafetyvest" in normalized
        or "noreflectivevest" in normalized
        or "novest" in normalized
    )


def _is_reflective_vest_text(value: str) -> bool:
    normalized = _normalize_for_match(value)
    if _is_no_reflective_vest_text(value):
        return False
    return (
        "反光衣" in normalized
        or "反光背心" in normalized
        or "安全背心" in normalized
        or "safetyvest" in normalized
        or "reflectivevest" in normalized
    )


def _ocr_line_bbox(line: Dict[str, Any]) -> List[float]:
    box = line.get("box") or line.get("bbox") or []
    if len(box) == 4 and all(isinstance(item, (int, float)) for item in box):
        return [float(value) for value in box]
    points: List[List[float]] = []
    for item in box:
        if isinstance(item, (list, tuple)) and len(item) >= 2:
            points.append([float(item[0]), float(item[1])])
    if not points:
        return []
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return [min(xs), min(ys), max(xs), max(ys)]


def _unavailable_response(model_id: str, reason: str) -> Dict[str, Any]:
    return {
        "success": True,
        "available": False,
        "status": "UNAVAILABLE",
        "source_model": model_id,
        "reason": reason,
        "candidates": [],
    }


def _normalize_capability(item: Dict[str, Any]) -> Dict[str, Any]:
    excel_no = item.get("excel_no") or item.get("no")
    algorithm = item.get("algorithm") or item.get("name") or ""
    model_id = _capability_model_id(excel_no, algorithm)
    aliases = [algorithm]
    for key in ("scene", "model_group", "test_type", "use_case"):
        value = item.get(key)
        if value:
            aliases.append(str(value))
    classes = item.get("classes") or [algorithm]
    return {
        "model_id": model_id,
        "name": algorithm,
        "version": _capability_version(excel_no),
        "model_type": "XINGMU_SCENARIO",
        "task_type": item.get("test_type") or "image_detection",
        "domain_tags": [item.get("scene") or "xingmu"],
        "classes": classes,
        "aliases": aliases,
        "endpoint": item.get("endpoint"),
        "model_group": item.get("model_group"),
        "event_type": item.get("event_type"),
        "sample_id": item.get("sample_id"),
        "claim_boundary": item.get("claim_boundary"),
        "status": "AVAILABLE",
        "available": True,
        "frontend_visible": True,
        "excel_no": excel_no,
        "source": "xingmu_dynamic_capability_catalog",
    }


def _capability_model_id(excel_no: Any, algorithm: str) -> str:
    if excel_no is None or str(excel_no).strip() == "":
        return f"xingmu-{canonical_name(algorithm)}"
    try:
        return f"xingmu-{int(excel_no):02d}-{canonical_name(algorithm)}"
    except (TypeError, ValueError):
        return f"xingmu-{canonical_name(str(excel_no))}-{canonical_name(algorithm)}"


def _capability_version(excel_no: Any) -> str:
    text = str(excel_no or "").strip()
    if text.startswith("v") and "-" in text:
        return text.split("-", 1)[0]
    return "v0.5.6_public_ocr_repair"


def _capabilities_from_capability_map(capability_map: Dict[str, Dict[str, Any]], hidden: set[str]) -> List[Dict[str, Any]]:
    capabilities = []
    for algorithm, mapping in capability_map.items():
        if algorithm in hidden or algorithm not in DISPLAYED_EXCEL_NO:
            continue
        excel_no = DISPLAYED_EXCEL_NO[algorithm]
        model_id = f"xingmu-{excel_no:02d}-{canonical_name(algorithm)}"
        aliases = [algorithm]
        if mapping.get("model_group"):
            aliases.append(str(mapping["model_group"]))
        if mapping.get("test_type"):
            aliases.append(str(mapping["test_type"]))
        capabilities.append(
            {
                "model_id": model_id,
                "name": algorithm,
                "version": "v0.5.6_public_ocr_repair",
                "model_type": "XINGMU_SCENARIO",
                "task_type": mapping.get("test_type") or "image_detection",
                "domain_tags": ["xingmu"],
                "classes": mapping.get("classes") or [algorithm],
                "aliases": aliases,
                "endpoint": mapping.get("endpoint"),
                "model_group": mapping.get("model_group"),
                "event_type": mapping.get("event_type"),
                "sample_id": mapping.get("sample_id"),
                "claim_boundary": mapping.get("claim_boundary"),
                "status": "AVAILABLE",
                "available": True,
                "frontend_visible": True,
                "excel_no": excel_no,
                "source": "xingmu_capability_map_displayed_32_fallback",
            }
        )
    return capabilities


def _merge_capabilities(base: List[Dict[str, Any]], extra: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    merged: Dict[str, Dict[str, Any]] = {}
    for item in base + extra:
        model_id = str(item.get("model_id") or "").strip()
        if not model_id:
            continue
        if model_id in merged:
            current = merged[model_id]
            current.update({key: value for key, value in item.items() if value not in (None, "", [])})
        else:
            merged[model_id] = dict(item)
    return list(merged.values())


def _deployment_registry_catalog(root: Path = XINGMU_ROOT) -> Dict[str, Any]:
    registry_path = _select_deploy_registry(root)
    if not registry_path:
        return {"available": False, "capabilities": []}
    try:
        import yaml

        data = yaml.safe_load(registry_path.read_text(encoding="utf-8")) or {}
    except Exception as exc:
        logger.warning(f"load xingmu deploy registry failed: {exc}")
        return {"available": False, "capabilities": [], "registry": str(registry_path), "reason": str(exc)}

    capabilities = _capabilities_from_deploy_registry(data, registry_path, root)
    return {
        "available": bool(capabilities),
        "root": str(root),
        "registry": str(registry_path),
        "version": data.get("version"),
        "capabilities": capabilities,
    }


def _select_deploy_registry(root: Path = XINGMU_ROOT) -> Path | None:
    current = root / "deploy" / "current" / "model_registry.yaml"
    if current.exists():
        return current
    deploy_dir = root / "deploy"
    if not deploy_dir.exists():
        return None
    candidates = [path for path in deploy_dir.glob("*/model_registry.yaml") if path.is_file()]
    if not candidates:
        return None
    return max(candidates, key=lambda path: path.stat().st_mtime)


def _capabilities_from_deploy_registry(data: Dict[str, Any], registry_path: Path, root: Path) -> List[Dict[str, Any]]:
    version = str(data.get("version") or registry_path.parent.name)
    claim_boundary = data.get("claim_boundary")
    context = _deploy_registry_context(data, root)
    capabilities: List[Dict[str, Any]] = []
    for item in data.get("models") or []:
        if not isinstance(item, dict):
            continue
        capabilities.extend(_deploy_model_to_capabilities(item, version, claim_boundary, registry_path, root, context))
    return capabilities


def _deploy_model_to_capabilities(
    item: Dict[str, Any],
    version: str,
    claim_boundary: Any,
    registry_path: Path,
    root: Path,
    context: Dict[str, Any],
) -> List[Dict[str, Any]]:
    raw_group = str(item.get("model_group") or item.get("model_id") or "").strip()
    if not raw_group:
        return []
    lower_group = raw_group.lower()
    raw_status = str(item.get("status") or "").strip()
    available = _deploy_model_available(raw_status, item)
    item_claim_boundary = item.get("claim_boundary") or claim_boundary
    common_resource = _deploy_resource(item, registry_path, root)
    metrics = {
        key: item.get(key)
        for key in ("test_mAP50", "test_mAP50_95", "test_f1", "direct_threshold", "relation_person_threshold", "relation_vest_threshold")
        if item.get(key) is not None
    }

    if "no_vest" in lower_group or "no_safety_vest" in lower_group:
        resource = dict(common_resource)
        resource.setdefault("runtime_model_group", context.get("direct_model_group") or "m9_reflective_vest_public_v1")
        resource.setdefault("source_model_group", raw_group)
        if context.get("direct_weight_path") and not resource.get("weight_path"):
            resource["weight_path"] = context["direct_weight_path"]
        if context.get("direct_onnx_path") and not resource.get("onnx_path"):
            resource["onnx_path"] = context["direct_onnx_path"]
        return [
            {
                "model_id": _deploy_model_id(item, "xingmu-m9-no-reflective-vest-direct-public-v1"),
                "name": "没穿反光衣",
                "version": version,
                "model_type": "XINGMU_SCENARIO",
                "task_type": "image_detection",
                "domain_tags": ["工地场景", "安全监管", "xingmu"],
                "classes": ["no_safety_vest"],
                "aliases": [
                    "没穿反光衣",
                    "未穿反光衣",
                    "未穿戴反光衣",
                    "没穿安全背心",
                    "未穿安全背心",
                    "no safety vest",
                    "no reflective vest",
                    "without reflective vest",
                    "no_vest",
                    "no_safety_vest",
                ],
                "endpoint": item.get("endpoint") or "/api/model-demo/predict",
                "model_group": resource["runtime_model_group"],
                "claim_boundary": item_claim_boundary,
                "status": "AVAILABLE" if available else "UNAVAILABLE",
                "available": available,
                "frontend_visible": True,
                "source": "xingmu_deploy_current_registry",
                "metrics": metrics,
                "resource": resource,
                "unavailable_reason": None if available else _deploy_unavailable_reason(raw_status, item),
            }
        ]

    if "reflective_vest" in lower_group or "safety_vest" in lower_group:
        is_relation = "relation" in lower_group
        name = "反光衣关系检测" if is_relation else "反光衣"
        declared_classes = [str(value).strip() for value in (item.get("classes") or []) if str(value).strip()]
        classes = ["person", "safety_vest"] if is_relation else (declared_classes or ["safety_vest"])
        default_id = "xingmu-m9-reflective-vest-relation-public-v1" if is_relation else "xingmu-m9-reflective-vest-direct-public-v1"
        aliases = [
            "反光衣",
            "反光背心",
            "安全背心",
            "穿反光衣",
            "穿安全背心",
            "reflective vest",
            "safety vest",
            "hi-vis vest",
            raw_group,
        ]
        aliases.extend(str(value).strip() for value in (item.get("aliases") or []) if str(value).strip())
        if "no_safety_vest" in classes:
            aliases.extend([
                "没穿反光衣",
                "未穿反光衣",
                "未穿戴反光衣",
                "没穿安全背心",
                "未穿安全背心",
                "no safety vest",
                "no reflective vest",
                "without reflective vest",
                "no_vest",
                "no_safety_vest",
            ])
        aliases = list(dict.fromkeys(aliases))
        return [
            {
                "model_id": _deploy_model_id(item, default_id),
                "name": name,
                "version": version,
                "model_type": "XINGMU_SCENARIO",
                "task_type": "image_detection",
                "domain_tags": ["工地场景", "安全监管", "xingmu"],
                "classes": classes,
                "aliases": aliases,
                "endpoint": item.get("endpoint") or "/api/model-demo/predict",
                "model_group": raw_group,
                "claim_boundary": item_claim_boundary,
                "status": "AVAILABLE" if available else "UNAVAILABLE",
                "available": available,
                "frontend_visible": True,
                "source": "xingmu_deploy_current_registry",
                "metrics": metrics,
                "resource": common_resource,
                "unavailable_reason": None if available else _deploy_unavailable_reason(raw_status, item),
            }
        ]

    return [
        {
            "model_id": _deploy_model_id(item, f"xingmu-{canonical_name(raw_group)}"),
            "name": str(item.get("name") or raw_group),
            "version": version,
            "model_type": "XINGMU_SCENARIO",
            "task_type": item.get("task_type") or "image_detection",
            "domain_tags": item.get("domain_tags") or ["xingmu"],
            "classes": item.get("classes") or [str(item.get("name") or raw_group)],
            "aliases": item.get("aliases") or [str(item.get("name") or raw_group), raw_group],
            "endpoint": item.get("endpoint") or "/api/model-demo/predict",
            "model_group": raw_group,
            "claim_boundary": item_claim_boundary,
            "status": "AVAILABLE" if available else "UNAVAILABLE",
            "available": available,
            "frontend_visible": bool(item.get("frontend_visible", True)),
            "source": "xingmu_deploy_current_registry",
            "metrics": metrics,
            "resource": common_resource,
            "unavailable_reason": None if available else _deploy_unavailable_reason(raw_status, item),
        }
    ]


def _deploy_model_id(item: Dict[str, Any], default_id: str) -> str:
    return str(item.get("model_id") or default_id).strip()


def _deploy_model_available(raw_status: str, item: Dict[str, Any]) -> bool:
    if item.get("available") is not None:
        return bool(item.get("available"))
    normalized = raw_status.lower()
    return normalized in {"available", "trained", "released", "validation_thresholded_image_event", "ready"}


def _deploy_unavailable_reason(raw_status: str, item: Dict[str, Any]) -> str:
    return str(item.get("unavailable_reason") or item.get("reason") or f"deploy registry status is {raw_status or 'unknown'}")


def _deploy_resource(item: Dict[str, Any], registry_path: Path, root: Path) -> Dict[str, Any]:
    weight_value = _first_present(item, "weight", "artifact_path_pt", "artifact_path_pytorch", "pt", "model_path")
    onnx_value = _first_present(item, "onnx", "artifact_path_onnx")
    resource = {
        "deploy_registry": str(registry_path),
        "deploy_dir": str(registry_path.parent),
        "model_group": item.get("model_group"),
        "weight_path": _resolve_xingmu_path(weight_value, root),
        "onnx_path": _resolve_xingmu_path(onnx_value, root),
    }
    return {key: value for key, value in resource.items() if value not in (None, "", [])}


def _deploy_registry_context(data: Dict[str, Any], root: Path) -> Dict[str, Any]:
    context: Dict[str, Any] = {}
    for item in data.get("models") or []:
        if not isinstance(item, dict):
            continue
        group = str(item.get("model_group") or "").lower()
        if "reflective_vest" not in group or "relation" in group or "no_vest" in group:
            continue
        context["direct_model_group"] = item.get("model_group")
        context["direct_weight_path"] = _resolve_xingmu_path(
            _first_present(item, "weight", "artifact_path_pt", "artifact_path_pytorch", "pt", "model_path"),
            root,
        )
        context["direct_onnx_path"] = _resolve_xingmu_path(_first_present(item, "onnx", "artifact_path_onnx"), root)
        break
    return {key: value for key, value in context.items() if value}


def _first_present(item: Dict[str, Any], *keys: str) -> Any:
    for key in keys:
        value = item.get(key)
        if value not in (None, "", []):
            return value
    return None


def _resolve_xingmu_path(value: Any, root: Path = XINGMU_ROOT) -> str | None:
    if not value:
        return None
    path = Path(str(value))
    if not path.is_absolute():
        path = root / path
    return str(path)


def _capability_weight_path(capability: Dict[str, Any]) -> Path | None:
    resource = capability.get("resource") or {}
    if not isinstance(resource, dict):
        return None
    weight = resource.get("weight_path")
    if not weight:
        return None
    path = Path(str(weight))
    return path if path.exists() else None


def _predict_local_yolo_weight(
    weight_path: Path,
    image_path: Path,
    capability: Dict[str, Any],
    model_id: str,
    label: str | None,
    job_id: Any,
    route_id: Any,
    conf: float | None,
) -> Dict[str, Any]:
    try:
        from ultralytics import YOLO
        import torch

        cache_key = str(weight_path)
        model = _LOCAL_YOLO_CACHE.get(cache_key)
        if model is None:
            model = YOLO(cache_key)
            _LOCAL_YOLO_CACHE[cache_key] = model
        device = 0 if torch.cuda.is_available() else "cpu"
        threshold = conf if conf is not None else _default_conf_for_capability(capability)
        results = model.predict(str(image_path), conf=threshold, device=device, verbose=False)
        candidates = _candidates_from_yolo_results(
            results[0] if results else None,
            image_path=image_path,
            capability=capability,
            model_id=model_id,
            label=label,
            job_id=job_id,
            route_id=route_id,
            metadata={"endpoint": "local_yolo_weight", "weight_path": str(weight_path)},
        )
        return {
            "success": True,
            "available": True,
            "status": "COMPLETED",
            "source_model": model_id,
            "candidate_count": len(candidates),
            "candidates": candidates,
            "metadata": {"weight_path": str(weight_path), "conf": threshold},
        }
    except Exception as exc:
        logger.warning(f"local YOLO fallback failed for {model_id}: {exc}")
        return _unavailable_response(model_id, f"local YOLO fallback failed: {exc}")


def _default_conf_for_capability(capability: Dict[str, Any]) -> float:
    resource = capability.get("resource") or {}
    if isinstance(resource, dict):
        for key in ("recommended_conf", "direct_threshold"):
            try:
                value = resource.get(key)
                if value is not None:
                    return float(value)
            except (TypeError, ValueError):
                continue
    return 0.25


def _candidates_from_yolo_results(
    result: Any,
    image_path: Path,
    capability: Dict[str, Any],
    model_id: str,
    label: str | None,
    job_id: Any,
    route_id: Any,
    metadata: Dict[str, Any] | None = None,
) -> List[Dict[str, Any]]:
    if result is None or getattr(result, "boxes", None) is None:
        return []
    allowed_classes = {_normalize_for_match(value) for value in capability.get("classes") or [] if value}
    names = getattr(result, "names", {}) or {}
    candidates = []
    for box in result.boxes:
        class_index = int(box.cls.item()) if getattr(box, "cls", None) is not None else -1
        class_name = str(names.get(class_index, class_index))
        if allowed_classes and _normalize_for_match(class_name) not in allowed_classes:
            continue
        xyxy = box.xyxy[0].detach().cpu().tolist()
        score = float(box.conf.item()) if getattr(box, "conf", None) is not None else 0.0
        detection = {"class_name": class_name, "confidence": score, "bbox": xyxy}
        candidates.append(
            {
                "image_id": image_path.name,
                "label": _candidate_label(detection, label, capability),
                "bbox_xyxy": [float(value) for value in xyxy],
                "score": score,
                "source_model": model_id,
                "model_version": capability.get("version"),
                "prompt": label or capability.get("name"),
                "raw_output": detection,
                "metadata": {
                    **(metadata or {}),
                    "route_id": route_id,
                    "job_id": job_id,
                    "image_path": str(image_path),
                },
            }
        )
    return candidates


def _fallback_catalog(reason: str) -> Dict[str, Any]:
    deploy_catalog = _deployment_registry_catalog()
    if deploy_catalog.get("capabilities"):
        return deploy_catalog
    sidecar_catalog = _sidecar_registry_catalog(reason)
    if sidecar_catalog.get("capabilities"):
        deploy_caps = deploy_catalog.get("capabilities") or []
        if deploy_caps:
            sidecar_catalog["capabilities"] = _merge_capabilities(sidecar_catalog.get("capabilities") or [], deploy_caps)
            sidecar_catalog["displayed_count"] = len(sidecar_catalog["capabilities"])
        return sidecar_catalog
    return {
        "available": False,
        "root": str(XINGMU_ROOT),
        "capabilities": [],
        "displayed_count": 0,
        "hidden_excluded": sorted(HIDDEN_FALLBACK),
        "reason": reason,
    }


def _sidecar_registry_catalog(reason: str) -> Dict[str, Any]:
    if not XINGMU_REGISTRY_PATH.exists():
        return {"available": False, "capabilities": []}
    try:
        import yaml

        data = yaml.safe_load(XINGMU_REGISTRY_PATH.read_text(encoding="utf-8")) or {}
    except Exception as exc:
        logger.warning(f"load xingmu sidecar registry failed: {exc}")
        return {"available": False, "capabilities": []}

    groups = {str(item.get("group_id")): item for item in data.get("model_groups") or [] if item.get("group_id")}
    capabilities = []
    for item in data.get("scenario_inventory") or []:
        if item.get("default_routable") is False:
            continue
        label = str(item.get("label") or "").strip()
        model_id = str(item.get("model_id") or "").strip()
        if not label or not model_id:
            continue
        group = groups.get(str(item.get("group_id"))) or {}
        aliases = [label]
        group_classes = [str(value) for value in group.get("classes") or [] if value and value != "endpoint_specific"]
        aliases.extend(group_classes)
        aliases.append(str(item.get("group_ref") or ""))
        capabilities.append(
            {
                "model_id": model_id,
                "name": label,
                "version": group.get("version") or data.get("generated_at"),
                "model_type": "XINGMU_SCENARIO",
                "task_type": item.get("task_type") or group.get("task_type") or "image_detection",
                "domain_tags": group.get("domain_tags") or ["xingmu"],
                "classes": group.get("classes") or [label],
                "aliases": [alias for alias in aliases if alias],
                "endpoint": item.get("endpoint") or group.get("endpoint"),
                "model_group": item.get("group_ref") or group.get("group_ref"),
                "status": group.get("status") or "available",
                "available": True,
                "frontend_visible": True,
                "excel_no": item.get("excel_no"),
                "source": "xingmu_sidecar_registry_fallback",
            }
        )
    return {
        "available": bool(capabilities),
        "root": str(XINGMU_ROOT),
        "registry": str(XINGMU_REGISTRY_PATH),
        "capabilities": capabilities,
        "displayed_count": len(capabilities),
        "hidden_excluded": [item.get("label") for item in data.get("hidden_excluded") or [] if item.get("label")],
        "coverage_summary": {},
        "fallback_reason": reason,
    }


def match_capabilities(labels: List[str]) -> List[Dict[str, Any]]:
    catalog = load_displayed_capabilities()
    matched = []
    seen = set()
    for label in labels:
        normalized_label = _normalize_for_match(label)
        scored: List[tuple[int, Dict[str, Any]]] = []
        for cap in catalog.get("capabilities", []):
            if cap["model_id"] in seen:
                continue
            if cap.get("available") is False or str(cap.get("status") or "").upper() == "UNAVAILABLE":
                continue
            aliases = cap.get("aliases") or []
            values = [cap.get("name", "")] + aliases + (cap.get("classes") or [])
            score = max((_alias_score(normalized_label, value) for value in values), default=0)
            if score > 0:
                scored.append((score, cap))
        for _, cap in sorted(scored, key=lambda item: item[0], reverse=True):
            if cap["model_id"] not in seen:
                matched.append(cap)
                seen.add(cap["model_id"])
    return matched


def model_groups_registry() -> List[Dict[str, Any]]:
    groups: Dict[str, Dict[str, Any]] = {}
    for cap in load_displayed_capabilities().get("capabilities") or []:
        group = cap.get("model_group") or cap.get("task_type")
        if not group:
            continue
        record = groups.setdefault(
            group,
            {
                "model_group": group,
                "endpoints": set(),
                "classes": set(),
                "capability_ids": [],
                "status": "AVAILABLE",
            },
        )
        if cap.get("endpoint"):
            record["endpoints"].add(cap["endpoint"])
        for class_name in cap.get("classes") or [cap.get("name")]:
            if class_name:
                record["classes"].add(str(class_name))
        record["capability_ids"].append(cap.get("model_id"))
    result = []
    for record in groups.values():
        result.append(
            {
                "model_group": record["model_group"],
                "endpoints": sorted(record["endpoints"]),
                "classes": sorted(record["classes"]),
                "capability_ids": record["capability_ids"],
                "status": record["status"],
            }
        )
    return result


def _capability_by_model_id(model_id: str) -> Dict[str, Any] | None:
    for capability in load_displayed_capabilities().get("capabilities") or []:
        if capability.get("model_id") == model_id:
            return capability
    return None


def _alias_match(normalized_label: str, value: str) -> bool:
    return _alias_score(normalized_label, value) > 0


def _alias_score(normalized_label: str, value: str) -> int:
    normalized_value = _normalize_for_match(value)
    if normalized_label == "安全帽" and ("未佩戴" in normalized_value or "未戴" in normalized_value or "nohelmet" in normalized_value):
        return 0
    if ("没穿反光衣" in normalized_label or "未穿反光衣" in normalized_label or "noreflectivevest" in normalized_label or "nosafetyvest" in normalized_label) and _is_reflective_vest_text(normalized_value):
        return 0
    if not normalized_label or not normalized_value:
        return 0
    if normalized_label == normalized_value:
        return 1000 + len(normalized_value)
    if normalized_label in normalized_value:
        return 500 + len(normalized_label)
    if normalized_value in normalized_label:
        return 400 + len(normalized_value)
    return 0


def _normalize_for_match(value: str) -> str:
    return (value or "").lower().replace(" ", "").replace("_", "").replace("-", "").replace("/", "")


def _tcp_available(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=1.0):
            return True
    except OSError:
        return False
