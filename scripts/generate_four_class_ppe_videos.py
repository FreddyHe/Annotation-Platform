#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import torch
from PIL import Image, ImageDraw, ImageFont
from ultralytics import YOLO


DEFAULT_INPUTS = [
    Path("/root/autodl-fs/Annotation-Platform/output/ppe_video_run/unzipped/20260506150900MEDIA_IDOU006.MP4"),
    Path("/root/autodl-fs/Annotation-Platform/output/ppe_video_run/unzipped/20260514103129MEDIA_IDUser001.MP4"),
    Path("/root/autodl-fs/Annotation-Platform/output/ppe_video_run/unzipped/20260528152912MEDIA_IDUser001.MP4"),
]
DEFAULT_OUTPUT_DIR = Path("/root/autodl-fs/Annotation-Platform/output/four_class_ppe_videos_20260610")
HELMET_MODEL = Path("/root/autodl-fs/xingmu_model/weights/m9_ppe_public_v5/focused/best.pt")
VEST_MODEL = Path("/root/autodl-fs/xingmu_model/weights/m9_reflective_vest_public_v1/direct/best.pt")

LABEL_STYLES = {
    "戴安全帽": {"color": (37, 99, 235), "source": "helmet"},
    "没戴安全帽": {"color": (220, 38, 38), "source": "helmet"},
    "反光衣": {"color": (22, 163, 74), "source": "vest"},
    "没穿反光衣": {"color": (245, 158, 11), "source": "vest"},
}


def find_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/root/autodl-fs/external/Eagle/Eagle2_5/streamlit_demo/static/SimHei.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    for path in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size=size)
    return ImageFont.load_default()


