from typing import Any, Dict, List, Optional

from fastapi import APIRouter
from pydantic import BaseModel, Field

from services import annotated_video_renderer


router = APIRouter()


class AnnotatedVideoRenderRequest(BaseModel):
    job_id: Optional[int] = None
    project_id: Optional[int] = None
    frames: List[Dict[str, Any]] = Field(default_factory=list)
    output_path: str
    fps: float = 2.0
    full_source_video: bool = False
    source_video_path: Optional[str] = None
    source_video_id: Optional[str] = None
    source_video_name: Optional[str] = None
    semantic_verification_enabled: Optional[bool] = None
    variant: Optional[str] = None


@router.post("/internal/annotated-video/render")
async def render_annotated_video(request: AnnotatedVideoRenderRequest):
    return annotated_video_renderer.render_annotated_video(request.dict())
