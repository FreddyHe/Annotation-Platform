#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import math
import os
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any

import requests


BASE = os.getenv("ANNOTATION_API_BASE", "http://127.0.0.1:8080/api/v1").rstrip("/")
USERNAME = os.getenv("ANNOTATION_USERNAME", "uavhuman0901")
PASSWORD = os.getenv("ANNOTATION_PASSWORD")
RUN_ID = os.getenv("RUN_ID", datetime.utcnow().strftime("%Y%m%d_%H%M%S"))
PROJECT_NAME = os.getenv("PROJECT_NAME", f"无人机003-006完整视频-LA主模型-{RUN_ID}")
EXISTING_PROJECT_ID = os.getenv("EXISTING_PROJECT_ID")
OUTPUT_DIR = Path(os.getenv("OUTPUT_DIR", f"/root/autodl-fs/Annotation-Platform/output/uav_003_006_locate_primary_{RUN_ID}"))
PROGRESS_MD = Path(os.getenv("PROGRESS_MD", f"/root/autodl-fs/Annotation-Platform/doc/uav_003_006_locate_primary_full_video_{RUN_ID}.md"))
CHUNK_SIZE = 5 * 1024 * 1024
LONG_REQUEST_TIMEOUT_SECONDS = int(os.getenv("AUTO_LABEL_LONG_REQUEST_TIMEOUT_SECONDS", str(240 * 60)))
LABELS = ["人", "车", "自行车", "电动车", "摩托车"]
REQUIREMENT_TEXT = "无人机航拍可见光、红外和低照度场景，检测人、车、自行车、电动车、摩托车五类目标。"
VIDEO_ROOT = Path("/root/autodl-fs/xingmu_model/张廷杰来信文件")
VIDEO_NAMES = ["003.mp4", "004.mp4", "005.mp4", "006.mp4"]


def now() -> str:
    return datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S UTC")


def log(message: str) -> None:
    print(f"[{now()}] {message}", flush=True)


def save_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def append_progress(title: str, body: str) -> None:
    PROGRESS_MD.parent.mkdir(parents=True, exist_ok=True)
    if not PROGRESS_MD.exists():
        PROGRESS_MD.write_text(
            f"# UAV 003-006 LocateAnything 主模型完整视频流程\n\n"
            f"- run_id: `{RUN_ID}`\n"
            f"- started_at: `{now()}`\n"
            f"- user: `{USERNAME}`\n\n",
            encoding="utf-8",
        )
    with PROGRESS_MD.open("a", encoding="utf-8") as fh:
        fh.write(f"\n## {title}\n\n{body}\n")


