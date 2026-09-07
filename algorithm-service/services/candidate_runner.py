import os
from typing import Any, Dict, List, Tuple

from adapters import border_adapter, grounding_dino_adapter, local_vlm_adapter, locate_anything_adapter, xingmu_adapter
from services import job_progress

GROUNDING_DINO_MODEL_ID = "grounding-dino"
LOCATE_ANYTHING_MODEL_ID = "local-locate-anything-3b"
ALLOWED_ROUTE_MODEL_IDS = (GROUNDING_DINO_MODEL_ID, LOCATE_ANYTHING_MODEL_ID)


def run_candidates(payload: Dict[str, Any]) -> Dict[str, Any]:
    job_id = payload.get("job_id")
    image_paths = payload.get("image_paths") or []
    job_progress.start(job_id, len(image_paths), "准备智能标注", "正在准备智能标注")
    route_plan = payload.get("route_plan") or {}
    labels = _route_labels(route_plan)
    label_schema = _route_label_schema(route_plan)
    locate_prompt_labels = _route_prompt_labels(route_plan, label_schema, labels, "locate_anything_labels")
    dino_prompt_labels = _route_prompt_labels(route_plan, label_schema, labels, "grounding_dino_labels")
    primary_model_id = route_plan.get("primaryModelId") or route_plan.get("primary_model_id")
    auxiliary_model_ids = route_plan.get("auxiliaryModelIds") or route_plan.get("auxiliary_model_ids") or []

    if image_paths:
        job_progress.update(
            job_id,
            processed=0,
            total=len(image_paths),
            stage="正在识别目标",
            message="正在识别目标",
        )
        candidates: List[Dict[str, Any]] = []
        adapter_reports: List[Dict[str, Any]] = []
        model_ids = _ordered_models(primary_model_id, auxiliary_model_ids)
        primary_model = model_ids[0] if model_ids else None
        auxiliary_models = model_ids[1:]
        auxiliary_policy = _auxiliary_policy(route_plan)
        auxiliary_sample_interval = _auxiliary_sample_interval(route_plan)
        open_vocab_labels = _open_vocab_labels(dino_prompt_labels)
        dino_box_threshold, dino_text_threshold = _grounding_dino_thresholds(route_plan)
        vlm_enabled = "local-qwen3-vl-4b" in model_ids
        vlm_runtime = local_vlm_adapter.status() if vlm_enabled else None
        vlm_candidate_limit = _vlm_candidate_limit()
        vlm_verified_count = 0
        vlm_skip_reported = False
        for image_index, image_path in enumerate(image_paths):
            image_candidates: List[Dict[str, Any]] = []
            image_reports: List[Dict[str, Any]] = []
            execution_models: List[str] = []
            if primary_model:
                execution_models.append(primary_model)
            run_auxiliary = _should_run_auxiliary(
                image_index=image_index,
                policy=auxiliary_policy,
                sample_interval=auxiliary_sample_interval,
                primary_candidates=image_candidates,
                after_primary=False,
            )
            if run_auxiliary:
                execution_models.extend(auxiliary_models)

            for model_id in execution_models:
                if not model_id:
                    continue
                if model_id == "grounding-dino":
                    result = grounding_dino_adapter.predict_image(
                        image_path=image_path,
                        labels=open_vocab_labels,
                        job_id=payload.get("job_id"),
                        route_id=route_plan.get("id"),
                        box_threshold=dino_box_threshold,
                        text_threshold=dino_text_threshold,
                    )
                elif str(model_id).startswith("xingmu-"):
                    result = xingmu_adapter.predict_image(
                        image_path=image_path,
                        model_id=str(model_id),
                        label=labels[0] if labels else None,
                        job_id=payload.get("job_id"),
                        route_id=route_plan.get("id"),
                    )
                elif str(model_id).startswith("border-"):
                    result = border_adapter.predict_image(
                        image_path=image_path,
                        model_id=str(model_id),
                        labels=labels,
                        job_id=payload.get("job_id"),
                        route_id=route_plan.get("id"),
                    )
                elif model_id == "local-locate-anything-3b":
                    result = locate_anything_adapter.predict_image(
                        image_path=image_path,
                        labels=locate_prompt_labels,
                        job_id=payload.get("job_id"),
                        route_id=route_plan.get("id"),
                    )
                elif model_id == "local-qwen3-vl-4b":
                    result = _vlm_status_report(vlm_runtime)
                else:
                    result = {
                        "success": True,
                        "available": False,
                        "status": "UNAVAILABLE",
                        "source_model": model_id,
                        "reason": "adapter is not executable in Stage 4",
                        "candidates": [],
                    }
                if model_id != "local-qwen3-vl-4b":
                    image_candidates.extend(
                        _canonicalize_candidate_labels(
                            result.get("candidates") or [],
                            labels,
                            label_schema,
                            dino_prompt_labels,
                        )
                    )
                image_reports.append(
                    {
                        "source_model": result.get("source_model") or model_id,
                        "available": result.get("available"),
                        "status": result.get("status"),
                        "candidate_count": len(result.get("candidates") or []),
                        "reason": result.get("reason"),
                        "metadata": result.get("metadata"),
                    }
                )
            if auxiliary_models and not run_auxiliary and _should_run_auxiliary(
                image_index=image_index,
                policy=auxiliary_policy,
                sample_interval=auxiliary_sample_interval,
                primary_candidates=image_candidates,
                after_primary=True,
            ):
                for model_id in auxiliary_models:
                    if model_id == "grounding-dino":
                        result = grounding_dino_adapter.predict_image(
                            image_path=image_path,
                            labels=open_vocab_labels,
                            job_id=payload.get("job_id"),
                            route_id=route_plan.get("id"),
                            box_threshold=dino_box_threshold,
                            text_threshold=dino_text_threshold,
                        )
                    elif model_id == "local-locate-anything-3b":
                        result = locate_anything_adapter.predict_image(
                            image_path=image_path,
                            labels=locate_prompt_labels,
                            job_id=payload.get("job_id"),
                            route_id=route_plan.get("id"),
                        )
                    else:
                        result = {
                            "success": True,
                            "available": False,
                            "status": "UNAVAILABLE",
                            "source_model": model_id,
                            "reason": "adapter is not executable as auxiliary in Stage 4",
                            "candidates": [],
                        }
                    if model_id != "local-qwen3-vl-4b":
                        image_candidates.extend(
                            _canonicalize_candidate_labels(
                                result.get("candidates") or [],
                                labels,
                                label_schema,
                                dino_prompt_labels,
                            )
                        )
                    image_reports.append(
                        {
                            "source_model": result.get("source_model") or model_id,
                            "available": result.get("available"),
                            "status": result.get("status"),
                            "candidate_count": len(result.get("candidates") or []),
                            "reason": result.get("reason"),
                            "metadata": result.get("metadata"),
                        }
                    )
            image_candidates, semantic_filter_report, verified_delta, skipped_due_to_limit = _apply_box_semantic_filter(
                image_candidates=image_candidates,
                labels=labels,
                image_path=image_path,
                job_id=payload.get("job_id"),
                route_id=route_plan.get("id"),
                runtime=vlm_runtime,
                candidate_limit=vlm_candidate_limit,
                verified_count=vlm_verified_count,
            )
            vlm_verified_count += verified_delta
            if skipped_due_to_limit and not vlm_skip_reported:
                image_reports.append(
                    {
                        "source_model": "local-qwen3-vl-4b",
                        "available": bool((vlm_runtime or {}).get("available")),
                        "status": "BOX_SEMANTIC_SKIPPED_CANDIDATE_LIMIT",
                        "candidate_count": len(image_candidates),
                        "reason": f"box semantic verifier reached LOCAL_VLM_MAX_CANDIDATES_PER_JOB={vlm_candidate_limit}",
                        "metadata": {
                            "image_count": len(image_paths),
                            "candidate_limit": vlm_candidate_limit,
                            "verified_candidate_count": vlm_verified_count,
                            "external_api_used": False,
                        },
                    }
                )
                vlm_skip_reported = True
            candidates.extend(image_candidates)
            if semantic_filter_report:
                image_reports.append(semantic_filter_report)
            adapter_reports.extend(image_reports)
            job_progress.update(
                job_id,
                processed=image_index + 1,
                total=len(image_paths),
                stage="正在识别目标",
                message=f"已处理 {image_index + 1}/{len(image_paths)} 张",
                extra={"candidate_count": len(candidates)},
            )
        job_progress.finish(job_id, stage="候选结果已生成", message="候选结果已生成")
        return {
            "success": True,
            "available": True,
            "status": "COMPLETED",
            "external_api_used": False,
            "job_id": job_id,
            "image_count": len(image_paths),
            "candidate_count": len(candidates),
            "candidates": candidates,
            "adapter_reports": adapter_reports,
        }

    job_progress.finish(job_id, stage="没有待识别图片", message="没有待识别图片")
    return {
        "success": True,
        "available": True,
        "status": "DRY_RUN_NO_PREDICTIONS",
        "external_api_used": False,
        "job_id": job_id,
        "image_count": len(image_paths),
        "candidates": [],
        "message": "未提供 image_paths，候选推理保持 dry-run，不伪造预测结果。",
    }


