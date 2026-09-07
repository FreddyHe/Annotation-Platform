from fastapi import APIRouter

from schemas.auto_label_to_model import FusionRequest
from services import prediction_fusion

router = APIRouter()


@router.post("/internal/fusion/merge")
async def merge_candidates(request: FusionRequest):
    return prediction_fusion.merge_candidates(request.dict())
