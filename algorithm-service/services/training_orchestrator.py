from typing import Any, Dict


def prepare_reviewed_dataset(payload: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "success": True,
        "available": False,
        "status": "TRAINING_NOT_STARTED_STAGE_3",
        "external_api_used": False,
        "project_id": payload.get("project_id"),
        "job_id": payload.get("job_id"),
        "reason": "训练编排依赖 Label Studio 复核结果回读和数据集导出，后续 Stage 8 接入。",
        "next_action": "先完成候选推理、融合、Label Studio prediction 同步和回读验收。",
    }
