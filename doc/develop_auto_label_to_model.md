你现在是一个工程开发 agent，请在 `/root/autodl-fs/Annotation-Platform` 项目中执行一次完整升级，把现有智能标注平台从固定 DINO 自动标注链路升级为“LLM 需求解析 + 数据集画像 + 模型路由 + 多模型候选推理 + 质量优先融合 + Label Studio 人审 + 数据集特定模型训练”的闭环能力。

本次任务的核心原则：

1. 不做研究实验式 demo，不做孤立脚本；要把能力接入现有 Annotation-Platform 产品流程。
2. 不新增“速度优先 / 质量优先 / 成本优先 / 召回优先”用户选择项。系统默认固定使用“质量优先”策略。
3. 不破坏现有五服务架构，不破坏现有登录、JWT、用户、组织、项目、Label Studio 同步、上传、自动标注、导出、训练、单图检测等已有流程。
4. 现有 `DINO_THRESHOLD` 和 `DINO_VLM` 链路必须保留，新增能力应作为更高阶的 `AUTO_LABEL_TO_MODEL` / `HYBRID_TEACHER_QUALITY` 流程接入。
5. 所有外部模型、服务和新模块都要有可降级逻辑：某个模型不可用时，系统不能崩溃，必须返回明确的 unavailable 状态、错误原因、下一步建议，并继续使用可用模型。
6. 不允许把 API key、token、JWT、Label Studio token、Hugging Face token、Roboflow key、Kaggle key 打印到日志或提交到代码。
7. 遇到网络下载失败，先加载代理与密钥环境，再重试：

   * `source ~/.config/network/mihomo_proxy.env`
   * `source ~/.config/ai-keys/env`
     不要直接放弃。
8. 遇到 GPU / NVML 异常，例如 `nvidia-smi` 报错 `Failed to initialize NVML`，立即停止长任务，保存日志、诊断、进度文件和已完成产物，把状态标记为 `INFRA_FAILURE_GPU_NVML_LOST`，不要继续假跑。
9. 每个阶段完成后更新进度文件，记录完成内容、修改文件、命令、结果、失败与修复、剩余任务。
10. 最终必须生成完整 Markdown 总结文档，说明架构变化、接口变化、使用流程、验收结果、未完成项和后续建议。

参考文件：

* Annotation-Platform:

  * `README.md`
  * `ARCHITECTURE.md`
  * `router/index.js`
  * `algorithm-service/main.py`
  * `AGENTS.md`
  * 现有进度文件，例如 `doc/develop0521.progress.md`，若存在必须先读。
* xingmu_model:

  * `README.md`
  * `PROJECT_SUMMARY.md`
  * `platform/README.md`
  * `platform/inference_service/app.py`
  * `platform/inference_service/ocr_service.py`
  * `platform/inference_service/change_service.py`
  * `platform/event_engine/`
  * 目录中已有 `deploy/v0.5.6_public_ocr_repair/`、`data_round5_10_public_ocr_repair/`、`release_packages/` 等产物时，不要移动或删除，先只做服务化接入和 manifest 化。

新增外部能力与链接：

1. LocateAnything

   * NVIDIA 项目页：https://research.nvidia.com/labs/lpr/locate-anything/
   * Hugging Face 模型：https://huggingface.co/nvidia/LocateAnything-3B
   * GitHub 代码：https://github.com/NVlabs/Eagle/tree/main/Embodied
   * arXiv：https://arxiv.org/abs/2605.27365
   * 作用：复杂自然语言 grounding、长尾对象补漏、DINO/YOLO/xingmu 候选框清洗、文字/界面/布局类定位扩展。
2. Grounding DINO

   * GitHub：https://github.com/IDEA-Research/GroundingDINO
   * 作用：开放集候选检测器，保留现有 DINO 链路，作为默认开放词汇 proposal teacher。
3. YOLO-World，本轮可作为可选预留，不作为主线硬依赖

   * Ultralytics 文档：https://docs.ultralytics.com/models/yolo-world/
   * 官方/论文代码之一：https://github.com/AILab-CVC/YOLO-World
   * 作用：未来用于实时开放词汇候选推理；本轮只要求 registry 和 adapter 结构预留，除非项目已有依赖或实现成本很低。
4. Label Studio 预标注

   * 预标注文档：https://labelstud.io/guide/predictions
   * Prediction API：https://api.labelstud.io/api-reference/api-reference/predictions/create
   * 作用：将融合后的 prediction 同步到 Label Studio，供人工复核。
