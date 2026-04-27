import os
import re
import json
import asyncio
import shutil
import logging
import yaml
import glob
import zipfile
from pathlib import Path
from fastapi import APIRouter, BackgroundTasks
from pydantic import BaseModel
from typing import Optional, Dict, List, Any

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/training", tags=["training"])

BASE_DATASET_DIR = "/root/autodl-fs/custom_datasets"
BASE_MODEL_DIR = "/root/autodl-fs/custom_models"
UPLOAD_BASE_DIR = "/root/autodl-fs/uploads"
os.makedirs(BASE_DATASET_DIR, exist_ok=True)
os.makedirs(BASE_MODEL_DIR, exist_ok=True)


class ParseRequest(BaseModel):
    download_command: Optional[str] = None


class DownloadRequest(BaseModel):
    task_id: str
    download_command: str


class InspectDatasetRequest(BaseModel):
    dataset_source: str = "ROBOFLOW"
    dataset_uri: Optional[str] = None
    download_command: Optional[str] = None


class TrainRequest(BaseModel):
    task_id: str
    model_name: str
    dataset_source: str = "ROBOFLOW"
    dataset_uri: Optional[str] = None
    download_command: Optional[str] = None
    epochs: int = 50
    batch_size: int = 16
    image_size: int = 640
    learning_rate: float = 0.01
    use_pretrained: bool = True
    automl: bool = False
    callback_url: Optional[str] = None


class TrainStatus(BaseModel):
    task_id: str
    status: str
    message: str
    progress: Optional[float] = None
    model_path: Optional[str] = None
    classes: Optional[list] = None
    map_score: Optional[float] = None
    precision_score: Optional[float] = None
    recall_score: Optional[float] = None
    logs: Optional[List[str]] = None


# 内存状态存储
_task_status: Dict[str, TrainStatus] = {}


def _update_status(
    task_id: str,
    status: str,
    message: str,
    progress: float,
    **kwargs
) -> None:
    previous_logs = []
    if task_id in _task_status and _task_status[task_id].logs:
        previous_logs = list(_task_status[task_id].logs)
    if not previous_logs or previous_logs[-1] != message:
        previous_logs.append(message)
    _task_status[task_id] = TrainStatus(
        task_id=task_id,
        status=status,
        message=message,
        progress=progress,
        logs=previous_logs,
        **kwargs
    )


def parse_download_command(raw_cmd: str) -> dict:
    """解析三种格式的下载命令：URL、curl、Python SDK"""
    if not raw_cmd:
        raise ValueError("下载命令为空")
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
        await _download_file(url, zip_path)
        _safe_extract_zip(zip_path, dest_dir)
        
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


def _safe_extract_zip(zip_path: str, dest_dir: str) -> str:
    os.makedirs(dest_dir, exist_ok=True)
    dest_abs = os.path.abspath(dest_dir)
    with zipfile.ZipFile(zip_path) as archive:
        for member in archive.infolist():
            member_path = os.path.abspath(os.path.join(dest_dir, member.filename))
            if not member_path.startswith(dest_abs + os.sep) and member_path != dest_abs:
                raise ValueError(f"ZIP包含不安全路径: {member.filename}")
        archive.extractall(dest_dir)
    return dest_dir


async def _download_file(url: str, dest_path: str) -> None:
    logger.info(f"下载数据集URL: {url}")
    proc = await asyncio.create_subprocess_exec(
        "curl",
        "-L",
        "--fail",
        "--connect-timeout",
        "20",
        "--max-time",
        "300",
        "-o",
        dest_path,
        url,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE
    )
    _, stderr = await proc.communicate()
    if proc.returncode != 0:
        raise RuntimeError(f"数据集下载失败: {stderr.decode()}")


async def _download_url_dataset(url: str, dest_dir: str) -> str:
    os.makedirs(dest_dir, exist_ok=True)
    zip_path = os.path.join(dest_dir, "dataset.zip")
    await _download_file(url, zip_path)
    return _safe_extract_zip(zip_path, dest_dir)


def _resolve_local_dataset_path(dataset_uri: str) -> str:
    if not dataset_uri:
        raise ValueError("数据集路径为空")
    if dataset_uri.startswith("/"):
        return dataset_uri
    return os.path.join(UPLOAD_BASE_DIR, dataset_uri)


