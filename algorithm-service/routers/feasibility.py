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
你是"AI视觉可行性评估"模块的图片场景分析器。你将根据一张图片与目标类别信息，输出对该场景做目标检测/缺陷检测的难度评分。

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


class GeneratePromptsRequest(BaseModel):
    categoryNameEn: str = Field(..., description="English category name")
    sceneDescription: str = Field(..., description="Scene description")
    llm_api_key: Optional[str] = Field(default=None, description="LLM API key (optional)")
    llm_base_url: Optional[str] = Field(default=None, description="LLM base URL (optional)")
    llm_model_name: Optional[str] = Field(default=None, description="LLM model name (optional)")


class GeneratePromptsResponse(BaseModel):
    prompts: List[str] = Field(..., description="List of prompt variants")


def _generate_fallback_prompts(category_name_en: str, scene_description: str) -> List[str]:
    """Generate simple rule-based prompt variants when LLM is unavailable"""
    prompts = [category_name_en]
    
    words = category_name_en.lower().split()
    if len(words) > 1:
        prompts.append(" ".join(words[::-1]))
    
    scene_words = scene_description.lower().split()[:3]
    if scene_words:
        prompts.append(f"{category_name_en} in {' '.join(scene_words)}")
    
    synonyms = {
        "large": ["big", "huge"],
        "rock": ["stone", "boulder"],
        "rail": ["track", "railway"],
        "spike": ["pin", "nail"],
        "loose": ["detached", "unfastened"],
    }
    
    for word in words:
        if word.lower() in synonyms:
            for syn in synonyms[word.lower()]:
                variant = category_name_en.replace(word, syn)
                if variant not in prompts:
                    prompts.append(variant)
                    break
    
    return prompts[:5]


@router.post("/feasibility/generate-prompts", response_model=GeneratePromptsResponse)
async def generate_prompts(request: GeneratePromptsRequest):
    """Generate prompt variants for OVD testing using LLM, with fallback to rule-based generation"""
    try:
        llm = LlmService(
            api_key=request.llm_api_key,
            base_url=request.llm_base_url,
            model_name=request.llm_model_name,
        )
        
        from openai import OpenAI
        client = OpenAI(api_key=llm.api_key, base_url=llm.base_url)
        
        system_prompt = """
You are a prompt engineering assistant for Open-Vocabulary Object Detection (OVD).
Given a category name and scene description, generate 5 semantically different English prompts that could help detect the target object.

Requirements:
1. Output ONLY a JSON object with a "prompts" array containing exactly 5 strings
2. Each prompt should be a short phrase (2-6 words)
3. Prompts should vary in specificity, synonyms, and context
4. All prompts must be in English
5. Do NOT include any explanation or markdown formatting
""".strip()

        user_prompt = f"""
Category: {request.categoryNameEn}
Scene: {request.sceneDescription}

Generate 5 prompt variants.
""".strip()

        response = client.chat.completions.create(
            model=llm.model_name,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.7,
            max_tokens=300,
        )
        
        content = response.choices[0].message.content or ""
        parsed = _extract_json_object(content)
        
        if "prompts" not in parsed or not isinstance(parsed["prompts"], list):
            raise ValueError("LLM response missing 'prompts' array")
        
        prompts = [str(p).strip() for p in parsed["prompts"] if p]
        if len(prompts) < 3:
            raise ValueError(f"LLM returned too few prompts: {len(prompts)}")
        
        logger.info(f"Generated {len(prompts)} prompts via LLM for '{request.categoryNameEn}'")
        return GeneratePromptsResponse(prompts=prompts[:5])
        
    except Exception as e:
        logger.warning(f"LLM prompt generation failed: {e}, falling back to rule-based generation")
        fallback_prompts = _generate_fallback_prompts(request.categoryNameEn, request.sceneDescription)
        return GeneratePromptsResponse(prompts=fallback_prompts)


class CategoryPrompts(BaseModel):
    categoryNameEn: str = Field(..., description="English category name")
    prompts: List[str] = Field(..., description="List of prompts to test")


class RunOvdTestRequest(BaseModel):
    categories: List[CategoryPrompts] = Field(..., description="Categories with prompts to test")
    imagePaths: List[str] = Field(..., description="Image paths to test on")


class OvdTestResultItem(BaseModel):
    categoryNameEn: str
    imagePath: str
    bestPrompt: str
    detectedCount: int
    averageConfidence: float
    bboxJson: str
    annotatedImagePath: Optional[str] = None


class RunOvdTestResponse(BaseModel):
    results: List[OvdTestResultItem]


