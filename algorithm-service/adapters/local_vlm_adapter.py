import base64
import io
import json
import os
import re
import uuid
from pathlib import Path
from typing import Any, Dict, List

from PIL import Image, ImageDraw
import requests


DEFAULT_BASE_URL = "http://127.0.0.1:5008"
DEFAULT_MODEL_NAME = "Qwen3-VL-4B-Instruct"


def status() -> Dict[str, Any]:
    base_url = os.getenv("LOCAL_VLM_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    model_name = os.getenv("LOCAL_VLM_MODEL_NAME", DEFAULT_MODEL_NAME)
    endpoint = f"{base_url}/v1/chat/completions"
    try:
        response = requests.get(f"{base_url}/v1/models", timeout=3)
        if response.status_code == 200:
            payload = response.json()
            model_ids = [item.get("id") for item in payload.get("data", []) if item.get("id")]
            if model_name in model_ids or (model_ids and model_name == DEFAULT_MODEL_NAME):
                return {
                    "model_id": "local-qwen3-vl-4b",
                    "name": "Local VLM Semantic Verifier",
                    "version": model_name,
                    "available": True,
                    "status": "AVAILABLE",
                    "local": True,
                    "requires_gpu": True,
                    "external_api_used": False,
                    "endpoint": endpoint,
                    "model_name": model_name,
                    "service_base_url": base_url,
                    "served_models": model_ids,
                    "reason": "本地 Qwen3-VL OpenAI-compatible vLLM 服务可用。",
                }
            return {
                "model_id": "local-qwen3-vl-4b",
                "name": "Local VLM Semantic Verifier",
                "available": False,
                "status": "UNAVAILABLE",
                "local": True,
                "requires_gpu": True,
                "external_api_used": False,
                "endpoint": endpoint,
                "model_name": model_name,
                "served_models": model_ids,
                "reason": f"本地 VLM 服务可访问，但未提供期望模型 {model_name}。",
                "next_action": "检查 LOCAL_VLM_MODEL_NAME 或 vLLM served-model-name。",
            }
        return {
            "model_id": "local-qwen3-vl-4b",
            "name": "Local VLM Semantic Verifier",
            "available": False,
            "status": "UNAVAILABLE",
            "local": True,
            "requires_gpu": True,
            "external_api_used": False,
            "endpoint": endpoint,
            "model_name": model_name,
            "reason": f"本地 VLM 服务 HTTP {response.status_code}。",
            "next_action": "检查本地 vLLM 服务日志和端口。",
        }
    except Exception as exc:
        reason = f"本地 VLM / Qwen3-VL-4B OpenAI-compatible 服务尚未配置或不可达：{exc}"
    return {
        "model_id": "local-qwen3-vl-4b",
        "name": "Local VLM Semantic Verifier",
        "available": False,
        "status": "UNAVAILABLE",
        "local": True,
        "requires_gpu": True,
        "external_api_used": False,
        "endpoint": endpoint,
        "model_name": model_name,
        "reason": reason,
        "next_action": "确认本地 VLM 服务地址、模型名和 GPU 显存预算后启用。",
    }


def semantic_check_image(
    image_path: str,
    labels: list[str],
    job_id: Any = None,
    route_id: Any = None,
) -> Dict[str, Any]:
    runtime = status()
    if not runtime.get("available"):
        return {
            "success": True,
            "available": False,
            "status": "UNAVAILABLE",
            "source_model": "local-qwen3-vl-4b",
            "reason": runtime.get("reason"),
            "candidates": [],
        }
    if not image_path or not Path(image_path).exists():
        return {
            "success": True,
            "available": False,
            "status": "IMAGE_NOT_FOUND",
            "source_model": "local-qwen3-vl-4b",
            "reason": f"image path not found: {image_path}",
            "candidates": [],
        }
    label_text = ", ".join(labels or [])
    if not label_text:
        return {
            "success": True,
            "available": False,
            "status": "NO_LABELS",
            "source_model": "local-qwen3-vl-4b",
            "reason": "Qwen semantic verifier requires labels.",
            "candidates": [],
        }

    prompt = (
        "你是施工安全自动标注的质量审核员。请只根据图像内容判断这些目标类别是否真实出现："
        f"{label_text}。\n"
        "判定规则：\n"
        "1. 反光衣：只包括真实人员穿着的高可视/反光背心或反光服；不要把红黄围挡、警示牌、文字、车辆、广告牌当作反光衣。\n"
        "2. 安全帽：只包括真实人员头上佩戴的安全帽；不要把牌子、灯、车体、墙面标识当作安全帽。\n"
        "3. 安全登高绳：只包括真实作业人员使用/连接的安全绳、安全带、登高防坠绳；不要把吊车臂、吊索、建筑线缆、杆子、边缘线、围挡当作安全登高绳。\n"
        "4. 如果图中只有警示牌、围挡、吊车、建筑、道路或文字，没有真实穿戴/使用这些 PPE 的人员，请把相关类别放入 absent_labels。\n"
        "请只输出严格 JSON，不要输出解释性前后缀，格式："
        '{"present_labels":["..."],"absent_labels":["..."],"reason":"..."}'
    )
    payload = {
        "model": runtime.get("model_name") or DEFAULT_MODEL_NAME,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": f"file://{image_path}"}},
                    {"type": "text", "text": prompt},
                ],
            }
        ],
        "temperature": 0,
        "max_tokens": 256,
    }
    try:
        response = requests.post(
            runtime["endpoint"],
            json=payload,
            timeout=int(os.getenv("LOCAL_VLM_TIMEOUT_SECONDS", "120")),
        )
        if response.status_code == 200:
            data = response.json()
            content = _message_content(data)
            decision = _parse_semantic_answer(content, labels)
            return {
                "success": True,
                "available": True,
                "status": "SEMANTIC_CHECK_COMPLETED",
                "source_model": "local-qwen3-vl-4b",
                "reason": None,
                "candidates": [],
                "metadata": {
                    "role": "semantic_verifier",
                    "job_id": job_id,
                    "route_id": route_id,
                    "labels": labels,
                    "answer": content,
                    "semantic_decision": decision,
                    "model_name": runtime.get("model_name"),
                    "endpoint": runtime.get("endpoint"),
                },
            }
        return {
            "success": False,
            "available": False,
            "status": "SERVICE_ERROR",
            "source_model": "local-qwen3-vl-4b",
            "reason": f"Qwen3-VL service HTTP {response.status_code}: {response.text[:500]}",
            "candidates": [],
        }
    except Exception as exc:
        return {
            "success": False,
            "available": False,
            "status": "SERVICE_UNREACHABLE",
            "source_model": "local-qwen3-vl-4b",
            "reason": f"Qwen3-VL service unreachable: {exc}",
            "candidates": [],
        }