def _copy_or_extract_local_dataset(dataset_uri: str, dest_dir: str) -> str:
    source_path = _resolve_local_dataset_path(dataset_uri)
    if not os.path.exists(source_path):
        raise FileNotFoundError(f"数据集路径不存在: {source_path}")
    if os.path.isdir(source_path):
        target_dir = os.path.join(dest_dir, "local_dataset")
        if os.path.exists(target_dir):
            shutil.rmtree(target_dir)
        shutil.copytree(source_path, target_dir)
        return target_dir
    if zipfile.is_zipfile(source_path):
        return _safe_extract_zip(source_path, dest_dir)
    raise ValueError("本地数据集仅支持目录或ZIP压缩包")


async def prepare_dataset(req: TrainRequest, dataset_dir: str) -> str:
    source = (req.dataset_source or "ROBOFLOW").upper()
    if source == "ROBOFLOW":
        parsed = parse_download_command(req.download_command)
        return await download_dataset(parsed, dataset_dir)
    if source == "URL_ZIP":
        if not req.dataset_uri:
            raise ValueError("URL_ZIP 数据源需要 dataset_uri")
        return await _download_url_dataset(req.dataset_uri, dataset_dir)
    if source == "UPLOAD_ZIP":
        if not req.dataset_uri:
            raise ValueError(f"{source} 数据源需要 dataset_uri")
        return _copy_or_extract_local_dataset(req.dataset_uri, dataset_dir)
    raise ValueError(f"不支持的数据源: {source}")


def find_data_yaml(dataset_dir: str) -> Optional[str]:
    """递归查找 YOLO 数据集 YAML，优先使用 data.yaml。"""
    yaml_candidates = []
    for root, dirs, files in os.walk(dataset_dir):
        for file in files:
            if file == "data.yaml":
                return os.path.join(root, file)
            if file.endswith((".yaml", ".yml")):
                yaml_candidates.append(os.path.join(root, file))
    for yaml_path in yaml_candidates:
        try:
            with open(yaml_path) as f:
                cfg = yaml.safe_load(f) or {}
            if cfg.get("names") is not None and (cfg.get("train") is not None or cfg.get("val") is not None or cfg.get("valid") is not None):
                return yaml_path
        except Exception:
            continue
    return None


def find_yolo_directory(dataset_dir: str) -> Optional[str]:
    for root, dirs, files in os.walk(dataset_dir):
        if "images" in dirs and "labels" in dirs:
            return root
    return None


def read_label_class_ids(labels_dir: str) -> List[int]:
    class_ids = set()
    if not labels_dir or not os.path.exists(labels_dir):
        return []
    for label_path in glob.glob(os.path.join(labels_dir, "**", "*.txt"), recursive=True):
        try:
            with open(label_path) as f:
                for line in f:
                    parts = line.strip().split()
                    if not parts:
                        continue
                    class_ids.add(int(float(parts[0])))
        except Exception:
            continue
    return sorted(class_ids)


def create_yolo_yaml_from_directory(dataset_dir: str) -> Optional[str]:
    yolo_root = find_yolo_directory(dataset_dir)
    if not yolo_root:
        return None

    images_root = os.path.join(yolo_root, "images")
    labels_root = os.path.join(yolo_root, "labels")
    class_ids = read_label_class_ids(labels_root)
    max_class_id = max(class_ids) if class_ids else 0
    names = {idx: f"class_{idx}" for idx in range(max_class_id + 1)}

    train_dir = os.path.join(images_root, "train")
    val_dir = os.path.join(images_root, "val")
    valid_dir = os.path.join(images_root, "valid")
    if not os.path.exists(train_dir):
        train_dir = images_root
    if not os.path.exists(val_dir):
        val_dir = valid_dir if os.path.exists(valid_dir) else train_dir

    data_yaml = os.path.join(yolo_root, "data.yaml")
    with open(data_yaml, "w") as f:
        yaml.dump({
            "path": yolo_root,
            "train": os.path.relpath(train_dir, yolo_root),
            "val": os.path.relpath(val_dir, yolo_root),
            "nc": len(names),
            "names": names
        }, f, allow_unicode=True, default_flow_style=False)
    return data_yaml


