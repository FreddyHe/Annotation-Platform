from fastapi import APIRouter, HTTPException, BackgroundTasks
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from loguru import logger
import uuid
import httpx
import base64
import io
import re
import json
from PIL import Image

from services import task_manager
from config import settings

router = APIRouter()


# ==================== 请求/响应模型 ====================

class DinoDetectRequest(BaseModel):
    """DINO 检测请求 - 标准化接口"""
    project_id: int = Field(..., description="Project ID")
    image_paths: List[str] = Field(..., description="List of image paths or URLs")
    labels: List[str] = Field(..., description="List of labels to detect")
    api_key: Optional[str] = Field(default=None, description="API key for cloud service")
    endpoint: Optional[str] = Field(default=None, description="Cloud service endpoint")
    task_id: Optional[str] = Field(default=None, description="Task ID for tracking")


class DinoDetectResponse(BaseModel):
    """DINO 检测响应"""
    success: bool
    message: str
    task_id: str
    status: str
    results: List[Dict[str, Any]]


class VlmCleanRequest(BaseModel):
    """VLM 清洗请求 - 标准化接口"""
    project_id: int = Field(..., description="Project ID")
    detections: List[Dict[str, Any]] = Field(default=[], description="DINO detection results")
    label_definitions: Dict[str, str] = Field(..., description="Label definitions")
    api_key: Optional[str] = Field(default=None, description="API key for cloud service")
    endpoint: Optional[str] = Field(default=None, description="Cloud service endpoint")
    vlm_api_key: Optional[str] = Field(default=None, description="VLM API key (preferred)")
    vlm_base_url: Optional[str] = Field(default=None, description="VLM Base URL (preferred)")
    vlm_model_name: Optional[str] = Field(default=None, description="VLM Model name (preferred)")
    task_id: Optional[str] = Field(default=None, description="Task ID for tracking")


class VlmCleanResponse(BaseModel):
    """VLM 清洗响应"""
    success: bool
    message: str
    task_id: str
    status: str
    results: List[Dict[str, Any]]


class ModelConfigTestRequest(BaseModel):
    api_key: Optional[str] = Field(default=None, description="API key")
    base_url: Optional[str] = Field(default=None, description="Base URL")
    model_name: Optional[str] = Field(default=None, description="Model name")


@router.post("/model-config/test-vlm")
async def test_vlm_model_config(request: ModelConfigTestRequest):
    from openai import OpenAI

    api_key = request.api_key or "sk-644be34708ab44a38a0a28c82e37d6b6"
    base_url = request.base_url or "https://dashscope.aliyuncs.com/compatible-mode/v1"
    model_name = request.model_name or "qwen-vl-plus"

    try:
        client = OpenAI(api_key=api_key, base_url=base_url)
        client.chat.completions.create(
            model=model_name,
            messages=[{"role": "user", "content": "ping"}],
            max_tokens=4,
            temperature=0
        )
        return {"success": True}
    except Exception as e:
        logger.error(f"VLM connectivity test failed: {e}")
        return {"success": False}


@router.post("/model-config/test-llm")
async def test_llm_model_config(request: ModelConfigTestRequest):
    from openai import OpenAI

    api_key = request.api_key or "sk-AomDFLTBpbXd6JXk2hSv2WvzWccvww3TGkPRnA5L51ENOmNt"
    base_url = request.base_url or "https://api.chatanywhere.tech/v1"
    model_name = request.model_name or "gpt-4.1"

    try:
        client = OpenAI(api_key=api_key, base_url=base_url)
        client.chat.completions.create(
            model=model_name,
            messages=[{"role": "user", "content": "ping"}],
            max_tokens=4,
            temperature=0
        )
        return {"success": True}
    except Exception as e:
        logger.error(f"LLM connectivity test failed: {e}")
        return {"success": False}


# ==================== 工具函数 ====================

def encode_image_to_base64(image: Image.Image) -> str:
    """将 PIL Image 转换为 Base64 字符串"""
    buffered = io.BytesIO()
    image.save(buffered, format="JPEG")
    return base64.b64encode(buffered.getvalue()).decode('utf-8')