5. Weighted Boxes Fusion，可选轻量依赖

   * GitHub：https://github.com/ZFTurbo/Weighted-Boxes-Fusion
   * PyPI：https://pypi.org/project/ensemble-boxes/
   * 作用：多模型候选框融合。若依赖安装有风险，可先实现 NMS + score merge，保留 WBF 接口。

目标架构：

现有架构：

```text
Vue 3 前端
  -> Spring Boot 后端 /api/v1
    -> Label Studio
    -> FastAPI 算法服务
    -> DINO 模型服务
```

升级后架构：

```text
Vue 3 前端
  -> Spring Boot 后端 /api/v1
    -> Label Studio
    -> FastAPI Algorithm Orchestrator
        -> Requirement Parser / LLM 需求解析
        -> Dataset Profiler 数据集画像
        -> Model Registry 模型注册中心
        -> Model Router 模型路由器
        -> Probe Inference 小样本试跑
        -> Candidate Runner 多模型候选推理
        -> Prediction Fusion 质量优先融合
        -> Label Studio Formatter 预标注转换
        -> Training Orchestrator 训练编排
    -> Model Services
        -> Grounding DINO
        -> LocateAnything
        -> xingmu_model inference_service
        -> YOLO-World 可选预留
    -> Training Service
    -> Model Registry / Model Management
```

本次新增主流程名称建议：

```text
AUTO_LABEL_TO_MODEL
HYBRID_TEACHER_QUALITY
```

用户侧产品流程：

```text
Step 1：选择或上传数据集
Step 2：输入类别或自然语言需求
Step 3：LLM 解析需求，生成标签体系，用户确认/编辑标签体系
Step 4：系统抽样做数据集画像和模型适配度分析
Step 5：系统固定采用质量优先自动标注策略，不提供速度/质量/成本/召回选择项
Step 6：执行自动标注，生成候选框、融合结果和预标注
Step 7：同步到 Label Studio，人审复核
Step 8：回读复核结果，导出高质量标注
Step 9：训练数据集特定 student model
Step 10：模型注册、单图检测、后续回流再训练
```

第五步的产品要求：

* 不出现策略选择单选框。
* 不出现“速度优先、质量优先、成本优先、召回优先”的用户可选入口。
* 页面只展示系统采用的固定策略：

  * 策略名称：质量优先自动标注
  * 策略说明：系统会优先保证预标注质量；对复杂自然语言类别、低置信样本、模型分歧样本使用 LocateAnything/VLM 清洗或进入人审优先队列；不会为了速度牺牲明显质量。
  * 用户可以确认开始自动标注，但不能切换策略。
* 后端也不能接收用户传入的 priority_mode 来改变策略。后端固定写入：

  * `priority_mode = "quality_first"`
  * `strategy = "HYBRID_TEACHER_QUALITY"`

第一阶段：启动检查与项目理解

执行内容：

1. 进入 `/root/autodl-fs/Annotation-Platform`。
2. 读取 `AGENTS.md`、`README.md`、`ARCHITECTURE.md`、现有进度文件和参考文件。
3. 检查 git 状态，记录 dirty files，不要覆盖用户已有修改。
4. 梳理现有服务：

   * frontend-vue
   * Spring Boot backend
   * Label Studio 接入
   * algorithm-service
   * DINO 模型服务
   * 训练流程
   * 单图检测流程
   * 可行性评估模块
5. 检查 xingmu_model 路径。优先尝试：

   * `/root/autodl-fs/xingmu_model`
   * `/root/autodl-fs/xingmu-model`
   * 若找不到，用 `find /root/autodl-fs -maxdepth 2 -iname "*xingmu*"` 定位。
6. 新建或更新进度文件：

   * `doc/develop_auto_label_to_model.progress.md`
7. 新建设计文档：

   * `doc/auto_label_to_model_design.md`

阶段一验收标准：

* 已记录当前 git 状态。
* 已确认 Annotation-Platform 和 xingmu_model 的实际路径。
* 已列出现有自动标注、训练、Label Studio 同步相关代码入口。
* 已创建或更新进度文件。
* 未修改业务代码或只新增文档。
* 进度文件记录了启动命令、检查结果和下一阶段计划。

第二阶段：数据结构与后端接口设计

执行内容：

新增或扩展数据库实体，名称可根据当前后端风格调整，但语义必须覆盖：

