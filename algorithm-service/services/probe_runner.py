from collections import defaultdict
from typing import Any, Dict, List

from services import candidate_runner


def run_probe(payload: Dict[str, Any]) -> Dict[str, Any]:
    image_paths = payload.get("image_paths") or []
    if image_paths:
        runner_result = candidate_runner.run_candidates(payload)
        adapter_reports = runner_result.get("adapter_reports") or []
        candidates = runner_result.get("candidates") or []
        model_reports = _model_reports(adapter_reports, candidates, len(image_paths))
        warnings = []
        if not candidates:
            warnings.append({"code": "EMPTY_PROBE", "message": "小样本试跑未产生候选框，需要检查标签、阈值或模型服务。"})
        for report in model_reports:
            if not report.get("available"):
                warnings.append({"code": "MODEL_UNAVAILABLE", "message": f"{report.get('model_id')} 不可用：{report.get('reason')}"})
            elif report.get("empty_rate", 0) > 0.8:
                warnings.append({"code": "HIGH_EMPTY_RATE", "message": f"{report.get('model_id')} 空结果率较高。"})

        return {
            "success": True,
            "available": True,
            "status": "PROBE_COMPLETED",
            "external_api_used": False,
            "job_id": payload.get("job_id"),
            "sample_count": len(image_paths),
            "candidate_count": len(candidates),
            "model_reports": model_reports,
            "warnings": warnings,
            "candidates": candidates,
        }

    return {
        "success": True,
        "available": True,
        "status": "DRY_RUN_READY",
        "external_api_used": False,
        "job_id": payload.get("job_id"),
        "message": "Stage 3 probe dry-run 已接入；真实小样本推理将在 adapter 接入阶段启用。",
        "probe_samples": [],
        "next_action": "进入 Stage 4/5 后接入 DINO/xingmu/LocateAnything 候选推理。",
    }


def _model_reports(adapter_reports: List[Dict[str, Any]], candidates: List[Dict[str, Any]], sample_count: int) -> List[Dict[str, Any]]:
    by_model_candidates: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for candidate in candidates:
        by_model_candidates[str(candidate.get("source_model") or "unknown")].append(candidate)

    grouped_reports: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for report in adapter_reports:
        grouped_reports[str(report.get("source_model") or "unknown")].append(report)

    model_ids = list(dict.fromkeys(list(grouped_reports.keys()) + list(by_model_candidates.keys())))
    results = []
    for model_id in model_ids:
        reports = grouped_reports.get(model_id, [])
        model_candidates = by_model_candidates.get(model_id, [])
        unavailable = [report for report in reports if not report.get("available")]
        image_runs = len(reports) or sample_count or 1
        empty_runs = len([report for report in reports if int(report.get("candidate_count") or 0) == 0])
        low_conf = [candidate for candidate in model_candidates if float(candidate.get("score") or 0) < 0.45]
        results.append(
            {
                "model_id": model_id,
                "available": not unavailable,
                "status": "UNAVAILABLE" if unavailable else "AVAILABLE",
                "sample_count": sample_count,
                "candidate_count": len(model_candidates),
                "empty_rate": round(empty_runs / max(1, image_runs), 4),
                "avg_boxes_per_image": round(len(model_candidates) / max(1, sample_count), 4),
                "low_confidence_ratio": round(len(low_conf) / max(1, len(model_candidates)), 4) if model_candidates else 0.0,
                "reason": unavailable[0].get("reason") if unavailable else None,
            }
        )
    return results