def crop_image_with_padding(image: Image.Image, bbox: List[float], min_dim: int = 10) -> Image.Image:
    """
    裁剪图片并处理过小尺寸
    
    Args:
        image: 原始图片
        bbox: [x_min, y_min, width, height]
        min_dim: 最小维度，小于该值则向外扩充
        
    Returns:
        裁剪后的图片
    """
    img_w, img_h = image.size
    x_min, y_min, width, height = bbox
    
    x1 = int(max(0, x_min))
    y1 = int(max(0, y_min))
    x2 = int(min(img_w, x_min + width))
    y2 = int(min(img_h, y_min + height))
    
    current_width = x2 - x1
    current_height = y2 - y1
    
    # 尺寸过小则扩张
    if current_width < min_dim or current_height < min_dim:
        expand_w_needed = max(0, min_dim - current_width)
        expand_h_needed = max(0, min_dim - current_height)
        
        delta_w = max(5, expand_w_needed // 2 + 1)
        delta_h = max(5, expand_h_needed // 2 + 1)
        
        x1_new = max(0, x1 - delta_w)
        y1_new = max(0, y1 - delta_h)
        x2_new = min(img_w, x2 + delta_w)
        y2_new = min(img_h, y2 + delta_h)
        
        # 迭代检查
        step = 2
        current_width = x2_new - x1_new
        current_height = y2_new - y1_new
        
        while (current_width < min_dim or current_height < min_dim) and \
              (x1_new > 0 or y1_new > 0 or x2_new < img_w or y2_new < img_h):
            
            prev_w, prev_h = current_width, current_height
            if current_width < min_dim:
                x1_new = max(0, x1_new - step)
                x2_new = min(img_w, x2_new + step)
            if current_height < min_dim:
                y1_new = max(0, y1_new - step)
                y2_new = min(img_h, y2_new + step)
            
            current_width = x2_new - x1_new
            current_height = y2_new - y1_new
            if current_width == prev_w and current_height == prev_h:
                break
        
        x1, y1, x2, y2 = x1_new, y1_new, x2_new, y2_new
    
    return image.crop((x1, y1, x2, y2))


def parse_vlm_json_response(response_text: str) -> tuple:
    """解析 VLM 返回的 JSON 响应"""
    try:
        text = response_text.strip()
        text = re.sub(r'^```(json)?\s*', '', text, flags=re.IGNORECASE | re.MULTILINE)
        text = re.sub(r'\s*```$', '', text, flags=re.MULTILINE)
        text = text.strip()

        start_idx = text.find('{')
        end_idx = text.rfind('}')

        if start_idx != -1 and end_idx != -1:
            json_str = text[start_idx : end_idx + 1]
            data = json.loads(json_str)

            reasoning = data.get("reasoning", "No reasoning provided.")
            decision = str(data.get("decision", "")).lower().strip()
            
            return (decision == "keep"), reasoning
                
    except Exception as e:
        logger.error(f"JSON Parse Error: {e}")
    
    return False, "Failed to parse JSON"


async def call_dino_service(
    image_paths: List[str], 
    labels: List[str],
    box_threshold: float = 0.3,
    text_threshold: float = 0.25
) -> List[Dict[str, Any]]:
    """
    调用本地 DINO 服务 (http://127.0.0.1:5002/predict)
    
    Args:
        image_paths: 图片路径列表（绝对路径）
        labels: 标签列表
        box_threshold: Box threshold (default: 0.3)
        text_threshold: Text threshold (default: 0.25)
        
    Returns:
        检测结果列表
    """
    results = []
    dino_url = "http://127.0.0.1:5003/predict"
    
    for image_path in image_paths:
        try:
            # 检查图片文件是否存在
            if not os.path.exists(image_path):
                logger.error(f"Image file not found: {image_path}")
                results.append({
                    "image_path": image_path,
                    "image_name": image_path.split('/')[-1],
                    "detections": [],
                    "labels": labels,
                    "error": f"File not found: {image_path}"
                })
                continue
            
            # 读取图片
            with open(image_path, 'rb') as f:
                image_data = f.read()
            
            # 组装 text_prompt
            text_prompt = " . ".join(labels)
            if not text_prompt.endswith("."):
                text_prompt += "."
            
            logger.info(f"Calling DINO for {image_path.split('/')[-1]} with prompt: {text_prompt}")
            
            # 调用 DINO 服务
            async with httpx.AsyncClient(timeout=60.0) as client:
                response = await client.post(
                    dino_url,
                    files={'image': (image_path.split('/')[-1], image_data, 'image/jpeg')},
                    data={
                        'text_prompt': text_prompt,
                        'box_threshold': str(box_threshold),
                        'text_threshold': str(text_threshold)
                    }
                )
                
                if response.status_code == 200:
                    data = response.json()
                    detections = data.get('detections', [])
                    
                    # 转换为绝对坐标 [x_min, y_min, width, height]
                    # DINO 返回的是相对坐标 [cx, cy, w, h]
                    with Image.open(image_path) as img:
                        img_w, img_h = img.size
                    
                    converted_detections = []
                    for det in detections:
                        box = det.get('box', [])
                        if len(box) == 4:
                            # [cx, cy, w, h] -> [x_min, y_min, width, height]
                            cx, cy, w, h = box
                            x_min = (cx - w / 2) * img_w
                            y_min = (cy - h / 2) * img_h
                            abs_w = w * img_w
                            abs_h = h * img_h
                            
                            converted_detections.append({
                                "image_path": image_path,
                                "image_name": image_path.split('/')[-1],
                                "label": det.get('label', 'unknown'),
                                "bbox": [x_min, y_min, abs_w, abs_h],
                                "score": det.get('logit_score', det.get('score', 0.0))
                            })
                    
                    results.append({
                        "image_path": image_path,
                        "image_name": image_path.split('/')[-1],
                        "detections": converted_detections,
                        "labels": labels
                    })
                else:
                    logger.error(f"DINO service error: {response.status_code} - {response.text}")
                    results.append({
                        "image_path": image_path,
                        "image_name": image_path.split('/')[-1],
                        "detections": [],
                        "labels": labels,
                        "error": f"DINO service error: {response.status_code}"
                    })
                    
        except Exception as e:
            logger.error(f"Error calling DINO service for {image_path}: {e}")
            results.append({
                "image_path": image_path,
                "image_name": image_path.split('/')[-1],
                "detections": [],
                "labels": labels,
                "error": str(e)
            })
    
    return results


async def call_vlm_cleaning(
    detections: List[Dict[str, Any]],
    label_definitions: Dict[str, str],
    vlm_api_key: Optional[str],
    vlm_base_url: Optional[str],
    vlm_model_name: Optional[str],
    legacy_api_key: Optional[str],
    legacy_endpoint: Optional[str]
) -> List[Dict[str, Any]]:
    """
    调用阿里云 Qwen-VL 进行 VLM 清洗
    
    Args:
        detections: DINO 检测结果
        label_definitions: 类别定义
        
    Returns:
        清洗结果列表
    """
    cleaned_results = []
    
    # 初始化 Qwen-VL 客户端
    from openai import OpenAI
    effective_api_key = vlm_api_key or legacy_api_key or "sk-644be34708ab44a38a0a28c82e37d6b6"
    effective_base_url = vlm_base_url or legacy_endpoint or "https://dashscope.aliyuncs.com/compatible-mode/v1"
    effective_model_name = vlm_model_name or "qwen-vl-plus"
    client = OpenAI(
        api_key=effective_api_key,
        base_url=effective_base_url
    )
    
    for detection in detections:
        try:
            image_path = detection.get("image_path", "unknown")
            label = detection.get("label", "unknown")
            bbox = detection.get("bbox", [])
            score = detection.get("score", 0.0)
            
            # 读取原图
            with Image.open(image_path) as img:
                # 裁剪图片
                cropped_img = crop_image_with_padding(img, bbox, min_dim=10)
                
                # 转换为 Base64
                original_b64 = encode_image_to_base64(img)
                cropped_b64 = encode_image_to_base64(cropped_img)
            
            # 构造 Prompt
            label_def = label_definitions.get(label, f"标准定义的 {label}。")
            
            prompt = f"""
【背景资料】
1. 待验证类别: [{label}]
2. 类别标准定义: {label_def}
3. 原始图片和裁剪图已提供。

【任务】
请判断裁剪图中的物体是否符合[{label}]的定义。
只有在你非常有把握这不是目标物体时，才 discard。

【输出JSON格式】
{{ 
  "reasoning": "你的推理过程",
  "decision": "keep" 或 "discard" 
}}
"""
            
            # 调用 Qwen-VL
            response = client.chat.completions.create(
                model=effective_model_name,
                messages=[
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": prompt},
                            {
                                "type": "image_url",
                                "image_url": {"url": f"data:image/jpeg;base64,{original_b64}"}
                            },
                            {
                                "type": "image_url",
                                "image_url": {"url": f"data:image/jpeg;base64,{cropped_b64}"}
                            }
                        ]
                    }
                ],
                temperature=0.1,
                max_tokens=1024
            )
            
            response_text = response.choices[0].message.content
            is_keep, reasoning = parse_vlm_json_response(response_text)
            
            result = {
                "image_path": image_path,
                "image_name": image_path.split('/')[-1],
                "original_label": label,
                "bbox": bbox,
                "score": score,
                "vlm_decision": "keep" if is_keep else "discard",
                "vlm_reasoning": reasoning,
                "label_definition": label_def
            }
            
            cleaned_results.append(result)
            
        except Exception as e:
            logger.error(f"Error calling VLM for detection: {e}")
            result = {
                "image_path": detection.get("image_path", "unknown"),
                "image_name": detection.get("image_name", "unknown"),
                "original_label": detection.get("label", "unknown"),
                "bbox": detection.get("bbox", []),
                "score": detection.get("score", 0.0),
                "vlm_decision": "discard",
                "vlm_reasoning": f"Error: {str(e)}",
                "label_definition": label_definitions.get(detection.get("label", ""), "")
            }
            cleaned_results.append(result)
    
    return cleaned_results


