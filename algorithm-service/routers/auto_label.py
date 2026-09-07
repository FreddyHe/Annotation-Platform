from fastapi import APIRouter

from schemas.auto_label_to_model import AutoLabelJobRequest
from services import candidate_runner, job_progress, probe_runner

router = APIRouter()


@router.post("/internal/auto-label/probe")
def probe_job(request: AutoLabelJobRequest):
    return probe_runner.run_probe(request.dict())


@router.post("/internal/auto-label/run")
def run_job(request: AutoLabelJobRequest):
    return candidate_runner.run_candidates(request.dict())


@router.get("/internal/auto-label/progress/{job_id}")
def get_job_progress(job_id: str):
    progress = job_progress.get(job_id)
    if not progress:
        return {
            "available": False,
            "job_id": job_id,
            "status": "UNKNOWN",
        }
    return {
        "available": True,
        **progress,
    }
