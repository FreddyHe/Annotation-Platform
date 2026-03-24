import os
import re
import json
import asyncio
import shutil
import logging
import yaml
import glob
from pathlib import Path
from fastapi import APIRouter, BackgroundTasks
from pydantic import BaseModel
from typing import Optional, Dict, List

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/training", tags=["training"])

BASE_DATASET_DIR = "/root/autodl-fs/custom_datasets"
BASE_MODEL_DIR = "/root/autodl-fs/custom_models"
os.makedirs(BASE_DATASET_DIR, exist_ok=True)
os.makedirs(BASE_MODEL_DIR, exist_ok=True)


class ParseRequest(BaseModel):
    download_command: str


class DownloadRequest(BaseModel):
    task_id: str
    download_command: str


class TrainRequest(BaseModel):
    task_id: str
    model_name: str
    download_command: str
    epochs: int = 50
    batch_size: int = 16
    callback_url: Optional[str] = None


class TrainStatus(BaseModel):
    task_id: str
    status: str
    message: str
    progress: Optional[float] = None
    model_path: Optional[str] = None
    classes: Optional[list] = None
    map_score: Optional[float] = None


# 内存状态存储
_task_status: Dict[str, TrainStatus] = {}


def parse_download_command(raw_cmd: str) -> dict:
    """解析三种格式的下载命令：URL、curl、Python SDK"""
    raw = raw_cmd.strip()

    url_pattern = r'https://universe\.roboflow\.com/ds/\S+\?key=\S+'
    url_match = re.search(url_pattern, raw)
    
    if url_match and 'curl' not in raw.lower() and 'import' not in raw.lower():
        url = url_match.group(0).rstrip('"').rstrip("'").rstrip(';')
        return {"type": "url", "url": url}

    if 'curl' in raw.lower():
        url_match = re.search(url_pattern, raw)
        if url_match:
            url = url_match.group(0).rstrip('"').rstrip("'").rstrip(';')
            return {"type": "curl", "url": url}

    if 'roboflow' in raw.lower() or 'Roboflow' in raw:
        api_key = re.search(r'api_key\s*=\s*["\']([^"\']+)["\']', raw)
        workspace = re.search(r'rf\.workspace\(\s*["\']([^"\']+)["\']', raw)
        project_match = re.search(r'\.project\(\s*["\']([^"\']+)["\']', raw)
        version_match = re.search(r'\.version\(\s*(\d+)\s*\)', raw)
        fmt_match = re.search(r'\.download\(\s*["\']([^"\']+)["\']', raw)

        if api_key and workspace and project_match and version_match:
            return {
                "type": "sdk",
                "api_key": api_key.group(1),
                "workspace": workspace.group(1),
                "project": project_match.group(1),
                "version": int(version_match.group(1)),
                "format": fmt_match.group(1) if fmt_match else "yolov8"
            }

    raise ValueError("无法解析下载命令。支持三种格式：\n1. Roboflow 直接 URL\n2. curl 命令\n3. Roboflow Python SDK 代码")


