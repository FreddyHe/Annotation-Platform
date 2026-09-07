from __future__ import annotations

import json
import os
import re
from typing import Any, Dict, List, Tuple

import requests

from schemas.auto_label_to_model import HYBRID_TEACHER_QUALITY, QUALITY_FIRST
from services import model_registry_service


def parse_requirement(payload: Dict[str, Any]) -> Dict[str, Any]:
    raw_text = _first_text(payload, "raw_user_text", "rawUserText", "requirement", "text")
    labels = _extract_labels(payload)
    if not labels:
        labels = _infer_labels(raw_text)

    llm_result = _parse_with_chatanywhere(raw_text, labels)
    if llm_result.get("label_schema"):
        label_schema = _normalize_schema(llm_result["label_schema"])
        parser = llm_result.get("parser", "chatanywhere")
        parser_status = "CHATANYWHERE_READY"
        external_api_used = True
        llm_policy = "chatanywhere_primary_fallback"
        parse_warning = llm_result.get("warning")
        model_used = llm_result.get("model")
    else:
        label_schema = [_label_schema(label) for label in labels]
        parser = "local_rule_fallback"
        parser_status = "RULE_FALLBACK_READY" if label_schema else "NEED_USER_LABEL_CONFIRMATION"
        external_api_used = False
        llm_policy = "local_rule_fallback"
        parse_warning = llm_result.get("warning")
        model_used = None

    prompt_pack = _prompt_pack(label_schema)
    response = {
        "success": True,
        "available": bool(label_schema),
        "external_api_used": external_api_used,
        "llm_policy": llm_policy,
        "parser": parser,
        "parser_status": parser_status,
        "parser_model": model_used,
        "task_type": "object_detection",
        "domain": "auto_label_to_model",
        "label_schema": label_schema,
        "prompt_pack": prompt_pack,
        "review_policy": {
            "priority_mode": QUALITY_FIRST,
            "auto_accept_threshold": 0.85,
            "manual_review_threshold": 0.45,
            "priority_review_labels": [item["display_name"] for item in label_schema],
        },
        "strategy": HYBRID_TEACHER_QUALITY,
        "priority_mode": QUALITY_FIRST,
        "next_action": None if label_schema else "请补充明确类别列表后再继续自动标注。",
    }
    if parse_warning:
        response["warning"] = parse_warning
    return response


def _parse_with_chatanywhere(raw_text: str, labels: List[str]) -> Dict[str, Any]:
    api_key = os.getenv("CHATANYWHERE_API_KEY", "").strip()
    if not api_key:
        return {"label_schema": [], "warning": "CHATANYWHERE_API_KEY is not set"}
    if not raw_text and not labels:
        return {"label_schema": [], "warning": "empty requirement"}

    base_url = os.getenv("CHATANYWHERE_BASE_URL", "https://api.chatanywhere.tech/v1").strip().rstrip("/")
    endpoint = base_url if base_url.endswith("/chat/completions") else f"{base_url}/chat/completions"
    primary_model = (
        os.getenv("CHATANYWHERE_PRIMARY_MODEL")
        or os.getenv("CHATANYWHERE_DEEPSEEK_MODEL")
        or "deepseek-chat"
    )
    fallback_model = (
        os.getenv("CHATANYWHERE_FALLBACK_MODEL")
        or os.getenv("CHATANYWHERE_QWEN_MODEL")
        or "qwen3-max"
    )
    models = _unique([primary_model, fallback_model])
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    messages = _messages(raw_text, labels)
    last_error = None
    for model in models:
        try:
            response = requests.post(
                endpoint,
                headers=headers,
                json={
                    "model": model,
                    "messages": messages,
                    "temperature": 0.1,
                    "response_format": {"type": "json_object"},
                },
                timeout=float(os.getenv("CHATANYWHERE_TIMEOUT_SECONDS", "45")),
            )
            if response.status_code >= 400:
                last_error = f"{model} HTTP {response.status_code}: {response.text[:200]}"
                continue
            body = response.json()
            content = (((body.get("choices") or [{}])[0].get("message") or {}).get("content") or "").strip()
            parsed = _loads_json(content)
            schema = _schema_from_llm_json(parsed)
            if schema:
                return {"label_schema": schema, "parser": "chatanywhere", "model": model}
            last_error = f"{model} returned no label_schema"
        except Exception as exc:
            last_error = f"{model} failed: {exc}"
    return {"label_schema": [], "warning": last_error or "ChatAnywhere parse failed"}


