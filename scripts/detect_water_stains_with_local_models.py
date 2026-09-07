#!/usr/bin/env python3
"""Run local Grounding DINO and LocateAnything over a directory of images."""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

import requests
from PIL import Image, ImageDraw, ImageFont


DINO_PROMPT = "water stain . wet patch . puddle ."
LOCATE_PROMPT = "the most prominent water stain, wet patch, or puddle on the floor"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("input_dir", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--dino-url", default="http://127.0.0.1:5003/predict")
    parser.add_argument("--locate-url", default="http://127.0.0.1:5010/predict")
    parser.add_argument("--box-threshold", type=float, default=0.22)
    parser.add_argument("--text-threshold", type=float, default=0.18)
    return parser.parse_args()


def image_files(path: Path) -> list[Path]:
    supported = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
    return sorted((item for item in path.iterdir() if item.suffix.lower() in supported), key=lambda p: p.name)


def run_dino(path: Path, url: str, box_threshold: float, text_threshold: float) -> dict[str, Any]:
    started = time.monotonic()
    with path.open("rb") as image_file:
        response = requests.post(
            url,
            files={"image": (path.name, image_file, "application/octet-stream")},
            data={
                "text_prompt": DINO_PROMPT,
                "box_threshold": str(box_threshold),
                "text_threshold": str(text_threshold),
            },
            timeout=180,
        )
    response.raise_for_status()
    raw = response.json()
    with Image.open(path) as image:
        width, height = image.size
    detections = []
    for item in raw.get("detections") or []:
        cx, cy, box_width, box_height = [float(value) for value in item["box"]]
        detections.append(
            {
                "label": "water stain on the ground",
                "model_phrase": item.get("label"),
                "score": float(item.get("logit_score") or 0.0),
                "bbox_xyxy": [
                    round((cx - box_width / 2) * width, 2),
                    round((cy - box_height / 2) * height, 2),
                    round((cx + box_width / 2) * width, 2),
                    round((cy + box_height / 2) * height, 2),
                ],
            }
        )
    detections = postprocess_detections(detections, width, height)
    return {
        "model": "grounding-dino",
        "image": path.name,
        "prompt": DINO_PROMPT,
        "elapsed_ms": round((time.monotonic() - started) * 1000),
        "detection_count": len(detections),
        "detections": detections,
        "raw_response": raw,
    }


def run_locate(path: Path, url: str) -> dict[str, Any]:
    started = time.monotonic()
    response = requests.post(
        url,
        json={
            "image_path": str(path.resolve()),
            "labels": [LOCATE_PROMPT],
            "mode": "ground_single",
            "max_new_tokens": 96,
            "temperature": 0.05,
        },
        timeout=300,
    )
    response.raise_for_status()
    raw = response.json()
    detections = []
    for item in raw.get("candidates") or []:
        detections.append(
            {
                "label": "water stain on the ground",
                "model_phrase": item.get("label"),
                "score": float(item.get("score") or 0.0),
                "bbox_xyxy": [float(value) for value in item.get("bbox_xyxy") or []],
            }
        )
    with Image.open(path) as image:
        width, height = image.size
    detections = postprocess_detections(detections, width, height)
    return {
        "model": "local-locate-anything-3b",
        "image": path.name,
        "prompt": LOCATE_PROMPT,
        "elapsed_ms": int(raw.get("elapsed_ms") or round((time.monotonic() - started) * 1000)),
        "detection_count": len(detections),
        "detections": detections,
        "raw_response": raw,
    }


def postprocess_detections(
    detections: list[dict[str, Any]], image_width: int, image_height: int
) -> list[dict[str, Any]]:
    """Remove whole-image failures and suppress duplicate boxes for visualization."""
    image_area = float(image_width * image_height)
    valid = []
    for detection in detections:
        box = detection.get("bbox_xyxy") or []
        if len(box) != 4:
            continue
        x1, y1, x2, y2 = box
        box_area = max(0.0, x2 - x1) * max(0.0, y2 - y1)
        if image_area and box_area / image_area >= 0.85:
            continue
        valid.append(detection)
    kept = []
    for detection in sorted(valid, key=lambda item: float(item.get("score") or 0.0), reverse=True):
        if all(box_iou(detection["bbox_xyxy"], item["bbox_xyxy"]) < 0.55 for item in kept):
            kept.append(detection)
    return kept


def box_iou(left: list[float], right: list[float]) -> float:
    x1 = max(left[0], right[0])
    y1 = max(left[1], right[1])
    x2 = min(left[2], right[2])
    y2 = min(left[3], right[3])
    intersection = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    left_area = max(0.0, left[2] - left[0]) * max(0.0, left[3] - left[1])
    right_area = max(0.0, right[2] - right[0]) * max(0.0, right[3] - right[1])
    union = left_area + right_area - intersection
    return intersection / union if union > 0 else 0.0


def draw_result(source: Path, target: Path, groups: list[tuple[str, str, dict[str, Any]]]) -> None:
    image = Image.open(source).convert("RGB")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default()
    line_width = max(2, round(max(image.size) / 300))
    for model_label, color, result in groups:
        for detection in result["detections"]:
            box = detection.get("bbox_xyxy") or []
            if len(box) != 4:
                continue
            x1, y1, x2, y2 = box
            x1, x2 = sorted((max(0, x1), min(image.width - 1, x2)))
            y1, y2 = sorted((max(0, y1), min(image.height - 1, y2)))
            draw.rectangle((x1, y1, x2, y2), outline=color, width=line_width)
            text = f"{model_label} {detection['score']:.2f}"
            text_box = draw.textbbox((x1, y1), text, font=font)
            text_height = text_box[3] - text_box[1] + 4
            label_y = max(0, y1 - text_height)
            draw.rectangle((x1, label_y, x1 + text_box[2] - text_box[0] + 6, label_y + text_height), fill=color)
            draw.text((x1 + 3, label_y + 2), text, fill="white", font=font)
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target, quality=95)


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    args = parse_args()
    files = image_files(args.input_dir)
    if not files:
        raise SystemExit(f"No images found in {args.input_dir}")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    all_results = []
    for index, path in enumerate(files, start=1):
        print(f"[{index}/{len(files)}] {path.name}: Grounding DINO", flush=True)
        dino = run_dino(path, args.dino_url, args.box_threshold, args.text_threshold)
        print(f"[{index}/{len(files)}] {path.name}: LocateAnything", flush=True)
        locate = run_locate(path, args.locate_url)
        item = {"image": path.name, "grounding_dino": dino, "locate_anything": locate}
        all_results.append(item)
        write_json(args.output_dir / "raw" / f"{path.stem}.json", item)
        draw_result(path, args.output_dir / "grounding_dino" / f"{path.stem}.jpg", [("DINO", "#e53935", dino)])
        draw_result(path, args.output_dir / "locate_anything" / f"{path.stem}.jpg", [("Locate", "#00897b", locate)])
        draw_result(
            path,
            args.output_dir / "comparison" / f"{path.stem}.jpg",
            [("DINO", "#e53935", dino), ("Locate", "#00897b", locate)],
        )
        print(
            f"[{index}/{len(files)}] detections: dino={dino['detection_count']} locate={locate['detection_count']}",
            flush=True,
        )
    summary = {
        "target": "地面上的水迹",
        "input_dir": str(args.input_dir.resolve()),
        "image_count": len(files),
        "grounding_dino_prompt": DINO_PROMPT,
        "locate_anything_prompt": LOCATE_PROMPT,
        "grounding_dino_box_threshold": args.box_threshold,
        "grounding_dino_text_threshold": args.text_threshold,
        "grounding_dino_detection_count": sum(item["grounding_dino"]["detection_count"] for item in all_results),
        "locate_anything_detection_count": sum(item["locate_anything"]["detection_count"] for item in all_results),
        "results": all_results,
    }
    write_json(args.output_dir / "summary.json", summary)


if __name__ == "__main__":
    main()