1. `model_registry`

   * `id`
   * `model_id`
   * `name`
   * `version`
   * `model_type`
   * `task_type`
   * `domain_tags_json`
   * `classes_json`
   * `aliases_json`
   * `endpoint`
   * `status`
   * `metrics_json`
   * `resource_json`
   * `license_info`
   * `created_at`
   * `updated_at`

2. `project_requirement`

   * `id`
   * `project_id`
   * `raw_user_text`
   * `raw_user_labels_json`
   * `parsed_schema_json`
   * `label_schema_json`
   * `prompt_pack_json`
   * `created_by`
   * `created_at`

3. `dataset_profile`

   * `id`
   * `project_id`
   * `dataset_id`
   * `num_images`
   * `sample_count`
   * `profile_json`
   * `quality_warnings_json`
   * `created_at`

4. `model_route_plan`

   * `id`
   * `project_id`
   * `dataset_id`
   * `requirement_id`
   * `primary_model_id`
   * `auxiliary_model_ids_json`
   * `strategy`
   * `priority_mode`
   * `reason`
   * `score_json`
   * `created_at`

5. `auto_label_job`

   * `id`
   * `project_id`
   * `dataset_id`
   * `route_plan_id`
   * `status`
   * `pipeline`
   * `priority_mode`
   * `total_images`
   * `processed_images`
   * `failed_images`
   * `created_by`
   * `created_at`
   * `updated_at`

6. `prediction_candidate`

   * `id`
   * `job_id`
   * `image_id`
   * `label`
   * `bbox_json`
   * `score`
   * `source_model`
   * `model_version`
   * `prompt`
   * `raw_output_json`
   * `created_at`

7. `fused_prediction`

   * `id`
   * `job_id`
   * `image_id`
   * `label`
   * `bbox_json`
   * `final_score`
   * `source_models_json`
   * `fusion_reason`
   * `review_priority`
   * `synced_to_label_studio`
   * `created_at`

新增后端 API，路径可根据当前项目风格微调：

```http
POST /api/v1/projects/{projectId}/requirements/parse
GET  /api/v1/projects/{projectId}/requirements/latest

POST /api/v1/projects/{projectId}/dataset-profile
GET  /api/v1/projects/{projectId}/dataset-profile/latest

GET  /api/v1/models/registry
POST /api/v1/models/registry/sync
GET  /api/v1/models/registry/{modelId}

POST /api/v1/projects/{projectId}/model-route/preview
GET  /api/v1/projects/{projectId}/model-route/latest

POST /api/v1/projects/{projectId}/auto-label/jobs
GET  /api/v1/projects/{projectId}/auto-label/jobs
GET  /api/v1/projects/{projectId}/auto-label/jobs/{jobId}
POST /api/v1/projects/{projectId}/auto-label/jobs/{jobId}/probe
POST /api/v1/projects/{projectId}/auto-label/jobs/{jobId}/run
POST /api/v1/projects/{projectId}/auto-label/jobs/{jobId}/sync-label-studio

GET  /api/v1/projects/{projectId}/auto-label/jobs/{jobId}/candidates
GET  /api/v1/projects/{projectId}/auto-label/jobs/{jobId}/fused-predictions

POST /api/v1/projects/{projectId}/train-from-reviewed-labels
```

接口要求：

* 所有接口必须走现有 JWT 鉴权和项目权限校验。
* 用户只能访问自己组织/项目下的数据。
* `priority_mode` 后端固定为 `quality_first`。
* 前端不提供 priority mode 选择。
* 即使请求体传入其他 priority mode，后端也要忽略或拒绝，并记录安全日志。
* 后端调用 algorithm-service，不直接访问模型服务。
* 后端返回结构必须包含：

  * `success`
  * `message`
  * `data`
  * `errorCode`
  * `requestId` 或已有等价追踪字段

阶段二验收标准：

* 后端实体、DTO、Service、Controller、Repository 或 Mapper 按项目现有风格实现。
* 新 API 能通过鉴权和权限校验。
* 不影响现有 `/api/v1` 接口。
* `priority_mode` 固定为 `quality_first`，没有用户可切换入口。
* 后端测试或编译通过。
* 更新接口文档到 `doc/auto_label_to_model_design.md`。

第三阶段：algorithm-service 编排层改造

执行内容：

把 `algorithm-service/main.py` 从单一 DINO/VLM 调用入口升级为算法编排入口。可保留 main.py 对外接口，但内部拆模块：

