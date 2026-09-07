from __future__ import annotations

from pathlib import Path
import subprocess
from typing import Any, Dict, List

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont


UPLOAD_ROOT = Path("/root/autodl-fs/uploads").resolve()

LABEL_COLORS = {
    "戴安全帽": (37, 99, 235),
    "没戴安全帽": (220, 38, 38),
    "反光衣": (22, 163, 74),
    "没穿反光衣": (245, 158, 11),
}
DEFAULT_COLORS = [
    (22, 163, 74),
    (37, 99, 235),
    (245, 158, 11),
    (220, 38, 38),
    (147, 51, 234),
    (8, 145, 178),
]


def render_annotated_video(payload: Dict[str, Any]) -> Dict[str, Any]:
    frames = [frame for frame in payload.get("frames") or [] if isinstance(frame, dict)]
    if not frames:
        return {
            "success": True,
            "available": False,
            "status": "NO_FRAMES",
            "reason": "no frames were supplied for annotated video rendering",
        }

    output_path = _safe_output_path(payload.get("output_path"))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    if payload.get("full_source_video") and payload.get("source_video_path"):
        return _render_full_source_video(payload, frames, output_path)

    tmp_path = output_path.with_suffix(".tmp.mp4")
    raw_tmp_path = output_path.with_suffix(".raw.mp4")
    if tmp_path.exists():
        tmp_path.unlink()
    if raw_tmp_path.exists():
        raw_tmp_path.unlink()

    first_image = _load_image(frames[0].get("image_path"))
    if first_image is None:
        return {
            "success": True,
            "available": False,
            "status": "FIRST_FRAME_UNREADABLE",
            "reason": f"first frame is unreadable: {frames[0].get('image_path')}",
        }

    height, width = first_image.shape[:2]
    fps = _positive_float(payload.get("fps"), 2.0)
    writer = cv2.VideoWriter(str(raw_tmp_path), cv2.VideoWriter_fourcc(*"mp4v"), fps, (width, height))
    if not writer.isOpened():
        return {
            "success": True,
            "available": False,
            "status": "WRITER_OPEN_FAILED",
            "reason": f"failed to open video writer: {raw_tmp_path}",
        }

    font = _font(max(18, min(width, height) // 34))
    frame_count = 0
    prediction_count = 0
    label_color_index: Dict[str, int] = {}

    try:
        for frame in frames:
            image = _load_image(frame.get("image_path"))
            if image is None:
                continue
            if image.shape[1] != width or image.shape[0] != height:
                image = cv2.resize(image, (width, height), interpolation=cv2.INTER_AREA)
            predictions = [item for item in frame.get("predictions") or [] if isinstance(item, dict)]
            prediction_count += len(predictions)
            writer.write(_draw_predictions(image, predictions, font, label_color_index))
            frame_count += 1
    finally:
        writer.release()

    if frame_count == 0:
        if raw_tmp_path.exists():
            raw_tmp_path.unlink()
        return {
            "success": True,
            "available": False,
            "status": "NO_READABLE_FRAMES",
            "reason": "none of the supplied frames could be read",
        }

    if _transcode_browser_mp4(raw_tmp_path, tmp_path, fps):
        tmp_path.replace(output_path)
    else:
        raw_tmp_path.replace(output_path)
    if raw_tmp_path.exists():
        raw_tmp_path.unlink()
    if tmp_path.exists():
        tmp_path.unlink()
    relative_path = str(output_path.relative_to(UPLOAD_ROOT))
    return {
        "success": True,
        "available": True,
        "status": "COMPLETED",
        "mode": str(payload.get("mode") or "sampled_keyframe_video"),
        "output_path": str(output_path),
        "output_relative_path": relative_path,
        "url": f"/api/v1/files/{relative_path}",
        "frame_count": frame_count,
        "prediction_count": prediction_count,
        "fps": fps,
        "width": width,
        "height": height,
    }


def _render_full_source_video(payload: Dict[str, Any], frames: List[Dict[str, Any]], output_path: Path) -> Dict[str, Any]:
    source_video_path = Path(str(payload.get("source_video_path"))).resolve()
    if not source_video_path.exists() or not source_video_path.is_file():
        return {
            "success": True,
            "available": False,
            "status": "SOURCE_VIDEO_NOT_FOUND",
            "reason": f"source video is unreadable: {source_video_path}",
        }

    cap = cv2.VideoCapture(str(source_video_path))
    if not cap.isOpened():
        return {
            "success": True,
            "available": False,
            "status": "SOURCE_VIDEO_OPEN_FAILED",
            "reason": f"failed to open source video: {source_video_path}",
        }

    fps = _positive_float(cap.get(cv2.CAP_PROP_FPS), 25.0)
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
    if width <= 0 or height <= 0:
        cap.release()
        return {
            "success": True,
            "available": False,
            "status": "SOURCE_VIDEO_BAD_DIMENSIONS",
            "reason": f"source video has invalid dimensions: {source_video_path}",
        }

    tmp_path = output_path.with_suffix(".tmp.mp4")
    raw_tmp_path = output_path.with_suffix(".raw.mp4")
    if tmp_path.exists():
        tmp_path.unlink()
    if raw_tmp_path.exists():
        raw_tmp_path.unlink()

    writer = cv2.VideoWriter(str(raw_tmp_path), cv2.VideoWriter_fourcc(*"mp4v"), fps, (width, height))
    if not writer.isOpened():
        cap.release()
        return {
            "success": True,
            "available": False,
            "status": "WRITER_OPEN_FAILED",
            "reason": f"failed to open video writer: {raw_tmp_path}",
        }

    keyframes = sorted(
        [frame for frame in frames if isinstance(frame, dict)],
        key=lambda item: _positive_float(item.get("timestamp_sec"), 0.0),
    )
    keyframe_times = [_positive_float(frame.get("timestamp_sec"), 0.0) for frame in keyframes]
    keyframe_prediction_count = sum(len(frame.get("predictions") or []) for frame in keyframes)
    font = _font(max(18, min(width, height) // 34))
    label_color_index: Dict[str, int] = {}
    frame_count = 0
    active_index = -1

    try:
        while True:
            ok, frame = cap.read()
            if not ok or frame is None:
                break
            timestamp_sec = frame_count / fps if fps > 0 else 0.0
            while active_index + 1 < len(keyframe_times) and timestamp_sec >= keyframe_times[active_index + 1]:
                active_index += 1
            predictions: List[Dict[str, Any]] = []
            if active_index >= 0:
                predictions = [
                    item for item in keyframes[active_index].get("predictions") or [] if isinstance(item, dict)
                ]
            writer.write(_draw_predictions(frame, predictions, font, label_color_index))
            frame_count += 1
    finally:
        writer.release()
        cap.release()

    if frame_count == 0:
        if raw_tmp_path.exists():
            raw_tmp_path.unlink()
        return {
            "success": True,
            "available": False,
            "status": "NO_SOURCE_FRAMES",
            "reason": "source video had no readable frames",
        }

    if _transcode_browser_mp4(raw_tmp_path, tmp_path, fps):
        tmp_path.replace(output_path)
    else:
        raw_tmp_path.replace(output_path)
    if raw_tmp_path.exists():
        raw_tmp_path.unlink()
    if tmp_path.exists():
        tmp_path.unlink()

    relative_path = str(output_path.relative_to(UPLOAD_ROOT))
    return {
        "success": True,
        "available": True,
        "status": "COMPLETED",
        "mode": "full_source_video",
        "source_video_id": payload.get("source_video_id"),
        "source_video_name": payload.get("source_video_name"),
        "output_path": str(output_path),
        "output_relative_path": relative_path,
        "url": f"/api/v1/files/{relative_path}",
        "frame_count": frame_count,
        "keyframe_count": len(keyframes),
        "prediction_count": keyframe_prediction_count,
        "fps": fps,
        "width": width,
        "height": height,
    }


def _safe_output_path(raw_path: Any) -> Path:
    if not raw_path:
        raise ValueError("output_path is required")
    path = Path(str(raw_path)).expanduser().resolve()
    if not path.is_relative_to(UPLOAD_ROOT):
        raise ValueError(f"output_path must be under {UPLOAD_ROOT}")
    return path


def _transcode_browser_mp4(input_path: Path, output_path: Path, fps: float) -> bool:
    commands = [
        [
            "ffmpeg",
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(input_path),
            "-r",
            str(fps),
            "-an",
            "-c:v",
            "h264_nvenc",
            "-preset",
            "p1",
            "-tune",
            "ull",
            "-rc",
            "vbr",
            "-cq",
            "23",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            str(output_path),
        ],
        [
            "ffmpeg",
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(input_path),
            "-r",
            str(fps),
            "-an",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-crf",
            "23",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            str(output_path),
        ],
    ]
    for command in commands:
        try:
            subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            return output_path.exists() and output_path.stat().st_size > 1024
        except (FileNotFoundError, subprocess.CalledProcessError):
            if output_path.exists():
                output_path.unlink()
            continue
    return False


def _load_image(path_value: Any) -> np.ndarray | None:
    if not path_value:
        return None
    path = Path(str(path_value))
    if not path.exists() or not path.is_file():
        return None
    image = cv2.imread(str(path))
    return image


def _font(size: int) -> ImageFont.ImageFont:
    candidates = [
        "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    for path in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size=size)
    return ImageFont.load_default()


def _draw_predictions(
    frame_bgr: np.ndarray,
    predictions: List[Dict[str, Any]],
    font: ImageFont.ImageFont,
    label_color_index: Dict[str, int],
) -> np.ndarray:
    image = Image.fromarray(cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(image)
    width, height = frame_bgr.shape[1], frame_bgr.shape[0]
    line_width = max(2, min(width, height) // 360)

    for prediction in predictions:
        label = str(prediction.get("label") or "目标")
        bbox = _bbox(prediction.get("bbox") or prediction.get("bbox_xyxy"))
        if not bbox:
            continue
        x1, y1, x2, y2 = _clip_bbox(bbox, width, height)
        if x2 <= x1 or y2 <= y1:
            continue
        color = _color(label, label_color_index)
        draw.rectangle([x1, y1, x2, y2], outline=color, width=line_width)
        text = label
        text_box = draw.textbbox((0, 0), text, font=font, stroke_width=1)
        text_w = text_box[2] - text_box[0]
        text_h = text_box[3] - text_box[1]
        pad_x = 6
        pad_y = 4
        label_y = y1 - text_h - pad_y * 2
        if label_y < 0:
            label_y = y1
        bg = [
            x1,
            label_y,
            min(x1 + text_w + pad_x * 2, width - 1),
            min(label_y + text_h + pad_y * 2, height - 1),
        ]
        draw.rectangle(bg, fill=color)
        draw.text(
            (bg[0] + pad_x, bg[1] + pad_y),
            text,
            fill=(255, 255, 255),
            font=font,
            stroke_width=1,
            stroke_fill=color,
        )
    return cv2.cvtColor(np.array(image), cv2.COLOR_RGB2BGR)


def _bbox(value: Any) -> List[float] | None:
    if not isinstance(value, list) or len(value) < 4:
        return None
    try:
        return [float(value[0]), float(value[1]), float(value[2]), float(value[3])]
    except (TypeError, ValueError):
        return None


def _clip_bbox(bbox: List[float], width: int, height: int) -> List[float]:
    x1, y1, x2, y2 = bbox
    return [
        max(0.0, min(x1, width - 1.0)),
        max(0.0, min(y1, height - 1.0)),
        max(0.0, min(x2, width - 1.0)),
        max(0.0, min(y2, height - 1.0)),
    ]


def _color(label: str, label_color_index: Dict[str, int]) -> tuple[int, int, int]:
    if label in LABEL_COLORS:
        return LABEL_COLORS[label]
    if label not in label_color_index:
        label_color_index[label] = len(label_color_index)
    return DEFAULT_COLORS[label_color_index[label] % len(DEFAULT_COLORS)]


def _positive_float(value: Any, default: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default
