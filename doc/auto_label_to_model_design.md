# AUTO_LABEL_TO_MODEL 设计文档

更新时间：2026-06-03

## 目标

将 Annotation-Platform 从固定 DINO 自动标注链路升级为工程化闭环：

```text
LLM/本地大模型需求解析
  -> 数据集画像
  -> 模型路由
  -> 多模型候选推理
  -> 质量优先融合
  -> Label Studio 人审
  -> 基于已复核标注训练数据集特定模型
  -> 模型注册与回流
```

新增主流程：

- `AUTO_LABEL_TO_MODEL`
- `HYBRID_TEACHER_QUALITY`

现有 `DINO_THRESHOLD` 和 `DINO_VLM` 必须保留，不得删除或改坏。

## 硬约束

1. 前端不得提供“速度优先 / 质量优先 / 成本优先 / 召回优先”策略选择器。
2. 系统固定使用：
   - `strategy = "HYBRID_TEACHER_QUALITY"`
   - `priority_mode = "quality_first"`
3. 后端不得接收用户可变 `priority_mode` 来改变策略；请求中出现可变 priority 时应忽略或拒绝。
4. 所有大模型判断均使用 GPU 本地部署，不调用外部 API：
   - LocateAnything 使用本地 GPU 服务。
   - Qwen3-VL-4B 或同类 VLM 语义验证使用本地 GPU 服务。
   - LLM 需求解析也应接入本地 OpenAI-compatible 服务或本地推理服务。
5. 不允许把 API key、JWT、Label Studio token、HF/Kaggle/Roboflow key 写入代码、日志或返回前端。
6. xingmu_model 只使用 32 个前端可验收场景对应的可靠能力；隐藏的 8 个不作为默认可路由模型。
7. xingmu_model 大目录只做 manifest 化和服务化接入，不移动、不删除、不重排已有数据、权重、release 包。
8. 模型不可用必须降级为 `available=false` 和明确原因，不能让平台主流程 500 崩溃。

## 当前平台架构

```text
Vue 3 前端
  -> Spring Boot 后端 /api/v1
    -> Label Studio
    -> FastAPI algorithm-service
    -> DINO 模型服务
```

当前服务状态：

- Spring Boot：`8080`
- Label Studio 公开代理：`5001`
- Label Studio 内部服务：`15001`
- DINO：`5003`
- algorithm-service：`8001`
- Vue 前端：`6006`

## 目标架构

```text
Vue 3 前端
  -> Spring Boot 后端 /api/v1
    -> Label Studio
    -> FastAPI Algorithm Orchestrator
        -> Requirement Parser
        -> Dataset Profiler
        -> Model Registry
        -> Model Router
        -> Probe Runner
        -> Candidate Runner
        -> Prediction Fusion
        -> Label Studio Formatter
        -> Training Orchestrator
    -> Model Services
        -> Grounding DINO
        -> Local LocateAnything
        -> Local Qwen3-VL / VLM semantic verifier
        -> xingmu_model inference_service
        -> YOLO-World adapter placeholder
```

## xingmu_model 接入口径

实际路径：

```text
/root/autodl-fs/xingmu_model
```

来源文件：

- `/root/autodl-fs/xingmu_model/platform/inference_service/capability_catalog.py`
- `/root/autodl-fs/xingmu_model/platform/inference_service/app.py`
- `/root/autodl-fs/xingmu_model/platform/frontend/app.js`
- `/root/autodl-fs/xingmu_model/reports/frontend_v053_single_version_coverage_20260525.md`

当前 xingmu_model 前端口径：

```json
{
  "excel_rows": 40,
  "displayed_capabilities": 32,
  "hidden_capabilities": 8,
  "mapped_capabilities": 32,
  "direct_testable": 32,
  "scenario_examples_ready": 32,
  "scenario_examples_expected": 32
}
```

### 32 个可验收场景

后续 Annotation-Platform 的 xingmu 路由只默认使用以下 32 个场景：