def _ordered_models(primary_model_id: str | None, auxiliary_model_ids: List[str]) -> List[str]:
    ordered = []
    if primary_model_id in ALLOWED_ROUTE_MODEL_IDS:
        ordered.append(primary_model_id)
    for model_id in auxiliary_model_ids or []:
        if model_id in ALLOWED_ROUTE_MODEL_IDS and model_id not in ordered:
            ordered.append(model_id)
    if LOCATE_ANYTHING_MODEL_ID not in ordered:
        ordered.insert(0, LOCATE_ANYTHING_MODEL_ID)
    if GROUNDING_DINO_MODEL_ID not in ordered:
        ordered.append(GROUNDING_DINO_MODEL_ID)
    return ordered


def _auxiliary_policy(route_plan: Dict[str, Any]) -> str:
    score = route_plan.get("score") or {}
    raw = (
        score.get("auxiliary_policy")
        or route_plan.get("auxiliaryPolicy")
        or route_plan.get("auxiliary_policy")
        or os.getenv("AUTO_LABEL_AUXILIARY_POLICY", "primary_empty_or_sampled")
    )
    text = str(raw or "").strip().lower()
    if text in {"full", "full_scan", "always"}:
        return "full_scan"
    if text in {"off", "none", "disabled"}:
        return "disabled"
    return "primary_empty_or_sampled"


