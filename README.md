# 自动标注与清洗平台

## 📋 项目概述

一个可视化的自动标注与清洗平台，支持用户上传图片数据集，通过"基础检测模型 + 大模型清洗"的双阶段流水线自动生成标注，并提供人工校验和修正的 UI 界面。

## 🏗️ 技术栈选型

### ✅ 最终选择：Streamlit (Python优先方案)

**选择理由**:
- ✅ **开发速度快**: 纯Python实现，无需前端知识，适合快速迭代
- ✅ **内置组件丰富**: 文件上传、图片展示、交互组件开箱即用
- ✅ **易于维护**: 前后端一体化，减少部署复杂度
- ✅ **标注编辑器**: 使用 `streamlit-drawable-canvas` 实现交互式标注编辑
- ✅ **适合场景**: 内部工具、快速原型、中小规模数据集处理

**技术栈组成**:
- **前端框架**: Streamlit 1.28+
- **后端框架**: Streamlit (内置FastAPI兼容接口)
- **检测模型**: Grounding DINO (复用现有代码)
- **VLM清洗**: Qwen3-VL-4B-Instruct (复用现有qwen_client_v4.py)
- **图像处理**: PIL, OpenCV
- **数据格式**: 支持YOLO/COCO格式导入导出

### 备选方案：FastAPI + Vue 3 (未来扩展)

如果需要更高性能或更复杂的交互，可以考虑迁移到FastAPI + Vue 3架构。

## 📁 项目结构

```
web_biaozhupingtai/
├── README.md                 # 项目说明文档
├── requirements.txt          # Python依赖
├── config.py                # 配置文件
├── main.py                  # 主启动文件
│
├── backend/                  # 后端核心逻辑
│   ├── __init__.py
│   ├── api/                  # API接口
│   │   ├── __init__.py
│   │   ├── upload.py         # 文件上传接口
│   │   ├── inference.py      # 推理接口
│   │   └── annotation.py     # 标注管理接口
│   │
│   ├── models/               # 模型调用封装(策略模式)
│   │   ├── __init__.py
│   │   ├── base_model.py     # 基础模型接口
│   │   ├── grounding_dino.py # Grounding DINO实现
│   │   └── vlm_refiner.py    # VLM清洗实现
│   │
│   ├── pipeline/             # 推理流水线
│   │   ├── __init__.py
│   │   ├── inference_pipeline.py  # 主流水线
│   │   └── data_processor.py      # 数据处理
│   │
│   └── utils/                # 工具函数
│       ├── __init__.py
│       ├── file_utils.py     # 文件处理
│       └── format_converter.py  # 格式转换(YOLO/COCO)
│
├── frontend/                 # 前端界面(如果使用FastAPI+Vue)
│   ├── src/
│   ├── public/
│   └── package.json
│
├── data/                     # 数据存储目录
│   ├── uploads/              # 用户上传的原始数据
│   ├── processed/            # 处理后的数据
│   └── annotations/          # 标注结果
│
└── logs/                     # 日志目录
```

## 🚀 快速开始

### 环境要求

- Python 3.8+
- CUDA (GPU支持)
- 已部署的 Grounding DINO 模型
- 已部署的 Qwen-VL 模型服务 (地址: http://122.51.47.91:25638/v1)

### 安装依赖

```bash
cd /root/autodl-fs/web_biaozhupingtai
pip install -r requirements.txt
```

### 启动服务

```bash
# 方式1: 使用启动脚本（推荐）
./run.sh

# 方式2: 直接启动
streamlit run main.py --server.port 6006 --server.address 0.0.0.0
```

### 访问服务

- **本地访问**: `http://localhost:6006`
- **公网访问**: 通过AutoDL端口映射，访问 `http://<公网IP>:6006`

> 💡 提示: 在AutoDL控制台配置端口映射，将内网端口6006映射到公网端口

## 📝 功能模块

### 1. 数据管理
- 支持上传文件夹或ZIP压缩包
- 自动解压和图片扫描
- 支持定义检测类别及其文本定义/Prompt

### 2. 自动标注流水线
- **阶段一**: Grounding DINO 基础检测
- **阶段二**: VLM 清洗和验证
- 支持策略模式，便于扩展新模型

### 3. 可视化编辑器
- 图片Gallery视图
- 标注详情页(显示Bounding Boxes)
- CRUD操作(新增/删除/修改框)
- 支持导出YOLO/COCO格式

## 🔧 配置说明

详见 `config.py` 文件，包含:
- 模型路径配置
- 端口配置
- 数据存储路径
- 阈值参数

## 📄 许可证

MIT License

