from fastapi import APIRouter

from schemas.auto_label_to_model import ReviewedTrainingRequest
from services import training_orchestrator

router = APIRouter()


@router.post("/internal/training/prepare-reviewed-dataset")
async def prepare_reviewed_dataset(request: ReviewedTrainingRequest):
    return training_orchestrator.prepare_reviewed_dataset(request.dict())
