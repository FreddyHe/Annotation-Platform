import logging
import os
import re
import shutil
import subprocess
from contextlib import nullcontext
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

GPU_IDLE_MAX_UTILIZATION = int(os.getenv("GPU_IDLE_MAX_UTILIZATION", "5"))
GPU_IDLE_MAX_MEMORY_MB = int(os.getenv("GPU_IDLE_MAX_MEMORY_MB", "512"))
GPU_IDLE_MAX_MEMORY_RATIO = float(os.getenv("GPU_IDLE_MAX_MEMORY_RATIO", "0.05"))


@dataclass(frozen=True)
class GpuStats:
    index: int
    utilization: int
    memory_used: int
    memory_total: int


def _torch_cuda_available() -> bool:
    try:
        import torch

        return torch.cuda.is_available()
    except Exception as exc:
        logger.warning("Unable to check torch CUDA availability: %s", exc)
        return False


def _query_gpu_stats() -> Tuple[Dict[int, GpuStats], Optional[str]]:
    if shutil.which("nvidia-smi") is None:
        return {}, "nvidia-smi not found"

    cmd = [
        "nvidia-smi",
        "--query-gpu=index,utilization.gpu,memory.used,memory.total",
        "--format=csv,noheader,nounits",
    ]
    try:
        output = subprocess.check_output(
            cmd,
            text=True,
            timeout=5,
            stderr=subprocess.DEVNULL,
        )
    except Exception as exc:
        return {}, f"nvidia-smi query failed: {exc}"

    stats: Dict[int, GpuStats] = {}
    for line in output.splitlines():
        parts = [part.strip() for part in line.split(",")]
        if len(parts) < 4:
            continue
        try:
            item = GpuStats(
                index=int(parts[0]),
                utilization=int(parts[1]),
                memory_used=int(parts[2]),
                memory_total=int(parts[3]),
            )
        except ValueError:
            continue
        stats[item.index] = item

    if not stats:
        return {}, "no GPU stats returned"
    return stats, None


def _requested_indices(requested_device: Optional[str], stats: Dict[int, GpuStats]) -> Tuple[List[int], bool]:
    raw = str(requested_device or "auto").strip().lower()
    if raw in ("", "auto", "cuda", "gpu"):
        return sorted(stats), True
    if raw == "cpu":
        return [], False
    if raw.startswith("cuda:"):
        raw = raw.split(":", 1)[1]

    indices: List[int] = []
    for part in re.split(r"[,\s]+", raw):
        if not part:
            continue
        if not part.isdigit():
            logger.warning("Unrecognized GPU device %r; falling back to GPU 0 probe", requested_device)
            return [0], False
        indices.append(int(part))

    return indices or [0], False


def _is_idle(stat: GpuStats) -> Tuple[bool, str]:
    memory_limit = max(GPU_IDLE_MAX_MEMORY_MB, int(stat.memory_total * GPU_IDLE_MAX_MEMORY_RATIO))
    if stat.utilization > GPU_IDLE_MAX_UTILIZATION:
        return False, f"utilization {stat.utilization}% > {GPU_IDLE_MAX_UTILIZATION}%"
    if stat.memory_used > memory_limit:
        return False, f"memory {stat.memory_used}MB > {memory_limit}MB"
    return True, f"utilization {stat.utilization}%, memory {stat.memory_used}MB"


def resolve_compute_device(
    requested_device: Optional[str] = "0",
    *,
    for_torch: bool = False,
    context: str = "compute task",
) -> str:
    """Return a GPU device only when it is detectable and idle; otherwise CPU."""
    raw = str(requested_device or "auto").strip().lower()
    if raw == "cpu":
        logger.info("%s requested CPU explicitly", context)
        return "cpu"

    if not _torch_cuda_available():
        logger.warning("%s falling back to CPU: CUDA is unavailable", context)
        return "cpu"

    stats, error = _query_gpu_stats()
    if error:
        logger.warning("%s falling back to CPU: %s", context, error)
        return "cpu"

    indices, auto_select = _requested_indices(requested_device, stats)
    if not indices:
        return "cpu"

    idle_indices: List[int] = []
    busy_reasons: List[str] = []
    for index in indices:
        stat = stats.get(index)
        if stat is None:
            busy_reasons.append(f"GPU {index} not found")
            continue
        idle, reason = _is_idle(stat)
        if idle:
            idle_indices.append(index)
        else:
            busy_reasons.append(f"GPU {index} busy ({reason})")

    selected: List[int]
    if auto_select:
        selected = idle_indices[:1]
    elif len(idle_indices) == len(indices):
        selected = indices
    elif not auto_select and all(index in stats for index in indices):
        detail = "; ".join(busy_reasons) if busy_reasons else "requested GPU is not idle"
        logger.warning("%s using explicitly requested GPU device despite busy check: %s", context, detail)
        selected = indices
    else:
        selected = []

    if not selected:
        detail = "; ".join(busy_reasons) if busy_reasons else "no idle GPU found"
        logger.warning("%s falling back to CPU: %s", context, detail)
        return "cpu"

    if for_torch:
        device = f"cuda:{selected[0]}"
    else:
        device = ",".join(str(index) for index in selected)
    logger.info("%s selected GPU device %s after idle check", context, device)
    return device


def is_gpu_device(device: Optional[str]) -> bool:
    value = str(device or "").strip().lower()
    return bool(value) and value != "cpu"


def configure_cuda_visible_devices(device: Optional[str]) -> None:
    if not is_gpu_device(device):
        os.environ.pop("CUDA_VISIBLE_DEVICES", None)
        return

    value = str(device).strip().lower()
    if value.startswith("cuda:"):
        value = value.split(":", 1)[1]
    os.environ["CUDA_VISIBLE_DEVICES"] = value


def gpu_lock_if_needed(device: Optional[str], lock):
    if is_gpu_device(device):
        return lock
    return nullcontext()