async def _call_dino_for_prompt(image_path: str, prompt: str, box_threshold: float = 0.4, text_threshold: float = 0.25) -> Dict[str, Any]:
    """Call DINO service for a single prompt"""
    import httpx
    import os
    
    dino_url = "http://127.0.0.1:5003/predict"
    
    try:
        if not os.path.exists(image_path):
            logger.error(f"Image file not found: {image_path}")
            return {"detections": [], "error": "File not found"}
        
        with open(image_path, 'rb') as f:
            image_data = f.read()
        
        text_prompt = prompt if prompt.endswith(".") else f"{prompt}."
        
        async with httpx.AsyncClient(timeout=60.0) as client:
            response = await client.post(
                dino_url,
                files={'image': (os.path.basename(image_path), image_data, 'image/jpeg')},
                data={
                    'text_prompt': text_prompt,
                    'box_threshold': str(box_threshold),
                    'text_threshold': str(text_threshold)
                }
            )
            
            if response.status_code == 200:
                data = response.json()
                detections = data.get('detections', [])
                
                with Image.open(image_path) as img:
                    img_w, img_h = img.size
                
                converted_detections = []
                for det in detections:
                    box = det.get('box', [])
                    if len(box) == 4:
                        cx, cy, w, h = box
                        x_min = (cx - w / 2) * img_w
                        y_min = (cy - h / 2) * img_h
                        abs_w = w * img_w
                        abs_h = h * img_h
                        
                        converted_detections.append({
                            "x": x_min,
                            "y": y_min,
                            "width": abs_w,
                            "height": abs_h,
                            "confidence": det.get('logit_score', det.get('score', 0.0))
                        })
                
                return {"detections": converted_detections}
            else:
                logger.error(f"DINO service error: {response.status_code}")
                return {"detections": [], "error": f"DINO service error: {response.status_code}"}
                
    except Exception as e:
        logger.error(f"Error calling DINO service: {e}")
        return {"detections": [], "error": str(e)}


def _draw_bboxes_on_image(image_path: str, bboxes: List[Dict], output_dir: str = "/root/autodl-fs/uploads/ovd_annotated") -> Optional[str]:
    """Draw bounding boxes on image and save annotated version"""
    import os
    from PIL import ImageDraw
    
    try:
        os.makedirs(output_dir, exist_ok=True)
        
        with Image.open(image_path) as img:
            draw = ImageDraw.Draw(img)
            
            for bbox in bboxes:
                x, y, w, h = bbox["x"], bbox["y"], bbox["width"], bbox["height"]
                draw.rectangle([x, y, x + w, y + h], outline="red", width=3)
                
                conf = bbox.get("confidence", 0.0)
                draw.text((x, y - 10), f"{conf:.2f}", fill="red")
            
            base_name = os.path.basename(image_path)
            name_without_ext = os.path.splitext(base_name)[0]
            output_path = os.path.join(output_dir, f"{name_without_ext}_annotated.jpg")
            
            img.save(output_path, "JPEG")
            return output_path
            
    except Exception as e:
        logger.error(f"Error drawing bboxes: {e}")
        return None


@router.post("/feasibility/run-ovd-test", response_model=RunOvdTestResponse)
async def run_ovd_test(request: RunOvdTestRequest):
    """Run OVD testing with multiple prompts per category and return best results"""
    results = []
    
    for category in request.categories:
        for image_path in request.imagePaths:
            best_prompt = None
            best_detections = []
            best_count = 0
            best_avg_conf = 0.0
            
            for prompt in category.prompts:
                try:
                    dino_result = await _call_dino_for_prompt(image_path, prompt, box_threshold=0.4)
                    
                    if "error" in dino_result:
                        logger.warning(f"DINO error for prompt '{prompt}': {dino_result['error']}")
                        continue
                    
                    detections = dino_result.get("detections", [])
                    count = len(detections)
                    avg_conf = sum(d.get("confidence", 0.0) for d in detections) / count if count > 0 else 0.0
                    
                    if avg_conf > best_avg_conf or (avg_conf == best_avg_conf and count > best_count):
                        best_prompt = prompt
                        best_detections = detections
                        best_count = count
                        best_avg_conf = avg_conf
                        
                except Exception as e:
                    logger.error(f"Error testing prompt '{prompt}': {e}")
                    continue
            
            if best_prompt is None:
                best_prompt = category.prompts[0] if category.prompts else category.categoryNameEn
            
            annotated_path = None
            if best_detections:
                annotated_path = _draw_bboxes_on_image(image_path, best_detections)
            
            results.append(OvdTestResultItem(
                categoryNameEn=category.categoryNameEn,
                imagePath=image_path,
                bestPrompt=best_prompt,
                detectedCount=best_count,
                averageConfidence=round(best_avg_conf, 4),
                bboxJson=json.dumps(best_detections),
                annotatedImagePath=annotated_path
            ))
    
    return RunOvdTestResponse(results=results)


