#!/usr/bin/env python3
"""Verify third-party vendor model registry integration without printing secrets."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List

import requests


PROJECT_ROOT = Path("/root/autodl-fs/Annotation-Platform")
ALGORITHM_SERVICE_DIR = PROJECT_ROOT / "algorithm-service"
ENV_FILE = PROJECT_ROOT / ".env.local"
DEFAULT_ALGORITHM_URL = "http://127.0.0.1:8001"
DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"
REQUIRED_ENV_KEYS = ("BORDER_API_BASE_URL", "BORDER_API_APP_KEY", "BORDER_API_APP_SECRET")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--algorithm-url", default=DEFAULT_ALGORITHM_URL)
    parser.add_argument("--backend-url", default=DEFAULT_BACKEND_URL)
    parser.add_argument("--backend-token", default=os.getenv("ANNOTATION_PLATFORM_TOKEN"))
    parser.add_argument("--sync-backend", action="store_true")
    parser.add_argument("--require-available", action="store_true", help="Fail if vendor runtime is unavailable.")
    parser.add_argument("--require-api-path", action="store_true", help="Fail if no vendor model has api_path configured.")
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args()

    _load_dotenv(ENV_FILE)
    _ensure_algorithm_import_path()

    from adapters import border_adapter  # pylint: disable=import-outside-toplevel

    capabilities = border_adapter.load_capabilities()
    items = capabilities.get("capabilities") or []
    api_path_items = [item for item in items if ((item.get("resource") or {}).get("api_path") or "").strip()]
    routing_enabled_items = [item for item in items if (item.get("resource") or {}).get("routing_enabled") is not False]
    runtime_status = capabilities.get("runtime_status") or {}
    all_matches = border_adapter.match_capabilities(["安全帽", "反光衣", "未穿反光衣"], available_only=False)
    available_matches = border_adapter.match_capabilities(["安全帽", "反光衣", "未穿反光衣"], available_only=True)

    summary: Dict[str, Any] = {
        "env_file_exists": ENV_FILE.exists(),
        "env_file_gitignored": _is_gitignored(ENV_FILE),
        "env_loaded": {key: bool(os.getenv(key)) for key in REQUIRED_ENV_KEYS},
        "local_registry": {
            "status": capabilities.get("status"),
            "available": capabilities.get("available"),
            "provider": capabilities.get("provider"),
            "version": capabilities.get("version"),
            "catalog_path": capabilities.get("catalog_path"),
            "capability_count": len(items),
            "routing_enabled_count": len(routing_enabled_items),
            "api_path_configured_count": len(api_path_items),
            "api_path_missing_count": len(items) - len(api_path_items),
            "border_runtime_status": _sanitize_runtime_status(runtime_status),
            "matched_all_model_ids": [item.get("model_id") for item in all_matches],
            "matched_available_model_ids": [item.get("model_id") for item in available_matches],
        },
        "algorithm_service": _check_algorithm_service(args.algorithm_url, args.timeout),
        "backend": _check_backend(args.backend_url, args.backend_token, args.sync_backend, args.timeout),
    }

    print(json.dumps(summary, ensure_ascii=False, indent=2))

    if not all(summary["env_loaded"].values()):
        return 2
    if summary["local_registry"]["capability_count"] == 0:
        return 3
    if args.require_available and not capabilities.get("available"):
        return 4
    if args.require_api_path and not api_path_items:
        return 5
    return 0


def _load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def _ensure_algorithm_import_path() -> None:
    path = str(ALGORITHM_SERVICE_DIR)
    if path not in sys.path:
        sys.path.insert(0, path)


def _is_gitignored(path: Path) -> bool:
    try:
        result = subprocess.run(
            ["git", "check-ignore", "-q", str(path.relative_to(PROJECT_ROOT))],
            cwd=PROJECT_ROOT,
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return result.returncode == 0
    except Exception:
        return False


def _sanitize_runtime_status(status: Dict[str, Any]) -> Dict[str, Any]:
    allowed_keys = (
        "model_id",
        "name",
        "version",
        "available",
        "status",
        "endpoint",
        "external_api_used",
        "local",
        "credentials_loaded",
        "base_url_configured",
        "api_host",
        "catalog_path",
        "reason",
    )
    return {key: status.get(key) for key in allowed_keys if key in status}


def _check_algorithm_service(base_url: str, timeout: float) -> Dict[str, Any]:
    try:
        response = requests.get(f"{base_url.rstrip('/')}/internal/models/registry", timeout=timeout)
        body = response.json() if response.headers.get("content-type", "").startswith("application/json") else {}
        items: List[Dict[str, Any]] = body.get("items") or []
        border_items = [item for item in items if item.get("model_type") == "BORDER_VENDOR"]
        return {
            "ok": response.ok,
            "http_status": response.status_code,
            "total": body.get("total") or len(items),
            "border_vendor_count": len(border_items),
            "available_border_vendor_count": len([item for item in border_items if item.get("available")]),
            "sample_model_ids": [item.get("model_id") for item in border_items[:8]],
        }
    except Exception as exc:
        return {"ok": False, "error": str(exc)}


def _check_backend(base_url: str, token: str | None, sync: bool, timeout: float) -> Dict[str, Any]:
    if not token:
        return {"skipped": True, "reason": "backend token not provided"}
    headers = {"Authorization": f"Bearer {token}"}
    result: Dict[str, Any] = {"skipped": False}
    try:
        if sync:
            response = requests.post(
                f"{base_url.rstrip('/')}/api/v1/models/registry/sync",
                headers=headers,
                timeout=timeout,
            )
            result["sync_http_status"] = response.status_code
            result["sync_ok"] = response.ok
        response = requests.get(
            f"{base_url.rstrip('/')}/api/v1/models/registry?modelType=BORDER_VENDOR",
            headers=headers,
            timeout=timeout,
        )
        body = response.json() if response.headers.get("content-type", "").startswith("application/json") else {}
        data = body.get("data") if isinstance(body, dict) else {}
        items = data.get("items") if isinstance(data, dict) else []
        result.update(
            {
                "query_http_status": response.status_code,
                "query_ok": response.ok,
                "total": data.get("total") if isinstance(data, dict) else None,
                "sample_model_ids": [item.get("modelId") or item.get("model_id") for item in (items or [])[:8]],
            }
        )
    except Exception as exc:
        result.update({"ok": False, "error": str(exc)})
    return result


if __name__ == "__main__":
    raise SystemExit(main())
