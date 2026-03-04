#!/usr/bin/env python
"""
detect_yolo.py
独立的 YOLO 检测脚本
在 xingmu_yolo 环境中运行

用法:
    conda run -n xingmu_yolo python detect_yolo.py \
        --model /path/to/best.pt \
        --image /path/to/image.jpg \
        --output /path/to/result.json \
        --conf 0.25
"""
import argparse
import json
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description='YOLO Detection Script')
    parser.add_argument('--model', type=str, required=True, help='Path to model weights (.pt)')
    parser.add_argument('--image', type=str, required=True, help='Path to input image')
    parser.add_argument('--output', type=str, required=True, help='Path to output JSON file')
    parser.add_argument('--conf', type=float, default=0.25, help='Confidence threshold')
    
    args = parser.parse_args()
    
    # 验证输入
    if not Path(args.model).exists():
        print(f"ERROR: Model not found: {args.model}", file=sys.stderr)
        sys.exit(1)
    
    if not Path(args.image).exists():
        print(f"ERROR: Image not found: {args.image}", file=sys.stderr)
        sys.exit(1)
    
    try:
        from ultralytics import YOLO
        
        print(f"Loading model: {args.model}")
        model = YOLO(args.model)
        
        print(f"Running detection on: {args.image}")
        print(f"Confidence threshold: {args.conf}")
        
        # 运行检测
        results = model(args.image, conf=args.conf, verbose=False)
        
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
                cls_name = model.names[cls]
                
                detections.append({
                    "bbox": [x1, y1, x2, y2],
                    "confidence": conf,
                    "class_id": cls,
                    "class_name": cls_name
                })
        
        # 保存结果
        output_data = {
            "success": True,
            "model": args.model,
            "image": args.image,
            "conf_threshold": args.conf,
            "num_detections": len(detections),
            "detections": detections,
            "class_names": model.names
        }
        
        # 确保输出目录存在
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        
        with open(args.output, 'w', encoding='utf-8') as f:
            json.dump(output_data, f, ensure_ascii=False, indent=2)
        
        print(f"SUCCESS: Detected {len(detections)} objects")
        print(f"Results saved to: {args.output}")
        
    except Exception as e:
        # 保存错误信息
        error_data = {
            "success": False,
            "error": str(e)
        }
        
        with open(args.output, 'w', encoding='utf-8') as f:
            json.dump(error_data, f, ensure_ascii=False, indent=2)
        
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()