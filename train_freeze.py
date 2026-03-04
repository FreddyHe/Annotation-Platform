from ultralytics import YOLO

def train_with_freeze():
    # 1. 使用稍微大一点的 yolov8s 模型，泛化能力更强
    model = YOLO('yolov8s.pt')  

    print("🚀 开始迁移学习训练 (冻结骨干网络)...")
    
    model.train(
        # 你的数据配置文件路径
        data='/root/autodl-fs/web_biaozhupingtai/training_runs/animal1_20260202_151356/dataset/data.yaml',
        
        epochs=100,       # 100轮足够了
        imgsz=640,
        batch=16,
        
        # 🔥 关键修改 1: 冻结骨干网络 (Backbone)
        # YOLOv8 通常有 22 层，冻结前 10 层可以保留基础特征
        freeze=10, 
        
        # 🔥 关键修改 2: 降低学习率，温柔地微调
        lr0=0.001,
        
        # 其他优化
        name='animal_freeze_training',
        project='/root/autodl-fs/training_results',
        exist_ok=True
    )

if __name__ == '__main__':
    train_with_freeze()