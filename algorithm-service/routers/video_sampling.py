from fastapi import APIRouter

from services import video_smart_sampler

router = APIRouter()


@router.post("/internal/video/smart-sample")
async def smart_sample_video(payload: dict):
    return video_smart_sampler.smart_sample_video(payload)