```text
algorithm-service/
  main.py
  routers/
    requirement.py
    dataset_profile.py
    model_registry.py
    model_route.py
    auto_label.py
    fusion.py
    label_studio_format.py
    training.py
  services/
    llm_requirement_parser.py
    dataset_profiler.py
    model_registry_service.py
    model_router.py
    probe_runner.py
    candidate_runner.py
    prediction_fusion.py
    label_studio_formatter.py
    training_orchestrator.py
  adapters/
    grounding_dino_adapter.py
    locate_anything_adapter.py
    xingmu_adapter.py
    yolo_world_adapter.py
  schemas/
    requirement_schema.py
    prediction_schema.py
    model_schema.py
    route_schema.py
    job_schema.py
```

新增内部接口：

```http
POST /internal/requirements/parse
POST /internal/datasets/profile
GET  /internal/models/registry
POST /internal/models/registry/sync
POST /internal/model-router/preview
POST /internal/auto-label/probe
POST /internal/auto-label/run
POST /internal/fusion/merge
POST /internal/label-studio/format-predictions
POST /internal/training/prepare-reviewed-dataset
```

LLM 需求解析器输出结构必须包含：

```json
{
  "task_type": "object_detection",
  "domain": "low_altitude_inspection",
  "label_schema": [
    {
      "canonical_name": "person_without_helmet",
      "display_name": "未戴安全帽人员",
      "description": "施工现场中头部未佩戴安全帽的人",
      "positive_prompts": [
        "person without hard hat",
        "worker without helmet",
        "未戴安全帽的人"
      ],
      "negative_prompts": [
        "person wearing helmet",
        "person wearing cap",
        "无法判断是否戴安全帽的远景人"
      ],
      "requires_relation_reasoning": true
    }
  ],
  "prompt_pack": {
    "grounding_dino": "person without hard hat . excavator . road crack . water ponding .",
    "locate_anything": [
      {
        "label": "person_without_helmet",
        "text": "Locate all workers not wearing safety helmets."
      }
    ],
    "xingmu": {
      "candidate_groups": ["M1", "M4", "M5"]
    }
  },
  "review_policy": {
    "priority_mode": "quality_first",
    "auto_accept_threshold": 0.85,
    "manual_review_threshold": 0.45,
    "priority_review_labels": ["person_without_helmet"]
  }
}
```

LLM 不可用时的降级逻辑：

* 若用户提供了明确类别列表，则用规则生成 label_schema。
* 若用户只提供自然语言且 LLM 不可用，则返回 `NEED_USER_LABEL_CONFIRMATION`，提示用户补充类别。
* 不允许生成空 label_schema 后继续跑自动标注。

Dataset Profiler 要实现轻量版：

* 抽样 30~100 张图。
* 统计图片数量、分辨率分布、宽高比、损坏图片、重复文件名。
* 判断是否高分辨率、小目标风险、密集目标风险。
* 可调用已有 VLM 或轻量图像分析模块做场景粗分类；不可用时用规则返回 unknown。
* 输出 quality warnings。

Model Router 固定采用质量优先策略：

```text
优先级：
1. xingmu_model 中与领域和类别强匹配的专用模型
2. Grounding DINO 作为开放集候选模型
3. LocateAnything 作为复杂语言、长尾补漏、候选清洗、分歧仲裁模型
4. YOLO-World 作为可选开放词汇高速候选，除非已接入，否则不作为必需项
```

质量优先 route 输出示例：

```json
{
  "strategy": "HYBRID_TEACHER_QUALITY",
  "priority_mode": "quality_first",
  "primary_model": "xingmu_m4_ppe_yolov8",
  "auxiliary_models": ["grounding_dino", "locate_anything"],
  "quality_policy": {
    "use_probe": true,
    "use_fusion": true,
    "use_vlm_verify_for_complex_labels": true,
    "manual_review_for_conflicts": true,
    "do_not_auto_drop_medium_confidence": true
  },
  "reason": "用户任务属于低空施工安全场景，xingmu M4 覆盖 PPE，Grounding DINO 用于开放词汇补漏，LocateAnything 用于未戴安全帽等复杂语义校验。"
}
```

阶段三验收标准：

* algorithm-service 能启动。
* 新内部 API 能返回合理 JSON。
* LLM 不可用时有明确降级逻辑。
* route 结果固定 `quality_first`。
* 任一模型服务不可用时，adapter 返回 `available=false` 和明确原因，不导致整个服务 500。
* `python -m compileall algorithm-service` 通过。
* 若项目已有 Python 测试框架，新增或更新测试并通过。