def _auxiliary_sample_interval(route_plan: Dict[str, Any]) -> int:
    score = route_plan.get("score") or {}
    raw = (
        score.get("auxiliary_sample_interval")
        or route_plan.get("auxiliarySampleInterval")
        or route_plan.get("auxiliary_sample_interval")
        or os.getenv("AUTO_LABEL_AUXILIARY_SAMPLE_INTERVAL", "25")
    )
    try:
        value = int(raw)
    except (TypeError, ValueError):
        value = 25
    return max(0, value)


def _should_run_auxiliary(
    image_index: int,
    policy: str,
    sample_interval: int,
    primary_candidates: List[Dict[str, Any]],
    after_primary: bool,
) -> bool:
    if policy == "disabled":
        return False
    if policy == "full_scan":
        return not after_primary
    if sample_interval > 0 and image_index % sample_interval == 0:
        return not after_primary
    return after_primary and not primary_candidates


def _route_labels(route_plan: Dict[str, Any]) -> List[str]:
    score = route_plan.get("score") or {}
    labels = score.get("display_labels") or score.get("labels") or route_plan.get("labels") or []
    return [str(label) for label in labels if label]


def _route_label_schema(route_plan: Dict[str, Any]) -> List[Dict[str, Any]]:
    score = route_plan.get("score") or {}
    value = (
        score.get("label_schema")
        or score.get("labelSchema")
        or route_plan.get("label_schema")
        or route_plan.get("labelSchema")
        or []
    )
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def _route_prompt_labels(
    route_plan: Dict[str, Any],
    label_schema: List[Dict[str, Any]],
    display_labels: List[str],
    score_key: str,
) -> List[str]:
    score = route_plan.get("score") or {}
    raw = score.get(score_key) or score.get("model_prompt_labels") or []
    labels = [str(value).strip() for value in raw if str(value).strip()] if isinstance(raw, list) else []
    if labels:
        return labels

    labels = []
    for item in label_schema:
        prompt = (
            item.get("model_prompt")
            or item.get("modelPrompt")
            or item.get("english_prompt")
            or item.get("englishPrompt")
            or item.get("english_name")
            or item.get("englishName")
        )
        if not prompt:
            positives = item.get("positive_prompts_en") or item.get("positivePromptsEn") or item.get("positive_prompts") or []
            if isinstance(positives, list) and positives:
                prompt = positives[0]
        if prompt:
            text = str(prompt).strip()
            if text and text not in labels:
                labels.append(text)
    if labels:
        return labels
    return [_english_label(label) for label in display_labels]


