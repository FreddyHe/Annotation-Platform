# 项目架构说明

## 📋 技术栈选型

### 最终选择：Streamlit (Python优先方案)

**选择理由**:
- ✅ **开发速度快**: 纯Python实现，无需前端知识，适合快速迭代
- ✅ **内置组件丰富**: 文件上传、图片展示、交互组件开箱即用
- ✅ **易于维护**: 前后端一体化，减少部署复杂度
- ✅ **适合场景**: 内部工具、快速原型、中小规模数据集处理

**技术栈组成**:
- **前端框架**: Streamlit 1.28+
- **后端框架**: Streamlit (内置FastAPI兼容接口)
- **检测模型**: Grounding DINO (复用现有代码)
- **VLM清洗**: Qwen3-VL-4B-Instruct (复用现有qwen_client_v4.py)
- **图像处理**: PIL, OpenCV
- **数据格式**: 支持YOLO/COCO格式导入导出

## 📁 项目目录结构

```
web_biaozhupingtai/
├── README.md                 # 项目说明文档
├── ARCHITECTURE.md           # 架构说明文档（本文件）
├── requirements.txt          # Python依赖
├── config.py                # 配置文件
├── main.py                  # Streamlit主应用
├── run.sh                   # 启动脚本
│
├── backend/                  # 后端核心逻辑
│   ├── __init__.py
│   │
│   ├── models/               # 模型调用封装(策略模式)
│   │   ├── __init__.py
│   │   ├── base_model.py     # 基础模型接口
│   │   ├── grounding_dino.py # Grounding DINO实现
│   │   └── vlm_refiner.py    # VLM清洗实现
│   │
│   ├── pipeline/             # 推理流水线
│   │   ├── __init__.py
│   │   └── inference_pipeline.py  # 主流水线
│   │
│   └── utils/                # 工具函数
│       ├── __init__.py
│       ├── config.py         # 配置导入
│       ├── file_utils.py     # 文件处理
│       └── format_converter.py  # 格式转换(YOLO/COCO)
│
├── data/                     # 数据存储目录
│   ├── uploads/              # 用户上传的原始数据
│   ├── processed/            # 处理后的数据
│   └── annotations/           # 标注结果
│
└── logs/                     # 日志目录
```

## 🏗️ 架构设计

### 1. 策略模式 (Strategy Pattern)

采用策略模式设计模型接口，便于未来扩展：

```python
BaseDetectionModel (抽象基类)
    └── GroundingDINOModel (当前实现)
    └── [未来] YOLOwModel
    └── [未来] GroundingDINO1.5Model

BaseVLMRefiner (抽象基类)
    └── QwenVLRefiner (当前实现)
    └── [未来] GPT4VRefiner
    └── [未来] ClaudeVisionRefiner
```

### 2. 推理流水线 (Inference Pipeline)

**双阶段处理流程**:

```
阶段一: 基础检测模型 (Grounding DINO)
    ↓
    生成候选框 (Candidate Boxes)
    ↓
    分流处理:
    - 高分框 (score >= 0.65) → 直接保留
    - 低分框 (score <= 0.3) → 直接丢弃
    - 模糊框 (0.3 < score < 0.65) → 进入阶段二
    ↓
阶段二: VLM清洗 (Qwen-VL)
    ↓
    对模糊框进行二次验证
    ↓
    最终结果合并
```

### 3. 数据流

```
用户上传
    ↓
文件管理模块 (file_utils.py)
    - ZIP解压
    - 图片扫描
    - 格式验证
    ↓
推理流水线 (inference_pipeline.py)
    - 单图处理
    - 批量处理
    ↓
结果存储
    - 内存缓存 (session_state)
    - 文件保存 (annotations/)
    ↓
格式转换 (format_converter.py)
    - COCO JSON
    - YOLO格式
```

## 🔧 核心模块说明

### 1. 模型模块 (`backend/models/`)

#### `base_model.py`
- 定义抽象接口 `BaseDetectionModel` 和 `BaseVLMRefiner`
- 确保所有模型实现统一的接口

#### `grounding_dino.py`
- 实现 Grounding DINO 检测模型
- 复用 `/root/autodl-fs/GroundingDINO/api_serverV1201.py` 中的逻辑
- 支持标签对齐和坐标转换