async def download_dataset(parsed: dict, dest_dir: str) -> str:
    """下载数据集到指定目录"""
    os.makedirs(dest_dir, exist_ok=True)
    
    if parsed["type"] in ["url", "curl"]:
        url = parsed["url"]
        zip_path = os.path.join(dest_dir, "dataset.zip")
        
        cmd = f'wget -q -O "{zip_path}" "{url}"'
        logger.info(f"下载命令: {cmd}")
        
        proc = await asyncio.create_subprocess_shell(
            cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        stdout, stderr = await proc.communicate()
        
        if proc.returncode != 0:
            raise RuntimeError(f"下载失败: {stderr.decode()}")
        
        unzip_cmd = f'cd "{dest_dir}" && unzip -o -q dataset.zip'
        proc = await asyncio.create_subprocess_shell(
            unzip_cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        await proc.communicate()
        
        if os.path.exists(zip_path):
            os.remove(zip_path)
        
        return dest_dir
    
    elif parsed["type"] == "sdk":
        script_content = f"""
from roboflow import Roboflow
rf = Roboflow(api_key="{parsed['api_key']}")
project = rf.workspace("{parsed['workspace']}").project("{parsed['project']}")
version = project.version({parsed['version']})
dataset = version.download("{parsed['format']}", location="{dest_dir}")
"""
        script_path = os.path.join(dest_dir, "download_script.py")
        with open(script_path, 'w') as f:
            f.write(script_content)
        
        cmd = f'cd "{dest_dir}" && python download_script.py'
        proc = await asyncio.create_subprocess_shell(
            cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        stdout, stderr = await proc.communicate()
        
        if proc.returncode != 0:
            raise RuntimeError(f"SDK下载失败: {stderr.decode()}")
        
        if os.path.exists(script_path):
            os.remove(script_path)
        
        return dest_dir
    
    raise ValueError(f"不支持的下载类型: {parsed['type']}")


def find_data_yaml(dataset_dir: str) -> Optional[str]:
    """递归查找 data.yaml 文件"""
    for root, dirs, files in os.walk(dataset_dir):
        for file in files:
            if file == "data.yaml":
                return os.path.join(root, file)
    return None


def detect_dataset_format(dataset_dir: str) -> str:
    """检测数据集格式：YOLO/COCO/VOC/UNKNOWN"""
    if find_data_yaml(dataset_dir):
        return "YOLO"
    
    for root, dirs, files in os.walk(dataset_dir):
        for file in files:
            if file.endswith('.json'):
                json_path = os.path.join(root, file)
                try:
                    with open(json_path, 'r') as f:
                        data = json.load(f)
                    if 'annotations' in data and 'images' in data:
                        return "COCO"
                except:
                    continue
    
    for root, dirs, files in os.walk(dataset_dir):
        for file in files:
            if file.endswith('.xml'):
                return "VOC"
    
    return "UNKNOWN"


def convert_coco_to_yolo(dataset_dir: str, output_dir: str) -> str:
    """将 COCO 格式转换为 YOLO 格式"""
    os.makedirs(output_dir, exist_ok=True)
    
    coco_json = None
    for root, dirs, files in os.walk(dataset_dir):
        for file in files:
            if file.endswith('.json'):
                json_path = os.path.join(root, file)
                try:
                    with open(json_path, 'r') as f:
                        data = json.load(f)
                    if 'annotations' in data and 'images' in data:
                        coco_json = json_path
                        break
                except:
                    continue
        if coco_json:
            break
    
    if not coco_json:
        raise ValueError("未找到有效的 COCO JSON 文件")
    
    with open(coco_json, 'r') as f:
        coco_data = json.load(f)
    
    categories = {cat['id']: cat['name'] for cat in coco_data.get('categories', [])}
    images_info = {img['id']: img for img in coco_data.get('images', [])}
    
    images_dir = os.path.join(output_dir, 'images', 'train')
    labels_dir = os.path.join(output_dir, 'labels', 'train')
    os.makedirs(images_dir, exist_ok=True)
    os.makedirs(labels_dir, exist_ok=True)
    
    annotations_by_image = {}
    for ann in coco_data.get('annotations', []):
        img_id = ann['image_id']
        if img_id not in annotations_by_image:
            annotations_by_image[img_id] = []
        annotations_by_image[img_id].append(ann)
    
    for img_id, img_info in images_info.items():
        img_filename = img_info['file_name']
        img_width = img_info['width']
        img_height = img_info['height']
        
        src_img_path = None
        for root, dirs, files in os.walk(dataset_dir):
            if img_filename in files:
                src_img_path = os.path.join(root, img_filename)
                break
        
        if src_img_path and os.path.exists(src_img_path):
            dst_img_path = os.path.join(images_dir, img_filename)
            shutil.copy2(src_img_path, dst_img_path)
            
            label_filename = os.path.splitext(img_filename)[0] + '.txt'
            label_path = os.path.join(labels_dir, label_filename)
            
            with open(label_path, 'w') as f:
                if img_id in annotations_by_image:
                    for ann in annotations_by_image[img_id]:
                        cat_id = ann['category_id']
                        bbox = ann['bbox']
                        
                        x_center = (bbox[0] + bbox[2] / 2) / img_width
                        y_center = (bbox[1] + bbox[3] / 2) / img_height
                        width = bbox[2] / img_width
                        height = bbox[3] / img_height
                        
                        class_idx = list(categories.keys()).index(cat_id)
                        f.write(f"{class_idx} {x_center} {y_center} {width} {height}\n")
    
    data_yaml_path = os.path.join(output_dir, 'data.yaml')
    yaml_content = {
        'path': output_dir,
        'train': 'images/train',
        'val': 'images/train',
        'nc': len(categories),
        'names': {i: name for i, (cat_id, name) in enumerate(categories.items())}
    }
    
    with open(data_yaml_path, 'w') as f:
        yaml.dump(yaml_content, f, default_flow_style=False)
    
    logger.info(f"COCO 转 YOLO 完成: {data_yaml_path}")
    return data_yaml_path


@router.post("/parse-command")
async def parse_command_endpoint(req: ParseRequest):
    """仅解析下载命令，不实际下载（测试用）"""
    try:
        result = parse_download_command(req.download_command)
        return {"success": True, "data": result}
    except Exception as e:
        return {"success": False, "message": str(e)}


@router.post("/download-and-convert")
async def download_and_convert_endpoint(req: DownloadRequest):
    """下载数据集并转换格式（测试用）"""
    try:
        parsed = parse_download_command(req.download_command)
        dest_dir = os.path.join(BASE_DATASET_DIR, req.task_id)
        actual_dir = await download_dataset(parsed, dest_dir)
        fmt = detect_dataset_format(actual_dir)

        data_yaml_path = None
        if fmt == "YOLO":
            data_yaml_path = find_data_yaml(actual_dir)
        elif fmt == "COCO":
            yolo_dir = os.path.join(dest_dir, "yolo_converted")
            data_yaml_path = convert_coco_to_yolo(actual_dir, yolo_dir)

        classes = []
        if data_yaml_path:
            with open(data_yaml_path) as f:
                cfg = yaml.safe_load(f)
            names = cfg.get("names", {})
            classes = [{"class_id": int(k), "name": v} for k, v in sorted(names.items(), key=lambda x: int(x[0]))]

        return {
            "success": True,
            "data": {
                "format": fmt,
                "dataset_dir": actual_dir,
                "data_yaml": data_yaml_path,
                "num_classes": len(classes),
                "classes": classes
            }
        }
    except Exception as e:
        logger.error(f"下载转换失败: {e}", exc_info=True)
        return {"success": False, "message": str(e)}


async def run_yolo_training(data_yaml: str, output_dir: str, epochs: int, batch_size: int) -> dict:
    """执行 YOLO 训练"""
    with open(data_yaml) as f:
        data_cfg = yaml.safe_load(f)
    names = data_cfg.get("names", {})

    train_cmd = (
        f"yolo detect train "
        f"data={data_yaml} "
        f"model=yolov8n.pt "
        f"epochs={epochs} "
        f"batch={batch_size} "
        f"imgsz=640 "
        f"project={output_dir} "
        f"name=train "
        f"exist_ok=True "
        f"verbose=True"
    )
    logger.info(f"训练命令: {train_cmd}")

    env = os.environ.copy()
    env.pop("CUDA_VISIBLE_DEVICES", None)

    proc = await asyncio.create_subprocess_shell(
        train_cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        env=env
    )
    stdout, _ = await proc.communicate()
    train_log = stdout.decode()

    if proc.returncode != 0:
        raise RuntimeError(f"训练失败:\n{train_log[-2000:]}")

    best_pt = os.path.join(output_dir, "train", "weights", "best.pt")
    if not os.path.exists(best_pt):
        candidates = glob.glob(os.path.join(output_dir, "**/best.pt"), recursive=True)
        if candidates:
            best_pt = candidates[0]
        else:
            raise RuntimeError("训练完成但未找到 best.pt")

    classes = [{"class_id": int(k), "name": v, "cn_name": v}
               for k, v in sorted(names.items(), key=lambda x: int(x[0]))]

    return {"model_path": best_pt, "classes": classes, "map_score": None}


async def run_full_pipeline(req: TrainRequest):
    """完整训练流水线：下载 → 转换 → 训练 → 回调"""
    task_id = req.task_id
    dataset_dir = os.path.join(BASE_DATASET_DIR, task_id)
    model_dir = os.path.join(BASE_MODEL_DIR, task_id)

    try:
        _task_status[task_id] = TrainStatus(
            task_id=task_id, status="DOWNLOADING", message="解析下载命令..."
        )
        parsed = parse_download_command(req.download_command)

        _task_status[task_id] = TrainStatus(
            task_id=task_id, status="DOWNLOADING", message="下载数据集中..."
        )
        actual_dir = await download_dataset(parsed, dataset_dir)

        _task_status[task_id] = TrainStatus(
            task_id=task_id, status="CONVERTING", message="检测数据集格式..."
        )
        fmt = detect_dataset_format(actual_dir)

        if fmt == "YOLO":
            data_yaml = find_data_yaml(actual_dir)
            with open(data_yaml) as f:
                cfg = yaml.safe_load(f)
            cfg["path"] = os.path.dirname(data_yaml)
            with open(data_yaml, "w") as f:
                yaml.dump(cfg, f, allow_unicode=True, default_flow_style=False)
        elif fmt == "COCO":
            _task_status[task_id] = TrainStatus(
                task_id=task_id, status="CONVERTING", message="COCO → YOLO 转换中..."
            )
            yolo_dir = os.path.join(dataset_dir, "yolo_converted")
            data_yaml = convert_coco_to_yolo(actual_dir, yolo_dir)
        else:
            raise RuntimeError(f"不支持的数据集格式: {fmt}")

        _task_status[task_id] = TrainStatus(
            task_id=task_id, status="TRAINING", message=f"YOLO 训练中 (epochs={req.epochs})..."
        )
        result = await run_yolo_training(data_yaml, model_dir, req.epochs, req.batch_size)

        _task_status[task_id] = TrainStatus(
            task_id=task_id,
            status="COMPLETED",
            message=f"训练完成！mAP50: {result.get('map_score', 'N/A')}",
            model_path=result["model_path"],
            classes=result["classes"],
            map_score=result.get("map_score")
        )

        if req.callback_url:
            import httpx
            async with httpx.AsyncClient() as client:
                await client.post(req.callback_url, json={
                    "taskId": task_id,
                    "status": "COMPLETED",
                    "modelPath": result["model_path"],
                    "classes": result["classes"],
                    "mapScore": result.get("map_score")
                }, timeout=10)

    except Exception as e:
        logger.error(f"[{task_id}] 流水线失败: {e}", exc_info=True)
        _task_status[task_id] = TrainStatus(
            task_id=task_id, status="FAILED", message=str(e)
        )
        if req.callback_url:
            try:
                import httpx
                async with httpx.AsyncClient() as client:
                    await client.post(req.callback_url, json={
                        "taskId": task_id, "status": "FAILED", "message": str(e)
                    }, timeout=10)
            except:
                pass


@router.post("/start")
async def start_training(req: TrainRequest, background_tasks: BackgroundTasks):
    """提交训练任务（后台异步执行）"""
    _task_status[req.task_id] = TrainStatus(
        task_id=req.task_id, status="DOWNLOADING", message="正在解析下载命令..."
    )
    background_tasks.add_task(run_full_pipeline, req)
    return {"success": True, "taskId": req.task_id, "message": "训练任务已提交"}


@router.get("/status/{task_id}")
async def get_training_status(task_id: str):
    """查询训练状态"""
    if task_id not in _task_status:
        return {"success": False, "message": "任务不存在"}
    return {"success": True, "data": _task_status[task_id].dict()}