class VlmEvaluateRequest(BaseModel):
    imagePath: str = Field(..., description="Original image path")
    annotatedImagePath: Optional[str] = Field(default=None, description="Annotated image path with bboxes")
    categoryName: str = Field(..., description="Category name")
    bboxJson: str = Field(..., description="Detected bboxes in JSON format")
    vlm_api_key: Optional[str] = Field(default=None, description="VLM API key (optional)")
    vlm_base_url: Optional[str] = Field(default=None, description="VLM base URL (optional)")
    vlm_model_name: Optional[str] = Field(default=None, description="VLM model name (optional)")


class BboxEvaluationDetail(BaseModel):
    bbox_idx: int = Field(..., description="Index of the bbox")
    is_correct: bool = Field(..., description="Whether VLM confirmed this detection")
    cropped_image_path: Optional[str] = Field(None, description="Path to cropped image")
    question: Optional[str] = Field(None, description="Question asked to VLM")
    vlm_answer: Optional[str] = Field(None, description="VLM's answer")
    bbox: Optional[dict] = Field(None, description="Original bbox coordinates")
    reason: Optional[str] = Field(None, description="Error reason if failed")


class VlmEvaluateResponse(BaseModel):
    totalGtEstimated: int = Field(..., description="Estimated total ground truth objects")
    detected: int = Field(..., description="Number of detected objects")
    falsePositive: int = Field(..., description="Number of false positives")
    precisionEstimate: float = Field(..., description="Estimated precision (0-1)")
    recallEstimate: float = Field(..., description="Estimated recall (0-1)")
    bboxQuality: str = Field(..., description="EXCELLENT/GOOD/FAIR/POOR")
    overallVerdict: str = Field(..., description="feasible/partially_feasible/not_feasible")
    notes: str = Field(..., description="Evaluation notes")
    detailResults: Optional[List[BboxEvaluationDetail]] = Field(None, description="Detailed evaluation for each bbox")


def _get_bbox_quality(precision: float, recall: float) -> str:
    """Determine bbox quality based on precision and recall"""
    avg_score = (precision + recall) / 2
    if avg_score > 0.9:
        return "EXCELLENT"
    elif avg_score > 0.7:
        return "GOOD"
    elif avg_score > 0.5:
        return "FAIR"
    else:
        return "POOR"


def _get_overall_verdict(precision: float, recall: float) -> str:
    """Determine overall verdict based on metrics"""
    if precision > 0.7 and recall > 0.7:
        return "feasible"
    elif precision > 0.5 or recall > 0.5:
        return "partially_feasible"
    else:
        return "not_feasible"


def _generate_default_evaluation(detected_count: int) -> Dict[str, Any]:
    """Generate default evaluation when VLM is unavailable"""
    return {
        "totalGtEstimated": max(detected_count, 1),
        "detected": detected_count,
        "falsePositive": 0,
        "precisionEstimate": 0.5,
        "recallEstimate": 0.5,
        "bboxQuality": "FAIR",
        "overallVerdict": "partially_feasible",
        "notes": "VLM服务不可用，返回默认评分"
    }


