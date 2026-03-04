#!/usr/bin/env python
"""
train_yolo.py
独立的 YOLO 训练脚本
在 xingmu_yolo 环境中运行

用法:
    conda run -n xingmu_yolo python train_yolo.py --data data.yaml --name my_run --epochs 100
"""
import argparse
import os
import sys
import gc
import json
import signal
import time
from pathlib import Path
from datetime import datetime

# 解析命令行参数
parser = argparse.ArgumentParser(description='YOLO Training Script')
parser.add_argument('--data', type=str, required=True, help='Path to data.yaml')
parser.add_argument('--name', type=str, default='train', help='Run name')
parser.add_argument('--epochs', type=int, default=100, help='Number of epochs')
parser.add_argument('--batch', type=int, default=16, help='Batch size')
parser.add_argument('--imgsz', type=int, default=640, help='Image size')
parser.add_argument('--model', type=str, default='yolov8n.pt', help='Pretrained model')
parser.add_argument('--device', type=str, default='0', help='CUDA device')
parser.add_argument('--project', type=str, default='runs/train', help='Project directory')
parser.add_argument('--workers', type=int, default=4, help='Number of workers')
parser.add_argument('--patience', type=int, default=50, help='Early stopping patience')

args = parser.parse_args()


def update_status(project_dir: str, status: str, extra_info: dict = None):
    """更新训练状态到 JSON 文件"""
    info_file = Path(project_dir) / "training_info.json"
    
    if info_file.exists():
        with open(info_file, 'r') as f:
            info = json.load(f)
    else:
        info = {}
    
    info['status'] = status
    info['updated_at'] = datetime.now().isoformat()
    
    if extra_info:
        info.update(extra_info)
    
    with open(info_file, 'w') as f:
        json.dump(info, f, indent=2)


def cleanup_resources():
    """清理 GPU 资源"""
    try:
        import torch
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            torch.cuda.ipc_collect()
        print("✅ 资源已清理")
    except Exception as e:
        print(f"⚠️ 资源清理失败: {e}")


def main():
    print("=" * 80)
    print("🚀 YOLO 训练脚本启动")
    print("=" * 80)
    print(f"📋 配置:")
    print(f"   - 数据文件: {args.data}")
    print(f"   - 运行名称: {args.name}")
    print(f"   - 训练轮数: {args.epochs}")
    print(f"   - 批次大小: {args.batch}")
    print(f"   - 图片尺寸: {args.imgsz}")
    print(f"   - 预训练模型: {args.model}")
    print(f"   - GPU 设备: {args.device}")
    print(f"   - 输出目录: {args.project}")
    print("=" * 80)
    
    # 检查数据文件
    if not Path(args.data).exists():
        print(f"❌ 数据文件不存在: {args.data}")
        update_status(args.project, 'failed', {'error': f'Data file not found: {args.data}'})
        sys.exit(1)
    
    # 设置环境变量
    os.environ['CUDA_VISIBLE_DEVICES'] = args.device
    
    try:
        import torch
        from ultralytics import YOLO
        
        print(f"📦 PyTorch 版本: {torch.__version__}")
        print(f"🎮 CUDA 可用: {torch.cuda.is_available()}")
        if torch.cuda.is_available():
            print(f"   GPU 数量: {torch.cuda.device_count()}")
            print(f"   当前 GPU: {torch.cuda.get_device_name(0)}")
        
        # 更新状态
        update_status(args.project, 'training', {
            'started_training_at': datetime.now().isoformat()
        })
        
        # 加载模型
        print(f"\n📥 加载预训练模型: {args.model}")
        model = YOLO(args.model)
        
        # 开始训练
        print(f"\n🏃 开始训练...")
        results = model.train(
            data=args.data,
            epochs=args.epochs,
            imgsz=args.imgsz,
            batch=args.batch,
            project=args.project,
            name=args.name,
            exist_ok=True,
            verbose=True,
            workers=args.workers,
            patience=args.patience,
            plots=True,
            save=True,
            device=0,  # 使用第一个可见的 GPU
            amp=True,  # 混合精度训练
        )
        
        print("\n" + "=" * 80)
        print("✅ 训练完成！")
        print("=" * 80)
        
        # 检查最佳模型
        best_pt = Path(args.project) / args.name / 'weights' / 'best.pt'
        last_pt = Path(args.project) / args.name / 'weights' / 'last.pt'
        
        if best_pt.exists():
            print(f"📦 最佳模型: {best_pt}")
            
            # 进行测试评估
            print("\n🔍 在测试集上评估...")
            try:
                best_model = YOLO(str(best_pt))
                metrics = best_model.val(split='test', verbose=True, plots=False)
                
                print(f"\n📊 测试结果:")
                print(f"   - mAP50: {metrics.box.map50:.4f}")
                print(f"   - mAP50-95: {metrics.box.map:.4f}")
                
                # 保存测试结果
                test_results = {
                    'mAP50': float(metrics.box.map50),
                    'mAP50_95': float(metrics.box.map),
                    'precision': float(metrics.box.mp) if hasattr(metrics.box, 'mp') else None,
                    'recall': float(metrics.box.mr) if hasattr(metrics.box, 'mr') else None,
                }
                
                results_file = Path(args.project) / args.name / 'test_results.json'
                with open(results_file, 'w') as f:
                    json.dump(test_results, f, indent=2)
                
                print(f"💾 测试结果已保存: {results_file}")
                
                # 更新状态
                update_status(args.project, 'completed', {
                    'completed_at': datetime.now().isoformat(),
                    'best_model': str(best_pt),
                    'test_results': test_results
                })
                
                del best_model
                
            except Exception as e:
                print(f"⚠️ 测试评估失败: {e}")
                update_status(args.project, 'completed', {
                    'completed_at': datetime.now().isoformat(),
                    'best_model': str(best_pt),
                    'test_error': str(e)
                })
        else:
            print("⚠️ 未找到 best.pt")
            update_status(args.project, 'completed', {
                'completed_at': datetime.now().isoformat(),
                'warning': 'best.pt not found'
            })
        
        # 清理
        del model
        cleanup_resources()
        
        print("\n🎉 训练流程完成！")
        
    except KeyboardInterrupt:
        print("\n⚠️ 训练被中断 (Ctrl+C)")
        update_status(args.project, 'interrupted', {
            'interrupted_at': datetime.now().isoformat()
        })
        cleanup_resources()
        sys.exit(0)
        
    except Exception as e:
        print(f"\n❌ 训练失败: {e}")
        import traceback
        traceback.print_exc()
        update_status(args.project, 'failed', {
            'failed_at': datetime.now().isoformat(),
            'error': str(e)
        })
        cleanup_resources()
        sys.exit(1)


if __name__ == "__main__":
    main()