def _open_vocab_labels(labels: List[str]) -> List[str]:
    result: List[str] = []
    for label in labels:
        for alias in [label, *_traffic_aliases(label)]:
            text = str(alias or "").strip()
            if text and text not in result:
                result.append(text)
    return result or labels


def _grounding_dino_thresholds(route_plan: Dict[str, Any]) -> Tuple[float, float]:
    score = route_plan.get("score") or {}
    options = score.get("grounding_dino_options") or score.get("groundingDinoOptions") or {}
    try:
        box_threshold = float(options.get("box_threshold", options.get("boxThreshold", 0.25)))
    except (TypeError, ValueError):
        box_threshold = 0.25
    try:
        text_threshold = float(options.get("text_threshold", options.get("textThreshold", 0.20)))
    except (TypeError, ValueError):
        text_threshold = 0.20
    return box_threshold, text_threshold


def _vlm_candidate_limit() -> int:
    raw = os.getenv("LOCAL_VLM_MAX_CANDIDATES_PER_JOB", os.getenv("LOCAL_VLM_MAX_IMAGES_PER_JOB", "180"))
    try:
        return int(raw)
    except (TypeError, ValueError):
        return 180


def _vlm_candidate_limit_per_image() -> int:
    raw = os.getenv("LOCAL_VLM_MAX_CANDIDATES_PER_IMAGE", "3")
    try:
        return int(raw)
    except (TypeError, ValueError):
        return 3


def _vlm_status_report(runtime: Dict[str, Any] | None) -> Dict[str, Any]:
    runtime = runtime or {}
    return {
        "success": True,
        "available": bool(runtime.get("available")),
        "status": "BOX_SEMANTIC_VERIFIER_READY" if runtime.get("available") else runtime.get("status", "UNAVAILABLE"),
        "source_model": "local-qwen3-vl-4b",
        "reason": runtime.get("reason"),
        "candidates": [],
        "metadata": {
            "role": "box_semantic_verifier",
            "model_name": runtime.get("model_name"),
            "endpoint": runtime.get("endpoint"),
            "external_api_used": False,
        },
    }


