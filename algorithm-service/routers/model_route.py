from fastapi import APIRouter

from schemas.auto_label_to_model import ModelRouteRequest
from services import model_router

router = APIRouter()


@router.post("/internal/model-router/preview")
async def preview_route(request: ModelRouteRequest):
    return model_router.preview_route(request.dict())