@router.post("/feasibility/vlm-evaluate", response_model=VlmEvaluateResponse)
async def vlm_evaluate(request: VlmEvaluateRequest):
    """Use VLM to evaluate each detected bbox individually by asking yes/no if it matches the prompt"""
    from openai import OpenAI
    import os
    
    effective_api_key = request.vlm_api_key or DEFAULT_VLM_API_KEY
    effective_base_url = request.vlm_base_url or DEFAULT_VLM_BASE_URL
    effective_model_name = request.vlm_model_name or DEFAULT_VLM_MODEL_NAME
    
    try:
        bboxes = json.loads(request.bboxJson) if request.bboxJson else []
        detected_count = len(bboxes)
        
        if detected_count == 0:
            return VlmEvaluateResponse(
                totalGtEstimated=0,
                detected=0,
                falsePositive=0,
                precisionEstimate=0.0,
                recallEstimate=0.0,
                bboxQuality="POOR",
                overallVerdict="not_feasible",
                notes="无检测结果"
            )
        
        if not os.path.exists(request.imagePath):
            logger.error(f"Image not found: {request.imagePath}")
            return VlmEvaluateResponse(**_generate_default_evaluation(detected_count))
        
        original_image = Image.open(request.imagePath).convert("RGB")
        img_width, img_height = original_image.size
        
        client = OpenAI(api_key=effective_api_key, base_url=effective_base_url)
        
        correct_detections = 0
        evaluation_results = []
        
        crop_dir = "/root/autodl-fs/uploads/vlm_crops"
        os.makedirs(crop_dir, exist_ok=True)
        
        image_basename = os.path.splitext(os.path.basename(request.imagePath))[0]
        
        for idx, bbox in enumerate(bboxes):
            x = bbox.get("x", 0)
            y = bbox.get("y", 0)
            width = bbox.get("width", 0)
            height = bbox.get("height", 0)
            
            x1 = max(0, int(x))
            y1 = max(0, int(y))
            x2 = min(img_width, int(x + width))
            y2 = min(img_height, int(y + height))
            
            if x2 <= x1 or y2 <= y1:
                logger.warning(f"Invalid bbox {idx}: ({x1},{y1},{x2},{y2})")
                evaluation_results.append({
                    "bbox_idx": idx,
                    "is_correct": False,
                    "reason": "无效框",
                    "cropped_image_path": None,
                    "question": None,
                    "vlm_answer": None
                })
                continue
            
            cropped_image = original_image.crop((x1, y1, x2, y2))
            
            crop_filename = f"{image_basename}_crop_{idx}.jpg"
            crop_path = os.path.join(crop_dir, crop_filename)
            cropped_image.save(crop_path, "JPEG", quality=95)
            
            cropped_b64 = _encode_image_to_base64(cropped_image)
            
            question = f'这是一张裁剪图。请严格判断：这张图中是否包含"{request.categoryName}"？\n\n要求：\n- 只有当你非常确定图中包含"{request.categoryName}"时，才回答"是"\n- 如果不确定、模糊、或者不是"{request.categoryName}"，必须回答"否"\n- 只输出"是"或"否"，不要其他任何文字'
            
            try:
                response = client.chat.completions.create(
                    model=effective_model_name,
                    messages=[{
                        "role": "user",
                        "content": [
                            {"type": "text", "text": question},
                            {
                                "type": "image_url",
                                "image_url": {"url": f"data:image/jpeg;base64,{cropped_b64}"}
                            }
                        ]
                    }],
                    temperature=0.0,
                    max_tokens=10,
                )
                
                answer = response.choices[0].message.content.strip()
                is_correct = "是" in answer or "yes" in answer.lower()
                
                if is_correct:
                    correct_detections += 1
                
                evaluation_results.append({
                    "bbox_idx": idx,
                    "is_correct": is_correct,
                    "cropped_image_path": crop_path,
                    "question": question,
                    "vlm_answer": answer,
                    "bbox": bbox
                })
                
                logger.debug(f"Bbox {idx}: VLM答案='{answer}', 判定={'正确' if is_correct else '错误'}")
                
            except Exception as e:
                logger.error(f"VLM evaluation failed for bbox {idx}: {e}")
                evaluation_results.append({
                    "bbox_idx": idx,
                    "is_correct": False,
                    "reason": str(e),
                    "cropped_image_path": crop_path,
                    "question": question,
                    "vlm_answer": None
                })
        
        false_positive = detected_count - correct_detections
        precision = correct_detections / detected_count if detected_count > 0 else 0.0
        
        if precision > 0.8:
            bbox_quality = "EXCELLENT"
            overall_verdict = "feasible"
        elif precision > 0.6:
            bbox_quality = "GOOD"
            overall_verdict = "feasible"
        elif precision > 0.4:
            bbox_quality = "FAIR"
            overall_verdict = "partially_feasible"
        else:
            bbox_quality = "POOR"
            overall_verdict = "not_feasible"
        
        notes = f"检测{detected_count}个框，VLM确认正确{correct_detections}个，误检{false_positive}个，准确率{precision*100:.1f}%"
        
        logger.info(f"VLM评估完成: {request.categoryName}, 检测={detected_count}, 正确={correct_detections}, P={precision:.2f}")
        
        detail_results = [BboxEvaluationDetail(**result) for result in evaluation_results]
        
        return VlmEvaluateResponse(
            totalGtEstimated=detected_count,
            detected=correct_detections,
            falsePositive=false_positive,
            precisionEstimate=round(precision, 4),
            recallEstimate=0.0,
            bboxQuality=bbox_quality,
            overallVerdict=overall_verdict,
            notes=notes,
            detailResults=detail_results
        )
        
    except Exception as e:
        logger.error(f"VLM evaluation failed: {e}")
        default_eval = _generate_default_evaluation(detected_count if 'detected_count' in locals() else 0)
        return VlmEvaluateResponse(**default_eval)


class CategoryForDatasetSearch(BaseModel):
    categoryNameEn: str = Field(..., description="Category name in English")
    categoryName: str = Field(..., description="Category name in Chinese")


class SearchDatasetsRequest(BaseModel):
    categories: List[CategoryForDatasetSearch] = Field(..., description="Categories to search datasets for")


class DatasetInfo(BaseModel):
    source: str = Field(..., description="Dataset source (Roboflow/HuggingFace)")
    datasetName: str = Field(..., description="Dataset name")
    datasetUrl: str = Field(..., description="Dataset URL")
    sampleCount: int = Field(..., description="Number of samples")
    annotationFormat: str = Field(..., description="Annotation format")
    license: str = Field(..., description="Dataset license")
    relevanceScore: float = Field(..., description="Relevance score (0-1)")