def iter_class_names(names: Any) -> List[tuple]:
    if isinstance(names, list):
        return [(idx, name) for idx, name in enumerate(names)]
    if isinstance(names, dict):
        def sort_key(item):
            try:
                return int(item[0])
            except Exception:
                return str(item[0])
        return [(int(k), v) for k, v in sorted(names.items(), key=sort_key)]
    return []


def detect_dataset_format(dataset_dir: str) -> str:
    """检测数据集格式：YOLO/COCO/VOC/UNKNOWN"""
    if find_data_yaml(dataset_dir) or find_yolo_directory(dataset_dir):
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


def _image_files_under(path: str) -> List[str]:
    if not path or not os.path.exists(path):
        return []
    patterns = ["*.jpg", "*.jpeg", "*.png", "*.bmp", "*.webp"]
    files = []
    for pattern in patterns:
        files.extend(glob.glob(os.path.join(path, "**", pattern), recursive=True))
        files.extend(glob.glob(os.path.join(path, "**", pattern.upper()), recursive=True))
    return sorted(set(files))


def _resolve_yaml_paths(base_dir: str, value: Any) -> List[str]:
    if value is None:
        return []
    values = value if isinstance(value, list) else [value]
    resolved = []
    for item in values:
        item_str = str(item)
        if item_str.startswith("/") and os.path.exists(item_str):
            resolved.append(item_str)
        else:
            resolved.append(os.path.join(base_dir, item_str))
    return resolved


def _infer_label_path(image_path: str) -> str:
    label_path = image_path.replace(os.sep + "images" + os.sep, os.sep + "labels" + os.sep)
    return os.path.splitext(label_path)[0] + ".txt"


def inspect_yolo_dataset(data_yaml: str) -> dict:
    with open(data_yaml) as f:
        cfg = yaml.safe_load(f) or {}

    dataset_root = cfg.get("path") or os.path.dirname(data_yaml)
    if not os.path.isabs(dataset_root):
        dataset_root = os.path.abspath(os.path.join(os.path.dirname(data_yaml), dataset_root))
    yaml_dir = os.path.dirname(data_yaml)
    if not os.path.exists(dataset_root):
        dataset_root = yaml_dir

    def has_split_images(root: str) -> bool:
        for split in ["train", "val", "valid", "test"]:
            for split_path in _resolve_yaml_paths(root, cfg.get(split)):
                if os.path.isfile(split_path):
                    return True
                if _image_files_under(split_path):
                    return True
        return False

    if not has_split_images(dataset_root) and has_split_images(yaml_dir):
        dataset_root = yaml_dir

    classes = [
        {"class_id": class_id, "name": str(name)}
        for class_id, name in iter_class_names(cfg.get("names", {}))
    ]
    split_summary = {}
    total_images = 0
    total_labels = 0
    missing_labels = 0

    for split in ["train", "val", "valid", "test"]:
        split_paths = _resolve_yaml_paths(dataset_root, cfg.get(split))
        split_images = []
        for split_path in split_paths:
            if os.path.isfile(split_path):
                with open(split_path) as f:
                    split_images.extend([line.strip() for line in f if line.strip()])
            else:
                split_images.extend(_image_files_under(split_path))

        label_count = 0
        missing_count = 0
        for image_path in split_images:
            abs_image = image_path if os.path.isabs(image_path) else os.path.join(dataset_root, image_path)
            if os.path.exists(_infer_label_path(abs_image)):
                label_count += 1
            else:
                missing_count += 1

        if split_images:
            split_summary[split] = {
                "images": len(split_images),
                "labels": label_count,
                "missingLabels": missing_count
            }
            total_images += len(split_images)
            total_labels += label_count
            missing_labels += missing_count

    warnings = []
    if not classes:
        warnings.append("data.yaml 缺少 names 类别定义")
    if total_images == 0:
        warnings.append("未找到训练/验证图片")
    if missing_labels > 0:
        warnings.append(f"有 {missing_labels} 张图片缺少对应标签文件")
    if "val" not in split_summary and "valid" not in split_summary:
        warnings.append("未检测到验证集，训练将难以评估泛化效果")

    return {
        "format": "YOLO",
        "valid": bool(classes and total_images > 0 and missing_labels < total_images),
        "dataYaml": data_yaml,
        "datasetRoot": dataset_root,
        "classes": classes,
        "classCount": len(classes),
        "imageCount": total_images,
        "labelCount": total_labels,
        "missingLabelCount": missing_labels,
        "splits": split_summary,
        "warnings": warnings
    }


