from fastapi import APIRouter

from schemas.auto_label_to_model import DatasetProfileRequest
from services import dataset_profiler

router = APIRouter()


@router.post("/internal/datasets/profile")
async def profile_dataset(request: DatasetProfileRequest):
    return dataset_profiler.profile_dataset(request.dict())
