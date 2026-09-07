from typing import Any, List, Optional

from fastapi import APIRouter
from pydantic import BaseModel, Field

from adapters import border_adapter, grounding_dino_adapter, locate_anything_adapter, xingmu_adapter

router = APIRouter()


class GroundingDinoPredictRequest(BaseModel):
    image_path: str
    labels: List[str] = Field(default_factory=list)
    job_id: Optional[Any] = None
    route_id: Optional[Any] = None
    box_threshold: float = 0.25
    text_threshold: float = 0.20


class XingmuPredictRequest(BaseModel):
    image_path: str
    model_id: str
    label: Optional[str] = None
    job_id: Optional[Any] = None
    route_id: Optional[Any] = None
    conf: Optional[float] = None


class LocateAnythingParseRequest(BaseModel):
    text: str
    label: str
    image_id: str
    image_width: int
    image_height: int
    job_id: Optional[Any] = None
    route_id: Optional[Any] = None


class LocateAnythingPredictRequest(BaseModel):
    image_path: str
    labels: List[str] = Field(default_factory=list)
    job_id: Optional[Any] = None
    route_id: Optional[Any] = None
    mode: str = "detect"


class BorderPredictRequest(BaseModel):
    image_path: str
    model_id: str
    labels: List[str] = Field(default_factory=list)
    job_id: Optional[Any] = None
    route_id: Optional[Any] = None
    conf: Optional[float] = None


@router.post("/internal/adapters/grounding-dino/predict")
async def grounding_dino_predict(request: GroundingDinoPredictRequest):
    return grounding_dino_adapter.predict_image(
        image_path=request.image_path,
        labels=request.labels,
        job_id=request.job_id,
        route_id=request.route_id,
        box_threshold=request.box_threshold,
        text_threshold=request.text_threshold,
    )


@router.post("/internal/adapters/xingmu/predict")
async def xingmu_predict(request: XingmuPredictRequest):
    return xingmu_adapter.predict_image(
        image_path=request.image_path,
        model_id=request.model_id,
        label=request.label,
        job_id=request.job_id,
        route_id=request.route_id,
        conf=request.conf,
    )


@router.post("/internal/adapters/locate-anything/parse")
async def locate_anything_parse(request: LocateAnythingParseRequest):
    return locate_anything_adapter.parse_output(
        text=request.text,
        label=request.label,
        image_id=request.image_id,
        image_width=request.image_width,
        image_height=request.image_height,
        job_id=request.job_id,
        route_id=request.route_id,
    )


@router.post("/internal/adapters/locate-anything/predict")
async def locate_anything_predict(request: LocateAnythingPredictRequest):
    return locate_anything_adapter.predict_image(
        image_path=request.image_path,
        labels=request.labels,
        job_id=request.job_id,
        route_id=request.route_id,
        mode=request.mode,
    )


@router.post("/internal/adapters/border/predict")
async def border_predict(request: BorderPredictRequest):
    return border_adapter.predict_image(
        image_path=request.image_path,
        model_id=request.model_id,
        labels=request.labels,
        job_id=request.job_id,
        route_id=request.route_id,
        conf=request.conf,
    )