| 编号 | 场景 | 算法 | 类型 | xingmu endpoint / model group |
|---:|---|---|---|---|
| 1 | 通用场景 | 人形 | image_detection | `/api/model-demo/predict`, `m1_uav_rgb_general` |
| 2 | 通用场景 | 红外人形 | image_detection | `/api/model-demo/predict`, `m2_thermal` |
| 3 | 通用场景 | 车辆 | image_detection | `/api/model-demo/predict`, `m1_uav_rgb_general` |
| 4 | 通用场景 | 红外车辆 | image_detection | `/api/model-demo/predict`, `m2_thermal` |
| 5 | 通用场景 | 人群聚集 | event_sample | `/api/model-demo/event-video-replay` |
| 6 | 通用场景 | 船只 | image_detection | `/api/model-demo/predict`, `m1_uav_rgb_general` |
| 7 | 通用场景 | 红外船只 | image_detection | `/api/model-demo/predict`, `m2_thermal` |
| 8 | 通用场景 | 通用文字OCR识别 | single_image_endpoint | `/api/model-demo/ocr` |
| 9 | 消防场景 | 烟火 | image_detection | `/api/model-demo/predict`, `m3_smoke_fire` |
| 10 | 消防场景 | 红外烟火 | image_detection | `/api/model-demo/predict`, `m2_thermal` |
| 11 | 工地场景 | 安全帽 | image_detection | `/api/model-demo/predict`, `m4_ppe` |
| 12 | 工地场景 | 安全帽未佩戴 | single_image_endpoint | `/api/model-demo/no-helmet-public` |
| 13 | 工地场景 | 工程车辆 | image_detection | `/api/model-demo/predict`, `m1_uav_rgb_general` |
| 14 | 工地场景 | 挖掘机 | image_detection | `/api/model-demo/predict`, `m1_uav_rgb_general` |
| 15 | 城管场景 | 渣土车 | image_detection | `/api/model-demo/predict`, `m1_uav_rgb_general` |
| 16 | 城管场景 | 垃圾包 | image_detection | `/api/model-demo/predict`, `m6_water_env` |
| 19 | 城管场景 | 建筑垃圾乱堆 | single_image_endpoint | `/api/model-demo/construction-waste-pile-proxy` |
| 20 | 市政场景 | 井盖 | single_image_endpoint | `/api/model-demo/manhole-abnormal-v2` |
| 21 | 能源场景 | 太阳能板 | image_detection | `/api/model-demo/predict`, `m7_solar` |
| 23 | 水利场景 | 水面漂浮物 | image_detection | `/api/model-demo/predict`, `m6_water_env` |
| 24 | 水利场景 | 水面油污 | image_detection | `/api/model-demo/predict`, `m6_water_env` |
| 25 | 水利场景 | 水面垃圾 | image_detection | `/api/model-demo/predict`, `m6_water_env` |
| 26 | 水利场景 | 水面植物 | image_detection | `/api/model-demo/predict`, `m6_water_env` |
| 27 | 水利场景 | 涉水识别 | single_image_endpoint | `/api/model-demo/water-intrusion-proxy-v2` |
| 28 | 环保场景 | 东西焚烧 | single_image_endpoint | `/api/model-demo/open-burning-dual-light-proxy-v2` |
| 29 | 交通场景 | 交通拥堵 | event_sample | `/api/model-demo/event-video-replay` |
| 30 | 交通场景 | 路面标线 | single_image_endpoint | `/api/model-demo/road-marking-wear` |
| 31 | 交通场景 | 路面破损 | image_detection | `/api/model-demo/predict`, `m5_road_municipal` |
| 32 | 交通场景 | 路面杂物 | image_detection | `/api/model-demo/predict`, `m5_road_municipal` |
| 33 | 交通场景 | 路面积水 | image_detection | `/api/model-demo/predict`, `m5_road_municipal` |
| 34 | 交通场景 | 非机动车道占用 | single_image_endpoint | `/api/model-demo/traffic-lane-parking-proxy-v2` |
| 36 | 交通场景 | 近景车牌号码识别 | single_image_endpoint | `/api/model-demo/plate-recognize` |

### 不默认使用的 8 个隐藏场景

