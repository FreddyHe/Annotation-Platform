import os
import cv2
import torch
from ultralytics import YOLO
from pathlib import Path

def get_ground_truth_count(image_path):
    try:
        # 兼容性处理：尝试寻找 labels 目录
        p = Path(image_path)
        # 假设结构: .../images/xxx.jpg -> .../labels/xxx.txt
        # 这里做一个更健壮的路径替换，防止 split 名字不同
        parts = list(p.parts)
        if 'images' in parts:
            idx = parts.index('images')
            parts[idx] = 'labels'
            label_path = Path(*parts).with_suffix('.txt')
            
            if label_path.exists():
                with open(label_path, 'r') as f:
                    lines = [line.strip() for line in f.readlines() if line.strip()]
                    return len(lines)
        return 0
    except Exception as e:
        return -1

def run_debug_inference():
    # ================= 配置修改 =================
    # 1. 🔥 模型路径：指向刚刚训练成功的 freeze 版本
    model_path = '/root/autodl-fs/training_results/animal_freeze_training/weights/best.pt'
    
    # 2. 数据集路径：指向日志中显示的最新数据集位置
    base_data_dir = '/root/autodl-fs/web_biaozhupingtai/training_runs/animal1_20260202_151356/dataset'
    
    # 3. 输出目录：改个名字，方便区分
    output_root = 'freeze_inference_results'
    
    # 4. 🔥 阈值回归正常：模型现在很强了，不需要 0.01 了，用 0.25 看真实效果
    conf_threshold = 0.7 
    img_size = 640         
    # ===========================================

    if not os.path.exists(model_path):
        print(f"❌ 错误：找不到模型文件 {model_path}")
        return

    print("⏳ 加载新训练的模型 (YOLOv8s - Freeze Backbone)...")
    model = YOLO(model_path)
    print(f"🔍 开始推理 (Conf={conf_threshold})...")

    target_folders = ['valid', 'test']
    
    for folder_name in target_folders:
        input_dir = os.path.join(base_data_dir, folder_name, 'images')
        output_dir = os.path.join(output_root, folder_name)
        
        if not os.path.exists(input_dir): 
            print(f"⚠️ 跳过 {folder_name}，目录不存在: {input_dir}")
            continue
            
        os.makedirs(output_dir, exist_ok=True)
        
        images = [f for f in os.listdir(input_dir) if f.lower().endswith(('.jpg', '.png', '.jpeg'))]
        
        print(f"\n📂 {folder_name} 集 (共 {len(images)} 张):")
        print(f"{'图片名称':<30} | {'GT':<3} | {'Pred':<4} | {'Max Conf':<10} | {'状态'}")
        print("-" * 80)

        for img_name in images: 
            img_path = os.path.join(input_dir, img_name)
            gt_count = get_ground_truth_count(img_path)
            
            # 推理
            results = model.predict(img_path, conf=conf_threshold, imgsz=img_size, verbose=False)
            result = results[0]
            
            pred_count = len(result.boxes)
            
            # 获取最高置信度
            max_conf = 0.0
            if pred_count > 0:
                max_conf = result.boxes.conf.max().item()
            
            # 状态判定
            if gt_count == pred_count:
                status = "✅ 完美"
            elif pred_count > gt_count:
                status = "⚠️ 误检(多)"
            else:
                status = "❌ 漏检(少)"

            # 如果置信度非常高，加个标记
            if max_conf > 0.8:
                status += " 🔥自信"
            
            print(f"{img_name[:30]:<30} | {gt_count:<3} | {pred_count:<4} | {max_conf:.4f}     | {status}")
            
            # 保存图片
            im_array = result.plot(line_width=2, font_size=1.0)
            # 在图上写上信息
            info_text = f"GT:{gt_count} Pred:{pred_count} Conf:{max_conf:.2f}"
            cv2.putText(im_array, info_text, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
            cv2.imwrite(os.path.join(output_dir, img_name), im_array)

    print(f"\n🎉 验证完成！图片已保存至: {os.path.abspath(output_root)}")
    print("💡 预期结果：现在 Pred 应该是个位数(1-3个)，且 Max Conf 应该在 0.6 - 0.9 之间。")

if __name__ == "__main__":
    run_debug_inference()