def _messages(raw_text: str, labels: List[str]) -> List[Dict[str, str]]:
    label_hint = "、".join(labels) if labels else "未提供，需从需求中抽取"
    return [
        {
            "role": "system",
            "content": (
                "你是自动标注平台的需求解析器。只输出严格 JSON，不要 Markdown。"
                "你的任务是把中文或中英混合检测需求转换成目标检测标签方案。"
                "前端显示字段使用中文 display_name，模型推理字段必须使用英文。"
            ),
        },
        {
            "role": "user",
            "content": (
                "请解析以下需求，输出 JSON：\n"
                "{\n"
                '  "label_schema": [\n'
                "    {\n"
                '      "display_name": "中文短标签",\n'
                '      "canonical_name": "english_snake_case",\n'
                '      "english_name": "plain English object phrase",\n'
                '      "model_prompt": "English prompt phrase for visual grounding models",\n'
                '      "positive_prompts": ["English alternatives for Grounding DINO / LocateAnything"],\n'
                '      "negative_prompts": [],\n'
                '      "requires_relation_reasoning": false\n'
                "    }\n"
                "  ]\n"
                "}\n\n"
                "规则：\n"
                "1. 如果用户给了类别列表，保留相同类别数量和顺序。\n"
                "2. Grounding DINO 和 LocateAnything 的 model_prompt / positive_prompts 必须是英文。\n"
                "3. 示例：人=>person，车=>car，自行车=>bicycle，电动车=>electric bicycle，摩托车=>motorcycle。\n"
                "4. 示例：没戴安全帽=>person without a safety helmet；反光衣=>reflective safety vest。\n\n"
                f"用户需求：{raw_text or ''}\n"
                f"用户已确认/候选类别：{label_hint}\n"
            ),
        },
    ]


def _loads_json(content: str) -> Dict[str, Any]:
    text = (content or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.IGNORECASE)
        text = re.sub(r"\s*```$", "", text)
    try:
        parsed = json.loads(text)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)
        if not match:
            return {}
        try:
            parsed = json.loads(match.group(0))
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}


def _schema_from_llm_json(parsed: Dict[str, Any]) -> List[Dict[str, Any]]:
    value = parsed.get("label_schema") or parsed.get("labels") or parsed.get("classes") or []
    if not isinstance(value, list):
        return []
    return _normalize_schema(value)


def _normalize_schema(items: List[Any]) -> List[Dict[str, Any]]:
    schema: List[Dict[str, Any]] = []
    seen = set()
    for raw in items:
        if isinstance(raw, str):
            item = _label_schema(raw)
        elif isinstance(raw, dict):
            display = _first_text(raw, "display_name", "displayName", "display_name_zh", "name", "label", "canonical_name")
            if not display:
                continue
            english = _first_text(raw, "model_prompt", "modelPrompt", "english_prompt", "englishPrompt", "english_name", "englishName")
            prompts = _string_list(raw.get("positive_prompts") or raw.get("positivePrompts") or raw.get("positive_prompts_en") or raw.get("positivePromptsEn"))
            if not english and prompts:
                english = prompts[0]
            if not english:
                english = _english_for_label(display)
            canonical = _first_text(raw, "canonical_name", "canonicalName") or _canonical(english)
            item = {
                "canonical_name": _canonical(canonical or english),
                "display_name": display,
                "english_name": _clean_english(english),
                "model_prompt": _clean_english(english),
                "description": _first_text(raw, "description") or f"用户确认的自动标注类别：{display}",
                "positive_prompts": _english_prompts(display, english, prompts),
                "positive_prompts_en": _english_prompts(display, english, prompts),
                "negative_prompts": _string_list(raw.get("negative_prompts") or raw.get("negativePrompts")),
                "requires_relation_reasoning": bool(raw.get("requires_relation_reasoning") or raw.get("requiresRelationReasoning")),
            }
        else:
            continue
        key = (item["display_name"], item["model_prompt"])
        if key in seen:
            continue
        seen.add(key)
        schema.append(item)
    return schema


