#!/usr/bin/env python3
"""
VLM 清洗脚本
在 LLM_DL 环境中独立运行
"""
import os
import sys
import json
import argparse
import tempfile
import re
from pathlib import Path
from PIL import Image
from tqdm import tqdm
from concurrent.futures import ThreadPoolExecutor, as_completed

# 添加必要的路径
sys.path.insert(0, "/root/autodl-fs/Paper_annotation_change")

from utils.qwen_client_v4 import QwenVLClient

def crop_image_to_temp(image_path, bbox, min_dim=10):
    """裁剪图片到临时文件"""
    try:
        x, y, w, h = bbox
        
        # 检查尺寸
        if w < min_dim or h < min_dim:
            return None
        
        # 裁剪
        img = Image.open(image_path)
        crop_box = (int(x), int(y), int(x + w), int(y + h))
        cropped = img.crop(crop_box)
        
        # 保存到临时文件
        temp_fd, temp_path = tempfile.mkstemp(suffix='.jpg', prefix='crop_')
        os.close(temp_fd)
        cropped.save(temp_path, 'JPEG', quality=95)
        
        return temp_path
    except Exception as e:
        print(f"裁剪失败: {e}")
        return None

def build_clean_prompt(label_name, definition):
    """构建清洗提示词"""
    return f"""
【任务】判断裁剪图中的物体是否符合类别定义

【待验证类别】{label_name}

【类别定义】{definition}

【图片说明】
- 第一张图：原始场景图
- 第二张图：待验证物体的裁剪图

【判断标准】
只有在你非常确定这不是 [{label_name}] 时才选择 discard。

【输出格式】
请严格按照以下JSON格式回复：
{{"decision": "keep 或 discard", "reasoning": "你的推理过程"}}
"""

def parse_json_response(text):
    """解析VLM响应中的JSON"""
    try:
        # 去除markdown代码块
        text = re.sub(r'^```(json)?\s*', '', text.strip(), flags=re.IGNORECASE | re.MULTILINE)
        text = re.sub(r'\s*```$', '', text, flags=re.MULTILINE)
        
        # 提取JSON
        start = text.find('{')
        end = text.rfind('}')
        
        if start != -1 and end != -1:
            json_str = text[start:end+1]
            return json.loads(json_str)
    except Exception as e:
        print(f"JSON解析失败: {e}")
    
    return None

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True, help='Input detections JSON file')
    parser.add_argument('--definitions', required=True, help='Class definitions JSON file')
    parser.add_argument('--output', required=True, help='Output cleaned JSON file')
    parser.add_argument('--vlm-url', default='http://122.51.47.91:25638/v1')
    parser.add_argument('--vlm-model', default='Qwen3-VL-4B-Instruct')
    parser.add_argument('--workers', type=int, default=8, help='Parallel workers')
    args = parser.parse_args()
    
    print("="*80)
    print("🧹 VLM 清洗脚本")
    print("="*80)
    print(f"📥 输入文件: {args.input}")
    print(f"📖 类别定义: {args.definitions}")
    print(f"🌐 VLM服务: {args.vlm_url}")
    print(f"👷 并发数: {args.workers}")
    print(f"💾 输出文件: {args.output}")
    print("="*80)
    
    # 加载检测结果
    print("\n📂 加载检测结果...")
    with open(args.input, 'r', encoding='utf-8') as f:
        detections = json.load(f)
    print(f"✅ 加载了 {len(detections)} 条检测结果")
    
    # 加载类别定义
    print("📖 加载类别定义...")
    with open(args.definitions, 'r', encoding='utf-8') as f:
        definitions = json.load(f)
    print(f"✅ 加载了 {len(definitions)} 个类别定义")
    
    # 初始化VLM客户端
    print("\n🔧 初始化VLM客户端...")
    client = QwenVLClient(
        base_url=args.vlm_url,
        model_name=args.vlm_model,
        max_retries=2,
        timeout=120
    )
    print("✅ 客户端初始化完成")
    
    # 清洗函数
    def clean_detection(det):
        """清洗单个检测框"""
        image_path = det['image_path']
        bbox = det['bbox']
        label = det['label']
        definition = definitions.get(label, f"标准定义的 {label}")
        
        # 裁剪图片
        crop_path = crop_image_to_temp(image_path, bbox)
        if not crop_path:
            return {**det, 'vlm_decision': 'keep', 'vlm_reasoning': '裁剪失败，默认保留'}
        
        try:
            # 构建提示词
            prompt = build_clean_prompt(label, definition)
            
            # 调用VLM
            response, _ = client.chat(
                prompt=prompt,
                images=[image_path, crop_path],
                temperature=0.1
            )
            
            # 解析响应
            result = parse_json_response(response)
            
            if result:
                decision = result.get('decision', 'keep').lower()
                reasoning = result.get('reasoning', '无推理过程')
            else:
                # 简单的关键词匹配
                if 'discard' in response.lower():
                    decision = 'discard'
                else:
                    decision = 'keep'
                reasoning = response[:200]
            
            return {
                **det,
                'vlm_decision': decision,
                'vlm_reasoning': reasoning
            }
        
        except Exception as e:
            print(f"\n❌ VLM调用失败: {e}")
            return {**det, 'vlm_decision': 'keep', 'vlm_reasoning': f'调用失败: {str(e)}'}
        
        finally:
            # 清理临时文件
            if crop_path and os.path.exists(crop_path):
                try:
                    os.remove(crop_path)
                except:
                    pass
    
    # 并发清洗
    print(f"\n🧹 开始清洗 (并发数: {args.workers})...")
    cleaned_results = []
    
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(clean_detection, det): det for det in detections}
        
        for future in tqdm(as_completed(futures), total=len(futures), desc="清洗进度"):
            try:
                result = future.result()
                cleaned_results.append(result)
            except Exception as e:
                print(f"\n❌ 处理异常: {e}")
                det = futures[future]
                cleaned_results.append({
                    **det,
                    'vlm_decision': 'keep',
                    'vlm_reasoning': f'异常: {str(e)}'
                })
    
    # 统计结果
    kept = [r for r in cleaned_results if r['vlm_decision'] == 'keep']
    discarded = [r for r in cleaned_results if r['vlm_decision'] == 'discard']
    
    print(f"\n📊 清洗统计:")
    print(f"   总数: {len(cleaned_results)}")
    print(f"   保留: {len(kept)}")
    print(f"   丢弃: {len(discarded)}")
    
    # 保存结果（保存完整结果，包含VLM决策）
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(cleaned_results, f, ensure_ascii=False, indent=2)
    
    print(f"\n✅ 清洗完成!")
    print(f"💾 结果已保存: {output_path}")

if __name__ == "__main__":
    main()