第四阶段：模型 adapter 接入

执行内容：

实现统一候选格式：

```json
{
  "image_id": "xxx.jpg",
  "label": "excavator",
  "bbox_xyxy": [120, 80, 430, 320],
  "score": 0.87,
  "source_model": "grounding_dino",
  "model_version": "v1",
  "prompt": "excavator",
  "raw_output": {},
  "metadata": {
    "route_id": "route_xxx",
    "job_id": "job_xxx"
  }
}
```

Grounding DINO adapter：

* 复用现有 DINO 服务。
* 输入 image + prompt + thresholds。
* 输出统一 candidate 格式。
* 保留现有 DINO_THRESHOLD 和 DINO_VLM 调用方式。
* 新增质量优先模式下更偏召回的初始阈值，但融合阶段再清洗，不直接全部同步。

LocateAnything adapter：

* 新增 `locate-anything-service` 或 algorithm-service 内部 worker，优先采用独立服务。
* 安装参考：

  * `git clone https://github.com/NVlabs/Eagle.git eagle`
  * `cd eagle/Embodied`
  * `pip install -e .`
  * 模型：`nvidia/LocateAnything-3B`
* 注意模型输出格式：

  * bbox: `<ref>label</ref><box><x1><y1><x2><y2></box>`
  * 坐标通常在 `[0, 1000]`，需要转换成像素坐标。
  * no object: `<box>none</box>`
* 实现 parser：

  * 能解析一个或多个 box。
  * 能解析 none。
  * 能处理格式异常并返回 parse_error。
  * 输出统一 candidate 格式。
* 模式：

  * 默认使用 hybrid。
  * 质量优先时，对复杂关系类、低置信候选、模型分歧样本调用。
  * 不强制对所有图片全量调用，避免成本爆炸。
* 如果机器显存不足或模型下载失败：

  * adapter 返回 unavailable。
  * route 仍可用 Grounding DINO / xingmu_model 继续跑。

xingmu_model adapter：

* 不直接读 161G 工作目录，不移动、不删除训练产物。
* 通过 `platform/inference_service/app.py` 或已有服务接口调用。
* 若当前没有统一模型列表接口，先实现轻量 manifest 扫描或静态配置：

  * M1 可见光无人机通用检测
  * M2 热成像检测
  * M3 烟火检测
  * M4 安全帽/PPE
  * M5 道路市政
  * M6 水域环境
  * M7 光伏巡检
  * M8 OCR/车牌/文字识别
  * M9 事件规则层
  * M10 变化检测
* 输出模型组、类别、endpoint、status。
* 调用推理接口并转换为统一 candidate 格式。
* 若 xingmu_model 服务不可用，返回 unavailable，不阻塞 Grounding DINO 流程。

YOLO-World adapter：

* 本轮只做结构预留。
* 若已有 ultralytics 环境且接入成本低，可实现 basic adapter。
* 不要让 YOLO-World 成为本轮验收阻塞项。

阶段四验收标准：

* Grounding DINO adapter 可调用现有 DINO 服务并输出统一 candidate。
* LocateAnything adapter 至少完成服务骨架、健康检查、parser、unavailable 降级；若资源允许，完成真实模型 smoke test。
* xingmu adapter 可返回模型组 registry；若服务可启动，完成至少一张图 smoke inference。
* 所有 adapter 统一输出 schema。
* 单个模型失败不会导致整个自动标注任务失败。
* 所有模型链接、安装说明、资源限制记录在文档中。

第五阶段：小样本 probe、质量优先融合和人审优先级

执行内容：

实现 probe 流程：

```text
抽样 30~100 张图片
    -> 调用 route 推荐模型
    -> 收集各模型候选
    -> 统计空结果率、平均框数、重复框、类别覆盖、低置信比例、模型分歧
    -> 生成模型适配度报告
```

probe 输出示例：

```json
{
  "probe_id": "probe_xxx",
  "sample_count": 50,
  "model_reports": [
    {
      "model_id": "xingmu_m4_ppe_yolov8",
      "available": true,
      "coverage_score": 0.82,
      "empty_rate": 0.12,
      "avg_boxes_per_image": 3.4,
      "warnings": []
    },
    {
      "model_id": "grounding_dino",
      "available": true,
      "coverage_score": 0.74,
      "empty_rate": 0.20,
      "avg_boxes_per_image": 5.1,
      "warnings": ["候选偏多，需要融合清洗"]
    },
    {
      "model_id": "locate_anything",
      "available": false,
      "coverage_score": null,
      "warnings": ["模型未下载或显存不足，已降级"]
    }
  ],
  "selected_strategy": "HYBRID_TEACHER_QUALITY",
  "priority_mode": "quality_first"
}
```