def request_json(session: requests.Session, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
    url = path if path.startswith("http") else BASE + path
    response = session.request(method, url, timeout=kwargs.pop("timeout", 120), **kwargs)
    try:
        payload = response.json()
    except Exception:
        payload = {"success": False, "message": response.text[:1000]}
    if response.status_code >= 400 or payload.get("success") is False:
        raise RuntimeError(f"{method} {url} failed: HTTP {response.status_code} {json.dumps(payload, ensure_ascii=False)[:2000]}")
    return payload


def find_videos() -> list[Path]:
    videos: list[Path] = []
    for name in VIDEO_NAMES:
        matches = sorted(VIDEO_ROOT.glob(f"**/extracted/{name}"))
        if not matches:
            raise FileNotFoundError(name)
        videos.append(matches[0])
    return videos


def ffprobe(path: Path) -> dict[str, Any]:
    output = subprocess.check_output([
        "ffprobe", "-v", "error",
        "-select_streams", "v:0",
        "-show_entries", "stream=codec_name,width,height,r_frame_rate,nb_frames",
        "-show_entries", "format=duration,size",
        "-of", "json",
        str(path),
    ])
    data = json.loads(output)
    stream = (data.get("streams") or [{}])[0]
    fmt = data.get("format") or {}
    return {
        "path": str(path),
        "name": path.name,
        "codec": stream.get("codec_name"),
        "width": stream.get("width"),
        "height": stream.get("height"),
        "fps": stream.get("r_frame_rate"),
        "frames": stream.get("nb_frames"),
        "duration": float(fmt.get("duration") or 0),
        "size": int(fmt.get("size") or path.stat().st_size),
    }


def frontend_file_id(project_id: int, path: Path) -> str:
    stat = path.stat()
    last_modified_ms = int(stat.st_mtime * 1000)
    raw = f"{project_id}::{path.name}::{stat.st_size}::{last_modified_ms}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]


def uploaded_chunks(session: requests.Session, file_id: str) -> set[int]:
    try:
        payload = request_json(session, "GET", f"/upload/chunks/{file_id}", timeout=30)
        return {int(v) for v in (payload.get("data") or {}).get("uploadedChunks") or []}
    except Exception:
        return set()


def upload_video(session: requests.Session, project_id: int, path: Path) -> dict[str, Any]:
    size = path.stat().st_size
    total_chunks = math.ceil(size / CHUNK_SIZE)
    file_id = frontend_file_id(project_id, path)
    done = uploaded_chunks(session, file_id)
    started = time.time()
    log(f"upload start {path.name}: size={size} chunks={total_chunks} already={len(done)}")
    with path.open("rb") as fh:
        for chunk_index in range(total_chunks):
            if chunk_index in done:
                continue
            fh.seek(chunk_index * CHUNK_SIZE)
            data = fh.read(CHUNK_SIZE)
            fields = {
                "fileId": file_id,
                "filename": path.name,
                "chunkIndex": str(chunk_index),
                "totalChunks": str(total_chunks),
                "fileSize": str(size),
                "projectId": str(project_id),
            }
            for attempt in range(1, 4):
                try:
                    response = session.post(
                        BASE + "/upload/chunk",
                        data=fields,
                        files={"file": (path.name, data, "application/octet-stream")},
                        timeout=120,
                    )
                    payload = response.json()
                    if response.status_code < 400 and payload.get("success") is not False:
                        break
                    raise RuntimeError(f"HTTP {response.status_code} {payload}")
                except Exception:
                    if attempt == 3:
                        raise
                    time.sleep(2 * attempt)
            if (chunk_index + 1) % 25 == 0 or chunk_index + 1 == total_chunks:
                log(f"upload {path.name}: {chunk_index + 1}/{total_chunks} chunks")
    log(f"merge start {path.name}")
    merge = request_json(
        session,
        "POST",
        "/upload/merge",
        json={"fileId": file_id, "filename": path.name, "totalChunks": total_chunks, "projectId": project_id},
        timeout=3600,
    )
    elapsed = round(time.time() - started, 2)
    log(f"upload+merge done {path.name}: elapsed={elapsed}s data={merge.get('data')}")
    return {
        "name": path.name,
        "path": str(path),
        "file_id": file_id,
        "size": size,
        "total_chunks": total_chunks,
        "merge_response": merge,
        "elapsed_sec": elapsed,
    }


def require_completed_job(stage: str, payload: dict[str, Any]) -> None:
    item = (payload.get("data") or {}).get("item") or {}
    status = str(item.get("status") or "").upper()
    if status and status != "COMPLETED":
        raise RuntimeError(f"{stage} did not complete: job status={status}, error={item.get('errorMessage')}")


def main() -> int:
    if not PASSWORD:
        print("ANNOTATION_PASSWORD is required", file=sys.stderr)
        return 2
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    videos = find_videos()
    video_infos = [ffprobe(path) for path in videos]
    save_json(OUTPUT_DIR / "source_videos.json", video_infos)
    append_progress("Source Videos", "\n".join(f"- `{item['name']}` duration={item['duration']:.3f}s size={item['size']}" for item in video_infos))

    session = requests.Session()
    login = request_json(session, "POST", "/auth/login", json={"username": USERNAME, "password": PASSWORD}, timeout=30)
    token = (login.get("data") or {}).get("token")
    if not token:
        raise RuntimeError("login did not return token")
    session.headers.update({"Authorization": f"Bearer {token}"})
    append_progress("Login", f"- status: success\n- user: `{USERNAME}`")

    upload_results = []
    if EXISTING_PROJECT_ID:
        project_id = int(EXISTING_PROJECT_ID)
        project = request_json(session, "GET", f"/projects/{project_id}", timeout=120)
        save_json(OUTPUT_DIR / "project_reuse.json", project)
        append_progress("Project Reused", f"- project_id: `{project_id}`\n- upload skipped: `true`")
        log(f"project reused id={project_id}")
    else:
        project = request_json(session, "POST", "/projects", json={"name": PROJECT_NAME, "labels": LABELS}, timeout=120)
        project_data = project.get("data") or {}
        project_id = int(project_data.get("id"))
        save_json(OUTPUT_DIR / "project_create.json", project)
        append_progress("Project Created", f"- project_id: `{project_id}`\n- name: `{PROJECT_NAME}`")
        log(f"project created id={project_id} name={PROJECT_NAME}")

        for video in videos:
            result = upload_video(session, project_id, video)
            upload_results.append(result)
            save_json(OUTPUT_DIR / "upload_results.partial.json", upload_results)
            append_progress("Uploaded " + video.name, f"- elapsed_sec: `{result['elapsed_sec']}`\n- merge_data: `{result['merge_response'].get('data')}`")
        save_json(OUTPUT_DIR / "upload_results.json", upload_results)

    project_after_upload = request_json(session, "GET", f"/projects/{project_id}", timeout=60)
    images = request_json(session, "GET", f"/projects/{project_id}/images", params={"page": 1, "size": 10000}, timeout=120)
    image_total = int((images.get("data") or {}).get("total") or 0)
    save_json(OUTPUT_DIR / "project_after_upload.json", project_after_upload)
    save_json(OUTPUT_DIR / "images_after_upload.json", images)
    append_progress("Images After Upload", f"- total sampled images: `{image_total}`")

    req = request_json(session, "POST", f"/projects/{project_id}/requirements/parse", json={"text": REQUIREMENT_TEXT}, timeout=120)
    label_schema = (req.get("data") or {}).get("item", {}).get("labelSchema") or []
    req_confirm = request_json(
        session,
        "POST",
        f"/projects/{project_id}/requirements/parse",
        json={"text": REQUIREMENT_TEXT, "labelSchema": label_schema},
        timeout=120,
    )
    profile = request_json(session, "POST", f"/projects/{project_id}/dataset-profile", json={}, timeout=120)
    route = request_json(session, "POST", f"/projects/{project_id}/model-route/preview", json={}, timeout=120)
    save_json(OUTPUT_DIR / "requirement_parse.json", req)
    save_json(OUTPUT_DIR / "requirement_confirm.json", req_confirm)
    save_json(OUTPUT_DIR / "dataset_profile.json", profile)
    save_json(OUTPUT_DIR / "route_preview.json", route)
    route_item = (route.get("data") or {}).get("item") or {}
    append_progress(
        "Route Preview",
        f"- primaryModelId: `{route_item.get('primaryModelId')}`\n"
        f"- auxiliaryModelIds: `{route_item.get('auxiliaryModelIds')}`\n"
        f"- reason: {route_item.get('reason')}",
    )

    job = request_json(session, "POST", f"/projects/{project_id}/auto-label/jobs", json={"semanticVerificationEnabled": False}, timeout=120)
    job_item = (job.get("data") or {}).get("item") or {}
    job_id = int(job_item.get("id"))
    save_json(OUTPUT_DIR / "job_create.json", job)
    append_progress("Job Created", f"- job_id: `{job_id}`\n- totalImages: `{job_item.get('totalImages')}`")
    log(f"job created id={job_id}, run start")

    run = request_json(session, "POST", f"/projects/{project_id}/auto-label/jobs/{job_id}/run", timeout=LONG_REQUEST_TIMEOUT_SECONDS)
    save_json(OUTPUT_DIR / "job_run.json", run)
    require_completed_job("auto-label run", run)
    run_data = run.get("data") or {}
    append_progress(
        "Job Run",
        f"- candidateCount: `{run_data.get('candidateCount')}`\n"
        f"- fusedPredictionCount: `{run_data.get('fusedPredictionCount')}`",
    )

    sync = request_json(session, "POST", f"/projects/{project_id}/auto-label/jobs/{job_id}/sync-label-studio", timeout=1800)
    save_json(OUTPUT_DIR / "label_studio_sync.json", sync)
    require_completed_job("label-studio sync", sync)
    sync_data = sync.get("data") or {}
    append_progress(
        "Label Studio Sync",
        f"- labelStudioProjectId: `{sync_data.get('labelStudioProjectId')}`\n"
        f"- importStats: `{json.dumps(sync_data.get('importStats'), ensure_ascii=False)}`",
    )

    rendered = request_json(session, "POST", f"/projects/{project_id}/auto-label/jobs/{job_id}/annotated-video", timeout=LONG_REQUEST_TIMEOUT_SECONDS)
    save_json(OUTPUT_DIR / "annotated_video_render.json", rendered)
    require_completed_job("annotated-video render", rendered)
    rendered_data = rendered.get("data") or {}
    videos_rendered = rendered_data.get("annotatedVideos") or []
    if not videos_rendered:
        raise RuntimeError("annotated-video render did not return annotatedVideos")
    append_progress("Annotated Videos", "\n".join(
        f"- `{item.get('sourceVideoName')}` -> `{item.get('relativePath')}`, frames={item.get('frameCount')}, preds={item.get('predictionCount')}"
        for item in videos_rendered
    ))

    final_job = request_json(session, "GET", f"/projects/{project_id}/auto-label/jobs/{job_id}", timeout=120)
    final_project = request_json(session, "GET", f"/projects/{project_id}", timeout=120)
    final_images = request_json(session, "GET", f"/projects/{project_id}/images", params={"page": 1, "size": 10000}, timeout=120)
    save_json(OUTPUT_DIR / "final_job.json", final_job)
    save_json(OUTPUT_DIR / "final_project.json", final_project)
    save_json(OUTPUT_DIR / "final_images.json", final_images)
    summary = {
        "run_id": RUN_ID,
        "username": USERNAME,
        "project_id": project_id,
        "project_name": PROJECT_NAME,
        "job_id": job_id,
        "label_studio_project_id": sync_data.get("labelStudioProjectId"),
        "source_videos": video_infos,
        "upload_results": upload_results,
        "sampled_image_total": image_total,
        "route": {
            "primaryModelId": route_item.get("primaryModelId"),
            "auxiliaryModelIds": route_item.get("auxiliaryModelIds"),
            "score": route_item.get("score"),
        },
        "candidateCount": run_data.get("candidateCount"),
        "fusedPredictionCount": run_data.get("fusedPredictionCount"),
        "annotatedVideos": videos_rendered,
        "output_dir": str(OUTPUT_DIR),
        "progress_md": str(PROGRESS_MD),
    }
    save_json(OUTPUT_DIR / "summary.json", summary)
    append_progress("Summary", f"```json\n{json.dumps(summary, ensure_ascii=False, indent=2)}\n```")
    log("FLOW_COMPLETED " + json.dumps({"project_id": project_id, "job_id": job_id, "output_dir": str(OUTPUT_DIR)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        log(f"FLOW_FAILED {exc}")
        append_progress("Failure", f"- error: `{exc}`")
        raise
