from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


QUALITY_FIRST = "quality_first"
HYBRID_TEACHER_QUALITY = "HYBRID_TEACHER_QUALITY"
AUTO_LABEL_TO_MODEL = "AUTO_LABEL_TO_MODEL"


class RequirementParseRequest(BaseModel):
    project_id: Optional[int] = None
    raw_user_text: Optional[str] = None
    rawUserText: Optional[str] = None
    requirement: Optional[str] = None
    text: Optional[str] = None
    labels: List[Any] = Field(default_factory=list)
    raw_user_labels_json: List[Any] = Field(default_factory=list)
    rawUserLabelsJson: List[Any] = Field(default_factory=list)


class DatasetProfileRequest(BaseModel):
    project_id: Optional[int] = None
    dataset_id: Optional[str] = None
    image_paths: List[str] = Field(default_factory=list)
    num_images: Optional[int] = None


class ModelRouteRequest(BaseModel):
    project_id: Optional[int] = None
    dataset_id: Optional[str] = None
    requirement: Dict[str, Any] = Field(default_factory=dict)
    label_schema: List[Dict[str, Any]] = Field(default_factory=list)


class AutoLabelJobRequest(BaseModel):
    job_id: Optional[int] = None
    project_id: Optional[int] = None
    dataset_id: Optional[str] = None
    pipeline: str = AUTO_LABEL_TO_MODEL
    strategy: str = HYBRID_TEACHER_QUALITY
    priority_mode: str = QUALITY_FIRST
    route_plan: Dict[str, Any] = Field(default_factory=dict)
    image_paths: List[str] = Field(default_factory=list)


class FusionRequest(BaseModel):
    job_id: Optional[int] = None
    labels: List[Any] = Field(default_factory=list)
    allowed_labels: List[Any] = Field(default_factory=list)
    allowedLabels: List[Any] = Field(default_factory=list)
    candidates: List[Dict[str, Any]] = Field(default_factory=list)
    image_width: Optional[int] = None
    image_height: Optional[int] = None
    iou_threshold: float = Field(default=0.55, ge=0.0, le=1.0)


class LabelStudioFormatRequest(BaseModel):
    job_id: Optional[int] = None
    project_id: Optional[int] = None
    labels: List[Any] = Field(default_factory=list)
    allowed_labels: List[Any] = Field(default_factory=list)
    allowedLabels: List[Any] = Field(default_factory=list)
    fused_predictions: List[Dict[str, Any]] = Field(default_factory=list)
    task_id_by_image: Dict[str, Any] = Field(default_factory=dict)
    taskIdByImage: Dict[str, Any] = Field(default_factory=dict)
    image_width: Optional[int] = None
    image_height: Optional[int] = None
    label_config: Optional[str] = None
    from_name: Optional[str] = None
    to_name: Optional[str] = None
    result_type: Optional[str] = None
    model_version: Optional[str] = None


class ReviewedTrainingRequest(BaseModel):
    project_id: Optional[int] = None
    job_id: Optional[int] = None
    label_studio_project_id: Optional[int] = None
    output_dir: Optional[str] = None