class CategoryDatasetResult(BaseModel):
    categoryName: str = Field(..., description="Category name")
    categoryNameEn: str = Field(..., description="Category name in English")
    searchUrl: str = Field(..., description="Search URL for this category")
    datasets: List[DatasetInfo] = Field(..., description="Found datasets")


class SearchDatasetsResponse(BaseModel):
    results: List[CategoryDatasetResult] = Field(..., description="Search results for each category")


class CategoryForResourceEstimation(BaseModel):
    categoryName: str = Field(..., description="Category name")
    feasibilityBucket: str = Field(..., description="Feasibility bucket: OVD_AVAILABLE/CUSTOM_LOW/CUSTOM_MEDIUM/CUSTOM_HIGH")
    sceneComplexity: str = Field(default="medium", description="Scene complexity: low/medium/high")
    sceneDiversity: str = Field(default="medium", description="Scene diversity: low/medium/high")
    datasetMatchLevel: Optional[str] = Field(default=None, description="Dataset match level: ALMOST_MATCH/PARTIAL_MATCH/NOT_USABLE")
    availablePublicSamples: int = Field(default=0, description="Total available public dataset samples")


class EstimateResourcesRequest(BaseModel):
    categories: List[CategoryForResourceEstimation] = Field(..., description="Categories to estimate resources for")


class ResourceEstimationResult(BaseModel):
    categoryName: str = Field(..., description="Category name")
    estimatedImages: int = Field(..., description="Estimated number of images needed")
    estimatedManDays: float = Field(..., description="Estimated man-days for annotation")
    gpuHours: float = Field(..., description="Estimated GPU hours for training")
    iterationCount: int = Field(..., description="Estimated iteration count")
    estimatedTotalDays: float = Field(..., description="Estimated total project days")
    publicDatasetImages: int = Field(default=0, description="Number of images from public datasets")
    trainingApproach: str = Field(default="", description="Training approach description")
    notes: str = Field(..., description="Estimation notes")


class EstimateResourcesResponse(BaseModel):
    results: List[ResourceEstimationResult] = Field(..., description="Resource estimation for each category")


def _calculate_relevance_score(query: str, dataset_name: str, tags: List[str] = None) -> float:
    """Calculate relevance score based on word overlap between query and dataset name/tags"""
    query_words = set(query.lower().replace('-', ' ').replace('_', ' ').split())
    dataset_words = set(dataset_name.lower().replace('-', ' ').replace('_', ' ').split())
    
    if tags:
        for tag in tags:
            dataset_words.update(tag.lower().replace('-', ' ').replace('_', ' ').split())
    
    if not query_words or not dataset_words:
        return 0.0
    
    intersection = query_words & dataset_words
    union = query_words | dataset_words
    
    jaccard_score = len(intersection) / len(union) if union else 0.0
    
    overlap_ratio = len(intersection) / len(query_words) if query_words else 0.0
    
    return min(1.0, (jaccard_score * 0.5 + overlap_ratio * 0.5))


def _search_roboflow_datasets(category_name_en: str) -> List[DatasetInfo]:
    """Search Roboflow Universe for datasets using real HTTP requests and HTML parsing"""
    try:
        import httpx
        from bs4 import BeautifulSoup
        import re
        
        search_query = f"object detection {category_name_en}"
        search_url = f"https://universe.roboflow.com/search?q={search_query.replace(' ', '+')}"
        
        headers = {
            'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.5',
            'Accept-Encoding': 'gzip, deflate, br',
            'DNT': '1',
            'Connection': 'keep-alive',
            'Upgrade-Insecure-Requests': '1'
        }
        
        try:
            with httpx.Client(timeout=15.0, follow_redirects=True) as client:
                response = client.get(search_url, headers=headers)
                
                if response.status_code == 200:
                    soup = BeautifulSoup(response.text, 'html.parser')
                    datasets = []
                    
                    dataset_cards = soup.find_all('a', href=re.compile(r'^/[^/]+/[^/]+/?$'))
                    
                    for card in dataset_cards[:10]:
                        try:
                            dataset_url = f"https://universe.roboflow.com{card['href']}"
                            dataset_name = card.get_text(strip=True)
                            
                            if not dataset_name or len(dataset_name) < 3:
                                continue
                            
                            sample_count_elem = card.find_next('span', text=re.compile(r'\d+\s*(images|samples)'))
                            sample_count = 500
                            if sample_count_elem:
                                match = re.search(r'(\d+)', sample_count_elem.get_text())
                                if match:
                                    sample_count = int(match.group(1))
                            
                            relevance = _calculate_relevance_score(category_name_en, dataset_name)
                            
                            datasets.append(DatasetInfo(
                                source="Roboflow",
                                datasetName=dataset_name,
                                datasetUrl=dataset_url,
                                sampleCount=sample_count,
                                annotationFormat="COCO JSON",
                                license="Varies",
                                relevanceScore=round(relevance, 3)
                            ))
                        except Exception as e:
                            logger.debug(f"Failed to parse dataset card: {e}")
                            continue
                    
                    if datasets:
                        logger.info(f"Found {len(datasets)} datasets from Roboflow for '{category_name_en}'")
                        return datasets
                    else:
                        logger.info(f"No datasets parsed from Roboflow HTML for '{category_name_en}'")
                        return []
                elif response.status_code == 403:
                    logger.warning(f"Roboflow returned 403 (access denied) for '{category_name_en}' - may need manual search")
                    return []
                else:
                    logger.warning(f"Roboflow search returned status {response.status_code}")
                    return []
        except httpx.TimeoutException:
            logger.warning(f"Roboflow search timeout for '{category_name_en}'")
            return []
        except Exception as e:
            logger.warning(f"Roboflow HTTP request failed: {e}")
            return []
            
    except Exception as e:
        logger.error(f"Roboflow search failed: {e}")
        return []


