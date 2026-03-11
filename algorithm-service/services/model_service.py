import os
import sys
import torch
from pathlib import Path
from typing import List, Dict, Any, Optional
from loguru import logger
from PIL import Image
import cv2
import numpy as np


class ModelService:
    """模型服务 - 统一管理所有模型的加载和推理"""
    
    def __init__(self):
        self.dino_model = None
        self.yolo_model = None
        self.vlm_client = None
        
        self.dino_config_path = None
        self.dino_weights_path = None
        self.yolo_model_path = None
        
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        logger.info(f"Model service initialized - Device: {self.device}")
    
    def load_dino_model(self, config_path: str, weights_path: str):
        """加载 Grounding DINO 模型"""
        try:
            logger.info(f"Loading Grounding DINO model from {weights_path}")
            
            # 添加 GroundingDINO 路径
            groundingdino_path = "/root/autodl-fs/GroundingDINO"
            if groundingdino_path not in sys.path:
                sys.path.insert(0, groundingdino_path)
            
            from groundingdino.util.inference import load_model
            
            self.dino_model = load_model(config_path, weights_path)
            self.dino_config_path = config_path
            self.dino_weights_path = weights_path
            
            logger.info("Grounding DINO model loaded successfully")
            return True
            
        except Exception as e:
            logger.error(f"Failed to load Grounding DINO model: {e}")
            raise
    
    def load_yolo_model(self, model_path: str):
        """加载 YOLO 模型"""
        try:
            logger.info(f"Loading YOLO model from {model_path}")
            
            from ultralytics import YOLO
            
            self.yolo_model = YOLO(model_path)
            self.yolo_model_path = model_path
            
            logger.info("YOLO model loaded successfully")
            return True
            
        except Exception as e:
            logger.error(f"Failed to load YOLO model: {e}")
            raise
    
    def init_vlm_client(self, vlm_url: str, vlm_model: str):
        """初始化 VLM 客户端"""
        try:
            logger.info(f"Initializing VLM client - URL: {vlm_url}, Model: {vlm_model}")
            
            # 添加 VLM 客户端路径
            vlm_client_path = "/root/autodl-fs/Paper_annotation_change"
            if vlm_client_path not in sys.path:
                sys.path.insert(0, vlm_client_path)
            
            from utils.qwen_client_v4 import QwenVLClient
            
            self.vlm_client = QwenVLClient(base_url=vlm_url, model_name=vlm_model)
            
            logger.info("VLM client initialized successfully")
            return True
            
        except Exception as e:
            logger.error(f"Failed to initialize VLM client: {e}")
            raise
    
    def run_dino_detection(
        self,
        image_path: str,
        labels: List[str],
        box_threshold: float = 0.3,
        text_threshold: float = 0.25
    ) -> List[Dict[str, Any]]:
        """运行 DINO 检测"""
        try:
            if self.dino_model is None:
                raise RuntimeError("DINO model not loaded")
            
            from groundingdino.util.inference import load_image, predict
            
            # 加载图片
            image_source, image = load_image(image_path)
            h, w, _ = image_source.shape
            
            # 构建文本提示
            text_prompt = " . ".join(labels) + " ."
            
            # 执行检测
            boxes, logits, phrases = predict(
                model=self.dino_model,
                image=image,
                caption=text_prompt,
                box_threshold=box_threshold,
                text_threshold=text_threshold,
                device=self.device
            )
            
            # 处理结果
            detections = []
            for i in range(len(boxes)):
                box = boxes[i]
                score = float(logits[i].max().item())
                phrase = phrases[i]
                
                # 转换 bbox 格式 [x1, y1, x2, y2] -> [x, y, w, h]
                x1, y1, x2, y2 = box.tolist()
                x, y, w, h = x1, y1, x2 - x1, y2 - y1
                
                # 归一化到 [0, 1]
                x_norm = x / w
                y_norm = y / h
                w_norm = w / w
                h_norm = h / h
                
                detections.append({
                    "label": phrase,
                    "bbox": [float(x_norm), float(y_norm), float(w_norm), float(h_norm)],
                    "bbox_absolute": [float(x1), float(y1), float(x2), float(y2)],
                    "confidence": float(score)
                })
            
            logger.info(f"DINO detection completed: {len(detections)} objects found in {image_path}")
            return detections
            
        except Exception as e:
            logger.error(f"DINO detection failed for {image_path}: {e}")
            raise
    
    def run_yolo_detection(
        self,
        image_path: str,
        confidence_threshold: float = 0.5,
        iou_threshold: float = 0.45
    ) -> List[Dict[str, Any]]:
        """运行 YOLO 检测"""
        try:
            if self.yolo_model is None:
                raise RuntimeError("YOLO model not loaded")
            
            # 运行检测
            results = self.yolo_model(
                image_path,
                conf=confidence_threshold,
                iou=iou_threshold,
                verbose=False,
                device=self.device
            )
            
            # 解析结果
            detections = []
            for r in results:
                boxes = r.boxes
                if boxes is None:
                    continue
                
                for i in range(len(boxes)):
                    box = boxes[i]
                    x1, y1, x2, y2 = box.xyxy[0].tolist()
                    conf = float(box.conf[0])
                    cls = int(box.cls[0])
                    cls_name = self.yolo_model.names[cls]
                    
                    # 转换 bbox 格式
                    x, y, w, h = x1, y1, x2 - x1, y2 - y1
                    
                    # 获取图片尺寸进行归一化
                    img = Image.open(image_path)
                    img_w, img_h = img.size
                    
                    x_norm = x / img_w
                    y_norm = y / img_h
                    w_norm = w / img_w
                    h_norm = h / img_h
                    
                    detections.append({
                        "class": cls_name,
                        "class_id": cls,
                        "bbox": [float(x_norm), float(y_norm), float(w_norm), float(h_norm)],
                        "bbox_absolute": [float(x1), float(y1), float(x2), float(y2)],
                        "confidence": float(conf)
                    })
            
            logger.info(f"YOLO detection completed: {len(detections)} objects found in {image_path}")
            return detections
            
        except Exception as e:
            logger.error(f"YOLO detection failed for {image_path}: {e}")
            raise
    
    def run_vlm_cleaning(
        self,
        image_path: str,
        bbox: List[float],
        label_name: str,
        label_definition: str,
        min_dim: int = 10
    ) -> Dict[str, Any]:
        """运行 VLM 清洗"""
        try:
            if self.vlm_client is None:
                raise RuntimeError("VLM client not initialized")
            
            # 裁剪图片
            x, y, w, h = bbox
            
            # 检查尺寸
            if w < min_dim or h < min_dim:
                return {
                    "decision": "discard",
                    "reason": f"Bounding box too small: {w}x{h} < {min_dim}x{min_dim}"
                }
            
            # 裁剪
            img = Image.open(image_path)
            crop_box = (int(x), int(y), int(x + w), int(y + h))
            cropped = img.crop(crop_box)
            
            # 构建提示词
            prompt = f"""
【任务】判断裁剪图中的物体是否符合类别定义

【待验证类别】{label_name}

【类别定义】{label_definition}

【图片说明】
- 第一张图：原始场景图
- 第二张图：待验证物体的裁剪图

【判断标准】
只有在你非常确定这不是 [{label_name}] 时才选择 discard。

【输出格式】
请严格按照以下JSON格式回复：
{{"decision": "keep 或 discard", "reasoning": "你的推理过程"}}
"""
            
            # 调用 VLM
            response = self.vlm_client.query(
                image=cropped,
                text=prompt
            )
            
            # 解析响应
            import re
            import json
            
            text = response.get("text", "")
            
            # 提取 JSON
            start = text.find('{')
            end = text.rfind('}')
            
            if start != -1 and end != -1:
                json_str = text[start:end+1]
                result = json.loads(json_str)
                
                return {
                    "decision": result.get("decision", "keep"),
                    "reason": result.get("reasoning", "No reasoning provided")
                }
            else:
                # 如果无法解析 JSON，默认保留
                return {
                    "decision": "keep",
                    "reason": "Failed to parse VLM response, defaulting to keep"
                }
            
        except Exception as e:
            logger.error(f"VLM cleaning failed for {image_path}: {e}")
            raise


# 全局模型服务实例
model_service = ModelService()