def _label_schema(label: str) -> Dict[str, Any]:
    english = _english_for_label(label)
    return {
        "canonical_name": _canonical(english),
        "display_name": label,
        "english_name": english,
        "model_prompt": english,
        "description": f"用户确认的自动标注类别：{label}",
        "positive_prompts": _english_prompts(label, english, []),
        "positive_prompts_en": _english_prompts(label, english, []),
        "negative_prompts": [],
        "requires_relation_reasoning": False,
    }


def _prompt_pack(label_schema: List[Dict[str, Any]]) -> Dict[str, Any]:
    display_labels = [item["display_name"] for item in label_schema]
    model_prompts = [item.get("model_prompt") or item.get("english_name") or item["display_name"] for item in label_schema]
    return {
        "grounding_dino": " . ".join(model_prompts) + (" ." if model_prompts else ""),
        "grounding_dino_labels": model_prompts,
        "locate_anything": [
            {
                "label": item["canonical_name"],
                "display_label": item["display_name"],
                "text": f"Locate all {item.get('model_prompt') or item.get('english_name') or item['display_name']} objects.",
            }
            for item in label_schema
        ],
        "locate_anything_labels": model_prompts,
        "xingmu": {"candidate_labels": display_labels, "only_displayed_32_capabilities": True},
    }


def _extract_labels(payload: Dict[str, Any]) -> List[str]:
    value = payload.get("labels") or payload.get("raw_user_labels_json") or payload.get("rawUserLabelsJson") or []
    labels = []
    if isinstance(value, str):
        labels = [part.strip() for part in value.replace("，", ",").replace("；", ",").replace(";", ",").split(",")]
    elif isinstance(value, list):
        for item in value:
            if isinstance(item, dict):
                label = item.get("display_name") or item.get("displayName") or item.get("name") or item.get("canonical_name")
            else:
                label = item
            if label:
                labels.append(str(label).strip())
    return _unique(labels)


def _infer_labels(raw_text: str) -> List[str]:
    if not raw_text:
        return []
    normalized = _normalize(raw_text)
    builtin = {
        "重物下的人": ["重物下的人", "吊物下的人", "悬挂物下的人", "suspendedload", "personundersuspendedload"],
        "人": ["人", "行人", "人员", "person", "people", "pedestrian"],
        "车": ["车", "车辆", "汽车", "机动车", "car", "vehicle"],
        "自行车": ["自行车", "单车", "脚踏车", "bicycle", "bike"],
        "电动车": ["电动车", "电瓶车", "电动自行车", "electricbike", "ebike", "electricscooter"],
        "摩托车": ["摩托车", "摩托", "机车", "motorcycle", "motorbike", "scooter"],
        "戴安全帽": ["戴安全帽", "佩戴安全帽", "wearinghelmet"],
        "没戴安全帽": ["没戴安全帽", "未戴安全帽", "未佩戴安全帽", "withouthelmet", "nohelmet"],
        "反光衣": ["反光衣", "反光背心", "安全背心", "reflectivevest", "safetyvest", "hivis"],
        "没穿反光衣": ["没穿反光衣", "未穿反光衣", "未穿反光背心", "withoutvest", "novest"],
    }
    labels = []
    for label, aliases in builtin.items():
        if label == "人" and "重物下的人" in labels:
            continue
        if any(_normalize(alias) and _normalize(alias) in normalized for alias in aliases):
            labels.append(label)
    if labels:
        return _unique(labels)

    for model in model_registry_service.list_models("XINGMU_SCENARIO"):
        values = [model.get("name", "")] + (model.get("aliases") or []) + (model.get("classes") or [])
        if any(_normalize(value) and _normalize(value) in normalized for value in values):
            labels.append(model.get("name"))
    return _unique(labels)


