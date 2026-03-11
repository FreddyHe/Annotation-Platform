import os
from pathlib import Path
from typing import Optional
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    APP_NAME: str = "Annotation Algorithm Service"
    APP_VERSION: str = "1.0.0"
    API_PREFIX: str = "/api/v1"
    
    HOST: str = "0.0.0.0"
    PORT: int = 8000
    
    CORS_ORIGINS: list = ["http://localhost:5173", "http://localhost:3000", "*"]
    CORS_ALLOW_CREDENTIALS: bool = True
    CORS_ALLOW_METHODS: list = ["*"]
    CORS_ALLOW_HEADERS: list = ["*"]
    
    BACKEND_URL: str = "http://localhost:8080"
    BACKEND_API_KEY: Optional[str] = None
    
    UPLOAD_BASE_PATH: str = "/root/autodl-fs/uploads"
    
    DINO_MODEL_PATH: str = "models/groundingdino_swint_ogc.pth"
    DINO_CONFIG_PATH: str = "models/GroundingDINO_SwinT_OGC.py"
    
    YOLO_MODEL_PATH: str = "models/yolov8n.pt"
    
    VLM_MODEL_NAME: str = "Qwen/Qwen-VL-Chat"
    VLM_MODEL_PATH: str = "models/Qwen-VL-Chat"
    VLM_MAX_TOKENS: int = 4096
    
    LOG_LEVEL: str = "INFO"
    LOG_FILE: str = "logs/algorithm-service.log"
    
    class Config:
        env_file = ".env"
        case_sensitive = True


settings = Settings()

BASE_DIR = Path(__file__).resolve().parent
LOG_DIR = BASE_DIR / "logs"
LOG_DIR.mkdir(exist_ok=True)
