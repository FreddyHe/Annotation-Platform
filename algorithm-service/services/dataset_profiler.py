from pathlib import Path
from typing import Any, Dict, List

from PIL import Image


def profile_dataset(payload: Dict[str, Any]) -> Dict[str, Any]:
    image_paths = [str(path) for path in payload.get("image_paths") or [] if path]
    num_images = int(payload.get("num_images") or len(image_paths) or 0)
    sample = image_paths[: min(len(image_paths), 50)]

    corrupt = 0
    sizes = []
    duplicate_names = []
    seen_names = set()
    for path in sample:
        name = Path(path).name
        if name in seen_names:
            duplicate_names.append(name)
        seen_names.add(name)
        try:
            with Image.open(path) as image:
                sizes.append({"path": path, "width": image.width, "height": image.height})
        except Exception:
            corrupt += 1

    warnings = []
    if num_images == 0:
        warnings.append({"code": "NO_IMAGES", "message": "未提供图片路径或图片数量，画像仅返回空数据集状态。"})
    if num_images and num_images < 20:
        warnings.append({"code": "SMALL_DATASET", "message": "图片数量较少，建议提高人工复核比例。"})
    if corrupt:
        warnings.append({"code": "CORRUPT_SAMPLE_IMAGES", "message": f"抽样中有 {corrupt} 张图片无法打开。"})
    if duplicate_names:
        warnings.append({"code": "DUPLICATE_FILENAMES", "message": f"抽样中存在重复文件名 {len(duplicate_names)} 个。"})

    return {
        "success": True,
        "available": True,
        "profiler": "lightweight_local",
        "external_api_used": False,
        "dataset_id": payload.get("dataset_id"),
        "num_images": num_images,
        "sample_count": len(sample) if sample else min(num_images, 50),
        "resolution_samples": sizes,
        "quality_warnings": warnings,
        "scene_guess": "unknown",
    }
