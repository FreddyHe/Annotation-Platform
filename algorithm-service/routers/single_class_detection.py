from fastapi import APIRouter, HTTPException, UploadFile, File, Form
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from loguru import logger
import cv2
import numpy as np
import base64
from io import BytesIO
from PIL import Image

from services.model_service import model_service
from config import settings


router = APIRouter()


class SingleClassDetectionRequest(BaseModel):
    class_id: int = Field(..., ge=0, le=9, description="Class ID (0-9)")
    model_path: str = Field(..., description="Path to YOLO model file")
    confidence_threshold: float = Field(default=0.5, ge=0.0, le=1.0, description="Confidence threshold")
    iou_threshold: float = Field(default=0.45, ge=0.0, le=1.0, description="IOU threshold for NMS")


class SingleClassDetectionResponse(BaseModel):
    success: bool
    message: str
    image_base64: Optional[str] = None
    detections: Optional[List[Dict[str, Any]]] = None


@router.post("/algo/single-class-detection", response_model=SingleClassDetectionResponse)
async def single_class_detection(
    image: UploadFile = File(..., description="Image file"),
    class_id: int = Form(..., ge=0, le=9, description="Class ID (0-9)"),
    model_path: str = Form(..., description="Path to YOLO model file"),
    confidence_threshold: float = Form(default=0.5, ge=0.0, le=1.0, description="Confidence threshold"),
    iou_threshold: float = Form(default=0.45, ge=0.0, le=1.0, description="IOU threshold for NMS")
):
    """单类别检测 - 检测指定类别的目标并返回标注图片"""
    logger.info(f"Received single class detection request: class_id={class_id}, model_path={model_path}")
    
    try:
        # 读取上传的图片
        image_bytes = await image.read()
        nparr = np.frombuffer(image_bytes, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        
        if img is None:
            raise HTTPException(status_code=400, detail="Invalid image file")
        
        # 保存临时图片用于 YOLO 推理
        temp_image_path = f"/tmp/temp_detection_{class_id}.jpg"
        cv2.imwrite(temp_image_path, img)
        
        # 确保模型已加载
        if model_service.yolo_model is None or model_service.yolo_model_path != model_path:
            logger.info(f"Loading YOLO model from {model_path}")
            model_service.load_yolo_model(model_path)
        
        # 运行 YOLO 检测
        results = model_service.yolo_model(
            temp_image_path,
            conf=confidence_threshold,
            iou=iou_threshold,
            verbose=False
        )
        
        # 过滤指定 class_id 的检测结果
        filtered_detections = []
        for r in results:
            boxes = r.boxes
            if boxes is None:
                continue
            
            for i in range(len(boxes)):
                box = boxes[i]
                cls = int(box.cls[0])
                
                # 只保留指定 class_id 的检测结果
                if cls == class_id:
                    x1, y1, x2, y2 = box.xyxy[0].tolist()
                    conf = float(box.conf[0])
                    cls_name = model_service.yolo_model.names[cls]
                    
                    # 绘制 bbox
                    color = (0, 255, 0)  # 绿色
                    cv2.rectangle(img, (int(x1), int(y1)), (int(x2), int(y2)), color, 2)
                    
                    # 绘制标签
                    label = f"{cls_name} {conf:.2f}"
                    label_size, _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 2)
                    cv2.rectangle(
                        img,
                        (int(x1), int(y1) - label_size[1] - 10),
                        (int(x1) + label_size[0], int(y1)),
                        color,
                        -1
                    )
                    cv2.putText(
                        img,
                        label,
                        (int(x1), int(y1) - 5),
                        cv2.FONT_HERSHEY_SIMPLEX,
                        0.5,
                        (255, 255, 255),
                        2
                    )
                    
                    # 获取图片尺寸进行归一化
                    img_h, img_w = img.shape[:2]
                    
                    # 转换 bbox 格式
                    x, y, w, h = x1, y1, x2 - x1, y2 - y1
                    
                    filtered_detections.append({
                        "class": cls_name,
                        "class_id": cls,
                        "bbox": [float(x / img_w), float(y / img_h), float(w / img_w), float(h / img_h)],
                        "bbox_absolute": [float(x1), float(y1), float(x2), float(y2)],
                        "confidence": float(conf)
                    })
        
        # 将标注后的图片转换为 Base64
        _, buffer = cv2.imencode('.jpg', img)
        img_base64 = base64.b64encode(buffer).decode('utf-8')
        
        logger.info(f"Single class detection completed: {len(filtered_detections)} objects detected for class_id={class_id}")
        
        return SingleClassDetectionResponse(
            success=True,
            message=f"Detection completed. Found {len(filtered_detections)} objects.",
            image_base64=img_base64,
            detections=filtered_detections
        )
        
    except Exception as e:
        logger.error(f"Single class detection failed: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Detection failed: {str(e)}")


@router.get("/algo/single-class-detection/model-info")
async def get_model_info():
    """获取可用的类别信息"""
    class_info = [
        {"class_id": 0, "name": "pedestrian", "cn_name": "行人"},
        {"class_id": 1, "name": "people", "cn_name": "人"},
        {"class_id": 2, "name": "bicycle", "cn_name": "自行车"},
        {"class_id": 3, "name": "car", "cn_name": "汽车"},
        {"class_id": 4, "name": "van", "cn_name": "面包车"},
        {"class_id": 5, "name": "truck", "cn_name": "卡车"},
        {"class_id": 6, "name": "tricycle", "cn_name": "三轮车"},
        {"class_id": 7, "name": "awning-tricycle", "cn_name": "带篷三轮车"},
        {"class_id": 8, "name": "bus", "cn_name": "公交车"},
        {"class_id": 9, "name": "motor", "cn_name": "摩托车"}
    ]
    
    return {
        "success": True,
        "message": "Available class information",
        "classes": class_info
    }
