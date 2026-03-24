import json
import re
from typing import Any, Dict, Optional

from loguru import logger


DEFAULT_LLM_API_KEY = "sk-AomDFLTBpbXd6JXk2hSv2WvzWccvww3TGkPRnA5L51ENOmNt"
DEFAULT_LLM_BASE_URL = "https://api.chatanywhere.tech/v1"
DEFAULT_LLM_MODEL_NAME = "gpt-4.1"


def _extract_json_object(text: str) -> Dict[str, Any]:
    cleaned = text.strip()
    cleaned = re.sub(r"^```(json)?\s*", "", cleaned, flags=re.IGNORECASE | re.MULTILINE)
    cleaned = re.sub(r"\s*```$", "", cleaned, flags=re.MULTILINE)
    cleaned = cleaned.strip()

    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start == -1 or end == -1 or end <= start:
        raise ValueError("LLM response does not contain a JSON object")

    json_str = cleaned[start : end + 1]
    return json.loads(json_str)


class LlmService:
    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model_name: Optional[str] = None,
    ):
        from openai import OpenAI

        self.api_key = api_key or DEFAULT_LLM_API_KEY
        self.base_url = base_url or DEFAULT_LLM_BASE_URL
        self.model_name = model_name or DEFAULT_LLM_MODEL_NAME
        self.client = OpenAI(api_key=self.api_key, base_url=self.base_url)

    def parse_requirement(self, raw_requirement: str) -> Dict[str, Any]:
        system_prompt = """
你是“AI视觉可行性评估”模块里的需求解析器。你的任务是把用户的自然语言需求解析为“可检测类别列表 + 场景描述”，用于后续的视觉模型评估。

要求：
1) 只输出一个 JSON 对象，不要输出任何额外文本、解释或 Markdown。
2) categories 必须是数组，每个元素包含：
   - categoryName: 中文类别名（短、可作为标签）
   - categoryNameEn: 英文类别名（短、可作为标签）
   - categoryType: "OBJECT" | "DEFECT" | "EVENT" | "STATE" | "OTHER"
   - sceneDescription: 该类别对应的典型场景描述（用于后续图像分析提示）
   - viewAngle: 建议视角/相机位置（例如“正视/俯视/侧视/近景/远景/车载/头顶”等）
3) summary 是一句话总结整体需求。
4) 如果 raw_requirement 中出现多个并列子需求（例如用 “+、/、以及、并且、同时” 连接），你应尽量拆分成多个类别。
5) 若信息不足，合理补全但不要编造具体型号/尺寸/数量。
""".strip()

        few_shot_1_user = "场景是做矿道内大型异物检测（石块、木头等），要求能在粉尘、弱光、遮挡情况下识别。"
        few_shot_1_assistant = json.dumps(
            {
                "categories": [
                    {
                        "categoryName": "大型石块",
                        "categoryNameEn": "large rock",
                        "categoryType": "OBJECT",
                        "sceneDescription": "矿道内地面/轨道附近出现的大型石块异物，可能部分被粉尘覆盖，光照不足且背景复杂。",
                        "viewAngle": "车载前视/低角度侧视，近景到中景",
                    },
                    {
                        "categoryName": "大型木块",
                        "categoryNameEn": "large wood",
                        "categoryType": "OBJECT",
                        "sceneDescription": "矿道内地面或设备附近出现的木块类异物，形状不规则，可能被遮挡。",
                        "viewAngle": "车载前视/侧视，中景",
                    },
                ],
                "summary": "在矿道弱光粉尘环境中检测可能造成阻塞/风险的大型异物（石块、木块等）。",
            },
            ensure_ascii=False,
        )

        few_shot_2_user = "焊装产线工位，检测销钉是否松动、缺失。"
        few_shot_2_assistant = json.dumps(
            {
                "categories": [
                    {
                        "categoryName": "销钉松动",
                        "categoryNameEn": "loose pin",
                        "categoryType": "DEFECT",
                        "sceneDescription": "焊装产线工位的连接销钉出现松动或未完全压入的状态，可能需要近距离观察细节。",
                        "viewAngle": "近景正视/斜上方俯视，固定工位相机",
                    },
                    {
                        "categoryName": "销钉缺失",
                        "categoryNameEn": "missing pin",
                        "categoryType": "DEFECT",
                        "sceneDescription": "焊装产线工位的连接位置缺少销钉，孔位/定位结构暴露。",
                        "viewAngle": "近景正视/俯视，固定工位相机",
                    },
                ],
                "summary": "在焊装产线工位检测销钉连接缺陷（松动、缺失）。",
            },
            ensure_ascii=False,
        )

        few_shot_3_user = "钢管内壁检测，关注裂纹、锈蚀、异物。"
        few_shot_3_assistant = json.dumps(
            {
                "categories": [
                    {
                        "categoryName": "内壁裂纹",
                        "categoryNameEn": "inner wall crack",
                        "categoryType": "DEFECT",
                        "sceneDescription": "钢管内壁表面出现细长裂纹，纹理与管壁反光干扰明显。",
                        "viewAngle": "管内窥镜前视，近景",
                    },
                    {
                        "categoryName": "锈蚀",
                        "categoryNameEn": "corrosion",
                        "categoryType": "DEFECT",
                        "sceneDescription": "钢管内壁锈蚀斑块，颜色偏红褐/暗色，边界不规则。",
                        "viewAngle": "管内窥镜前视，近景",
                    },
                    {
                        "categoryName": "内壁异物",
                        "categoryNameEn": "foreign object",
                        "categoryType": "OBJECT",
                        "sceneDescription": "钢管内部出现残留异物或附着物，可能遮挡内壁表面。",
                        "viewAngle": "管内窥镜前视，近景",
                    },
                ],
                "summary": "对钢管内壁进行缺陷与异物检测，重点关注裂纹、锈蚀与残留物。",
            },
            ensure_ascii=False,
        )

        few_shot_4_user = "田间巡检，识别罂粟植株。"
        few_shot_4_assistant = json.dumps(
            {
                "categories": [
                    {
                        "categoryName": "罂粟植株",
                        "categoryNameEn": "poppy plant",
                        "categoryType": "OBJECT",
                        "sceneDescription": "田间自然光场景下的罂粟植株，可能与相似作物混杂，背景杂乱。",
                        "viewAngle": "手持/无人机俯视，中景到远景",
                    }
                ],
                "summary": "在田间巡检场景中识别罂粟植株并与相似植物区分。",
            },
            ensure_ascii=False,
        )

        user_prompt = f"""
raw_requirement:
{raw_requirement}

请输出符合要求的 JSON。
""".strip()

        try:
            response = self.client.chat.completions.create(
                model=self.model_name,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": few_shot_1_user},
                    {"role": "assistant", "content": few_shot_1_assistant},
                    {"role": "user", "content": few_shot_2_user},
                    {"role": "assistant", "content": few_shot_2_assistant},
                    {"role": "user", "content": few_shot_3_user},
                    {"role": "assistant", "content": few_shot_3_assistant},
                    {"role": "user", "content": few_shot_4_user},
                    {"role": "assistant", "content": few_shot_4_assistant},
                    {"role": "user", "content": user_prompt},
                ],
                temperature=0.2,
                max_tokens=1600,
            )
            content = response.choices[0].message.content or ""
            parsed = _extract_json_object(content)
            if not isinstance(parsed, dict):
                raise ValueError("LLM response JSON is not an object")
            if "categories" not in parsed or "summary" not in parsed:
                raise ValueError("LLM response JSON missing required keys: categories/summary")
            return parsed
        except Exception as e:
            logger.error(f"LLM parse_requirement failed: {e}")
            raise

    def chat(self, messages: list, temperature: float = 0.7, max_tokens: int = 4000) -> str:
        """
        General-purpose chat method for LLM interactions
        
        Args:
            messages: List of message dicts with 'role' and 'content'
            temperature: Sampling temperature (0-1)
            max_tokens: Maximum tokens to generate
            
        Returns:
            str: The LLM response content
        """
        try:
            response = self.client.chat.completions.create(
                model=self.model_name,
                messages=messages,
                temperature=temperature,
                max_tokens=max_tokens,
            )
            content = response.choices[0].message.content or ""
            return content
        except Exception as e:
            logger.error(f"LLM chat failed: {e}")
            raise