def _search_huggingface_datasets(category_name_en: str) -> List[DatasetInfo]:
    """Search HuggingFace for datasets (mock implementation)"""
    try:
        import httpx
        import asyncio
        
        async def search():
            async with httpx.AsyncClient(timeout=5.0) as client:
                response = await client.get(
                    f"https://huggingface.co/api/datasets?search={category_name_en}&limit=5",
                    follow_redirects=True
                )
                if response.status_code == 200:
                    return []
        
        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():
                return []
            else:
                return loop.run_until_complete(search()) or []
        except:
            return []
    except Exception as e:
        logger.debug(f"HuggingFace search failed: {e}")
        return []


@router.post("/feasibility/search-datasets", response_model=SearchDatasetsResponse)
async def search_datasets(request: SearchDatasetsRequest):
    """Search for open-source datasets matching the categories"""
    results = []
    
    for category in request.categories:
        search_query = f"object detection {category.categoryNameEn}"
        search_url = f"https://universe.roboflow.com/search?q={search_query.replace(' ', '+')}"
        
        datasets = []
        
        roboflow_datasets = _search_roboflow_datasets(category.categoryNameEn)
        datasets.extend(roboflow_datasets)
        
        hf_datasets = _search_huggingface_datasets(category.categoryNameEn)
        datasets.extend(hf_datasets)
        
        datasets.sort(key=lambda d: (d.relevanceScore, d.sampleCount), reverse=True)
        
        top_datasets = datasets[:4]
        
        if not top_datasets:
            logger.info(f"No datasets found for {category.categoryName}, returning fallback note")
        
        results.append(CategoryDatasetResult(
            categoryName=category.categoryName,
            categoryNameEn=category.categoryNameEn,
            searchUrl=search_url,
            datasets=top_datasets
        ))
    
    logger.info(f"Dataset search completed for {len(request.categories)} categories")
    return SearchDatasetsResponse(results=results)


def _calculate_complexity_multiplier(complexity: str) -> float:
    """Calculate complexity multiplier"""
    multipliers = {"low": 0.7, "medium": 1.0, "high": 1.5}
    return multipliers.get(complexity.lower(), 1.0)


def _calculate_diversity_multiplier(diversity: str) -> float:
    """Calculate diversity multiplier"""
    multipliers = {"low": 0.8, "medium": 1.0, "high": 1.3}
    return multipliers.get(diversity.lower(), 1.0)