def predict_with_backoff(model: YOLO, frames: list[np.ndarray], **kwargs: Any) -> list[Any]:
    try:
        return list(model.predict(frames, verbose=False, **kwargs))
    except RuntimeError as exc:
        if "out of memory" not in str(exc).lower() or len(frames) <= 1:
            raise
        torch.cuda.empty_cache()
        mid = max(1, len(frames) // 2)
        return predict_with_backoff(model, frames[:mid], **kwargs) + predict_with_backoff(model, frames[mid:], **kwargs)


def result_boxes(result: Any, label_map: dict[str, str]) -> list[dict[str, Any]]:
    detections: list[dict[str, Any]] = []
    names = getattr(result, "names", {}) or {}
    boxes = getattr(result, "boxes", None)
    if boxes is None:
        return detections
    for box in boxes:
        cls_id = int(box.cls.item())
        class_name = str(names.get(cls_id, cls_id))
        label = label_map.get(class_name)
        if not label:
            continue
        xyxy = [float(v) for v in box.xyxy[0].detach().cpu().tolist()]
        score = float(box.conf.item())
        detections.append({"label": label, "bbox": xyxy, "score": score})
    return detections


def draw_detections(frame_bgr: np.ndarray, detections: list[dict[str, Any]], font: ImageFont.ImageFont) -> np.ndarray:
    image = Image.fromarray(cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(image)
    line_width = max(2, min(frame_bgr.shape[:2]) // 360)
    for det in detections:
        label = det["label"]
        color = LABEL_STYLES[label]["color"]
        x1, y1, x2, y2 = det["bbox"]
        x1 = max(0, min(float(x1), frame_bgr.shape[1] - 1))
        x2 = max(0, min(float(x2), frame_bgr.shape[1] - 1))
        y1 = max(0, min(float(y1), frame_bgr.shape[0] - 1))
        y2 = max(0, min(float(y2), frame_bgr.shape[0] - 1))
        if x2 <= x1 or y2 <= y1:
            continue
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
        bg = [x1, label_y, min(x1 + text_w + pad_x * 2, frame_bgr.shape[1] - 1), label_y + text_h + pad_y * 2]
        draw.rectangle(bg, fill=color)
        draw.text((bg[0] + pad_x, bg[1] + pad_y), text, fill=(255, 255, 255), font=font, stroke_width=1, stroke_fill=color)
    return cv2.cvtColor(np.array(image), cv2.COLOR_RGB2BGR)


def process_video(
    input_path: Path,
    output_path: Path,
    helmet_model: YOLO,
    vest_model: YOLO,
    device: str,
    batch_size: int,
    helmet_conf: float,
    vest_conf: float,
    imgsz: int,
    max_frames: int | None = None,
) -> dict[str, Any]:
    cap = cv2.VideoCapture(str(input_path))
    if not cap.isOpened():
        raise RuntimeError(f"failed to open video: {input_path}")
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = float(cap.get(cv2.CAP_PROP_FPS) or 25.0)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    expected_total = min(total, max_frames) if max_frames else total
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = output_path.with_suffix(".tmp.mp4")
    writer = cv2.VideoWriter(str(temp_path), cv2.VideoWriter_fourcc(*"mp4v"), fps, (width, height))
    if not writer.isOpened():
        cap.release()
        raise RuntimeError(f"failed to open writer: {temp_path}")

    font = find_font(max(18, min(width, height) // 34))
    counts: Counter[str] = Counter()
    frame_index = 0
    helmet_labels = {"helmet": "戴安全帽", "no_helmet": "没戴安全帽"}
    vest_labels = {"safety_vest": "反光衣", "no_safety_vest": "没穿反光衣"}

    print(f"[start] {input_path.name}: {width}x{height}, fps={fps:.3f}, frames={expected_total or total}")
    while True:
        frames: list[np.ndarray] = []
        for _ in range(batch_size):
            if max_frames is not None and frame_index + len(frames) >= max_frames:
                break
            ok, frame = cap.read()
            if not ok:
                break
            frames.append(frame)
        if not frames:
            break

        helmet_results = predict_with_backoff(
            helmet_model,
            frames,
            conf=helmet_conf,
            imgsz=imgsz,
            device=device,
            half=device != "cpu",
        )
        vest_results = predict_with_backoff(
            vest_model,
            frames,
            conf=vest_conf,
            imgsz=imgsz,
            device=device,
            half=device != "cpu",
            classes=[1, 2],
        )

        for frame, helmet_result, vest_result in zip(frames, helmet_results, vest_results):
            detections = result_boxes(helmet_result, helmet_labels) + result_boxes(vest_result, vest_labels)
            for det in detections:
                counts[det["label"]] += 1
            writer.write(draw_detections(frame, detections, font))
            frame_index += 1
        if frame_index % 100 == 0 or (expected_total and frame_index >= expected_total):
            print(f"[progress] {input_path.name}: {frame_index}/{expected_total or total or '?'} frames, counts={dict(counts)}", flush=True)
        if max_frames is not None and frame_index >= max_frames:
            break

    cap.release()
    writer.release()
    temp_path.replace(output_path)
    return {
        "input": str(input_path),
        "output": str(output_path),
        "width": width,
        "height": height,
        "fps": fps,
        "frames": frame_index,
        "detections": dict(counts),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--imgsz", type=int, default=960)
    parser.add_argument("--helmet-conf", type=float, default=0.25)
    parser.add_argument("--vest-conf", type=float, default=0.21)
    parser.add_argument("--max-frames", type=int, default=None)
    parser.add_argument("videos", nargs="*", type=Path, default=DEFAULT_INPUTS)
    args = parser.parse_args()

    missing = [path for path in [HELMET_MODEL, VEST_MODEL, *args.videos] if not path.exists()]
    if missing:
        raise FileNotFoundError("missing required files: " + ", ".join(str(path) for path in missing))

    device = "0" if torch.cuda.is_available() else "cpu"
    print(f"[device] {device}")
    helmet_model = YOLO(str(HELMET_MODEL))
    vest_model = YOLO(str(VEST_MODEL))

    summaries = []
    for video in args.videos:
        output = args.output_dir / f"{video.stem}_four_class_ppe.mp4"
        summaries.append(
            process_video(
                input_path=video,
                output_path=output,
                helmet_model=helmet_model,
                vest_model=vest_model,
                device=device,
                batch_size=max(1, args.batch_size),
                helmet_conf=args.helmet_conf,
                vest_conf=args.vest_conf,
                imgsz=args.imgsz,
                max_frames=args.max_frames,
            )
        )
    summary_path = args.output_dir / "summary.json"
    summary_path.write_text(json.dumps({"videos": summaries}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[done] summary: {summary_path}")


if __name__ == "__main__":
    main()