def verify_candidate_box(
    image_path: str,
    label: str,
    bbox_xyxy: List[float],
    candidate: Dict[str, Any] | None = None,
    job_id: Any = None,
    route_id: Any = None,
    runtime: Dict[str, Any] | None = None,
) -> Dict[str, Any]:
    """Use the local VLM to verify whether one marked box matches one label."""
    runtime = runtime or status()
    if not runtime.get("available"):
        return {
            "success": True,
            "available": False,
            "status": "UNAVAILABLE",
            "source_model": "local-qwen3-vl-4b",
            "keep": True,
            "reason": runtime.get("reason"),
        }
    if not image_path or not Path(image_path).exists():
        return {
            "success": True,
            "available": False,
            "status": "IMAGE_NOT_FOUND",
            "source_model": "local-qwen3-vl-4b",
            "keep": False,
            "reason": f"image path not found: {image_path}",
        }
    if not label:
        return {
            "success": True,
            "available": False,
            "status": "NO_LABEL",
            "source_model": "local-qwen3-vl-4b",
            "keep": False,
            "reason": "box semantic verifier requires one target label.",
        }

    try:
        prepared = _prepare_box_images(image_path, bbox_xyxy)
    except Exception as exc:
        return {
            "success": True,
            "available": False,
            "status": "INVALID_BOX",
            "source_model": "local-qwen3-vl-4b",
            "keep": False,
            "reason": f"cannot prepare marked/cropped box images: {exc}",
        }

    prompt = _box_verification_prompt(label)
    payload = {
        "model": runtime.get("model_name") or DEFAULT_MODEL_NAME,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{prepared['marked_b64']}"},
                    },
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{prepared['cropped_b64']}"},
                    },
                ],
            }
        ],
        "temperature": 0,
        "max_tokens": 512,
    }
    try:
        response = requests.post(
            runtime["endpoint"],
            json=payload,
            timeout=int(os.getenv("LOCAL_VLM_TIMEOUT_SECONDS", "120")),
        )
        if response.status_code != 200:
            return {
                "success": False,
                "available": False,
                "status": "SERVICE_ERROR",
                "source_model": "local-qwen3-vl-4b",
                "keep": True,
                "reason": f"Qwen3-VL service HTTP {response.status_code}: {response.text[:500]}",
                "metadata": _box_metadata(prepared, label, candidate, job_id, route_id),
            }
        data = response.json()
        content = _message_content(data)
        decision = _parse_box_answer(content)
        status_name = "BOX_SEMANTIC_VERIFIED" if decision.get("parse_ok") else "BOX_SEMANTIC_UNPARSED"
        keep = bool(decision.get("keep")) if decision.get("parse_ok") else False
        return {
            "success": True,
            "available": True,
            "status": status_name,
            "source_model": "local-qwen3-vl-4b",
            "keep": keep,
            "reason": decision.get("reasoning") or ("VLM answer was not parseable" if not decision.get("parse_ok") else None),
            "metadata": {
                **_box_metadata(prepared, label, candidate, job_id, route_id),
                "answer": content,
                "semantic_decision": decision,
                "model_name": runtime.get("model_name"),
                "endpoint": runtime.get("endpoint"),
            },
        }
    except Exception as exc:
        return {
            "success": False,
            "available": False,
            "status": "SERVICE_UNREACHABLE",
            "source_model": "local-qwen3-vl-4b",
            "keep": True,
            "reason": f"Qwen3-VL service unreachable: {exc}",
            "metadata": _box_metadata(prepared, label, candidate, job_id, route_id),
        }