以下能力在 xingmu 前端当前被隐藏，Annotation-Platform 默认路由也不得自动使用：

- 17 城管场景 / 小吃车
- 18 城管场景 / 违规摆摊
- 22 水利场景 / 非法垂钓
- 35 交通场景 / 违停区域占用
- 37 交通场景 / 高速应急车道占用
- 38 交通场景 / 行人闯入高速公路
- 39 林业场景 / 林场违建识别
- 40 林业场景 / 非法偷盗识别

## 现有关键代码入口

### Spring Boot

- 项目管理：`ProjectController`
- 自动标注：`AutoAnnotationController`, `AutoAnnotationService`
- 算法转发：`AlgorithmController`, `AlgorithmServiceImpl`
- Label Studio：`LabelStudioController`, `LabelStudioProxyServiceImpl`, `LabelStudioWebhookController`
- 上传：`FileUploadController`
- 训练：`TrainingController`, `TrainingService`, `CustomModelController`, `CustomModelService`
- 单图检测：`SingleClassDetectionController`, `SingleClassDetectionService`
- 可行性评估：`FeasibilityAssessmentController`, `FeasibilityAssessmentService`
- 增量回流：`EdgeSimulatorController`, `InferenceDataPointController`, `VlmJudgeService`
- 权限：`ProjectAccessService`

### algorithm-service

- 入口：`algorithm-service/main.py`
- 现有路由：`routers/auto_annotation.py`, `routers/feasibility.py`, `routers/vlm.py`, `routers/dino.py`, `routers/training.py`, `routers/single_class_detection.py`, `routers/edge_inference.py`, `routers/reinference.py`
- 现有 LLM：`services/llm_service.py`
- 现有模型服务封装：`services/model_service.py`
- 现有任务管理：`services/task_manager.py`

### frontend-vue

- 路由：`frontend-vue/src/router/index.js`
- 项目列表：`ProjectList.vue`
- 项目详情：`ProjectDetail.vue`
- 单类别训练/检测：`SingleClassWorkflow.vue`, `ModelTraining.vue`, `SingleClassDetection.vue`
- 可行性评估：`views/feasibility/*`
- 组件：`DataManager.vue`, `DinoTask.vue`, `VlmTask.vue`, `Training.vue`, `ReviewResults.vue`, `EdgeSimulator.vue`

## 当前 API 调用风险

现有代码中 LLM/VLM 使用外部 OpenAI-compatible API：

- `algorithm-service/services/llm_service.py`
- `algorithm-service/routers/auto_annotation.py`
- `algorithm-service/routers/feasibility.py`
- `backend-springboot/src/main/java/com/annotation/platform/service/user/UserModelConfigService.java`

后续阶段必须把这些路径改造为本地 GPU 大模型服务调用，或封装成本地 OpenAI-compatible endpoint，并移除硬编码 key 的运行依赖。

## 新增接口规划

后端 `/api/v1`：

- `POST /projects/{projectId}/requirements/parse`
- `GET /projects/{projectId}/requirements/latest`
- `POST /projects/{projectId}/dataset-profile`
- `GET /projects/{projectId}/dataset-profile/latest`
- `GET /models/registry`
- `POST /models/registry/sync`
- `GET /models/registry/{modelId}`
- `POST /projects/{projectId}/model-route/preview`
- `GET /projects/{projectId}/model-route/latest`
- `POST /projects/{projectId}/auto-label/jobs`
- `GET /projects/{projectId}/auto-label/jobs`
- `GET /projects/{projectId}/auto-label/jobs/{jobId}`
- `POST /projects/{projectId}/auto-label/jobs/{jobId}/probe`
- `POST /projects/{projectId}/auto-label/jobs/{jobId}/run`
- `POST /projects/{projectId}/auto-label/jobs/{jobId}/sync-label-studio`
- `GET /projects/{projectId}/auto-label/jobs/{jobId}/candidates`
- `GET /projects/{projectId}/auto-label/jobs/{jobId}/fused-predictions`
- `POST /projects/{projectId}/train-from-reviewed-labels`

### Stage 2 已实现后端接口