#### `vlm_refiner.py`
- 实现 Qwen-VL 清洗模型
- 复用 `/root/autodl-fs/Paper_annotation_change/utils/qwen_client_v4.py`
- 支持图片裁剪、场景描述生成、框验证

### 2. 流水线模块 (`backend/pipeline/`)

#### `inference_pipeline.py`
- 实现双阶段推理流程
- 支持单图和批量处理
- 提供进度回调接口

### 3. 工具模块 (`backend/utils/`)

#### `file_utils.py`
- ZIP解压
- 图片扫描和验证
- 文件操作

#### `format_converter.py`
- YOLO格式转换
- COCO格式转换
- 批量导出功能

### 4. 前端界面 (`main.py`)

**四个主要标签页**:

1. **数据上传** (Tab 1)
   - 支持单个文件、ZIP压缩包、文件夹路径
   - 图片预览

2. **批量处理** (Tab 2)
   - 配置处理参数
   - 进度显示
   - 结果统计

3. **结果查看** (Tab 3)
   - 图片Gallery
   - 标注可视化
   - 检测详情表格

4. **标注编辑** (Tab 4)
   - 导出功能 (COCO/YOLO)
   - [未来] 交互式编辑界面

## ⚙️ 配置说明

### 模型配置 (`config.py`)

```python
# Grounding DINO
GD_CONFIG_PATH = "/root/autodl-fs/GroundingDINO/groundingdino/config/GroundingDINO_SwinT_OGC.py"
GD_CHECKPOINT_PATH = "/root/autodl-fs/GroundingDINO/weights/groundingdino_swint_ogc.pth"

# VLM
VLM_BASE_URL = "http://122.51.47.91:25638/v1"
VLM_MODEL_NAME = "Qwen3-VL-4B-Instruct"

# 阈值
GD_HIGH_SCORE_THRESH = 0.65  # 高分直接保留
GD_LOW_SCORE_THRESH = 0.3   # 低分直接丢弃
```

### 服务器配置

```python
SERVER_HOST = "0.0.0.0"
SERVER_PORT = 6006  # 可通过AutoDL端口映射暴露到公网
```

## 🚀 部署说明

### 1. 环境要求

- Python 3.8+
- CUDA (GPU支持)
- 已部署的 Grounding DINO 模型
- 已部署的 Qwen-VL 模型服务

### 2. 安装依赖

```bash
cd /root/autodl-fs/web_biaozhupingtai
pip install -r requirements.txt
```

### 3. 启动服务

```bash
# 方式1: 使用启动脚本
./run.sh

# 方式2: 直接启动
streamlit run main.py --server.port 6006 --server.address 0.0.0.0
```

### 4. 访问服务

- 本地访问: `http://localhost:6006`
- 公网访问: 通过AutoDL端口映射，访问 `http://<公网IP>:6006`

## 🔄 扩展指南

### 添加新的检测模型

1. 在 `backend/models/` 下创建新文件，继承 `BaseDetectionModel`
2. 实现 `load_model()` 和 `detect()` 方法
3. 在 `inference_pipeline.py` 中替换默认模型

### 添加新的VLM清洗模型

1. 在 `backend/models/` 下创建新文件，继承 `BaseVLMRefiner`
2. 实现 `refine()` 方法
3. 在 `inference_pipeline.py` 中替换默认VLM

### 添加新的导出格式

1. 在 `backend/utils/format_converter.py` 中添加转换函数
2. 在 `main.py` 的导出功能中添加选项

## 📝 注意事项

1. **路径复用**: 所有代码都复用现有项目中的工具函数，避免重复开发
2. **内存管理**: 大批量处理时注意内存使用，建议分批处理
3. **错误处理**: 所有模型调用都包含异常处理，确保单个失败不影响整体流程
4. **性能优化**: 使用 `@st.cache_resource` 缓存模型实例，避免重复加载

## 🐛 已知问题

1. 标注编辑器功能尚未完全实现（当前仅支持查看和导出）
2. 大批量处理时可能需要较长时间，建议分批处理
3. VLM服务需要确保网络连接正常

## 📄 许可证

MIT License