def _message_content(data: Dict[str, Any]) -> str:
    choices = data.get("choices") or []
    if not choices:
        return ""
    message = choices[0].get("message") or {}
    content = message.get("content")
    return content if isinstance(content, str) else str(content or "")


def _box_verification_prompt(label: str) -> str:
    return (
        "你是施工安全自动标注的候选框质量审核员。你会看到两张图：\n"
        "1. 第一张是原始场景图，红色矩形框标出了待审核候选框。\n"
        "2. 第二张是同一个候选框对应的裁剪图。\n\n"
        f"待验证标签：{label}\n\n"
        "请只判断红框/裁剪图里的主体是否就是该标签。不要因为原图其他位置存在该标签而保留这个框。\n"
        "只有当红框里的主体明确符合该标签时才 keep；如果红框框到的是文字、警示牌、围挡、车辆、建筑、杆子、吊臂、吊索、背景线条、阴影，或者无法确认，请 discard。\n\n"
        "施工 PPE 的严格规则：\n"
        "- 反光衣：必须是真实人员身上穿着的高可视/反光背心或反光服；红黄围挡、警示牌、文字、车辆广告不算。\n"
        "- 安全帽：必须是真实人员头上佩戴的安全帽；牌子、灯、车体、墙面标识不算。\n"
        "- 安全登高绳：必须是真实作业人员佩戴、连接或使用的防坠安全绳/安全带/登高绳；吊车臂、吊索、建筑线缆、杆子、围挡边缘不算。\n\n"
        "请只输出严格 JSON，不要输出解释性前后缀："
        '{"decision":"keep 或 discard","reasoning":"一句话说明判断依据"}'
    )


def _prepare_box_images(image_path: str, bbox_xyxy: List[float]) -> Dict[str, Any]:
    image = Image.open(image_path).convert("RGB")
    width, height = image.size
    x1, y1, x2, y2 = _clamp_box(bbox_xyxy, width, height)
    if x2 <= x1 or y2 <= y1:
        raise ValueError(f"invalid bbox after clamp: {[x1, y1, x2, y2]}")

    marked = image.copy()
    draw = ImageDraw.Draw(marked)
    line_width = max(4, int(min(width, height) * 0.006))
    for offset in range(line_width):
        draw.rectangle([x1 - offset, y1 - offset, x2 + offset, y2 + offset], outline=(255, 0, 0))

    crop_pad = max(8, int(max(x2 - x1, y2 - y1) * 0.12))
    crop_box = [
        max(0, x1 - crop_pad),
        max(0, y1 - crop_pad),
        min(width, x2 + crop_pad),
        min(height, y2 + crop_pad),
    ]
    cropped = image.crop(tuple(crop_box))
    return {
        "bbox_xyxy": [round(value, 2) for value in [x1, y1, x2, y2]],
        "crop_box_xyxy": [round(value, 2) for value in crop_box],
        "marked_b64": _encode_image_to_base64(marked),
        "cropped_b64": _encode_image_to_base64(cropped),
        "debug_id": uuid.uuid4().hex,
    }