def _apply_box_semantic_filter(
    image_candidates: List[Dict[str, Any]],
    labels: List[str],
    image_path: str,
    job_id: Any,
    route_id: Any,
    runtime: Dict[str, Any] | None,
    candidate_limit: int,
    verified_count: int,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any] | None, int, bool]:
    if not runtime:
        return image_candidates, None, 0, False
    if not runtime.get("available"):
        return image_candidates, {
            "source_model": "local-qwen3-vl-4b",
            "available": False,
            "status": "BOX_SEMANTIC_FILTER_UNAVAILABLE",
            "candidate_count": len(image_candidates),
            "reason": runtime.get("reason"),
            "metadata": {
                "image_path": image_path,
                "external_api_used": False,
            },
        }, 0, False

    kept: List[Dict[str, Any]] = []
    removed: List[Dict[str, Any]] = []
    invalid_label_removed: List[Dict[str, Any]] = []
    verified_delta = 0
    image_verified_count = 0
    skipped_due_to_limit = False
    image_candidate_limit = _vlm_candidate_limit_per_image()
    for candidate in sorted(image_candidates, key=_candidate_score, reverse=True):
        label = _match_candidate_label(candidate.get("label"), labels)
        if not label:
            invalid_label_removed.append(candidate)
            continue

        item = dict(candidate)
        raw_label = str(item.get("label") or "")
        item["label"] = label
        metadata = dict(item.get("metadata") or {})
        if raw_label != label:
            metadata["raw_label_before_box_semantic_verification"] = raw_label
        item["metadata"] = metadata

        reached_job_limit = candidate_limit >= 0 and verified_count + verified_delta >= candidate_limit
        reached_image_limit = image_candidate_limit >= 0 and image_verified_count >= image_candidate_limit
        if reached_job_limit or reached_image_limit:
            kept.append(item)
            skipped_due_to_limit = True
            continue

        verification = local_vlm_adapter.verify_candidate_box(
            image_path=image_path,
            label=label,
            bbox_xyxy=_bbox(item),
            candidate=item,
            job_id=job_id,
            route_id=route_id,
            runtime=runtime,
        )
        verified_delta += 1
        image_verified_count += 1
        verification_metadata = verification.get("metadata") or {}
        metadata = dict(item.get("metadata") or {})
        metadata["box_semantic_verification"] = {
            "status": verification.get("status"),
            "keep": verification.get("keep"),
            "reason": verification.get("reason"),
            "decision": (verification_metadata.get("semantic_decision") or {}).get("decision"),
            "bbox_xyxy": verification_metadata.get("bbox_xyxy"),
            "external_api_used": False,
        }
        item["metadata"] = metadata

        if verification.get("available") and verification.get("keep") is False:
            removed.append({"candidate": item, "verification": verification})
            continue
        kept.append(item)

    removed_count = len(removed) + len(invalid_label_removed)
    if removed_count:
        status = "BOX_SEMANTIC_FILTER_APPLIED"
    elif verified_delta:
        status = "BOX_SEMANTIC_FILTER_NO_REMOVAL"
    else:
        status = "BOX_SEMANTIC_FILTER_NO_VERIFIABLE_CANDIDATES"
    reason = (
        f"box semantic verifier removed {removed_count} detector candidates before fusion"
        if removed_count else
        "box semantic verifier did not remove candidates for this image"
    )
    return kept, {
        "source_model": "local-qwen3-vl-4b",
        "available": True,
        "status": status,
        "candidate_count": len(kept),
        "reason": reason,
        "metadata": {
            "image_path": image_path,
            "verified_candidate_count": verified_delta,
            "image_candidate_limit": image_candidate_limit,
            "job_candidate_limit": candidate_limit,
            "removed_candidate_count": removed_count,
            "invalid_label_removed_count": len(invalid_label_removed),
            "vlm_removed_count": len(removed),
            "skipped_due_to_candidate_limit": skipped_due_to_limit,
            "removed_label_sample": _removed_label_sample(removed, invalid_label_removed),
            "external_api_used": False,
        },
    }, verified_delta, skipped_due_to_limit


def _match_candidate_label(
    raw_label: Any,
    labels: List[str],
    label_schema: List[Dict[str, Any]] | None = None,
    model_prompt_aliases: List[str] | None = None,
) -> str | None:
    text = _compact(str(raw_label or ""))
    if not text:
        return None
    alias_pairs = _label_alias_pairs(labels, label_schema)
    for label, _, normalized in alias_pairs:
        if text == normalized:
            return label
    for label, _, normalized in sorted(alias_pairs, key=lambda item: len(item[2]), reverse=True):
        if not normalized or len(normalized) < 2:
            continue
        reverse_coverage = len(text) / len(normalized) if text in normalized else 0.0
        if normalized in text or (len(text) >= 4 and reverse_coverage >= 0.6):
            return label
    if len(labels) == 1:
        for alias in model_prompt_aliases or []:
            normalized_alias = _compact(alias)
            if normalized_alias and (normalized_alias in text or text in normalized_alias):
                return labels[0]
    if _has_any(text, ["reflectivevest", "safetyvest", "vest", "fan", "guang", "反光", "光"]):
        label = _first_matching_label(labels, ["反光衣", "反光", "vest"])
        if label:
            return label
    if _has_any(text, ["lanyard", "rope", "lifeline", "登高", "安全绳", "高绳", "绳", "高"]):
        label = _first_matching_label(labels, ["安全登高绳", "登高", "安全绳", "绳"])
        if label:
            return label
    if _has_any(text, ["helmet", "hardhat", "hat", "安全帽", "头盔", "帽"]):
        label = _first_matching_label(labels, ["安全帽", "helmet", "帽"])
        if label:
            return label
    return None