融合策略：

1. 同类别高 IoU 框：

   * 默认 NMS 或 WBF。
   * 若安装 `ensemble-boxes` 成功，可使用 WBF。
   * 若安装失败，使用内置 NMS + score merge。
2. 多模型一致：

   * 提高 final_score。
   * `fusion_reason = "multi_model_agreement"`
3. 只有专用模型命中：

   * 如果 xingmu 专用模型与类别强匹配，可保留为高质量候选。
4. 只有开放集模型命中：

   * 保留为中等优先级人审候选。
5. LocateAnything 与其他模型冲突：

   * 对复杂自然语言标签，LocateAnything 结果权重更高。
   * 对普通实体类别，专用检测器或 Grounding DINO 权重更高。
6. 低置信、模型分歧、边界异常：

   * 不自动丢弃。
   * 标记为 `review_priority = "high"`，优先人审。
7. 明显无效框：

   * 面积为 0、坐标越界严重、类别为空、解析失败，进入 reject 日志，不同步到 Label Studio。

融合后输出：

```json
{
  "image_id": "xxx.jpg",
  "label": "person_without_helmet",
  "bbox_xyxy": [100, 80, 220, 360],
  "final_score": 0.79,
  "source_models": ["grounding_dino", "locate_anything"],
  "fusion_reason": "open_vocab_candidate_verified_by_locate_anything",
  "review_priority": "high",
  "sync_policy": "prediction_for_human_review"
}
```

阶段五验收标准：

* probe API 可运行并保存结果。
* fusion API 可运行并保存 candidate 和 fused_prediction。
* `priority_mode` 始终为 `quality_first`。
* 中低置信样本不会静默丢失，必须有 review_priority 或 reject reason。
* 融合结果能追溯 source_model、prompt、raw_output。
* 有单元测试覆盖：

  * 空候选
  * 重叠框
  * 多模型一致
  * 模型冲突
  * LocateAnything parse none
  * 坐标越界
  * 模型 unavailable

第六阶段：Label Studio prediction 同步

执行内容：

实现 `label_studio_formatter.py`：

* 输入 fused_prediction。
* 输出 Label Studio prediction JSON。
* 坐标从像素 xyxy 转成 Label Studio rectanglelabels 格式：

  * `x`
  * `y`
  * `width`
  * `height`
  * 百分比坐标
* 必须匹配当前项目 labeling config。
* `from_name`、`to_name`、`type` 应从项目配置读取，不能硬编码一个全局值。
* 写入 `model_version`：

  * `HYBRID_TEACHER_QUALITY/{job_id}`
* 写入 score。
* 每条 prediction 保留 source metadata，若 Label Studio 格式不支持直接展示，则保留在平台数据库。

同步策略：

```text
fused_prediction
    -> Label Studio prediction JSON
    -> 后端调用 Label Studio API
    -> Label Studio 页面展示预标注
    -> 人工复核
```

阶段六验收标准：

* 至少用一个测试项目同步一批 prediction 到 Label Studio。
* Label Studio 页面能看到预标注框。
* 标签名与项目配置一致。
* 复核后平台能回读 review stats / review results。
* 不破坏原有 DINO prediction 同步逻辑。
* 对 Label Studio API 调用失败有重试与错误记录。

第七阶段：前端页面与路由

执行内容：

在 Vue 3 前端新增或接入页面：

```text
/projects/:id/auto-label
/projects/:id/auto-label/jobs/:jobId
/models/registry
```

如果现有路由结构不同，按当前 `router/index.js` 风格接入。

页面流程：

1. 数据集选择

   * 显示项目内已上传图片/数据集。
   * 显示图片数量、上传状态。
2. 需求输入

   * 支持类别列表。
   * 支持自然语言描述。
3. LLM 解析结果确认

   * 展示 canonical_name、display_name、description、positive_prompts、negative_prompts。
   * 用户可编辑标签名、描述、同义词、排除条件。
   * 用户确认后再进入下一步。
