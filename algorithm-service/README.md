# Annotation Algorithm Service

独立的算法微服务，提供 DINO、VLM、YOLO 等深度学习模型的推理能力。

## 功能特性

- **DINO 检测**: Grounding DINO 目标检测
- **VLM 清洗**: 基于视觉语言模型的标签清洗
- **YOLO 检测**: YOLOv8 目标检测

## 技术栈

- FastAPI
- PyTorch
- Ultralytics (YOLO)
- Transformers (VLM)
- Loguru (日志)

## 安装依赖

```bash
pip install -r requirements.txt
```

## 配置

创建 `.env` 文件：

```env
APP_NAME=Annotation Algorithm Service
HOST=0.0.0.0
PORT=8000
CORS_ORIGINS=["http://localhost:5173", "*"]
UPLOAD_BASE_PATH=/root/autodl-fs/uploads
LOG_LEVEL=INFO
```

## 启动服务

### 开发模式（支持热重载）

```bash
python main.py
```

### 生产模式

```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --workers 4
```

## API 文档

启动服务后访问：

- Swagger UI: http://localhost:8000/api/v1/docs
- ReDoc: http://localhost:8000/api/v1/redoc

## API 端点

### 健康检查

- `GET /api/v1/health` - 健康检查
- `GET /api/v1/health/detailed` - 详细健康检查

### DINO 检测

- `POST /api/v1/algo/dino/detect` - 运行 DINO 检测
- `GET /api/v1/algo/dino/status/{task_id}` - 查询任务状态
- `GET /api/v1/algo/dino/results/{task_id}` - 获取任务结果
- `POST /api/v1/algo/dino/cancel/{task_id}` - 取消任务

### VLM 清洗

- `POST /api/v1/algo/vlm/clean` - 运行 VLM 清洗
- `GET /api/v1/algo/vlm/status/{task_id}` - 查询任务状态
- `GET /api/v1/algo/vlm/results/{task_id}` - 获取任务结果
- `POST /api/v1/algo/vlm/cancel/{task_id}` - 取消任务

### YOLO 检测

- `POST /api/v1/algo/yolo/detect` - 运行 YOLO 检测
- `GET /api/v1/algo/yolo/status/{task_id}` - 查询任务状态
- `GET /api/v1/algo/yolo/results/{task_id}` - 获取任务结果
- `POST /api/v1/algo/yolo/cancel/{task_id}` - 取消任务

## 项目结构

```
algorithm-service/
├── main.py              # FastAPI 应用入口
├── config.py            # 配置管理
├── requirements.txt     # Python 依赖
├── routers/            # 路由模块
│   ├── __init__.py
│   ├── health.py       # 健康检查
│   ├── dino.py         # DINO 检测
│   ├── vlm.py          # VLM 清洗
│   └── yolo.py         # YOLO 检测
├── models/             # 模型文件目录
├── logs/               # 日志目录
└── README.md           # 本文件
```

## 开发说明

当前版本为接口骨架，实际的模型加载和推理逻辑将在后续阶段实现。
