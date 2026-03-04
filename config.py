"""
配置文件 - 自动标注与清洗平台
"""
import os
from pathlib import Path

# ==================== 基础路径配置 ====================
BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
UPLOADS_DIR = DATA_DIR / "uploads"
PROCESSED_DIR = DATA_DIR / "processed"
ANNOTATIONS_DIR = DATA_DIR / "annotations"
LOGS_DIR = BASE_DIR / "logs"

# 创建必要的目录
for dir_path in [DATA_DIR, UPLOADS_DIR, PROCESSED_DIR, ANNOTATIONS_DIR, LOGS_DIR]:
    dir_path.mkdir(parents=True, exist_ok=True)

# ==================== 服务器配置 ====================
SERVER_HOST = "0.0.0.0"
SERVER_PORT = 6006

# ==================== 外部脚本路径 ====================
# Grounding DINO 标注脚本（在 groundingdino310 环境下运行）
DINO_SCRIPT_PATH = "/root/autodl-fs/web_biaozhupingtai/scripts/run_dino_detection.py"

# VLM 清洗脚本（在 LLM_DL 环境下运行）
VLM_SCRIPT_PATH = "/root/autodl-fs/web_biaozhupingtai/scripts/run_vlm_clean.py"

# ==================== Conda 环境配置 ====================
CONDA_ENVS = {
    'dino': 'groundingdino310',  # Grounding DINO 环境
    'vlm': 'LLM_DL',              # VLM 环境
}

# ==================== Grounding DINO 配置 ====================
GD_CONFIG_PATH = "/root/autodl-fs/GroundingDINO/groundingdino/config/GroundingDINO_SwinT_OGC.py"
GD_CHECKPOINT_PATH = "/root/autodl-fs/GroundingDINO/weights/groundingdino_swint_ogc.pth"
GD_BOX_THRESHOLD = 0.3  # 基础检测阈值

# ==================== VLM 配置 ====================
VLM_BASE_URL = "http://122.51.47.91:25638/v1"
VLM_MODEL_NAME = "Qwen3-VL-4B-Instruct"
VLM_MAX_TOKENS = 4096
VLM_TIMEOUT = 180
VLM_MAX_RETRIES = 3
VLM_MIN_DIM = 10  # 裁剪图片最小尺寸

# ==================== 文件上传配置 ====================
ALLOWED_IMAGE_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.bmp', '.webp'}
MAX_UPLOAD_SIZE_MB = 500
MAX_IMAGES_PER_BATCH = 1000

# ==================== 数据格式配置 ====================
SUPPORTED_EXPORT_FORMATS = ['yolo', 'coco']

# ==================== 日志配置 ====================
LOG_LEVEL = "INFO"
LOG_FILE = LOGS_DIR / "app.log"

# ==================== 工具函数 ====================
def get_project_path(relative_path: str) -> Path:
    """获取项目内的绝对路径"""
    return BASE_DIR / relative_path

def get_upload_path(project_name: str) -> Path:
    """获取指定项目的上传路径"""
    path = UPLOADS_DIR / project_name
    path.mkdir(parents=True, exist_ok=True)
    return path

def get_processed_path(project_name: str) -> Path:
    """获取指定项目的处理结果路径"""
    path = PROCESSED_DIR / project_name
    path.mkdir(parents=True, exist_ok=True)
    return path

def get_annotation_path(project_name: str) -> Path:
    """获取指定项目的标注路径"""
    path = ANNOTATIONS_DIR / project_name
    path.mkdir(parents=True, exist_ok=True)
    return path