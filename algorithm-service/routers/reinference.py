from typing import Any, Dict, List

from fastapi import APIRouter
from pydantic import BaseModel, Field

router = APIRouter(prefix="/algo/reinference", tags=["ReInference"])


class VlmJudgeItem(BaseModel):
    id: int
    image_path: str
    detections: List[Dict[str, Any]] = Field(default_factory=list)
    avg_confidence: float = 0.0


class VlmJudgeRequest(BaseModel):
    project_id: int
    round_id: int
    candidates: List[VlmJudgeItem] = Field(default_factory=list)


def _judge(item: VlmJudgeItem) -> Dict[str, Any]:
    if not item.detections:
        return {
            "id": item.id,
            "decision": "uncertain",
            "reasoning": "No detection boxes were produced, requires manual review."
        }
    if item.avg_confidence >= 0.65:
        return {
            "id": item.id,
            "decision": "keep",
            "reasoning": f"Average confidence {item.avg_confidence:.4f} is high enough for low-A feedback."
        }
    if item.avg_confidence < 0.45:
        return {
            "id": item.id,
            "decision": "discard",
            "reasoning": f"Average confidence {item.avg_confidence:.4f} is too low for feedback."
        }
    return {
        "id": item.id,
        "decision": "uncertain",
        "reasoning": f"Average confidence {item.avg_confidence:.4f} needs human confirmation."
    }


@router.post("/vlm-judge")
async def vlm_judge(request: VlmJudgeRequest):
    results = [_judge(item) for item in request.candidates]
    return {
        "success": True,
        "project_id": request.project_id,
        "round_id": request.round_id,
        "count": len(results),
        "results": results
    }