已在 Spring Boot 后端实现并通过 smoke 的接口：

- `POST /api/v1/models/registry/sync`
- `GET /api/v1/models/registry`
- `GET /api/v1/models/registry/{modelId}`
- `POST /api/v1/projects/{projectId}/requirements/parse`
- `GET /api/v1/projects/{projectId}/requirements/latest`
- `POST /api/v1/projects/{projectId}/dataset-profile`
- `GET /api/v1/projects/{projectId}/dataset-profile/latest`
- `POST /api/v1/projects/{projectId}/model-route/preview`
- `GET /api/v1/projects/{projectId}/model-route/latest`
- `POST /api/v1/projects/{projectId}/auto-label/jobs`
- `GET /api/v1/projects/{projectId}/auto-label/jobs`
- `GET /api/v1/projects/{projectId}/auto-label/jobs/{jobId}`
- `POST /api/v1/projects/{projectId}/auto-label/jobs/{jobId}/probe`
- `POST /api/v1/projects/{projectId}/auto-label/jobs/{jobId}/run`
- `POST /api/v1/projects/{projectId}/auto-label/jobs/{jobId}/sync-label-studio`
- `GET /api/v1/projects/{projectId}/auto-label/jobs/{jobId}/candidates`
- `GET /api/v1/projects/{projectId}/auto-label/jobs/{jobId}/fused-predictions`
- `POST /api/v1/projects/{projectId}/train-from-reviewed-labels`

实现说明：

- 统一使用现有 `Result` 响应包装，`requestId` 放入 `data.requestId` 作为追踪字段。
- Project-scoped API 全部先走 `ProjectAccessService`，确保用户只能访问自己组织下项目。
- `priority_mode` 后端固定为 `quality_first`，请求体传入 `speed_first`、`cost_first` 等值会被忽略并记录安全日志。
- `strategy` 固定为 `HYBRID_TEACHER_QUALITY`。
- xingmu 注册中心只 seed 前端展示的 32 个可验收能力；隐藏 8 个只作为 `hiddenXingmuScenariosExcluded` 返回，不进入默认路由。
- LocateAnything、本地 Qwen3-VL/VLM、YOLO-World 在 Stage 2 只进入 registry/adaptor placeholder，并明确 `UNAVAILABLE`，禁止回退到外部 API。
- `probe/run/sync-label-studio/train-from-reviewed-labels` 目前是后端状态接口和降级骨架；真实 algorithm-service 编排在 Stage 3+ 接入。

algorithm-service 内部接口：

- `POST /internal/requirements/parse`
- `POST /internal/datasets/profile`
- `GET /internal/models/registry`
- `POST /internal/models/registry/sync`
- `POST /internal/model-router/preview`
- `POST /internal/auto-label/probe`
- `POST /internal/auto-label/run`
- `POST /internal/fusion/merge`
- `POST /internal/label-studio/format-predictions`
- `POST /internal/training/prepare-reviewed-dataset`

### Stage 3 已实现 algorithm-service 内部编排层

已新增并挂载：

- `algorithm-service/schemas/auto_label_to_model.py`
- `algorithm-service/routers/requirement.py`
- `algorithm-service/routers/dataset_profile.py`
- `algorithm-service/routers/model_registry.py`
- `algorithm-service/routers/model_route.py`
- `algorithm-service/routers/auto_label.py`
- `algorithm-service/routers/fusion.py`
- `algorithm-service/routers/label_studio_format.py`
- `algorithm-service/routers/training_orchestration.py`
- `algorithm-service/services/llm_requirement_parser.py`
- `algorithm-service/services/dataset_profiler.py`
- `algorithm-service/services/model_registry_service.py`
- `algorithm-service/services/model_router.py`
- `algorithm-service/services/probe_runner.py`
- `algorithm-service/services/candidate_runner.py`
- `algorithm-service/services/prediction_fusion.py`
- `algorithm-service/services/label_studio_formatter.py`
- `algorithm-service/services/training_orchestrator.py`
- `algorithm-service/adapters/grounding_dino_adapter.py`
- `algorithm-service/adapters/xingmu_adapter.py`
- `algorithm-service/adapters/locate_anything_adapter.py`
- `algorithm-service/adapters/local_vlm_adapter.py`
- `algorithm-service/adapters/yolo_world_adapter.py`

