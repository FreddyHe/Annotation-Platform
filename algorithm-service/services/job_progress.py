from __future__ import annotations

from datetime import datetime, timezone
from threading import Lock
from typing import Any, Dict


_LOCK = Lock()
_PROGRESS: Dict[str, Dict[str, Any]] = {}


def start(job_id: Any, total: int, stage: str, message: str | None = None) -> Dict[str, Any]:
    key = _key(job_id)
    item = _new_item(job_id, total, stage, message)
    with _LOCK:
        _PROGRESS[key] = item
    return dict(item)


def update(
    job_id: Any,
    *,
    processed: int | None = None,
    total: int | None = None,
    stage: str | None = None,
    message: str | None = None,
    extra: Dict[str, Any] | None = None,
) -> Dict[str, Any]:
    key = _key(job_id)
    with _LOCK:
        item = dict(_PROGRESS.get(key) or _new_item(job_id, total or 0, stage or "RUNNING"))
        if total is not None:
            item["total"] = max(0, int(total or 0))
        if processed is not None:
            item["processed"] = max(0, int(processed or 0))
        if stage:
            item["stage"] = stage
        if message:
            item["message"] = message
        if extra:
            item.update(extra)
        _recompute(item)
        _PROGRESS[key] = item
    return dict(item)


def finish(job_id: Any, *, stage: str = "COMPLETED", message: str | None = None) -> Dict[str, Any]:
    key = _key(job_id)
    with _LOCK:
        item = dict(_PROGRESS.get(key) or _new_item(job_id, 0, stage))
        item["status"] = "COMPLETED"
        item["stage"] = stage
        item["message"] = message or stage
        total = int(item.get("total") or 0)
        if total > 0:
            item["processed"] = total
        _recompute(item)
        item["percent"] = 100.0
        item["estimated_remaining_seconds"] = 0.0
        _PROGRESS[key] = item
    return dict(item)


def fail(job_id: Any, reason: str) -> Dict[str, Any]:
    key = _key(job_id)
    with _LOCK:
        item = dict(_PROGRESS.get(key) or _new_item(job_id, 0, "FAILED"))
        item["status"] = "FAILED"
        item["stage"] = "FAILED"
        item["message"] = reason
        _recompute(item)
        _PROGRESS[key] = item
    return dict(item)


def get(job_id: Any) -> Dict[str, Any]:
    with _LOCK:
        item = dict(_PROGRESS.get(_key(job_id)) or {})
    if item:
        _recompute(item)
    return item


def _recompute(item: Dict[str, Any]) -> None:
    now = _now()
    started_at = _parse_time(item.get("started_at")) or _parse_time(now)
    updated_at = _parse_time(now)
    elapsed = max(0.0, (updated_at - started_at).total_seconds())
    processed = max(0, int(item.get("processed") or 0))
    total = max(0, int(item.get("total") or 0))
    item["updated_at"] = now
    item["elapsed_seconds"] = round(elapsed, 1)
    item["percent"] = round((processed / total) * 100.0, 2) if total > 0 else 0.0
    if processed > 0 and total > processed:
        rate = processed / max(elapsed, 1.0)
        item["items_per_second"] = round(rate, 4)
        item["estimated_remaining_seconds"] = round((total - processed) / max(rate, 1e-6), 1)
    elif total > 0 and processed >= total:
        item["items_per_second"] = round(processed / max(elapsed, 1.0), 4)
        item["estimated_remaining_seconds"] = 0.0


def _key(job_id: Any) -> str:
    return str(job_id or "unknown")


def _new_item(job_id: Any, total: int, stage: str, message: str | None = None) -> Dict[str, Any]:
    now = _now()
    return {
        "job_id": job_id,
        "status": "RUNNING",
        "stage": stage,
        "message": message or stage,
        "processed": 0,
        "total": max(0, int(total or 0)),
        "percent": 0.0,
        "started_at": now,
        "updated_at": now,
        "elapsed_seconds": 0.0,
        "estimated_remaining_seconds": None,
    }


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _parse_time(value: Any) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
