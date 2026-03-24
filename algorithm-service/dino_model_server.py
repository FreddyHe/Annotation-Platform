import os
import argparse
from functools import lru_cache

import numpy as np
import torch
from PIL import Image, ImageDraw, ImageFont
from flask import Flask, request, jsonify

# 导入 GroundingDINO 的相关模块
import groundingdino.datasets.transforms as T
from groundingdino.models import build_model
from groundingdino.util.slconfig import SLConfig
from groundingdino.util.utils import clean_state_dict, get_phrases_from_posmap

# --- 核心设置 ---
CONFIG_PATH = "/root/autodl-fs/GroundingDINO/groundingdino/config/GroundingDINO_SwinT_OGC.py"
CHECKPOINT_PATH = "/root/autodl-fs/GroundingDINO/weights/groundingdino_swint_ogc.pth"
DEVICE = "cpu"  # 强制使用 CPU

@lru_cache(maxsize=None)
def load_model(model_config_path, model_checkpoint_path):
    print("--- Loading GroundingDINO model... This will happen only once. ---")
    args = SLConfig.fromfile(model_config_path)
    args.device = DEVICE
    model = build_model(args)
    checkpoint = torch.load(model_checkpoint_path, map_location="cpu", weights_only=True) 
    model.load_state_dict(clean_state_dict(checkpoint["model"]), strict=False)
    model.eval()
    print("--- Model loaded successfully! ---")
    return model.to(DEVICE)

# --- 图像预处理 ---
transform = T.Compose(
    [
        T.RandomResize([800], max_size=1333),
        T.ToTensor(),
        T.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ]
)

# --- 推理函数 ---
def get_grounding_output(model, image, caption, box_threshold=0.4, text_threshold=0.25):
    """
    【已修改】
    1. box_threshold 默认值改为 0.4
    2. 返回 max_logits_filt (最高分) 而不是 logits_filt (完整向量)
    """
    caption = caption.lower().strip()
    if not caption.endswith("."):
        caption = caption + "."
    
    image_tensor, _ = transform(image, None)

    with torch.no_grad():
        outputs = model(image_tensor[None].to(DEVICE), captions=[caption])

    logits = outputs["pred_logits"].cpu().sigmoid()[0]
    boxes = outputs["pred_boxes"].cpu()[0]

    # 获取每个框的最高分
    all_max_logits = logits.max(dim=1)[0]
    
    # 使用 box_threshold (默认0.4) 进行过滤
    filt_mask = all_max_logits > box_threshold
    
    boxes_filt = boxes[filt_mask]
    logits_filt = logits[filt_mask] # 完整向量，仅用于 get_phrases
    max_logits_filt = all_max_logits[filt_mask] # 【新】最高分，用于返回

    # 获取预测的短语
    tokenizer = model.tokenizer
    tokenized = tokenizer(caption)
    pred_phrases = []
    for logit, box in zip(logits_filt, boxes_filt):
        pred_phrase = get_phrases_from_posmap(logit > text_threshold, tokenized, tokenizer)
        pred_phrases.append(pred_phrase)

    # 【已修改】返回最高分列表，而不是完整向量列表
    return boxes_filt, max_logits_filt, pred_phrases

# --- Flask App 初始化 ---
app = Flask(__name__)
grounding_dino_model = load_model(CONFIG_PATH, CHECKPOINT_PATH)

# --- API 端点定义 ---
@app.route('/predict', methods=['POST'])
def predict():
    if 'image' not in request.files:
        return jsonify({'error': 'No image file provided'}), 400
    if 'text_prompt' not in request.form:
        return jsonify({'error': 'No text_prompt provided'}), 400

    try:
        image_file = request.files['image']
        text_prompt = request.form['text_prompt']
        box_threshold = float(request.form.get('box_threshold', 0.4))
        text_threshold = float(request.form.get('text_threshold', 0.25))
        image_pil = Image.open(image_file.stream).convert("RGB")

        # 【已修改】这里接收的是 max_scores (最高分列表)
        boxes, max_scores, labels = get_grounding_output(
            model=grounding_dino_model,
            image=image_pil,
            caption=text_prompt,
            box_threshold=box_threshold,
            text_threshold=text_threshold
        )
        
        detections = []
        # 【已修改】在循环中加入 score (最高分)
        for box, score, label in zip(boxes.tolist(), max_scores.tolist(), labels):
            detections.append({
                "box": box,
                "logit_score": score, # 【已修改】键名改为 logit_score
                "label": label
            })

        return jsonify({"detections": detections})

    except Exception as e:
        return jsonify({'error': str(e)}), 500

# --- 启动服务 ---
if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5003, debug=False)