# ==================== DINO 检测接口 ====================

@router.post("/algo/detect/dino", response_model=DinoDetectResponse)
async def dino_detection(request: DinoDetectRequest, background_tasks: BackgroundTasks):
    """
    Grounding DINO 检测接口（真实实现）
    
    Args:
        request: DINO 检测请求，包含图片路径、类别列表等
        
    Returns:
        标准化的检测结果列表
    """
    logger.info(f"Received DINO detection request: project_id={request.project_id}, images={len(request.image_paths)}, labels={request.labels}")
    
    # 生成任务 ID
    task_id = request.task_id or str(uuid.uuid4())
    
    # 创建任务记录
    task = await task_manager.create_task(
        task_id=task_id,
        task_type="DINO_DETECTION",
        project_id=request.project_id,
        total_images=len(request.image_paths),
        parameters={
            "labels": request.labels
        }
    )
    
    # 启动后台任务执行
    background_tasks.add_task(
        run_dino_detection_task,
        task_id,
        request.project_id,
        request.image_paths,
        request.labels,
        request.box_threshold,
        request.text_threshold
    )
    
    logger.info(f"Task {task_id}: DINO detection task started")
    
    return DinoDetectResponse(
        success=True,
        message="DINO detection task started successfully",
        task_id=task_id,
        status="RUNNING",
        results=[]
    )