def _canonicalize_candidate_labels(
    candidates: List[Dict[str, Any]],
    labels: List[str],
    label_schema: List[Dict[str, Any]] | None = None,
    model_prompt_aliases: List[str] | None = None,
) -> List[Dict[str, Any]]:
    result: List[Dict[str, Any]] = []
    for candidate in candidates:
        label = _match_candidate_label(candidate.get("label"), labels, label_schema, model_prompt_aliases)
        item = dict(candidate)
        if label:
            raw_label = str(item.get("label") or "")
            item["label"] = label
            if raw_label != label:
                metadata = dict(item.get("metadata") or {})
                metadata["raw_label_before_candidate_normalization"] = raw_label
                item["metadata"] = metadata
        result.append(item)
    return result


def _label_alias_pairs(
    labels: List[str],
    label_schema: List[Dict[str, Any]] | None = None,
) -> List[Tuple[str, str, str]]:
    pairs: List[Tuple[str, str, str]] = []
    seen = set()
    for label in labels:
        for alias in [label, *_traffic_aliases(label)]:
            normalized = _compact(alias)
            key = (label, normalized)
            if normalized and key not in seen:
                pairs.append((label, alias, normalized))
                seen.add(key)

    for item in label_schema or []:
        display_name = str(
            item.get("display_name")
            or item.get("displayName")
            or item.get("name")
            or ""
        ).strip()
        target_label = next(
            (label for label in labels if _compact(label) == _compact(display_name)),
            display_name if display_name in labels else None,
        )
        if not target_label:
            continue
        aliases: List[Any] = [
            display_name,
            item.get("canonical_name"),
            item.get("canonicalName"),
            item.get("english_name"),
            item.get("englishName"),
            item.get("model_prompt"),
            item.get("modelPrompt"),
        ]
        for key in ("positive_prompts_en", "positivePromptsEn", "positive_prompts", "positivePrompts"):
            value = item.get(key)
            if isinstance(value, list):
                aliases.extend(value)
        for alias in aliases:
            text = str(alias or "").strip()
            normalized = _compact(text)
            key = (target_label, normalized)
            if normalized and key not in seen:
                pairs.append((target_label, text, normalized))
                seen.add(key)
    return pairs


def _traffic_aliases(label: str) -> List[str]:
    normalized = _compact(label)
    aliases = {
        "人": ["person", "pedestrian", "people", "human"],
        "人形": ["person", "pedestrian", "people", "human"],
        "车": ["车辆", "汽车", "机动车", "car", "vehicle", "automobile", "van", "truck"],
        "车辆": ["车", "汽车", "机动车", "car", "vehicle", "automobile", "van", "truck"],
        "自行车": ["单车", "脚踏车", "bicycle", "bike"],
        "电动车": ["电瓶车", "电动自行车", "电动摩托车", "electric bicycle", "e-bike", "ebike", "electric scooter", "electric motorcycle"],
        "摩托车": ["摩托", "机车", "motorcycle", "motorbike", "scooter"],
        "安全帽": ["头盔", "helmet", "hard hat", "hardhat", "safety helmet"],
        "戴安全帽": ["佩戴安全帽", "person wearing a safety helmet", "worker wearing safety helmet", "wearing helmet"],
        "没戴安全帽": ["未戴安全帽", "未佩戴安全帽", "person without a safety helmet", "worker without safety helmet", "without helmet", "no helmet"],
        "反光衣": ["反光背心", "安全背心", "reflective safety vest", "safety vest", "reflective vest", "hi-vis vest"],
        "没穿反光衣": ["未穿反光衣", "未穿反光背心", "person without a reflective safety vest", "worker without safety vest", "without reflective vest", "no safety vest"],
        "person": ["pedestrian", "people", "human"],
        "car": ["vehicle", "automobile", "van", "truck"],
        "vehicle": ["car", "automobile", "van", "truck"],
        "bicycle": ["bike"],
        "electricbicycle": ["e-bike", "ebike", "electric scooter", "electric motorcycle"],
        "motorcycle": ["motorbike", "scooter"],
        "safetyhelmet": ["helmet", "hard hat", "hardhat"],
        "reflectivesafetyvest": ["safety vest", "reflective vest", "hi-vis vest"],
    }
    return aliases.get(normalized, [])


