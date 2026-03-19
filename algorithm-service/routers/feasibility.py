import base64
import io
import json
import re
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field
from loguru import logger
from PIL import Image

from services.llm_service import LlmService


router = APIRouter()


DEFAULT_VLM_API_KEY = "sk-644be34708ab44a38a0a28c82e37d6b6"
DEFAULT_VLM_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
DEFAULT_VLM_MODEL_NAME = "qwen-vl-plus"


def _extract_json_object(text: str) -> Dict[str, Any]:
    cleaned = text.strip()
    cleaned = re.sub(r"^```(json)?\s*", "", cleaned, flags=re.IGNORECASE | re.MULTILINE)
    cleaned = re.sub(r"\s*```$", "", cleaned, flags=re.MULTILINE)
    cleaned = cleaned.strip()

    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start == -1 or end == -1 or end <= start:
        raise ValueError("Response does not contain a JSON object")

    json_str = cleaned[start : end + 1]
    return json.loads(json_str)


def _encode_image_to_base64(image: Image.Image) -> str:
    buffered = io.BytesIO()
    image.save(buffered, format="JPEG")
    return base64.b64encode(buffered.getvalue()).decode("utf-8")


class RequirementParseRequest(BaseModel):
    raw_requirement: str = Field(..., description="Raw user requirement text")
    llm_api_key: Optional[str] = Field(default=None, description="LLM API key (optional)")
    llm_base_url: Optional[str] = Field(default=None, description="LLM base URL (optional)")
    llm_model_name: Optional[str] = Field(default=None, description="LLM model name (optional)")


class RequirementCategory(BaseModel):
    categoryName: str
    categoryNameEn: str
    categoryType: str
    sceneDescription: str
    viewAngle: str


class RequirementParseResponse(BaseModel):
    categories: List[RequirementCategory]
    summary: str


@router.post("/feasibility/parse-requirement", response_model=RequirementParseResponse)
async def parse_requirement(request: RequirementParseRequest):
    try:
        llm = LlmService(
            api_key=request.llm_api_key,
            base_url=request.llm_base_url,
            model_name=request.llm_model_name,
        )
        result = llm.parse_requirement(request.raw_requirement)
        return result
    except Exception as e:
        logger.error(f"parse-requirement failed: {e}")
        raise HTTPException(
            status_code=503,
            detail={
                "message": "LLM service unavailable",
                "error": str(e),
            },
        )


@router.post("/feasibility/analyze-image")
async def analyze_image(
    image: UploadFile = File(...),
    category_name: str = Form(...),
    scene_description: str = Form(...),
    vlm_api_key: Optional[str] = Form(default=None),
    vlm_base_url: Optional[str] = Form(default=None),
    vlm_model_name: Optional[str] = Form(default=None),
):
    from openai import OpenAI

    effective_api_key = vlm_api_key or DEFAULT_VLM_API_KEY
    effective_base_url = vlm_base_url or DEFAULT_VLM_BASE_URL
    effective_model_name = vlm_model_name or DEFAULT_VLM_MODEL_NAME

    try:
        content_bytes = await image.read()
        pil = Image.open(io.BytesIO(content_bytes)).convert("RGB")
        image_b64 = _encode_image_to_base64(pil)

        prompt = f"""
你是“AI视觉可行性评估”模块的图片场景分析器。你将根据一张图片与目标类别信息，输出对该场景做目标检测/缺陷检测的难度评分。

目标类别：{category_name}
场景描述：{scene_description}

请只输出一个 JSON 对象，字段如下（数值范围 0~1，越大代表越强）：
{{
  "sceneComplexity": 0.0,
  "targetOccupancy": 0.0,
  "backgroundInterference": 0.0,
  "occlusionSeverity": 0.0,
  "notes": "一句话解释评分原因"
}}
""".strip()

        client = OpenAI(api_key=effective_api_key, base_url=effective_base_url)
        response = client.chat.completions.create(
            model=effective_model_name,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {
                            "type": "image_url",
                            "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"},
                        },
                    ],
                }
            ],
            temperature=0.1,
            max_tokens=800,
        )

        response_text = response.choices[0].message.content or ""
        parsed = _extract_json_object(response_text)
        return parsed
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"analyze-image failed: {e}")
        raise HTTPException(
            status_code=503,
            detail={
                "message": "VLM service unavailable",
                "error": str(e),
            },
        )