async def run_dino_detection_task(
    task_id: str,
    project_id: int,
    image_paths: List[str],
    labels: List[str],
    box_threshold: float = 0.3,
    text_threshold: float = 0.25
):
    """
    DINO 检测后台任务（真实实现）
    
    调用本地 DINO 服务进行检测。
    """
    try:
        # 设置任务为运行中
        await task_manager.set_task_running(task_id)
        
        logger.info(f"Task {task_id}: Starting DINO detection (real mode)")
        logger.info(f"Thresholds - box: {box_threshold}, text: {text_threshold}")
        
        # 调用 DINO 服务
        results = await call_dino_service(image_paths, labels, box_threshold, text_threshold)
        
        # 保存结果
        for result in results:
            await task_manager.add_task_result(task_id, result)
        
        # 设置任务为已完成
        await task_manager.set_task_completed(task_id)
        logger.info(f"Task {task_id}: DINO detection completed, results={len(results)}")
        
    except Exception as e:
        logger.error(f"Task {task_id}: DINO detection failed - {e}", exc_info=True)
        await task_manager.set_task_failed(task_id, str(e))


# ==================== VLM 清洗接口 ====================

@router.post("/algo/clean/vlm", response_model=VlmCleanResponse)
async def vlm_cleaning(request: VlmCleanRequest, background_tasks: BackgroundTasks):
    """
    VLM 清洗接口（真实实现）
    
    Args:
        request: VLM 清洗请求，包含 DINO 检测结果、类别定义等
        
    Returns:
        标准化的清洗结果列表
    """
    logger.info(f"Received VLM cleaning request: project_id={request.project_id}, detections={len(request.detections)}")
    
    # 生成任务 ID
    task_id = request.task_id or str(uuid.uuid4())
    
    # 创建任务记录
    task = await task_manager.create_task(
        task_id=task_id,
        task_type="VLM_CLEANING",
        project_id=request.project_id,
        total_images=len(request.detections),
        parameters={
            "detections_count": len(request.detections)
        }
    )
    
    # 启动后台任务执行
    background_tasks.add_task(
        run_vlm_cleaning_task,
        task_id,
        request.project_id,
        request.detections,
        request.label_definitions,
        request.vlm_api_key,
        request.vlm_base_url,
        request.vlm_model_name,
        request.api_key,
        request.endpoint
    )
    
    logger.info(f"Task {task_id}: VLM cleaning task started")
    
    return VlmCleanResponse(
        success=True,
        message="VLM cleaning task started successfully",
        task_id=task_id,
        status="RUNNING",
        results=[]
    )


