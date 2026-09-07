from __future__ import annotations

import json
import math
import re
import time
from pathlib import Path
from typing import Any, Dict, List

import cv2
import numpy as np


UPLOAD_ROOT = Path("/root/autodl-fs/uploads").resolve()


def smart_sample_video(payload: Dict[str, Any]) -> Dict[str, Any]:
    video_path = Path(str(payload.get("video_path") or "")).resolve()
    if not video_path.exists() or not video_path.is_file():
        return {
            "success": True,
            "available": False,
            "status": "VIDEO_NOT_FOUND",
            "reason": f"video file not found: {video_path}",
        }

    source_video_id = str(payload.get("source_video_id") or _slug(video_path.stem))
    original_name = str(payload.get("source_video_name") or video_path.name)
    output_dir = Path(str(payload.get("output_dir") or video_path.parent / "smart-frames" / source_video_id)).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    options = _options(payload.get("options") or {})
    started_at = time.time()
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        return {
            "success": True,
            "available": False,
            "status": "VIDEO_OPEN_FAILED",
            "reason": f"failed to open video: {video_path}",
        }

    fps = _positive_float(cap.get(cv2.CAP_PROP_FPS), 25.0)
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    duration_sec = total_frames / fps if total_frames > 0 and fps > 0 else 0.0
    requested_scan_step = max(1, int(round(fps * options["scan_interval_sec"])))
    max_frames = int(options["max_frames"])
    unlimited_frames = max_frames <= 0
    frame_indices = _planned_frame_indices(total_frames, requested_scan_step, max_frames)
    coverage_limited = (
        max_frames > 0
        and total_frames > 0
        and len(range(0, max(total_frames, 1), requested_scan_step)) > len(frame_indices)
    )
    effective_scan_interval_sec = (
        (frame_indices[1] - frame_indices[0]) / fps
        if fps > 0 and len(frame_indices) > 1 else options["scan_interval_sec"]
    )
    options["requested_scan_interval_sec"] = options["scan_interval_sec"]
    options["effective_scan_interval_sec"] = round(float(effective_scan_interval_sec), 6)
    options["coverage_limited_by_max_frames"] = bool(coverage_limited)
    options["planned_analyzed_frames"] = len(frame_indices)

    bg_subtractor = cv2.createBackgroundSubtractorMOG2(
        history=int(options["background_history"]),
        varThreshold=float(options["background_var_threshold"]),
        detectShadows=False,
    )
    orb = cv2.ORB_create(nfeatures=600)

    selected: List[Dict[str, Any]] = []
    prev_analysis: Dict[str, Any] | None = None
    last_kept_time: float | None = None
    last_candidate: Dict[str, Any] | None = None
    moving_votes = 0
    fixed_votes = 0
    analyzed_count = 0

    for frame_index in frame_indices:
        cap.set(cv2.CAP_PROP_POS_FRAMES, frame_index)
        ok, frame = cap.read()
        if not ok or frame is None:
            continue
        timestamp_sec = frame_index / fps if fps > 0 else float(analyzed_count) * options["scan_interval_sec"]
        analysis = _analyze_frame(frame, prev_analysis, bg_subtractor, orb)
        analyzed_count += 1

        if analysis["global_motion_score"] >= options["moving_camera_threshold"]:
            moving_votes += 1
        else:
            fixed_votes += 1

        keep_reasons = _keep_reasons(
            analysis=analysis,
            selected=selected,
            timestamp_sec=timestamp_sec,
            last_kept_time=last_kept_time,
            options=options,
        )

        candidate = {
            "source_video_id": source_video_id,
            "source_video_name": original_name,
            "frame_index": frame_index,
            "timestamp_sec": timestamp_sec,
            "analysis": analysis,
            "frame": frame,
        }

        if keep_reasons:
            if (
                not coverage_limited
                and "scene_changed" in keep_reasons
                and last_candidate is not None
                and last_candidate.get("timestamp_sec") != last_kept_time
                and (unlimited_frames or len(selected) < max_frames - 1)
            ):
                context_time = float(last_candidate["timestamp_sec"])
                if last_kept_time is None or context_time - last_kept_time >= options["min_gap_sec"]:
                    selected.append(_save_selected_frame(output_dir, last_candidate, ["pre_change_context"], options))
                    last_kept_time = context_time
            if unlimited_frames or len(selected) < max_frames:
                selected.append(_save_selected_frame(output_dir, candidate, keep_reasons, options))
                last_kept_time = timestamp_sec

        prev_analysis = analysis
        last_candidate = candidate

    cap.release()

    if not selected and total_frames > 0:
        cap = cv2.VideoCapture(str(video_path))
        ok, frame = cap.read()
        cap.release()
        if ok and frame is not None:
            analysis = _analyze_frame(frame, None, bg_subtractor, orb)
            selected.append(_save_selected_frame(output_dir, {
                "source_video_id": source_video_id,
                "source_video_name": original_name,
                "frame_index": 0,
                "timestamp_sec": 0.0,
                "analysis": analysis,
                "frame": frame,
            }, ["first_frame"], options))

    selected.sort(key=lambda item: float(item.get("timestamp_sec") or 0))
    camera_mode = "moving" if moving_votes > fixed_votes else "fixed"
    manifest = {
        "sampler": "server-smart-sampler-v1",
        "deep_shot_boundary_backend": "not_configured",
        "source_video_id": source_video_id,
        "source_video_name": original_name,
        "source_video_path": str(video_path),
        "duration_sec": duration_sec,
        "fps": fps,
        "width": width,
        "height": height,
        "total_frames": total_frames,
        "analyzed_frames": analyzed_count,
        "selected_frames": len(selected),
        "camera_mode": camera_mode,
        "options": options,
        "frames": selected,
        "elapsed_sec": round(time.time() - started_at, 3),
    }
    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest["manifest_path"] = str(manifest_path)

    return {
        "success": True,
        "available": True,
        "status": "COMPLETED",
        "source_video_id": source_video_id,
        "source_video_name": original_name,
        "manifest_path": str(manifest_path),
        "manifest": manifest,
        "frames": selected,
    }


