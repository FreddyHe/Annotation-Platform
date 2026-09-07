#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path
from typing import Any, Dict, Iterable, List, Tuple

import cv2
import numpy as np
import requests
from PIL import Image, ImageDraw, ImageFont
from ultralytics import YOLO


DEFAULT_VIDEO_DIR = Path("/root/autodl-fs/Annotation-Platform/output/ppe_video_run/unzipped")
DEFAULT_OUTPUT_DIR = Path("/root/autodl-fs/Annotation-Platform/output/ppe_detection_videos_20260609")
DEFAULT_HELMET_WEIGHTS = Path(
    "/root/autodl-fs/xingmu_model/releases/v0.4.5_final_public_demo/weights/m9_ppe_public_v5/focused/best.pt"
)
DEFAULT_FONT = Path("/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf")


COLORS = {
    "反光衣": (255, 170, 0),
    "安全帽": (0, 210, 90),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Render PPE detection videos for reflective vest and safety helmet.")
    parser.add_argument("--video-dir", type=Path, default=DEFAULT_VIDEO_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--target-fps", type=float, default=2.0)
    parser.add_argument("--helmet-weights", type=Path, default=DEFAULT_HELMET_WEIGHTS)
    parser.add_argument("--helmet-conf", type=float, default=0.25)
    parser.add_argument("--locate-url", default="http://127.0.0.1:5010/predict")
    parser.add_argument("--locate-timeout", type=int, default=180)
    parser.add_argument("--locate-mode", default="detect", choices=["detect", "ground_multi", "ground_single"])
    parser.add_argument(
        "--vest-label",
        action="append",
        default=None,
        help="Open-vocabulary LocateAnything label for reflective vests. Can be repeated.",
    )
    parser.add_argument("--vest-output-label", default="反光衣")
    parser.add_argument("--vest-color-gate", action="store_true")
    parser.add_argument("--vest-color-min-ratio", type=float, default=0.06)
    parser.add_argument("--vest-color-min-area", type=int, default=20)
    parser.add_argument("--device", default="0")
    parser.add_argument("--max-new-tokens", type=int, default=256)
    parser.add_argument("--font", type=Path, default=DEFAULT_FONT)
    parser.add_argument("--max-sampled-frames", type=int, default=0)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def video_files(video_dir: Path) -> List[Path]:
    files: List[Path] = []
    for suffix in ("*.mp4", "*.MP4", "*.avi", "*.AVI", "*.mov", "*.MOV", "*.mkv", "*.MKV"):
        files.extend(video_dir.glob(suffix))
    return sorted(set(files))


def draw_boxes(frame_bgr: np.ndarray, boxes: Iterable[Dict[str, Any]], font: ImageFont.ImageFont) -> np.ndarray:
    image = Image.fromarray(cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(image)
    for item in boxes:
        label = str(item["label"])
        score = float(item.get("score", 0.0))
        x1, y1, x2, y2 = [int(round(v)) for v in item["bbox"]]
        color = COLORS.get(label, (255, 255, 255))
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = max(x1 + 1, x2), max(y1 + 1, y2)
        draw.rectangle([x1, y1, x2, y2], outline=color, width=3)
        text = label
        bbox = draw.textbbox((x1, max(0, y1 - 24)), text, font=font)
        bg = [bbox[0] - 2, bbox[1] - 2, bbox[2] + 2, bbox[3] + 2]
        draw.rectangle(bg, fill=color)
        draw.text((bbox[0], bbox[1]), text, fill=(0, 0, 0), font=font)
    return cv2.cvtColor(np.asarray(image), cv2.COLOR_RGB2BGR)


def load_font(path: Path) -> ImageFont.ImageFont:
    if path.exists():
        return ImageFont.truetype(str(path), 22)
    return ImageFont.load_default()


def helmet_boxes(model: YOLO, frame_bgr: np.ndarray, conf: float, device: str) -> List[Dict[str, Any]]:
    results = model.predict(frame_bgr, imgsz=640, conf=conf, device=device, verbose=False)
    boxes: List[Dict[str, Any]] = []
    for result in results:
        names = result.names or {}
        if result.boxes is None:
            continue
        for box in result.boxes:
            cls_id = int(box.cls.item())
            cls_name = str(names.get(cls_id, cls_id))
            if cls_name != "helmet":
                continue
            x1, y1, x2, y2 = [float(v) for v in box.xyxy[0].tolist()]
            score = float(box.conf.item())
            boxes.append(
                {
                    "label": "安全帽",
                    "bbox": [x1, y1, x2, y2],
                    "score": score,
                    "source": "xingmu-m9-ppe-focused",
                }
            )
    return boxes


def locate_reflective_vest(
    frame_path: Path,
    locate_url: str,
    timeout: int,
    max_new_tokens: int,
    labels: List[str],
    output_label: str,
    mode: str,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    payload = {
        "image_path": str(frame_path),
        "labels": labels,
        "mode": mode,
        "max_new_tokens": max_new_tokens,
    }
    try:
        response = requests.post(locate_url, json=payload, timeout=timeout)
        raw: Dict[str, Any] = {
            "http_status": response.status_code,
            "elapsed_ms": None,
        }
        if response.status_code != 200:
            raw["error"] = response.text[:500]
            return [], raw
        data = response.json()
        raw.update(
            {
                "status": data.get("status"),
                "candidate_count": data.get("candidate_count"),
                "elapsed_ms": data.get("elapsed_ms"),
                "answer": data.get("answer"),
                "labels": labels,
                "mode": mode,
            }
        )
        boxes: List[Dict[str, Any]] = []
        for candidate in data.get("candidates") or []:
            label = str(candidate.get("label") or output_label)
            if "反光" not in label and "vest" not in label.lower():
                continue
            bbox = candidate.get("bbox_xyxy") or []
            if len(bbox) != 4:
                continue
            boxes.append(
                {
                    "label": output_label,
                    "prompt_label": label,
                    "bbox": [float(v) for v in bbox],
                    "score": float(candidate.get("score") or 0.65),
                    "source": "local-locate-anything-3b",
                }
            )
        return boxes, raw
    except Exception as exc:
        return [], {"error": str(exc), "http_status": None}


def allowed_vest_color_score(frame_bgr: np.ndarray, bbox: List[float]) -> Dict[str, Any]:
    h, w = frame_bgr.shape[:2]
    x1, y1, x2, y2 = [int(round(v)) for v in bbox]
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(w, x2), min(h, y2)
    if x2 <= x1 or y2 <= y1:
        return {"ratio": 0.0, "area": 0, "reason": "empty_bbox"}

    width = x2 - x1
    height = y2 - y1
    roi_x1 = x1 + int(round(width * 0.20))
    roi_x2 = x1 + int(round(width * 0.80))
    roi_y1 = y1
    roi_y2 = y1 + int(round(height * 0.35))
    crop = frame_bgr[roi_y1:roi_y2, roi_x1:roi_x2]
    if crop.size == 0:
        return {"ratio": 0.0, "area": 0, "reason": "empty_roi", "torso_roi": [roi_x1, roi_y1, roi_x2, roi_y2]}

    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    hue = hsv[:, :, 0]
    sat = hsv[:, :, 1]
    val = hsv[:, :, 2]
    bright = (sat >= 70) & (val >= 110)
    green_yellow = (hue >= 30) & (hue <= 90) & bright
    orange_yellow = (hue >= 10) & (hue <= 35) & bright
    mask = green_yellow | orange_yellow
    area = int(mask.sum())
    total = int(mask.size)
    return {
        "ratio": float(area / total) if total else 0.0,
        "area": area,
        "green_yellow_area": int(green_yellow.sum()),
        "orange_yellow_area": int(orange_yellow.sum()),
        "torso_roi": [roi_x1, roi_y1, roi_x2, roi_y2],
    }


def apply_vest_color_gate(
    frame_bgr: np.ndarray,
    boxes: List[Dict[str, Any]],
    min_ratio: float,
    min_area: int,
) -> Tuple[List[Dict[str, Any]], int]:
    kept: List[Dict[str, Any]] = []
    dropped = 0
    for item in boxes:
        score = allowed_vest_color_score(frame_bgr, [float(v) for v in item["bbox"]])
        keep = score.get("ratio", 0.0) >= min_ratio and int(score.get("area", 0)) >= min_area
        item["vest_color_gate"] = {**score, "keep": keep, "min_ratio": min_ratio, "min_area": min_area}
        if keep:
            kept.append(item)
        else:
            dropped += 1
    return kept, dropped


def source_fps(capture: cv2.VideoCapture) -> float:
    fps = float(capture.get(cv2.CAP_PROP_FPS) or 0.0)
    if not math.isfinite(fps) or fps <= 0:
        return 25.0
    return fps


def process_video(
    video_path: Path,
    output_dir: Path,
    target_fps: float,
    helmet_model: YOLO,
    args: argparse.Namespace,
    font: ImageFont.ImageFont,
) -> Dict[str, Any]:
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise RuntimeError(f"cannot open video: {video_path}")

    fps = source_fps(cap)
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    sample_step = max(1, int(round(fps / target_fps)))
    effective_fps = fps / sample_step
    vest_labels = args.vest_label or ["反光衣"]

    output_dir.mkdir(parents=True, exist_ok=True)
    frame_dir = output_dir / "frames" / video_path.stem
    frame_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"{video_path.stem}_ppe_detect_{target_fps:g}fps.mp4"
    detail_path = output_dir / f"{video_path.stem}_detections.jsonl"
    if output_path.exists() and not args.overwrite:
        raise RuntimeError(f"output exists; pass --overwrite: {output_path}")

    writer = cv2.VideoWriter(str(output_path), cv2.VideoWriter_fourcc(*"mp4v"), target_fps, (width, height))
    if not writer.isOpened():
        raise RuntimeError(f"cannot create output video: {output_path}")

    processed = 0
    reflective_count = 0
    reflective_dropped_by_color = 0
    helmet_count = 0
    start = time.time()
    with detail_path.open("w", encoding="utf-8") as detail:
        frame_index = -1
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            frame_index += 1
            if frame_index % sample_step != 0:
                continue

            sampled_name = f"{video_path.stem}_{processed:06d}.jpg"
            sampled_path = frame_dir / sampled_name
            cv2.imwrite(str(sampled_path), frame)

            boxes = helmet_boxes(helmet_model, frame, args.helmet_conf, args.device)
            vest_boxes, locate_raw = locate_reflective_vest(
                sampled_path,
                args.locate_url,
                args.locate_timeout,
                args.max_new_tokens,
                vest_labels,
                args.vest_output_label,
                args.locate_mode,
            )
            if args.vest_color_gate:
                vest_boxes, dropped = apply_vest_color_gate(
                    frame,
                    vest_boxes,
                    args.vest_color_min_ratio,
                    args.vest_color_min_area,
                )
                reflective_dropped_by_color += dropped
            boxes.extend(vest_boxes)
            reflective_count += len(vest_boxes)
            helmet_count += sum(1 for item in boxes if item["label"] == "安全帽")

            annotated = draw_boxes(frame, boxes, font)
            writer.write(annotated)
            detail.write(
                json.dumps(
                    {
                        "video": str(video_path),
                        "source_frame_index": frame_index,
                        "output_frame_index": processed,
                        "time_sec": frame_index / fps,
                        "detections": boxes,
                        "locate_raw": locate_raw,
                    },
                    ensure_ascii=False,
                )
                + "\n"
            )
            processed += 1
            if args.max_sampled_frames and processed >= args.max_sampled_frames:
                break
            if processed % 20 == 0:
                elapsed = time.time() - start
                print(
                    json.dumps(
                        {
                            "video": video_path.name,
                            "processed_frames": processed,
                            "source_frame_index": frame_index,
                            "source_total_frames": frame_count,
                            "elapsed_sec": round(elapsed, 1),
                            "helmet_boxes": helmet_count,
                            "reflective_vest_boxes": reflective_count,
                        },
                        ensure_ascii=False,
                    ),
                    flush=True,
                )

    cap.release()
    writer.release()
    elapsed = time.time() - start
    summary = {
        "video": str(video_path),
        "output_video": str(output_path),
        "detections_jsonl": str(detail_path),
        "sampled_frame_dir": str(frame_dir),
        "source_fps": fps,
        "target_fps": target_fps,
        "effective_source_sample_fps": effective_fps,
        "sample_step": sample_step,
        "source_frames": frame_count,
        "processed_frames": processed,
        "width": width,
        "height": height,
        "helmet_boxes": helmet_count,
        "reflective_vest_boxes": reflective_count,
        "reflective_vest_boxes_dropped_by_color_gate": reflective_dropped_by_color,
        "elapsed_sec": round(elapsed, 2),
        "route": {
            args.vest_output_label: "local-locate-anything-3b",
            "安全帽": "xingmu-m9-ppe-focused",
        },
        "route_prompt": {
            args.vest_output_label: vest_labels,
            "安全帽": ["helmet"],
        },
        "locate_mode": args.locate_mode,
        "vest_color_gate": {
            "enabled": bool(args.vest_color_gate),
            "min_ratio": args.vest_color_min_ratio,
            "min_area": args.vest_color_min_area,
            "allowed_colors": ["绿色/黄绿色", "橙黄色"],
        },
    }
    return summary


def main() -> None:
    args = parse_args()
    videos = video_files(args.video_dir)
    if not videos:
        raise SystemExit(f"no videos found in {args.video_dir}")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    font = load_font(args.font)
    helmet_model = YOLO(str(args.helmet_weights))
    summaries: List[Dict[str, Any]] = []
    for video in videos:
        print(json.dumps({"event": "start_video", "video": str(video)}, ensure_ascii=False), flush=True)
        summaries.append(process_video(video, args.output_dir, args.target_fps, helmet_model, args, font))
        summary_path = args.output_dir / "summary.json"
        summary_path.write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"event": "done_video", **summaries[-1]}, ensure_ascii=False), flush=True)
    print(json.dumps({"event": "complete", "summary": str(args.output_dir / "summary.json")}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