4. 数据集画像与模型适配度

   * 展示数据集画像。
   * 展示模型可用性。
   * 展示系统推荐主模型和辅助模型。
   * 展示推荐理由。
5. 固定质量优先策略确认

   * 只展示“质量优先自动标注”。
   * 不提供速度/质量/成本/召回 selector。
   * 不允许前端传入 priority mode 覆盖后端。
6. 自动标注任务执行

   * 展示 job 状态。
   * 展示 total_images、processed_images、failed_images。
   * 展示模型调用情况。
   * 展示错误和降级原因。
7. 结果预览与同步 Label Studio

   * 展示若干图片的融合框预览。
   * 展示 source_model 和 review_priority。
   * 提供“同步到 Label Studio”按钮。
8. 人审与训练入口

   * 提供“进入 Label Studio 复核”。
   * 复核后提供“用已复核标注训练模型”。

前端文案要求：

* 不出现英文用户可见标签，除非是模型名或必要技术名。
* 不出现“容易错检、容易漏检”等负面营销式文案。
* 用正向表达：

  * “建议人工优先复核”
  * “需要进一步确认”
  * “模型结果存在分歧”
  * “系统已进入质量优先处理”
* 不要把 unavailable 显示成崩溃。应显示：

  * “该模型当前不可用，系统已自动降级到可用模型。”
* 前端不得暴露内部 token、服务地址、绝对路径。

阶段七验收标准：

* 新路由能访问。
* 项目详情页有“智能自动标注”入口。
* 第五步没有策略选择器，只有固定“质量优先自动标注”说明和确认按钮。
* 前端不会发送可变 priority mode。
* job 状态轮询正常。
* prediction 预览能显示框、类别、来源模型、人审优先级。
* `npm run build` 通过。
* 若项目有前端测试，相关测试通过。

第八阶段：训练闭环接入

执行内容：

把已复核 Label Studio 标注接入训练流程：

```text
Label Studio reviewed annotations
    -> 平台回读 review results
    -> 导出 COCO / YOLO 格式
    -> 数据检查
    -> train/val split
    -> 启动 student model 训练
    -> 保存指标
    -> 注册模型
    -> 单图检测页面可选择新模型
```

训练策略：

* Teacher models：

  * Grounding DINO
  * LocateAnything
  * xingmu_model
  * YOLO-World 可选
* Student models：

  * 复用现有单类别 AutoML 训练能力。
  * 如已有多类别训练能力，则支持多类别 YOLO / RT-DETR。
  * 若当前只支持单类别，先完成单类别闭环，并在文档中标明多类别训练为后续扩展。
* 必须冻结数据版本：

  * `dataset_version`
  * `label_schema_version`
  * `reviewed_annotation_version`
  * `train_val_split_version`

模型卡输出：

```text
模型名称
项目 ID
训练数据版本
标注版本
类别列表
训练图片数
验证图片数
训练配置
mAP50 / precision / recall / F1 或现有指标
每类指标
推荐阈值
失败样例
适用场景
不适用场景
模型权重路径
推理服务 endpoint
```

阶段八验收标准：

* 人审后的标注能导出为训练格式。
* 能启动一次训练任务或复用现有训练入口完成 smoke train。
* 训练状态可轮询。
* 训练产物能进入模型管理。
* 单图检测页面能选择或至少看到新模型记录。
* 若训练因资源不足无法完整运行，必须完成 dry-run / small-run，并记录未完成原因，不允许伪造训练完成。

第九阶段：xingmu_model 资产治理与平台化接入

执行内容：

不要清理或重排 xingmu_model 大目录。先做低风险接入：

1. 生成或补充模型 manifest：

   * `platform/model_registry/xingmu_models.yaml`
   * 或 Annotation-Platform 内部 `config/xingmu_model_registry.yaml`
2. 每个模型组至少记录：

   * group_id
   * group_name
   * task_type
   * domain_tags
   * classes
   * aliases
   * endpoint
   * status
   * version
   * notes
3. 对接 `platform/inference_service/app.py`。
4. 能在 Annotation-Platform 的 Model Registry 页面看到 xingmu 模型组。
5. Model Router 能根据类别/领域选择 xingmu 模型。

阶段九验收标准：

* xingmu M1–M10 至少以 manifest 形式出现在 Model Registry。
* 不移动、不删除、不重命名 xingmu_model 大目录中的数据、权重、release 包。
* 至少完成一个 xingmu 模型组的服务可用性检查。
* 如果服务无法启动，记录原因和启动命令，不阻塞平台主流程。