def inspect_coco_dataset(dataset_dir: str) -> dict:
    json_files = glob.glob(os.path.join(dataset_dir, "**", "*.json"), recursive=True)
    best = None
    for json_path in json_files:
        try:
            with open(json_path) as f:
                data = json.load(f)
            if "images" in data and "annotations" in data and "categories" in data:
                best = (json_path, data)
                break
        except Exception:
            continue

    if not best:
        return {
            "format": "COCO",
            "valid": False,
            "warnings": ["未找到有效 COCO JSON 标注文件"],
            "classes": [],
            "classCount": 0,
            "imageCount": 0,
            "labelCount": 0
        }

    json_path, data = best
    classes = [
        {"class_id": int(cat.get("id", idx)), "name": str(cat.get("name", f"class_{idx}"))}
        for idx, cat in enumerate(data.get("categories", []))
    ]
    image_count = len(data.get("images", []))
    annotation_count = len(data.get("annotations", []))
    warnings = []
    if not classes:
        warnings.append("COCO JSON 缺少 categories")
    if image_count == 0:
        warnings.append("COCO JSON 缺少 images")
    if annotation_count == 0:
        warnings.append("COCO JSON 缺少 annotations")

    return {
        "format": "COCO",
        "valid": bool(classes and image_count > 0 and annotation_count > 0),
        "annotationFile": json_path,
        "classes": classes,
        "classCount": len(classes),
        "imageCount": image_count,
        "labelCount": annotation_count,
        "warnings": warnings
    }


def inspect_voc_dataset(dataset_dir: str) -> dict:
    xml_files = glob.glob(os.path.join(dataset_dir, "**", "*.xml"), recursive=True)
    images = _image_files_under(dataset_dir)
    warnings = []
    if not xml_files:
        warnings.append("未找到 VOC XML 标注文件")
    if not images:
        warnings.append("未找到图片文件")
    warnings.append("VOC 训练前需要转换为 YOLO 格式")
    return {
        "format": "VOC",
        "valid": bool(xml_files and images),
        "classes": [],
        "classCount": 0,
        "imageCount": len(images),
        "labelCount": len(xml_files),
        "warnings": warnings
    }


def inspect_dataset_dir(dataset_dir: str) -> dict:
    fmt = detect_dataset_format(dataset_dir)
    if fmt == "YOLO":
        data_yaml = find_data_yaml(dataset_dir) or create_yolo_yaml_from_directory(dataset_dir)
        return inspect_yolo_dataset(data_yaml)
    if fmt == "COCO":
        return inspect_coco_dataset(dataset_dir)
    if fmt == "VOC":
        return inspect_voc_dataset(dataset_dir)
    return {
        "format": "UNKNOWN",
        "valid": False,
        "classes": [],
        "classCount": 0,
        "imageCount": len(_image_files_under(dataset_dir)),
        "labelCount": 0,
        "warnings": ["无法识别数据集格式，请提供 YOLO data.yaml 或 COCO JSON 标注"]
    }


def choose_automl_params(inspection: dict) -> dict:
    image_count = int(inspection.get("imageCount") or 0)
    class_count = int(inspection.get("classCount") or 1)
    splits = inspection.get("splits") or {}
    has_validation = bool(splits.get("val") or splits.get("valid"))

    if image_count <= 20:
        epochs = 10
    elif image_count <= 100:
        epochs = 30
    elif image_count <= 500:
        epochs = 60
    else:
        epochs = 80

    if not has_validation:
        epochs = max(epochs, 30)

    if image_count <= 80 or class_count >= 12:
        batch_size = 4
    elif image_count <= 400 or class_count >= 6:
        batch_size = 8
    else:
        batch_size = 16

    return {
        "epochs": epochs,
        "batch_size": batch_size,
        "image_size": 640,
        "learning_rate": 0.003 if batch_size <= 4 else 0.005 if batch_size <= 8 else 0.01,
        "use_pretrained": True
    }