Stage 3 行为：

- 内部接口走 `/internal/...`，不叠加 `/api/v1` 前缀，供 Spring Boot 后端调用。
- 需求解析使用本地规则 fallback，不调用外部 LLM API。
- 数据画像使用轻量本地规则，不调用外部 VLM API。
- 模型注册动态读取 xingmu `capability_catalog.py`；当 `algo_service` 环境无法读取 Excel 行时，用已验收的 32 场景 fallback manifest，仍只暴露 32 个前端展示可靠能力。
- 模型路由固定返回 `priority_mode=quality_first` 和 `strategy=HYBRID_TEACHER_QUALITY`。
- xingmu 匹配成功时优先使用 xingmu 专用能力，DINO 作为辅助开放词汇 teacher。
- LocateAnything、本地 VLM、YOLO-World 返回明确 `UNAVAILABLE` / local-only 原因，不回退外部 API。
- `probe/run/fusion/label-studio formatter/training` 已接入 dry-run 状态，但不伪造候选框或预标注。
- Spring Boot 后端已能调用 Stage 3 dry-run 接口，并将 dry-run 状态落到 `runtime.algorithm_status`，不会把 job 卡在 `PROBING/RUNNING/SYNCING`。

安全修复：

- algorithm-service 旧 LLM/VLM 默认远程配置已改为本地 OpenAI-compatible 占位。
- backend 用户模型默认配置已改为本地 OpenAI-compatible 占位。
- 旧 model-config 测试接口和 VLM 清洗入口新增 local base URL 校验；远程 URL 会拒绝或进入人工复核降级。

### Stage 4 已实现模型 adapter 接入

已新增/增强：

- `algorithm-service/adapters/grounding_dino_adapter.py`
  - TCP health check：`127.0.0.1:5003`
  - 调用现有 DINO `/predict`
  - 将 DINO 归一化 `cx,cy,w,h` 转为像素 `bbox_xyxy`
  - 输出统一 candidate schema
- `algorithm-service/adapters/xingmu_adapter.py`
  - xingmu service health：`GET http://127.0.0.1:9000/healthz`
  - 返回 xingmu model groups registry
  - 对 32 个可验收能力中的 `/api/model-demo/predict` 模型组执行推理封装
  - 输出统一 candidate schema
  - 不支持的单图/OCR/事件端点返回 `UNSUPPORTED_ENDPOINT`，不阻塞任务
- `algorithm-service/adapters/locate_anything_adapter.py`
  - 服务状态保持 local-only unavailable
  - 新增 parser，可解析 `<box>none</box>`、`<x1>...` 结构和简单逗号 box
  - 将 `[0,1000]` 坐标缩放为像素坐标
- `algorithm-service/services/candidate_runner.py`
  - 有 `image_paths` 时按 route 调用 xingmu / DINO adapter
  - 单个 adapter 失败只进入 `adapter_reports`
  - 不伪造预测结果
- `algorithm-service/routers/adapters.py`
  - `POST /internal/adapters/grounding-dino/predict`
  - `POST /internal/adapters/xingmu/predict`
  - `POST /internal/adapters/locate-anything/parse`
- `algorithm-service/routers/model_registry.py`
  - `GET /internal/models/adapters/status`
  - `GET /internal/models/xingmu/model-groups`

Stage 4 smoke：

- DINO adapter 单图真实推理成功，返回 1 个统一 candidate。
- xingmu adapter M1 单图 smoke 成功，返回 0 框但协议成功。
- xingmu 烟火模型 smoke 返回 HTTP 500，被 adapter 转成 `available=false`，未导致服务 500。
- candidate runner 使用 `xingmu-01-人形 + grounding-dino` route 成功返回 1 个 candidate，并保留两个 adapter report。
- LocateAnything parser 成功解析 box 与 none。

### Stage 5 已实现 probe 与质量优先融合

已新增/增强：