第十阶段：测试、验收与非回归

必须执行的验证命令，根据项目实际调整：

后端：

```bash
cd /root/autodl-fs/Annotation-Platform
mvn test
# 或进入 backend-springboot 后执行
```

前端：

```bash
cd /root/autodl-fs/Annotation-Platform/frontend-vue
npm ci
npm run build
# 如果 package.json 有 test，再执行 npm run test
```

算法服务：

```bash
cd /root/autodl-fs/Annotation-Platform
python -m compileall algorithm-service
# 如果有 pytest，则执行 pytest
```

服务 smoke test：

* 登录/注册不受影响。
* 创建项目不受影响。
* 配置标签不受影响。
* 上传图片不受影响。
* 现有 DINO_THRESHOLD 自动标注不受影响。
* 现有 DINO_VLM 自动标注不受影响。
* 新增 AUTO_LABEL_TO_MODEL 可创建 job。
* LLM 解析接口可返回 label_schema 或降级提示。
* dataset profile 可生成。
* route preview 固定 quality_first。
* probe 可运行。
* fusion 可生成 fused_prediction。
* Label Studio sync 可写入 predictions。
* Label Studio 页面可展示预标注。
* review stats / review results 可回读。
* train-from-reviewed-labels 可启动或进入明确状态。
* 模型管理可看到新模型或训练任务记录。
* 单图检测不受影响。

人工/界面验收：

* `/dashboard` 正常。
* `/projects` 正常。
* `/projects/:id` 正常。
* `/projects/:id/auto-label` 正常。
* `/model-training` 正常。
* `/single-class-detection` 正常。
* `/feasibility` 正常。
* `/profile` 正常。
* `/settings` 正常。
* 第五步没有优先级选项，只显示质量优先策略说明。
* 没有新增英文用户可见文案，模型名除外。
* 错误状态有友好提示。
* 不泄露内部路径和 token。

最终验收产物：

1. 代码修改完成。
2. 所有测试命令输出保存到进度文件。
3. 新增或更新文档：

   * `doc/auto_label_to_model_design.md`
   * `doc/develop_auto_label_to_model.progress.md`
   * `doc/auto_label_to_model_final_report.md`
4. final report 必须包含：

   * 本次目标
   * 已完成阶段
   * 修改文件列表
   * 新增接口列表
   * 新增数据结构
   * 新增前端页面
   * 新增模型 adapter
   * LocateAnything 接入状态
   * Grounding DINO 复用状态
   * xingmu_model 接入状态
   * YOLO-World 是否预留/接入
   * Label Studio prediction 同步状态
   * 训练闭环状态
   * 测试命令和结果
   * 已知问题
   * 未完成项
   * 下一步建议

失败处理规则：

* 任何阶段失败，先定位原因并尝试修复。
* 网络失败先加载代理与密钥环境后重试。
* 依赖冲突时，不要强行污染主环境；优先新增隔离 requirements、服务级 Dockerfile 或文档化环境。
* 模型太大无法下载时，完成 adapter、parser、healthcheck、unavailable 降级和文档，不阻塞主流程。
* GPU 不可用时，不跑真实模型推理，完成 CPU 可执行测试和服务降级测试。
* 不允许把“只完成 healthcheck”写成完整能力完成。必须区分：

  * scaffold
  * dry_run
  * healthcheck
  * adapter_ready
  * model_downloaded
  * real_inference_pass
  * label_studio_sync_pass
  * train_smoke_pass
  * production_ready

最终交付前自检清单：

* 第五步固定质量优先，前端无 selector，后端无可变 priority mode。
* 现有 DINO_THRESHOLD / DINO_VLM 没有被删除。
* Label Studio 预标注 JSON 与项目标签配置一致。
* 每个 prediction 能追溯 source_model、prompt、job_id。
* 模型不可用时有降级，不是 500 崩溃。
* 权限校验仍然生效。
* 没有提交密钥。
* 前端 build 通过。
* 后端测试或编译通过。
* algorithm-service compile/test 通过。
* 文档完整。
* 进度文件完整。
* 最终报告完整
关于xingmumodel中的模型。我有一个前端页面中展示了32类可验收场景32 个可验收场景展示，只用这32个可靠的模型。因为有些模型是不可靠的。
所有要用到大模型的地方，例如location anything或者用qwen3vl4b来做语义验证，都使用GPU本地部署来做，不要调api做。