def _planned_frame_indices(total_frames: int, requested_scan_step: int, max_frames: int) -> List[int]:
    if total_frames <= 0:
        return []
    requested = list(range(0, max(total_frames, 1), max(1, requested_scan_step)))
    if max_frames <= 0 or len(requested) <= max_frames:
        if requested and requested[-1] != total_frames - 1:
            if max_frames <= 0 or len(requested) < max_frames:
                requested.append(total_frames - 1)
            else:
                requested[-1] = total_frames - 1
        return requested
    if max_frames == 1:
        return [0]
    values = np.linspace(0, total_frames - 1, max_frames)
    indices = sorted({int(round(value)) for value in values})
    if indices[0] != 0:
        indices.insert(0, 0)
    if indices[-1] != total_frames - 1:
        indices.append(total_frames - 1)
    while len(indices) > max_frames:
        indices.pop(-2)
    return indices


def _options(raw: Dict[str, Any]) -> Dict[str, Any]:
    defaults = {
        "scan_interval_sec": 1.0,
        "max_frames": 0,
        "min_gap_sec": 0.75,
        "heartbeat_sec": 20.0,
        "scene_threshold": 0.18,
        "motion_threshold": 0.06,
        "hash_threshold": 10,
        "moving_camera_threshold": 0.025,
        "moving_camera_scene_threshold": 0.10,
        "analysis_width": 360,
        "jpeg_quality": 92,
        "background_history": 80,
        "background_var_threshold": 24,
    }
    merged = {**defaults}
    for key, value in raw.items():
        if key in merged:
            merged[key] = value
    return merged