def _english_label(label: str) -> str:
    normalized = _compact(label)
    mapping = [
        (("没戴安全帽", "未戴安全帽", "未佩戴安全帽", "withouthelmet", "nohelmet"), "person without a safety helmet"),
        (("戴安全帽", "佩戴安全帽", "wearinghelmet"), "person wearing a safety helmet"),
        (("没穿反光衣", "未穿反光衣", "未穿反光背心", "withoutvest", "novest"), "person without a reflective safety vest"),
        (("反光衣", "反光背心", "安全背心", "reflectivevest", "safetyvest", "hivisvest"), "reflective safety vest"),
        (("安全帽", "头盔", "helmet", "hardhat"), "safety helmet"),
        (("电动车", "电瓶车", "电动自行车", "electricbike", "ebike", "electricscooter"), "electric bicycle"),
        (("自行车", "单车", "脚踏车", "bicycle", "bike"), "bicycle"),
        (("摩托车", "摩托", "机车", "motorcycle", "motorbike", "scooter"), "motorcycle"),
        (("车", "车辆", "汽车", "机动车", "car", "vehicle"), "car"),
        (("人", "行人", "人员", "person", "people", "pedestrian", "human"), "person"),
    ]
    for needles, english in mapping:
        if any(_compact(needle) == normalized or _compact(needle) in normalized for needle in needles):
            return english
    return str(label or "").strip()


def _compact(value: str) -> str:
    return "".join(ch for ch in str(value or "").lower() if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")


def _has_any(text: str, needles: List[str]) -> bool:
    compact_needles = [_compact(needle) for needle in needles if needle]
    return any(needle and needle in text for needle in compact_needles)


def _first_matching_label(labels: List[str], needles: List[str]) -> str | None:
    for needle in needles:
        compact_needle = _compact(needle)
        for label in labels:
            if compact_needle and compact_needle in _compact(label):
                return label
    return None


def _bbox(candidate: Dict[str, Any]) -> List[float]:
    box = candidate.get("bbox_xyxy") or candidate.get("bbox") or []
    if len(box) < 4:
        return [0.0, 0.0, 0.0, 0.0]
    return [float(value) for value in box[:4]]


def _candidate_score(candidate: Dict[str, Any]) -> float:
    for key in ("score", "confidence", "probability"):
        try:
            return float(candidate.get(key) or 0)
        except (TypeError, ValueError):
            continue
    return 0.0


def _removed_label_sample(
    removed: List[Dict[str, Any]],
    invalid_label_removed: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    sample: List[Dict[str, Any]] = []
    for item in removed[:10]:
        candidate = item.get("candidate") or {}
        verification = item.get("verification") or {}
        sample.append(
            {
                "label": candidate.get("label"),
                "raw_label": (candidate.get("metadata") or {}).get("raw_label_before_box_semantic_verification"),
                "source_model": candidate.get("source_model"),
                "score": candidate.get("score"),
                "reason": verification.get("reason"),
                "status": verification.get("status"),
            }
        )
    for candidate in invalid_label_removed[: max(0, 10 - len(sample))]:
        sample.append(
            {
                "label": candidate.get("label"),
                "source_model": candidate.get("source_model"),
                "score": candidate.get("score"),
                "reason": "candidate label could not be matched to project label",
                "status": "INVALID_LABEL_FOR_PROJECT",
            }
        )
    return sample