def _english_for_label(label: str) -> str:
    normalized = _normalize(label)
    mapping: List[Tuple[Tuple[str, ...], str]] = [
        (("重物下的人", "吊物下的人", "悬挂物下的人", "suspendedload", "personundersuspendedload"), "person under a suspended load"),
        (("没戴安全帽", "未戴安全帽", "未佩戴安全帽", "withouthelmet", "nohelmet"), "person without a safety helmet"),
        (("戴安全帽", "佩戴安全帽", "wearinghelmet"), "person wearing a safety helmet"),
        (("没穿反光衣", "未穿反光衣", "未穿反光背心", "withoutvest", "novest"), "person without a reflective safety vest"),
        (("反光衣", "反光背心", "安全背心", "reflectivevest", "safetyvest", "hivisvest"), "reflective safety vest"),
        (("安全帽", "头盔", "helmet", "hardhat"), "safety helmet"),
        (("电动车", "电瓶车", "电动自行车", "electricbike", "ebike", "electricscooter"), "electric bicycle"),
        (("自行车", "单车", "脚踏车", "bicycle", "bike"), "bicycle"),
        (("摩托车", "摩托", "机车", "motorcycle", "motorbike", "scooter"), "motorcycle"),
        (("车", "车辆", "汽车", "机动车", "car", "vehicle"), "car"),
        (("人", "行人", "人员", "person", "people", "pedestrian", "human"), "person"),
    ]
    for needles, english in mapping:
        if any(_normalize(needle) == normalized or _normalize(needle) in normalized for needle in needles):
            return english
    return _clean_english(label)


def _english_prompts(display: str, english: str, prompts: List[str]) -> List[str]:
    values = [english, *prompts]
    aliases = {
        "person": ["pedestrian", "people", "human"],
        "car": ["vehicle", "automobile", "van", "truck"],
        "bicycle": ["bike"],
        "electric bicycle": ["e-bike", "electric scooter"],
        "motorcycle": ["motorbike", "scooter"],
        "safety helmet": ["hard hat"],
        "reflective safety vest": ["safety vest", "hi-vis vest", "reflective vest"],
        "person without a safety helmet": ["worker without safety helmet", "person no helmet"],
        "person wearing a safety helmet": ["worker wearing safety helmet"],
        "person without a reflective safety vest": ["worker without safety vest", "person no reflective vest"],
        "person under a suspended load": ["person under a heavy suspended object", "worker under a suspended load"],
    }
    values.extend(aliases.get(_clean_english(english), []))
    return _unique([_clean_english(value) for value in values if _clean_english(value)])


def _first_text(payload: Dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = payload.get(key)
        if value:
            return str(value).strip()
    return ""


def _string_list(value: Any) -> List[str]:
    if isinstance(value, str):
        return [part.strip() for part in re.split(r"[,，;；、\n]+", value) if part.strip()]
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    return []


def _unique(values: List[str]) -> List[str]:
    seen = set()
    result = []
    for value in values:
        if value and value not in seen:
            seen.add(value)
            result.append(value)
    return result


def _normalize(value: str) -> str:
    return (value or "").lower().replace(" ", "").replace("_", "").replace("-", "").replace("/", "")


def _canonical(value: str) -> str:
    canonical = re.sub(r"[^a-z0-9]+", "_", _clean_english(value)).strip("_")
    return canonical or "unknown"


def _clean_english(value: str) -> str:
    return re.sub(r"\s+", " ", str(value or "").strip()).lower()
