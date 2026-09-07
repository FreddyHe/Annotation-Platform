#!/usr/bin/env python3
"""Import a third-party Apifox/OpenAPI JSON export into border_model_registry.yaml.

The script only reads API documentation. It never reads or writes app keys/secrets.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any, Dict, Iterable, List, Tuple

import yaml


DEFAULT_OUTPUT = Path("/root/autodl-fs/Annotation-Platform/config/border_model_registry.yaml")
HTTP_METHODS = {"get", "post", "put", "patch", "delete"}
POSITIVE_KEYWORDS = (
    "predict",
    "detect",
    "infer",
    "recognition",
    "recognize",
    "识别",
    "检测",
    "推理",
    "算法",
    "目标",
)
NEGATIVE_KEYWORDS = (
    "login",
    "logout",
    "token",
    "auth",
    "health",
    "swagger",
    "doc",
    "user",
    "password",
    "登录",
    "鉴权",
    "健康",
    "文档",
)
IMAGE_KEYWORDS = ("image", "img", "file", "multipart", "图片", "图像", "照片", "视频帧")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apifox_json", help="Path to Apifox/OpenAPI exported JSON.")
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT), help="Registry YAML to update.")
    parser.add_argument("--dry-run", action="store_true", help="Print detected endpoints without writing YAML.")
    parser.add_argument("--api-path", help="Force a specific vendor predict path.")
    parser.add_argument("--file-field", default=None, help="Multipart file field name if known.")
    parser.add_argument("--label-field", default=None, help="Label/category form field name if known.")
    args = parser.parse_args()

    source = Path(args.apifox_json)
    output = Path(args.output)
    document = json.loads(source.read_text(encoding="utf-8"))
    endpoints = sorted(_discover_endpoints(document), key=lambda item: item["score"], reverse=True)
    best = args.api_path or (endpoints[0]["path"] if endpoints else "")

    summary = {
        "source": str(source),
        "output": str(output),
        "detected_count": len(endpoints),
        "selected_api_path": best,
        "top_candidates": endpoints[:10],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    if args.dry_run:
        return 0
    if not best:
        raise SystemExit("No candidate predict endpoint found. Re-run with --api-path /your/predict/path.")

    registry = _load_registry(output)
    registry["default_api_path"] = best
    registry.setdefault("provider", "border-online-api")
    registry.setdefault("version", "border-online-apifox")
    for model in registry.get("models") or []:
        if not isinstance(model, dict):
            continue
        model.setdefault("endpoint", "/internal/adapters/border/predict")
        model["api_path"] = model.get("api_path") or best
        request = dict(model.get("request") or {})
        if args.file_field:
            request["file_field"] = args.file_field
        if args.label_field:
            request["label_field"] = args.label_field
        if request:
            model["request"] = request
    output.write_text(yaml.safe_dump(registry, allow_unicode=True, sort_keys=False), encoding="utf-8")
    return 0


def _load_registry(path: Path) -> Dict[str, Any]:
    if not path.exists():
        return {"provider": "border-online-api", "version": "border-online-apifox", "models": []}
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        return {"provider": "border-online-api", "version": "border-online-apifox", "models": []}
    if not isinstance(data.get("models"), list):
        data["models"] = []
    return data


def _discover_endpoints(document: Any) -> List[Dict[str, Any]]:
    discovered: Dict[Tuple[str, str], Dict[str, Any]] = {}
    for endpoint in _openapi_endpoints(document):
        discovered[(endpoint["method"], endpoint["path"])] = endpoint
    for endpoint in _recursive_apifox_endpoints(document):
        key = (endpoint["method"], endpoint["path"])
        if key not in discovered or endpoint["score"] > discovered[key]["score"]:
            discovered[key] = endpoint
    return [item for item in discovered.values() if item["score"] > 0 and item["path"]]


def _openapi_endpoints(document: Any) -> Iterable[Dict[str, Any]]:
    if not isinstance(document, dict) or not isinstance(document.get("paths"), dict):
        return []
    endpoints = []
    for path, path_item in document["paths"].items():
        if not isinstance(path_item, dict):
            continue
        for method, operation in path_item.items():
            if method.lower() not in HTTP_METHODS or not isinstance(operation, dict):
                continue
            text = _joined_text(path, method, operation)
            endpoints.append(
                {
                    "method": method.upper(),
                    "path": path,
                    "name": operation.get("summary") or operation.get("operationId") or path,
                    "score": _score_endpoint(text, operation),
                    "source": "openapi.paths",
                }
            )
    return endpoints


def _recursive_apifox_endpoints(document: Any) -> Iterable[Dict[str, Any]]:
    endpoints: List[Dict[str, Any]] = []
    for obj in _walk_dicts(document):
        method = _first_string(obj, "method", "requestMethod")
        path = _first_string(obj, "path", "url", "apiPath", "endpoint")
        if not method or not path or method.lower() not in HTTP_METHODS:
            request = obj.get("request")
            if isinstance(request, dict):
                method = method or _first_string(request, "method", "requestMethod")
                path = path or _first_string(request, "path", "url", "apiPath", "endpoint")
        if not method or not path or method.lower() not in HTTP_METHODS:
            continue
        path = _normalize_path(path)
        text = _joined_text(path, method, obj)
        endpoints.append(
            {
                "method": method.upper(),
                "path": path,
                "name": _first_string(obj, "name", "title", "summary") or path,
                "score": _score_endpoint(text, obj),
                "source": "recursive",
            }
        )
    return endpoints


def _score_endpoint(text: str, obj: Dict[str, Any]) -> int:
    normalized = text.lower()
    score = 0
    score += sum(20 for keyword in POSITIVE_KEYWORDS if keyword.lower() in normalized)
    score += sum(8 for keyword in IMAGE_KEYWORDS if keyword.lower() in normalized)
    score -= sum(30 for keyword in NEGATIVE_KEYWORDS if keyword.lower() in normalized)
    if "post" in normalized:
        score += 8
    if _has_multipart(obj):
        score += 20
    return score


def _has_multipart(obj: Any) -> bool:
    text = json.dumps(obj, ensure_ascii=False).lower()[:20000]
    return "multipart/form-data" in text or "formdata" in text or '"file"' in text or "文件" in text


def _joined_text(*values: Any) -> str:
    chunks: List[str] = []
    for value in values:
        if isinstance(value, (str, int, float)):
            chunks.append(str(value))
        elif isinstance(value, dict):
            for key in ("name", "title", "summary", "description", "operationId", "tags"):
                item = value.get(key)
                if isinstance(item, list):
                    chunks.extend(str(part) for part in item)
                elif item is not None:
                    chunks.append(str(item))
            chunks.append(json.dumps(value, ensure_ascii=False)[:6000])
    return " ".join(chunks)


def _walk_dicts(value: Any) -> Iterable[Dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from _walk_dicts(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_dicts(child)


def _first_string(obj: Dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = obj.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _normalize_path(value: str) -> str:
    value = value.strip()
    value = re.sub(r"^https?://[^/]+", "", value)
    if not value.startswith("/"):
        value = "/" + value
    return value


if __name__ == "__main__":
    raise SystemExit(main())