@router.post("/feasibility/estimate-resources", response_model=EstimateResourcesResponse)
async def estimate_resources(request: EstimateResourcesRequest):
    """Estimate resources needed for each category based on feasibility bucket and dataset availability"""
    results = []
    
    for category in request.categories:
        bucket = category.feasibilityBucket.upper()
        complexity_mult = _calculate_complexity_multiplier(category.sceneComplexity)
        diversity_mult = _calculate_diversity_multiplier(category.sceneDiversity)
        public_samples = category.availablePublicSamples
        match_level = category.datasetMatchLevel
        
        if bucket == "OVD_AVAILABLE":
            # 桶A：无需标注，直接部署OVD
            estimated_images = 0
            estimated_man_days = round(1.0 + (complexity_mult - 1) * 0.3, 1)
            gpu_hours = 0.0
            iteration_count = 1
            estimated_total_days = round(estimated_man_days + 1, 1)
            public_dataset_images = 0
            training_approach = "直接部署OVD模型，无需训练"
            notes = "OVD效果达标，无需标注数据，仅需配置和测试"
            
        elif bucket == "CUSTOM_LOW":
            # 桶B低定制：公开数据集几乎一致，补充15%
            supplement_ratio = 0.15 * complexity_mult
            estimated_images = int(public_samples * supplement_ratio * diversity_mult)
            estimated_images = max(100, min(estimated_images, 1000))  # 限制在100-1000之间
            estimated_man_days = round(estimated_images / 300, 1)
            gpu_hours = round((public_samples + estimated_images) / 200, 1)
            iteration_count = 2
            estimated_total_days = round(estimated_man_days + gpu_hours / 8 + 3, 1)
            public_dataset_images = public_samples
            training_approach = f"基于{public_samples}张公开数据集进行微调，补充标注{estimated_images}张"
            notes = f"数据集几乎一致，利用公开数据{public_samples}张，补充标注{estimated_images}张进行微调"
            
        elif bucket == "CUSTOM_MEDIUM":
            # 桶B中定制：公开数据集部分相关，补充50%
            supplement_ratio = 0.5 * complexity_mult
            estimated_images = int(public_samples * supplement_ratio * diversity_mult)
            estimated_images = max(300, min(estimated_images, 1500))  # 限制在300-1500之间
            estimated_man_days = round(estimated_images / 300, 1)
            gpu_hours = round((public_samples + estimated_images) / 150, 1)
            iteration_count = 3
            estimated_total_days = round(estimated_man_days + gpu_hours / 8 + 5, 1)
            public_dataset_images = public_samples
            training_approach = f"基于{public_samples}张公开数据集，补充标注{estimated_images}张进行定制训练"
            notes = f"数据集部分相关，利用公开数据{public_samples}张，补充标注{estimated_images}张"
            
        elif bucket == "CUSTOM_HIGH":
            # 桶C：公开数据集不可用，全量标注
            base_images = 1600  # 基数确保大于CUSTOM_MEDIUM
            estimated_images = int(base_images * complexity_mult * diversity_mult)
            estimated_man_days = round(estimated_images / 250, 1)
            gpu_hours = round(estimated_images / 80, 1)
            iteration_count = 4
            estimated_total_days = round(estimated_man_days + gpu_hours / 8 + 7, 1)
            public_dataset_images = 0
            training_approach = f"全量标注{estimated_images}张图片，从零训练"
            notes = f"数据集不可用，需从头标注{estimated_images}张图片进行完全定制训练"
            
        else:
            # 默认情况
            base_images = 1000
            estimated_images = int(base_images * complexity_mult * diversity_mult)
            estimated_man_days = round(estimated_images / 300, 1)
            gpu_hours = round(estimated_images / 100, 1)
            iteration_count = 3
            estimated_total_days = round(estimated_man_days + gpu_hours / 8 + 5, 1)
            public_dataset_images = public_samples
            training_approach = f"定制训练，标注{estimated_images}张图片"
            notes = f"需要定制训练，预计标注{estimated_images}张图片"
        
        results.append(ResourceEstimationResult(
            categoryName=category.categoryName,
            estimatedImages=estimated_images,
            estimatedManDays=estimated_man_days,
            gpuHours=gpu_hours,
            iterationCount=iteration_count,
            estimatedTotalDays=estimated_total_days,
            publicDatasetImages=public_dataset_images,
            trainingApproach=training_approach,
            notes=notes
        ))
    
    logger.info(f"Resource estimation completed for {len(request.categories)} categories")
    return EstimateResourcesResponse(results=results)


class FeasibilityReportRequest(BaseModel):
    assessmentName: str = Field(..., description="Assessment name")
    rawRequirement: str = Field(..., description="Original requirement description")
    structuredRequirement: Optional[str] = Field(None, description="Structured requirement JSON")
    datasetSize: Optional[int] = Field(None, description="Total available dataset size")
    categoryCount: Optional[int] = Field(None, description="Expected category count")
    samplesPerCategory: Optional[int] = Field(None, description="Samples per category")
    imageQuality: Optional[str] = Field(None, description="Image quality level")
    annotationCompleteness: Optional[int] = Field(None, description="Annotation completeness percentage")
    targetSize: Optional[str] = Field(None, description="Target size level")
    backgroundComplexity: Optional[str] = Field(None, description="Background complexity level")
    interClassSimilarity: Optional[str] = Field(None, description="Inter-class similarity level")
    expectedAccuracy: Optional[int] = Field(None, description="Expected accuracy percentage")
    trainingResource: Optional[str] = Field(None, description="Available training resource")
    timeBudgetDays: Optional[int] = Field(None, description="Time budget in days")
    categories: List[Dict[str, Any]] = Field(..., description="Category assessment results")
    ovdResults: List[Dict[str, Any]] = Field(default_factory=list, description="OVD test results")
    vlmResults: List[Dict[str, Any]] = Field(default_factory=list, description="VLM evaluation results")
    datasetMatchLevel: Optional[str] = Field(None, description="Dataset match level")
    userJudgmentNotes: Optional[str] = Field(None, description="User judgment notes")
    resourceEstimations: List[Dict[str, Any]] = Field(default_factory=list, description="Resource estimations")
    llmApiKey: Optional[str] = Field(default=None, description="LLM API key")
    llmBaseUrl: Optional[str] = Field(default=None, description="LLM base URL")
    llmModelName: Optional[str] = Field(default=None, description="LLM model name")