def parse_yolo_metrics(output_dir: str) -> dict:
    """Read Ultralytics results.csv when available."""
    import csv

    candidates = glob.glob(os.path.join(output_dir, "**", "results.csv"), recursive=True)
    if not candidates:
        return {}

    results_csv = candidates[0]
    try:
        with open(results_csv, newline="") as f:
            rows = list(csv.DictReader(f))
        if not rows:
            return {}
        last = rows[-1]

        def read_metric(*names):
            for name in names:
                value = last.get(name)
                if value is not None and str(value).strip() != "":
                    try:
                        return float(value)
                    except ValueError:
                        continue
            return None

        return {
            "map_score": read_metric("metrics/mAP50(B)", "metrics/mAP50", "map50"),
            "precision_score": read_metric("metrics/precision(B)", "metrics/precision", "precision"),
            "recall_score": read_metric("metrics/recall(B)", "metrics/recall", "recall"),
            "results_csv": results_csv,
        }
    except Exception as e:
        logger.warning(f"解析训练指标失败: {e}")
        return {}


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
            classes = [{"class_id": class_id, "name": name} for class_id, name in iter_class_names(names)]
        elif fmt == "YOLO":
            data_yaml_path = create_yolo_yaml_from_directory(actual_dir)
            if data_yaml_path:
                with open(data_yaml_path) as f:
                    cfg = yaml.safe_load(f)
                names = cfg.get("names", {})
                classes = [{"class_id": class_id, "name": name} for class_id, name in iter_class_names(names)]

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


@router.post("/inspect-dataset")
async def inspect_dataset_endpoint(req: InspectDatasetRequest):
    """准备并检查训练数据集结构，不启动训练。"""
    import uuid

    inspect_id = f"inspect_{uuid.uuid4().hex[:12]}"
    inspect_dir = os.path.join(BASE_DATASET_DIR, inspect_id)
    try:
        train_req = TrainRequest(
            task_id=inspect_id,
            model_name="dataset-inspection",
            dataset_source=req.dataset_source,
            dataset_uri=req.dataset_uri,
            download_command=req.download_command,
        )
        actual_dir = await prepare_dataset(train_req, inspect_dir)
        result = inspect_dataset_dir(actual_dir)
        result["datasetDir"] = actual_dir
        return {"success": True, "data": result}
    except Exception as e:
        logger.error(f"数据集预检失败: {e}", exc_info=True)
        return {
            "success": False,
            "message": str(e),
            "data": {
                "valid": False,
                "format": "UNKNOWN",
                "warnings": [str(e)]
            }
        }
    finally:
        try:
            if os.path.exists(inspect_dir):
                shutil.rmtree(inspect_dir)
        except Exception as cleanup_error:
            logger.warning(f"清理预检目录失败: {cleanup_error}")


