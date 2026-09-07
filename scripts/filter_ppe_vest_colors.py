#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any, Dict, List, Tuple

import cv2
import numpy as np

from render_ppe_detection_videos import DEFAULT_FONT, draw_boxes, load_font


DEFAULT_INPUT_DIR = Path("/root/autodl-fs/Annotation-Platform/output/ppe_detection_videos_20260609")
DEFAULT_OUTPUT_DIR = Path("/root/autodl-fs/Annotation-Platform/output/ppe_detection_videos_20260609_vest_color_filtered")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Filter reflective-vest detections by orange-red or fluorescent green/yellow-green color.")
    parser.add_argument("--input-dir", type=Path, default=DEFAULT_INPUT_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--min-color-ratio", type=float, default=0.06)
    parser.add_argument("--min-color-area", type=int, default=35)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def allowed_color_stats(crop_bgr: np.ndarray) -> Dict[str, Any]:
    if crop_bgr.size == 0:
        return {
            "ratio": 0.0,
            "area": 0,
            "total": 0,
            "green_yellow_area": 0,
            "orange_red_area": 0,
            "max_component_area": 0,
        }

    hsv = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2HSV)
    hue = hsv[:, :, 0]
    sat = hsv[:, :, 1]
    val = hsv[:, :, 2]

    bright = (sat >= 70) & (val >= 110)
    green_yellow = (hue >= 30) & (hue <= 90) & bright
    orange_red = (((hue >= 0) & (hue <= 25)) | (hue >= 165)) & bright
    mask = (green_yellow | orange_red).astype("uint8")

    component_count, _labels, stats, _centroids = cv2.connectedComponentsWithStats(mask, 8)
    max_component_area = 0
    if component_count > 1:
        max_component_area = int(stats[1:, cv2.CC_STAT_AREA].max())

    area = int(mask.sum())
    total = int(mask.size)
    return {
        "ratio": float(area / total) if total else 0.0,
        "area": area,
        "total": total,
        "green_yellow_area": int(green_yellow.sum()),
        "orange_red_area": int(orange_red.sum()),
        "max_component_area": max_component_area,
    }


def vest_color_score(frame_bgr: np.ndarray, bbox: List[float]) -> Dict[str, Any]:
    h, w = frame_bgr.shape[:2]
    x1, y1, x2, y2 = [int(round(v)) for v in bbox]
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(w, x2), min(h, y2)
    if x2 <= x1 or y2 <= y1:
        return {"keep": False, "ratio": 0.0, "area": 0, "reason": "empty_bbox"}

    width = x2 - x1
    height = y2 - y1
    torso_x1 = x1 + int(round(width * 0.20))
    torso_x2 = x1 + int(round(width * 0.80))
    torso_y1 = y1
    torso_y2 = y1 + int(round(height * 0.35))
    full_stats = allowed_color_stats(frame_bgr[y1:y2, x1:x2])
    torso_stats = allowed_color_stats(frame_bgr[torso_y1:torso_y2, torso_x1:torso_x2])
    return {
        "keep": False,
        "ratio": torso_stats["ratio"],
        "area": torso_stats["area"],
        "green_yellow_area": torso_stats["green_yellow_area"],
        "orange_red_area": torso_stats["orange_red_area"],
        "max_component_area": torso_stats["max_component_area"],
        "full_ratio": full_stats["ratio"],
        "full_area": full_stats["area"],
        "full_green_yellow_area": full_stats["green_yellow_area"],
        "full_orange_red_area": full_stats["orange_red_area"],
        "torso_roi": [torso_x1, torso_y1, torso_x2, torso_y2],
    }


def should_keep_vest(frame_bgr: np.ndarray, item: Dict[str, Any], min_ratio: float, min_area: int) -> Tuple[bool, Dict[str, Any]]:
    score = vest_color_score(frame_bgr, [float(v) for v in item["bbox"]])
    keep = score["ratio"] >= min_ratio and score["area"] >= min_area
    score["keep"] = keep
    score["min_ratio"] = min_ratio
    score["min_area"] = min_area
    return keep, score


def output_video_path(output_dir: Path, source_video: str, target_fps: float) -> Path:
    stem = Path(source_video).stem
    return output_dir / f"{stem}_ppe_detect_vest_color_{target_fps:g}fps.mp4"


def process(args: argparse.Namespace) -> List[Dict[str, Any]]:
    input_summary = json.loads((args.input_dir / "summary.json").read_text(encoding="utf-8"))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    font = load_font(DEFAULT_FONT)
    output_summary: List[Dict[str, Any]] = []

    for source in input_summary:
        target_fps = float(source["target_fps"])
        width = int(source["width"])
        height = int(source["height"])
        output_path = output_video_path(args.output_dir, source["video"], target_fps)
        detail_out = args.output_dir / f"{Path(source['video']).stem}_detections_color_filtered.jsonl"
        if output_path.exists() and not args.overwrite:
            raise RuntimeError(f"output exists; pass --overwrite: {output_path}")

        writer = cv2.VideoWriter(str(output_path), cv2.VideoWriter_fourcc(*"mp4v"), target_fps, (width, height))
        if not writer.isOpened():
            raise RuntimeError(f"cannot create output video: {output_path}")

        frame_dir = Path(source["sampled_frame_dir"])
        source_stem = Path(source["video"]).stem
        kept_helmet = 0
        kept_vest = 0
        dropped_vest = 0
        processed_frames = 0

        with Path(source["detections_jsonl"]).open(encoding="utf-8") as inp, detail_out.open("w", encoding="utf-8") as out:
            for line in inp:
                obj = json.loads(line)
                frame_path = frame_dir / f"{source_stem}_{int(obj['output_frame_index']):06d}.jpg"
                frame = cv2.imread(str(frame_path))
                if frame is None:
                    raise RuntimeError(f"missing frame: {frame_path}")

                filtered: List[Dict[str, Any]] = []
                filter_meta: List[Dict[str, Any]] = []
                for item in obj.get("detections") or []:
                    label = item.get("label")
                    if label == "安全帽":
                        filtered.append(item)
                        kept_helmet += 1
                    elif label == "反光衣":
                        keep, score = should_keep_vest(frame, item, args.min_color_ratio, args.min_color_area)
                        filter_meta.append({"bbox": item.get("bbox"), **score})
                        if keep:
                            filtered.append(item)
                            kept_vest += 1
                        else:
                            dropped_vest += 1

                rendered = draw_boxes(frame, filtered, font)
                writer.write(rendered)
                obj["detections"] = filtered
                obj["vest_color_filter"] = filter_meta
                out.write(json.dumps(obj, ensure_ascii=False) + "\n")
                processed_frames += 1

        writer.release()
        summary = dict(source)
        summary.update(
            {
                "output_video": str(output_path),
                "detections_jsonl": str(detail_out),
                "helmet_boxes": kept_helmet,
                "reflective_vest_boxes": kept_vest,
                "reflective_vest_boxes_dropped_by_color": dropped_vest,
                "vest_color_filter": {
                    "allowed_colors": ["橙红色", "绿色/黄绿色"],
                    "min_color_ratio": args.min_color_ratio,
                    "min_color_area": args.min_color_area,
                },
                "processed_frames": processed_frames,
            }
        )
        output_summary.append(summary)
        print(json.dumps({"event": "done_video", **summary}, ensure_ascii=False), flush=True)

    (args.output_dir / "summary.json").write_text(json.dumps(output_summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return output_summary


def main() -> None:
    args = parse_args()
    summary = process(args)
    print(json.dumps({"event": "complete", "summary": str(args.output_dir / "summary.json"), "videos": len(summary)}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