class FeasibilityReportResponse(BaseModel):
    report: str = Field(..., description="Generated feasibility report in markdown format")


@router.post("/feasibility/generate-report", response_model=FeasibilityReportResponse)
async def generate_feasibility_report(request: FeasibilityReportRequest):
    """
    Generate comprehensive feasibility report using LLM
    """
    logger.info(f"Generating feasibility report for: {request.assessmentName}")
    
    # Prepare data summary
    categories_summary = []
    for cat in request.categories:
        categories_summary.append(f"- {cat.get('categoryName', 'Unknown')}: {cat.get('feasibilityBucket', 'Unknown')} (置信度: {cat.get('confidence', 0):.2f})")
    
    ovd_summary = []
    for ovd in request.ovdResults:
        ovd_summary.append(f"- {ovd.get('categoryName', 'Unknown')}: 检测到 {ovd.get('detectedCount', 0)} 个目标")
    
    vlm_summary = []
    for vlm in request.vlmResults:
        vlm_summary.append(f"- {vlm.get('categoryName', 'Unknown')}: 准确率 {vlm.get('precisionEstimate', 0)*100:.1f}%, 召回率 {vlm.get('recallEstimate', 0)*100:.1f}%")
    
    resource_summary = []
    for res in request.resourceEstimations:
        resource_summary.append(f"- {res.get('categoryName', 'Unknown')}: {res.get('trainingApproach', 'Unknown')}, 需标注 {res.get('estimatedImages', 0)} 张, 预计 {res.get('estimatedTotalDays', 0)} 天")
    
    # Build prompt
    prompt = f"""你是一个AI项目可行性分析专家。请根据以下信息，生成一份专业的项目落地可行性报告。报告应该使用Markdown格式，包含以下章节：

# 项目基本信息
- 项目名称：{request.assessmentName}
- 原始需求：{request.rawRequirement}
- 数据量：{request.datasetSize if request.datasetSize is not None else '未填写'}
- 类别数量：{request.categoryCount if request.categoryCount is not None else '未填写'}
- 单类别样本数：{request.samplesPerCategory if request.samplesPerCategory is not None else '未填写'}
- 图片质量：{request.imageQuality or '未填写'}
- 标注完整度：{request.annotationCompleteness if request.annotationCompleteness is not None else '未填写'}%
- 目标尺寸：{request.targetSize or '未填写'}
- 背景复杂度：{request.backgroundComplexity or '未填写'}
- 类间相似度：{request.interClassSimilarity or '未填写'}
- 预期精度：{request.expectedAccuracy if request.expectedAccuracy is not None else '未填写'}%
- 训练资源：{request.trainingResource or '未填写'}
- 时间预算：{request.timeBudgetDays if request.timeBudgetDays is not None else '未填写'}天

# 需求解析结果
{request.structuredRequirement or '未提供结构化需求'}

# 技术可行性评估

## 类别分析
{chr(10).join(categories_summary) if categories_summary else '无类别信息'}

## OVD测试结果
{chr(10).join(ovd_summary) if ovd_summary else '未进行OVD测试'}

## VLM质量评估
{chr(10).join(vlm_summary) if vlm_summary else '未进行VLM评估'}

## 数据集匹配度
- 匹配度：{request.datasetMatchLevel or '未评估'}
- 用户备注：{request.userJudgmentNotes or '无'}

# 资源估算
{chr(10).join(resource_summary) if resource_summary else '未进行资源估算'}

请基于以上信息，生成一份完整的可行性报告，包括：
1. **项目概述**：简要总结项目目标和需求
2. **技术可行性分析**：分析各类别的技术实现路径（桶A/B/C）及其含义
3. **数据准备策略**：根据数据集匹配度，说明数据准备方案
4. **资源投入评估**：汇总人力、时间、GPU资源需求
5. **风险与建议**：指出潜在风险和优化建议
6. **结论**：给出明确的可行性结论和推荐方案

报告应专业、清晰、有条理，使用Markdown格式输出。"""

    try:
        llm_service = LlmService(
            api_key=request.llmApiKey or DEFAULT_VLM_API_KEY,
            base_url=request.llmBaseUrl or DEFAULT_VLM_BASE_URL,
            model_name=request.llmModelName or DEFAULT_VLM_MODEL_NAME
        )
        
        response = llm_service.chat([{"role": "user", "content": prompt}])
        report = response.strip()
        
        logger.info(f"Feasibility report generated successfully, length: {len(report)}")
        return FeasibilityReportResponse(report=report)
        
    except Exception as e:
        logger.error(f"Failed to generate feasibility report: {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to generate report: {str(e)}")