def _analyze_frame(
    frame: np.ndarray,
    prev: Dict[str, Any] | None,
    bg_subtractor: cv2.BackgroundSubtractor,
    orb: cv2.ORB,
) -> Dict[str, Any]:
    small = _resize_for_analysis(frame)
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1], None, [24, 16], [0, 180, 0, 256])
    cv2.normalize(hist, hist, alpha=0, beta=1, norm_type=cv2.NORM_MINMAX)
    edges = cv2.Canny(gray, 80, 160)
    dhash_bits = _dhash_bits(gray)
    dhash_hex = _bits_to_hex(dhash_bits)
    fg_mask = bg_subtractor.apply(small)
    foreground_ratio = float(np.count_nonzero(fg_mask > 0) / fg_mask.size) if fg_mask.size else 0.0

    metrics = {
        "hash": dhash_hex,
        "hash_distance": 0,
        "hash_distance_norm": 0.0,
        "histogram_diff": 0.0,
        "gray_diff": 0.0,
        "edge_diff": 0.0,
        "motion_score": foreground_ratio,
        "global_motion_score": 0.0,
        "compensated_residual": 0.0,
        "scene_score": 0.0,
        "deep_shot_score": None,
    }

    if prev is not None:
        hist_diff = float(cv2.compareHist(prev["hist"], hist, cv2.HISTCMP_BHATTACHARYYA))
        gray_diff = _mean_abs_diff(gray, prev["gray"])
        edge_diff = _mean_abs_diff(edges, prev["edges"])
        hash_distance = _hamming(dhash_bits, prev["dhash_bits"])
        motion = _global_motion_and_residual(prev["gray"], gray, orb)
        residual = float(motion["residual"])
        global_motion = float(motion["global_motion"])
        hash_norm = hash_distance / max(1, len(dhash_bits))
        scene_score = (
            0.30 * min(1.0, hist_diff)
            + 0.20 * min(1.0, gray_diff * 2.0)
            + 0.20 * min(1.0, edge_diff * 2.0)
            + 0.20 * min(1.0, residual * 2.0)
            + 0.10 * min(1.0, hash_norm * 2.0)
        )
        metrics.update({
            "hash_distance": int(hash_distance),
            "hash_distance_norm": float(hash_norm),
            "histogram_diff": hist_diff,
            "gray_diff": gray_diff,
            "edge_diff": edge_diff,
            "motion_score": max(foreground_ratio, residual),
            "global_motion_score": global_motion,
            "compensated_residual": residual,
            "scene_score": float(scene_score),
        })

    metrics.update({
        "hist": hist,
        "gray": gray,
        "edges": edges,
        "dhash_bits": dhash_bits,
    })
    return metrics


def _keep_reasons(
    analysis: Dict[str, Any],
    selected: List[Dict[str, Any]],
    timestamp_sec: float,
    last_kept_time: float | None,
    options: Dict[str, Any],
) -> List[str]:
    if not selected:
        return ["first_frame"]
    if last_kept_time is not None and timestamp_sec - last_kept_time < options["min_gap_sec"]:
        return []

    reasons: List[str] = []
    if analysis["scene_score"] >= options["scene_threshold"]:
        reasons.append("scene_changed")
    if analysis["motion_score"] >= options["motion_threshold"]:
        reasons.append("motion_changed")
    if analysis["hash_distance"] >= options["hash_threshold"]:
        reasons.append("perceptual_hash_changed")
    if (
        analysis["global_motion_score"] >= options["moving_camera_threshold"]
        and analysis["scene_score"] >= options["moving_camera_scene_threshold"]
    ):
        reasons.append("moving_camera_view_changed")
    if last_kept_time is not None and timestamp_sec - last_kept_time >= options["heartbeat_sec"]:
        reasons.append("coverage_heartbeat")
    return _unique(reasons)


def _save_selected_frame(
    output_dir: Path,
    candidate: Dict[str, Any],
    reasons: List[str],
    options: Dict[str, Any],
) -> Dict[str, Any]:
    source_video_id = str(candidate["source_video_id"])
    timestamp_sec = float(candidate["timestamp_sec"])
    frame_index = int(candidate["frame_index"])
    filename = f"{_slug(source_video_id)}_t{int(round(timestamp_sec * 1000)):010d}_f{frame_index:08d}.jpg"
    path = output_dir / filename
    quality = int(options["jpeg_quality"])
    cv2.imwrite(str(path), candidate["frame"], [int(cv2.IMWRITE_JPEG_QUALITY), quality])
    analysis = candidate["analysis"]
    return {
        "source_video_id": source_video_id,
        "source_video_name": candidate["source_video_name"],
        "frame_index": frame_index,
        "timestamp_sec": round(timestamp_sec, 3),
        "file_path": str(path),
        "file_name": filename,
        "reason": reasons,
        "hash": analysis.get("hash"),
        "hash_distance": analysis.get("hash_distance"),
        "shot_score": analysis.get("deep_shot_score"),
        "scene_score": round(float(analysis.get("scene_score") or 0.0), 6),
        "motion_score": round(float(analysis.get("motion_score") or 0.0), 6),
        "global_motion_score": round(float(analysis.get("global_motion_score") or 0.0), 6),
        "compensated_residual": round(float(analysis.get("compensated_residual") or 0.0), 6),
    }


