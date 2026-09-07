from fastapi import APIRouter

from schemas.auto_label_to_model import RequirementParseRequest
from services import llm_requirement_parser

router = APIRouter()


@router.post("/internal/requirements/parse")
async def parse_requirement(request: RequirementParseRequest):
    return llm_requirement_parser.parse_requirement(request.dict())