def _clamp_box(bbox_xyxy: List[float], width: int, height: int) -> List[float]:
    if len(bbox_xyxy or []) < 4:
        raise ValueError("bbox requires four coordinates")
    x1, y1, x2, y2 = [float(value) for value in bbox_xyxy[:4]]
    if x2 < x1:
        x1, x2 = x2, x1
    if y2 < y1:
        y1, y2 = y2, y1
    return [
        max(0.0, min(float(width), x1)),
        max(0.0, min(float(height), y1)),
        max(0.0, min(float(width), x2)),
        max(0.0, min(float(height), y2)),
    ]


def _encode_image_to_base64(image: Image.Image) -> str:
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=92)
    return base64.b64encode(buffer.getvalue()).decode("utf-8")


def _box_metadata(
    prepared: Dict[str, Any],
    label: str,
    candidate: Dict[str, Any] | None,
    job_id: Any,
    route_id: Any,
) -> Dict[str, Any]:
    return {
        "role": "box_semantic_verifier",
        "job_id": job_id,
        "route_id": route_id,
        "label": label,
        "bbox_xyxy": prepared.get("bbox_xyxy"),
        "crop_box_xyxy": prepared.get("crop_box_xyxy"),
        "source_candidate_model": (candidate or {}).get("source_model"),
        "source_candidate_score": (candidate or {}).get("score"),
        "external_api_used": False,
    }


def _parse_box_answer(content: str) -> Dict[str, Any]:
    parsed = _load_json_object(content)
    decision_value = parsed.get("decision")
    if decision_value is None and "keep" in parsed:
        decision_value = parsed.get("keep")
    if decision_value is None and "is_match" in parsed:
        decision_value = parsed.get("is_match")

    decision_text = str(decision_value).strip().lower()
    if isinstance(decision_value, bool):
        keep = decision_value
        parse_ok = True
    elif decision_text in {"keep", "true", "yes", "y", "是", "对", "匹配", "保留"}:
        keep = True
        parse_ok = True
    elif decision_text in {"discard", "false", "no", "n", "否", "不", "不匹配", "丢弃", "删除"}:
        keep = False
        parse_ok = True
    else:
        text = str(content or "")
        keep_match = re.search(r"\bkeep\b|保留|是|匹配", text, flags=re.I)
        discard_match = re.search(r"\bdiscard\b|丢弃|删除|否|不匹配|不是", text, flags=re.I)
        if discard_match and not keep_match:
            keep = False
            parse_ok = True
        elif keep_match and not discard_match:
            keep = True
            parse_ok = True
        else:
            keep = False
            parse_ok = False

    return {
        "keep": keep,
        "decision": "keep" if keep else "discard",
        "reasoning": str(parsed.get("reasoning") or parsed.get("reason") or "")[:500],
        "parse_ok": parse_ok,
    }


def _parse_semantic_answer(content: str, labels: list[str]) -> Dict[str, Any]:
    parsed = _load_json_object(content)
    present = _normalize_label_list(parsed.get("present_labels"), labels)
    absent = _normalize_label_list(parsed.get("absent_labels"), labels)
    if not present and not absent:
        text = str(content or "")
        for label in labels:
            if re.search(rf"{re.escape(label)}\\s*[:：]?\\s*(是|有|存在|present|true|yes)", text, re.I):
                present.append(label)
            elif re.search(rf"{re.escape(label)}\\s*[:：]?\\s*(否|无|没有|不存在|absent|false|no)", text, re.I):
                absent.append(label)
    absent = [label for label in absent if label not in present]
    return {
        "present_labels": present,
        "absent_labels": absent,
        "reason": str(parsed.get("reason") or "")[:500],
        "parse_ok": bool(present or absent),
    }


def _load_json_object(content: str) -> Dict[str, Any]:
    text = str(content or "").strip()
    if not text:
        return {}
    text = re.sub(r"^```(?:json)?\\s*", "", text, flags=re.I)
    text = re.sub(r"\\s*```$", "", text)
    match = re.search(r"\\{.*\\}", text, flags=re.S)
    if match:
        text = match.group(0)
    try:
        value = json.loads(text)
        return value if isinstance(value, dict) else {}
    except Exception:
        return {}


def _normalize_label_list(value: Any, allowed_labels: list[str]) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        raw_items = [item.strip() for item in re.split(r"[,，、;；\\n]+", value) if item.strip()]
    elif isinstance(value, list):
        raw_items = [str(item).strip() for item in value if str(item).strip()]
    else:
        raw_items = []

    normalized = []
    for item in raw_items:
        for label in allowed_labels:
            if item == label or _compact(label) in _compact(item) or _compact(item) in _compact(label):
                if label not in normalized:
                    normalized.append(label)
                break
    return normalized


def _compact(value: str) -> str:
    return re.sub(r"\\s+", "", str(value or "").lower())
