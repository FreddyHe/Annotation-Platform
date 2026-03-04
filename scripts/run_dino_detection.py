#!/usr/bin/env python3
"""
Grounding DINO 检测脚本
在 groundingdino310 环境中独立运行
"""
import os
import sys
import json
import argparse
from pathlib import Path
from PIL import Image
import torch
from tqdm import tqdm

# 添加 GroundingDINO 路径
sys.path.insert(0, "/root/autodl-fs/GroundingDINO")

from groundingdino.util.inference import load_model, load_image, predict

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True, help='GroundingDINO config path')
    parser.add_argument('--weights', required=True, help='GroundingDINO weights path')
    parser.add_argument('--image-dir', required=True, help='Input image directory')
    parser.add_argument('--labels', required=True, help='Target labels (comma-separated)')
    parser.add_argument('--output', required=True, help='Output JSON file path')
    parser.add_argument('--box-threshold', type=float, default=0.3)
    parser.add_argument('--text-threshold', type=float, default=0.25)
    args = parser.parse_args()
    
    print("="*80)
    print("🚀 Grounding DINO 检测脚本")
    print("="*80)
    print(f"📂 图片目录: {args.image_dir}")
    print(f"🏷️  目标类别: {args.labels}")
    print(f"📊 Box阈值: {args.box_threshold}")
    print(f"💾 输出文件: {args.output}")
    print("="*80)
    
    # 加载模型
    print("\n🔧 加载 Grounding DINO 模型...")
    model = load_model(args.config, args.weights)
    print("✅ 模型加载完成")
    
    # 解析类别
    target_labels = [label.strip() for label in args.labels.split(',')]
    text_prompt = " . ".join(target_labels) + " ."
    print(f"📝 Prompt: {text_prompt}")
    
    # 扫描图片
    image_dir = Path(args.image_dir)
    image_files = []
    
    # 先尝试当前目录
    for ext in ['.jpg', '.jpeg', '.png', '.bmp', '.webp']:
        image_files.extend(image_dir.glob(f'*{ext}'))
        image_files.extend(image_dir.glob(f'*{ext.upper()}'))
    
    # 如果当前目录没有图片，递归查找子目录
    if not image_files:
        print(f"⚠️  当前目录无图片，尝试递归查找...")
        for ext in ['.jpg', '.jpeg', '.png', '.bmp', '.webp']:
            image_files.extend(image_dir.rglob(f'*{ext}'))
            image_files.extend(image_dir.rglob(f'*{ext.upper()}'))
    
    image_files = sorted(list(set(image_files)))
    print(f"\n📷 找到 {len(image_files)} 张图片")
    
    if not image_files:
        print("❌ 未找到任何图片文件")
        sys.exit(1)
    
    # 执行检测
    all_results = []
    
    for img_path in tqdm(image_files, desc="检测进度"):
        try:
            # 加载图片
            image_source, image = load_image(str(img_path))
            h, w, _ = image_source.shape
            
            # 执行检测
            boxes, logits, phrases = predict(
                model=model,
                image=image,
                caption=text_prompt,
                box_threshold=args.box_threshold,
                text_threshold=args.text_threshold,
                device="cuda" if torch.cuda.is_available() else "cpu"
            )
            
            # 处理结果
            for i in range(len(boxes)):
                box = boxes[i]
                score = logits[i].max().item()
                phrase = phrases[i]
                
                # 匹配类别
                matched_label = None
                for label in target_labels:
                    if label.lower() in phrase.lower():
                        matched_label = label
                        break
                
                if matched_label is None and len(target_labels) == 1:
                    matched_label = target_labels[0]
                
                if matched_label:
                    # 转换坐标 (cx, cy, w, h) -> (x, y, w, h)
                    cx, cy, bw, bh = box * torch.Tensor([w, h, w, h])
                    x_min = float(cx - bw / 2)
                    y_min = float(cy - bh / 2)
                    w_abs = float(bw)
                    h_abs = float(bh)
                    
                    all_results.append({
                        "image_path": str(img_path),
                        "image_name": img_path.name,
                        "label": matched_label,
                        "bbox": [x_min, y_min, w_abs, h_abs],
                        "score": score,
                        "raw_phrase": phrase
                    })
        
        except Exception as e:
            print(f"\n❌ 处理失败 {img_path.name}: {e}")
            continue
    
    # 保存结果
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2)
    
    print(f"\n✅ 检测完成!")
    print(f"📊 总检测数: {len(all_results)}")
    print(f"💾 结果已保存: {output_path}")

if __name__ == "__main__":
    main()