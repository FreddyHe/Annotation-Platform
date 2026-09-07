from fastapi import APIRouter

from schemas.auto_label_to_model import LabelStudioFormatRequest
from services import label_studio_formatter

router = APIRouter()


@router.post("/internal/label-studio/format-predictions")
async def format_predictions(request: LabelStudioFormatRequest):
    return label_studio_formatter.format_predictions(request.dict())