- `algorithm-service/services/probe_runner.py`
  - 有 `image_paths` 时调用 candidate runner
  - 输出 `sample_count`
  - 输出每个模型的 `available/status`
  - 输出 `empty_rate`
  - 输出 `avg_boxes_per_image`
  - 输出 `low_confidence_ratio`
  - 输出 warning，例如 `HIGH_EMPTY_RATE`、`MODEL_UNAVAILABLE`、`EMPTY_PROBE`
- `algorithm-service/services/prediction_fusion.py`
  - 内置 NMS + score merge
  - 按 `image_id + label` 分组
  - 根据 IoU 合并候选框
  - 使用 score 加权框坐标
  - 输出 `final_score`
  - 输出 `source_models`
  - 输出 `fusion_reason`
  - 输出 `review_priority`
  - WBF 先保留接口，不新增依赖

质量优先人审策略：

- `final_score < 0.45`：`review_priority = HIGH`
- 单模型单候选：`review_priority = MEDIUM`
- 多候选/多模型支持且分数较高：`review_priority = LOW`

Stage 5 smoke：

- probe 使用一张本地图片和 route `xingmu-01-人形 + grounding-dino`：
  - `sampleCount=1`
  - `candidateCount=1`
  - xingmu report：available，empty rate 1.0
  - DINO report：available，candidate count 1，low confidence ratio 1.0
  - warning：`HIGH_EMPTY_RATE`
- fusion 使用 probe 产生的真实 DINO candidate：
  - `status=FUSION_COMPLETED`
  - `candidateCount=1`
  - `fusedCount=1`
  - `review_priority=HIGH`
  - `fusion_reason=single_candidate_manual_review_recommended`

## 新增数据结构规划

- `model_registry`
- `project_requirement`
- `dataset_profile`
- `model_route_plan`
- `auto_label_job`
- `prediction_candidate`
- `fused_prediction`

所有实体都必须通过项目/组织权限校验。

## 前端规划

新增或接入：

- `/projects/:id/auto-label`
- `/projects/:id/auto-label/jobs/:jobId`
- `/models/registry`

项目详情页新增“智能自动标注”入口。

第五步只显示固定策略：

```text
质量优先自动标注
系统会优先保证预标注质量；对复杂自然语言类别、低置信样本、模型分歧样本使用本地大模型语义验证或进入人审优先队列。
```

不提供策略切换，不向后端发送可变 `priority_mode`。

## 分阶段实施建议

1. Stage 1：完成启动检查、文档和现状确认。
2. Stage 2：后端实体、Repository、Service、Controller，先实现 registry、requirement、profile、route、job 的最小闭环。
3. Stage 3：algorithm-service 编排层，先 scaffold + dry-run + local-unavailable 降级。
4. Stage 4：adapter 接入：DINO、xingmu 32 场景、本地 LocateAnything 骨架、本地 VLM 骨架、YOLO-World placeholder。
5. Stage 5：probe 和 fusion。
6. Stage 6：Label Studio prediction formatter 与同步。
7. Stage 7：前端页面。
8. Stage 8：训练闭环。
9. Stage 9：xingmu manifest 治理。
10. Stage 10：测试、验收、最终报告。

## 当前未完成项

- Stage 2 后端实体、Repository、Service、Controller 和最小接口闭环已完成。
- Stage 3 algorithm-service 内部编排层、dry-run 和 local-only 降级已完成。
- Stage 4 模型 adapter 接入、统一 candidate schema、DINO/xingmu/Locate parser smoke 已完成。
- Stage 5 probe 报告、质量优先 NMS/score merge 和人审优先级已完成。
- 尚未确认本地 LocateAnything 模型权重、服务环境和显存预算。
- 尚未确认本地 Qwen3-VL-4B 或替代 VLM 服务端口。
- 已清理本轮发现的外部 LLM/VLM 默认值；后续仍需全仓持续扫描并对前端配置项做 UX 调整。
- 尚未新增前端页面。
- Stage 2/3/4/5 后端与 algorithm-service smoke 已通过；Stage 6+ 尚未验收。