async def run_vlm_cleaning_task(
    task_id: str,
    project_id: int,
    detections: List[Dict[str, Any]],
    label_definitions: Dict[str, str],
    vlm_api_key: Optional[str],
    vlm_base_url: Optional[str],
    vlm_model_name: Optional[str],
    legacy_api_key: Optional[str],
    legacy_endpoint: Optional[str]
):
    """
    VLM 清洗后台任务（真实实现）
    
    调用阿里云 Qwen-VL 进行清洗。
    """
    try:
        # 设置任务为运行中
        await task_manager.set_task_running(task_id)
        
        logger.info(f"Task {task_id}: Starting VLM cleaning (real mode)")
        
        # 调用 VLM 清洗
        results = await call_vlm_cleaning(
            detections=detections,
            label_definitions=label_definitions,
            vlm_api_key=vlm_api_key,
            vlm_base_url=vlm_base_url,
            vlm_model_name=vlm_model_name,
            legacy_api_key=legacy_api_key,
            legacy_endpoint=legacy_endpoint
        )
        
        # 保存结果
        for result in results:
            await task_manager.add_task_result(task_id, result)
        
        # 设置任务为已完成
        await task_manager.set_task_completed(task_id)
        logger.info(f"Task {task_id}: VLM cleaning completed, results={len(results)}")
        
    except Exception as e:
        logger.error(f"Task {task_id}: VLM cleaning failed - {e}", exc_info=True)
        await task_manager.set_task_failed(task_id, str(e))


# ==================== 状态查询接口 ====================

@router.get("/algo/status/{task_id}")
async def get_task_status(task_id: str):
    """查询任务状态"""
    task = await task_manager.get_task(task_id)
    
    if task is None:
        raise HTTPException(status_code=404, detail=f"Task {task_id} not found")
    
    progress = int((task.processed_images / task.total_images * 100)) if task.total_images > 0 else 0
    
    return {
        "task_id": task_id,
        "task_type": task.task_type,
        "status": task.status.value,
        "progress": progress,
        "total": task.total_images,
        "processed": task.processed_images,
        "created_at": task.created_at.isoformat() if task.created_at else None,
        "completed_at": task.completed_at.isoformat() if task.completed_at else None,
        "error": task.error_message
    }


@router.get("/algo/results/{task_id}")
async def get_task_results(task_id: str):
    """获取任务结果"""
    task = await task_manager.get_task(task_id)
    
    if task is None:
        raise HTTPException(status_code=404, detail=f"Task {task_id} not found")
    
    if task.status.value not in ["completed", "failed"]:
        raise HTTPException(
            status_code=400,
            detail=f"Task {task_id} is not completed yet. Current status: {task.status.value}"
        )
    
    results = await task_manager.get_task_results(task_id)
    
    return {
        "success": task.status.value == "completed",
        "task_id": task_id,
        "status": task.status.value,
        "total": task.total_images,
        "processed": task.processed_images,
        "results": results or []
    }


@router.post("/algo/cancel/{task_id}")
async def cancel_task(task_id: str):
    """取消任务"""
    task = await task_manager.get_task(task_id)
    
    if task is None:
        raise HTTPException(status_code=404, detail=f"Task {task_id} not found")
    
    if task.status.value in ["completed", "failed", "cancelled"]:
        raise HTTPException(
            status_code=400,
            detail=f"Cannot cancel task {task_id}. Current status: {task.status.value}"
        )
    
    await task_manager.set_task_cancelled(task_id)
    logger.info(f"Task {task_id}: Cancelled by user")
    
    return {
        "success": True,
        "message": f"Task {task_id} cancelled successfully"
    }