def _resize_for_analysis(frame: np.ndarray, max_width: int = 360) -> np.ndarray:
    h, w = frame.shape[:2]
    if w <= max_width:
        return frame
    scale = max_width / float(w)
    return cv2.resize(frame, (max_width, max(1, int(round(h * scale)))), interpolation=cv2.INTER_AREA)


def _dhash_bits(gray: np.ndarray) -> List[int]:
    small = cv2.resize(gray, (9, 8), interpolation=cv2.INTER_AREA)
    diff = small[:, 1:] > small[:, :-1]
    return [1 if value else 0 for value in diff.flatten()]


def _bits_to_hex(bits: List[int]) -> str:
    value = 0
    for bit in bits:
        value = (value << 1) | int(bit)
    return f"{value:016x}"


def _hamming(left: List[int], right: List[int]) -> int:
    return sum(1 for a, b in zip(left, right) if a != b)


def _mean_abs_diff(left: np.ndarray, right: np.ndarray) -> float:
    if left.shape != right.shape:
        right = cv2.resize(right, (left.shape[1], left.shape[0]), interpolation=cv2.INTER_AREA)
    return float(np.mean(cv2.absdiff(left, right)) / 255.0)


def _global_motion_and_residual(prev_gray: np.ndarray, gray: np.ndarray, orb: cv2.ORB) -> Dict[str, float]:
    if prev_gray.shape != gray.shape:
        prev_gray = cv2.resize(prev_gray, (gray.shape[1], gray.shape[0]), interpolation=cv2.INTER_AREA)
    kp1, des1 = orb.detectAndCompute(prev_gray, None)
    kp2, des2 = orb.detectAndCompute(gray, None)
    if des1 is None or des2 is None or len(kp1) < 8 or len(kp2) < 8:
        return {"global_motion": 0.0, "residual": _mean_abs_diff(prev_gray, gray)}
    matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True)
    matches = sorted(matcher.match(des1, des2), key=lambda match: match.distance)[:80]
    if len(matches) < 8:
        return {"global_motion": 0.0, "residual": _mean_abs_diff(prev_gray, gray)}
    pts1 = np.float32([kp1[m.queryIdx].pt for m in matches]).reshape(-1, 1, 2)
    pts2 = np.float32([kp2[m.trainIdx].pt for m in matches]).reshape(-1, 1, 2)
    matrix, mask = cv2.findHomography(pts1, pts2, cv2.RANSAC, 4.0)
    if matrix is None:
        return {"global_motion": 0.0, "residual": _mean_abs_diff(prev_gray, gray)}
    warped = cv2.warpPerspective(prev_gray, matrix, (gray.shape[1], gray.shape[0]))
    residual = _mean_abs_diff(warped, gray)
    dx = float(matrix[0, 2])
    dy = float(matrix[1, 2])
    diagonal = math.sqrt(gray.shape[0] ** 2 + gray.shape[1] ** 2) or 1.0
    translation = math.sqrt(dx * dx + dy * dy) / diagonal
    inlier_ratio = float(np.count_nonzero(mask) / len(mask)) if mask is not None and len(mask) else 0.0
    return {
        "global_motion": max(0.0, translation * (0.5 + 0.5 * inlier_ratio)),
        "residual": residual,
    }


def _positive_float(value: Any, default: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def _unique(items: List[str]) -> List[str]:
    result: List[str] = []
    for item in items:
        if item and item not in result:
            result.append(item)
    return result


def _slug(value: str) -> str:
    text = re.sub(r"[^a-zA-Z0-9._-]+", "_", str(value or "")).strip("._-")
    return text or "video"