async def run_yolo_training(
    data_yaml: str,
    output_dir: str,
    epochs: int,
    batch_size: int,
    image_size: int,
    learning_rate: float,
    use_pretrained: bool
) -> dict:
    """执行 YOLO 训练"""
    with open(data_yaml) as f:
        data_cfg = yaml.safe_load(f)
    names = data_cfg.get("names", {})

    train_cmd = (
        f"yolo detect train "
        f"data={data_yaml} "
        f"model={'yolov8n.pt' if use_pretrained else 'yolov8n.yaml'} "
        f"epochs={epochs} "
        f"batch={batch_size} "
        f"imgsz={image_size} "
        f"lr0={learning_rate} "
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

    classes = [{"class_id": class_id, "name": name, "cn_name": name}
               for class_id, name in iter_class_names(names)]

    metrics = parse_yolo_metrics(output_dir)

    return {
        "model_path": best_pt,
        "classes": classes,
        "map_score": metrics.get("map_score"),
        "precision_score": metrics.get("precision_score"),
        "recall_score": metrics.get("recall_score"),
        "results_csv": metrics.get("results_csv")
    }


async def run_full_pipeline(req: TrainRequest):
    """完整训练流水线：下载 → 转换 → 训练 → 回调"""
    task_id = req.task_id
    dataset_dir = os.path.join(BASE_DATASET_DIR, task_id)
    model_dir = os.path.join(BASE_MODEL_DIR, task_id)

    try:
        _update_status(task_id, "DOWNLOADING", f"准备数据源: {req.dataset_source}...", 0.1)
        actual_dir = await prepare_dataset(req, dataset_dir)

        _update_status(task_id, "CONVERTING", "检测数据集格式...", 0.35)
        fmt = detect_dataset_format(actual_dir)

        inspection = None
        if fmt == "YOLO":
            data_yaml = find_data_yaml(actual_dir) or create_yolo_yaml_from_directory(actual_dir)
            if not data_yaml:
                raise RuntimeError("未找到 YOLO YAML，也无法从 images/labels 自动生成")
            with open(data_yaml) as f:
                cfg = yaml.safe_load(f)
            cfg["path"] = os.path.dirname(data_yaml)
            with open(data_yaml, "w") as f:
                yaml.dump(cfg, f, allow_unicode=True, default_flow_style=False)
            inspection = inspect_yolo_dataset(data_yaml)
        elif fmt == "COCO":
            _update_status(task_id, "CONVERTING", "COCO → YOLO 转换中...", 0.45)
            yolo_dir = os.path.join(dataset_dir, "yolo_converted")
            data_yaml = convert_coco_to_yolo(actual_dir, yolo_dir)
            inspection = inspect_yolo_dataset(data_yaml)
        else:
            raise RuntimeError(f"不支持的数据集格式: {fmt}")

        train_params = {
            "epochs": req.epochs,
            "batch_size": req.batch_size,
            "image_size": req.image_size,
            "learning_rate": req.learning_rate,
            "use_pretrained": req.use_pretrained
        }
        if req.automl:
            train_params = choose_automl_params(inspection or {})
            _update_status(
                task_id,
                "CONVERTING",
                (
                    "AutoML 已读取 "
                    f"{inspection.get('classCount', 0) if inspection else 0} 个类别、"
                    f"{inspection.get('imageCount', 0) if inspection else 0} 张图片，"
                    f"选择 epochs={train_params['epochs']}, batch={train_params['batch_size']}, "
                    f"imgsz={train_params['image_size']}"
                ),
                0.52
            )

        _update_status(
            task_id,
            "TRAINING",
            f"YOLO 训练中 (epochs={train_params['epochs']}, imgsz={train_params['image_size']})...",
            0.6
        )
        result = await run_yolo_training(
            data_yaml,
            model_dir,
            train_params["epochs"],
            train_params["batch_size"],
            train_params["image_size"],
            train_params["learning_rate"],
            train_params["use_pretrained"]
        )

        _update_status(
            task_id,
            "COMPLETED",
            f"训练完成！mAP50: {result.get('map_score', 'N/A')}",
            1.0,
            model_path=result["model_path"],
            classes=result["classes"],
            map_score=result.get("map_score"),
            precision_score=result.get("precision_score"),
            recall_score=result.get("recall_score")
        )

        if req.callback_url:
            import httpx
            async with httpx.AsyncClient() as client:
                await client.post(req.callback_url, json={
                    "taskId": task_id,
                    "status": "COMPLETED",
                    "modelPath": result["model_path"],
                    "classes": result["classes"],
                    "mapScore": result.get("map_score"),
                    "precisionScore": result.get("precision_score"),
                    "recallScore": result.get("recall_score"),
                    "resultsCsv": result.get("results_csv")
                }, timeout=10)

    except Exception as e:
        logger.error(f"[{task_id}] 流水线失败: {e}", exc_info=True)
        _update_status(task_id, "FAILED", str(e), _task_status.get(task_id, TrainStatus(task_id=task_id, status="FAILED", message="", progress=0)).progress or 0)
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
    _update_status(req.task_id, "DOWNLOADING", "正在解析下载命令...", 0.05)
    background_tasks.add_task(run_full_pipeline, req)
    return {"success": True, "taskId": req.task_id, "message": "训练任务已提交"}


@router.get("/status/{task_id}")
async def get_training_status(task_id: str):
    """查询训练状态"""
    if task_id not in _task_status:
        return {"success": False, "message": "任务不存在"}
    return {"success": True, "data": _task_status[task_id].dict()}
