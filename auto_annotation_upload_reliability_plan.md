# 自动标注模式切换、大文件上传容错与长任务可靠性改造方案

> 日期：2026-04-23  
> 范围：只形成方案文档，暂不改业务代码。  
> 目标：在自动标注页支持两种处理模式；在数据管理页增强大文件上传容错；在自动标注长任务中保证任务可恢复、可观察、可完成。

## 1. 当前现状

### 1.1 自动标注

当前前端入口是：

- `frontend-vue/src/components/AlgorithmTasks.vue`

它只有一个“一键自动标注”按钮，文案和流程固定为：

- Grounding DINO 检测
- VLM 智能清洗
- 同步到 Label Studio

当前后端入口是：

- `backend-springboot/src/main/java/com/annotation/platform/controller/AutoAnnotationController.java`
- `backend-springboot/src/main/java/com/annotation/platform/service/algorithm/AutoAnnotationService.java`

`AutoAnnotationService.startAutoAnnotation(projectId, userId, processRange)` 当前固定执行：

1. 清理旧结果
2. DINO 检测
3. 保存 DINO 结果
4. VLM 清洗
5. 保存清洗结果
6. 同步到 Label Studio

问题：

- 没有“跳过 VLM，仅按 DINO 分数过滤”的模式。
- DINO 阈值没有从前端/后端参数传入，`DinoDetectRequest` 当前也没有阈值字段。
- 自动标注任务没有独立任务表，只通过 `Project.status` 表示粗粒度状态。
- 后端轮询算法服务的最长时间固定：DINO 5 分钟，VLM 10 分钟，不适合大量图片。

### 1.2 VLM 配置

当前用户模型配置接口是：

- `GET /api/v1/api/user/model-config`
- `PUT /api/v1/api/user/model-config`
- `POST /api/v1/api/user/model-config/test-vlm`

相关代码：

- `backend-springboot/src/main/java/com/annotation/platform/controller/UserModelConfigController.java`
- `backend-springboot/src/main/java/com/annotation/platform/service/user/UserModelConfigService.java`
- `frontend-vue/src/api/index.js`

我读取到当前 admin 用户的有效 VLM 配置为：

- VLM Base URL：`https://dashscope.aliyuncs.com/compatible-mode/v1`
- VLM Model：`qwen-vl-plus`
- VLM API Key：`sk-6****d6b6`（后端接口返回的脱敏值）

说明：

- 默认值来自 `UserModelConfigService`。
- 完整 key 不应展示到页面日志、文档或对话中；用户编辑时可以输入新 key，保存后后端只回显脱敏值。

### 1.3 数据管理与大文件上传

当前前端上传组件：

- `frontend-vue/src/components/DataManager.vue`
- `frontend-vue/src/components/FileUpload.vue`

当前后端上传接口：

- `backend-springboot/src/main/java/com/annotation/platform/controller/FileUploadController.java`
- `backend-springboot/src/main/java/com/annotation/platform/service/upload/impl/FileUploadServiceImpl.java`

现有能力：

- 5MB 分块上传。
- 上传完成后调用 `/upload/merge` 合并。
- 后端配置允许最大 5GB：`app.file.upload.max-file-size: 5368709120`。
- 前端文案写了“自动断点续传”，但实际实现还不完整。

当前缺口：

- 前端每个 chunk 只上传一次，失败后整文件失败。
- 没有并发上传控制。
- 没有暂停/继续/取消。
- 没有把 `fileId` 和已完成 chunk 列表持久化到浏览器本地，刷新页面后无法恢复。
- 后端上传进度存在内存 `uploadProgressMap`，服务重启后丢失。
- 后端没有“查询已上传 chunk 列表”的接口，前端无法精确跳过已上传块。
- 后端合并前只看内存进度，不会以磁盘 chunk 文件作为最终事实来源。

## 2. 自动标注新增两种模式

### 2.1 模式 A：保持现状，DINO + VLM 清洗

语义：

- DINO 先检出候选框。
- VLM 根据类别定义二次判断。
- 适合追求质量、类别定义复杂、误检成本高的场景。

前端需要用户填写/确认：

- VLM Base URL
- VLM API Key
- VLM Model

默认预填：

- 通过 `GET /api/v1/api/user/model-config` 读取。
- key 使用后端脱敏值展示；如果用户不修改 key，提交保存时仍传脱敏值，后端现有 `shouldUpdateSecret()` 会保留真实 key。
- 提供“测试 VLM 连通性”按钮，调用 `POST /api/v1/api/user/model-config/test-vlm`。

### 2.2 模式 B：只用 DINO，分数阈值过滤

语义：

- DINO 直接生成最终预标注。
- `score >= 0.7` 保留。
- `score < 0.7` 舍弃。
- 不调用 VLM。
- 适合追求速度、类别清晰、可接受置信度阈值策略的场景。

阈值：

- 默认 `0.7`。
- 前端可以先固定为 0.7，也建议做成可编辑输入，范围 `0.0 - 1.0`，默认 0.7。
- 后端必须以请求参数为准，不应只在前端过滤。

## 3. 自动标注前端改动

### 3.1 文件

- `frontend-vue/src/components/AlgorithmTasks.vue`
- `frontend-vue/src/api/index.js`

### 3.2 UI 改动

在“操作”卡片中新增一个模式切换控件，建议使用 `el-segmented` 或 `el-radio-group`：

- `DINO + VLM 清洗`
- `DINO 置信度过滤`

当选择 `DINO + VLM 清洗`：

- 显示 VLM 配置区：
  - Base URL 输入框
  - API Key 密码输入框
  - Model 输入框/下拉
  - “测试连通性”按钮
  - “保存配置”按钮

当选择 `DINO 置信度过滤`：

- 显示 DINO 阈值设置：
  - 默认 `0.7`
  - 提示：低于该阈值的检测框不会同步到 Label Studio

启动确认弹窗需要根据模式动态展示：

- 模式 A：`DINO 检测 + VLM 清洗`
- 模式 B：`DINO 检测，score >= 0.7 保留`

### 3.3 API 改动

`autoAnnotationAPI.startAutoAnnotation(projectId, params)` 当前只传：

```js
{ processRange: 'all' }
```

建议扩展为：

```js
{
  processRange: 'all',
  mode: 'DINO_VLM' | 'DINO_THRESHOLD',
  dinoScoreThreshold: 0.7,
  vlmConfigSnapshot: {
    baseUrl: '...',
    modelName: '...',
    apiKeyProvided: true
  }
}
```

注意：

- API Key 不建议进入任务日志。
- 如果 VLM 配置已保存到后端用户配置，启动任务时不需要再次传完整 key，只传 `mode` 即可。

## 4. 自动标注后端改动

### 4.1 新增请求 DTO

当前 `AutoAnnotationController` 使用 `Map<String, String>` 接收参数，建议改成明确 DTO：

新增：

- `backend-springboot/src/main/java/com/annotation/platform/dto/request/algorithm/AutoAnnotationStartRequest.java`

字段建议：

```java
private String processRange;          // all / unprocessed
private AnnotationMode mode;          // DINO_VLM / DINO_THRESHOLD
private Double dinoScoreThreshold;    // 默认 0.7
```

枚举：

```java
public enum AnnotationMode {
    DINO_VLM,
    DINO_THRESHOLD
}
```

原因：

- 避免 `Map<String, String>` 无类型约束。
- 后续可以继续加入批次大小、重试策略、任务优先级。

### 4.2 Controller

修改：

- `AutoAnnotationController.startAutoAnnotation(...)`

从：

```java
autoAnnotationService.startAutoAnnotation(projectId, userId, processRange);
```

改为：

```java
autoAnnotationService.startAutoAnnotation(projectId, userId, request);
```

返回值建议增加：

```json
{
  "taskId": "...",
  "mode": "DINO_THRESHOLD",
  "message": "Auto annotation started successfully"
}
```

### 4.3 Service 主流程

修改：

- `AutoAnnotationService.startAutoAnnotation(...)`

分支逻辑：

```java
if (mode == DINO_VLM) {
    runDino();
    saveDinoResults();
    runVlmCleaning();
    saveCleanedResults();
    syncPredictionsToLabelStudio();
}

if (mode == DINO_THRESHOLD) {
    runDino();
    saveDinoResults();
    filterDinoResultsByScore(0.7);
    saveThresholdFilteredResultsAsFinalPredictions();
    syncPredictionsToLabelStudio();
}
```

需要新增/调整的方法：

- `filterDinoResultsByScore(Object dinoResults, double threshold)`
- `saveDinoThresholdResults(Project project, Object filteredResults, String dinoTaskId, double threshold)`
- `syncPredictionsToLabelStudio(...)` 需要能读取最终结果来源：
  - VLM 模式读取 `VLM_CLEANING`
  - DINO 阈值模式读取 `DINO_DETECTION` 或新增 `DINO_THRESHOLD_FILTERED`

建议新增枚举值：

- `DetectionResult.ResultType.DINO_THRESHOLD_FILTERED`

原因：

- 保留原始 DINO 检测结果。
- 另存阈值过滤后的最终结果，方便审计和前端展示。

### 4.4 DINO 阈值传递

当前后端 `DinoDetectRequest` 没有阈值字段：

- `backend-springboot/src/main/java/com/annotation/platform/dto/request/algorithm/DinoDetectRequest.java`

建议增加：

```java
private Double boxThreshold;
private Double textThreshold;
```

并在：

- `AlgorithmServiceImpl.startDinoDetection(...)`

把它们传给算法服务：

```java
requestBody.put("box_threshold", request.getBoxThreshold());
requestBody.put("text_threshold", request.getTextThreshold());
```

注意：

- “0.7 以上保留”建议用后处理 `score` 阈值，而不是替代 DINO 的 `box_threshold`。
- DINO 的 `box_threshold` 可以仍保持较低，例如 0.3，让后端统一按 `score >= 0.7` 过滤最终结果。

## 5. VLM 配置填写与预填

### 5.1 当前默认值

代码位置：

- `UserModelConfigService.DEFAULT_VLM_*`

当前有效配置：

- Base URL：`https://dashscope.aliyuncs.com/compatible-mode/v1`
- Model：`qwen-vl-plus`
- API Key：`sk-6****d6b6`（脱敏）

### 5.2 前端读取

在 `AlgorithmTasks.vue` 加载时：

```js
const response = await userAPI.getModelConfig()
```

当前 `frontend-vue/src/api/index.js` 里已有：

- `userAPI.getModelConfig()`
- `userAPI.updateModelConfig(data)`
- `userAPI.testVlmModelConfig()`

但路径是 `/api/user/model-config`，因为 axios baseURL 已是 `/api/v1`，实际接口为：

- `/api/v1/api/user/model-config`

### 5.3 保存与测试

用户修改配置后：

1. `PUT /api/v1/api/user/model-config`
2. `POST /api/v1/api/user/model-config/test-vlm`
3. 测试成功后再允许启动 `DINO_VLM` 模式，或者允许用户跳过测试但给出风险提示。

## 6. 大文件上传容错机制

### 6.1 前端需要改动

文件：

- `frontend-vue/src/components/FileUpload.vue`
- `frontend-vue/src/api/index.js`

建议能力：

1. 稳定 fileId
   - 当前 fileId 是 `Date.now() + random`。
   - 建议改为：`projectId + file.name + file.size + file.lastModified` 的 hash。
   - 原因：刷新页面后仍能识别同一文件。

2. 查询已上传分块
   - 上传前调用后端接口获取已完成 chunk 列表。
   - 已存在的 chunk 跳过。

3. chunk 重试
   - 每个 chunk 失败后自动重试 3 次。
   - 使用指数退避：1s、2s、4s。

4. 并发控制
   - 同时上传 3-5 个 chunk。
   - 避免几 GB 文件串行上传过慢，也避免并发过高压垮后端。

5. 暂停/继续/取消
   - 暂停：停止调度新 chunk，正在上传的请求允许完成。
   - 继续：重新查询进度后继续未完成 chunk。
   - 取消：调用后端删除 chunk 目录和本地状态。

6. 本地持久化
   - 用 `localStorage` 或 `IndexedDB` 记录：
     - fileId
     - filename
     - size
     - totalChunks
     - uploadedChunks
     - projectId
     - lastUpdated

7. 页面恢复提示
   - 如果进入页面发现未完成上传任务，显示“发现未完成上传，是否继续？”

### 6.2 后端需要改动

文件：

- `FileUploadController.java`
- `FileUploadService.java`
- `FileUploadServiceImpl.java`

新增接口建议：

```http
GET /api/v1/upload/progress/{fileId}
GET /api/v1/upload/chunks/{fileId}
DELETE /api/v1/upload/session/{fileId}
POST /api/v1/upload/merge
```

其中 `GET /upload/chunks/{fileId}` 返回：

```json
{
  "fileId": "...",
  "filename": "...",
  "totalChunks": 1024,
  "uploadedChunks": [0, 1, 2, 10, 11],
  "missingChunks": [3, 4, 5]
}
```

后端持久化建议：

- 新增表 `upload_sessions`
- 新增表 `upload_chunks`

或轻量方案：

- 以 `/tmp/upload_chunks/{fileId}/manifest.json` 作为本地持久化清单。

推荐优先级：

1. 第一版用 manifest 文件，开发快。
2. 后续再升级 DB 表，支持多实例部署。

后端合并前必须：

- 以磁盘 chunk 文件为最终事实，不只依赖内存 `uploadProgressMap`。
- 检查所有 chunk 存在。
- 检查每个 chunk 大小合理。
- 合并后校验总大小。
- 可选：支持前端传 `fileHash`，后端合并后校验 SHA-256。

## 7. 自动标注长任务可靠性

### 7.1 当前风险

当前自动标注任务是 `@Async` 后台执行，状态主要写在 `Project.status`：

- `DETECTING`
- `CLEANING`
- `SYNCING`
- `COMPLETED`
- `FAILED`

风险：

- 服务重启后任务状态丢失，不能恢复。
- 后端轮询算法服务有固定超时，几百/几千张图不够。
- 前端页面关闭后只能靠项目状态粗略恢复，缺少详细进度。
- 失败后不知道失败在哪张图、哪个阶段。
- 没有取消、暂停、继续能力。

### 7.2 建议新增任务表

新增实体：

- `AutoAnnotationJob`
- `AutoAnnotationJobStep`

表 `auto_annotation_jobs` 字段建议：

- `id`
- `project_id`
- `user_id`
- `mode`
- `status`：`PENDING/RUNNING/PAUSED/CANCELLING/CANCELLED/COMPLETED/FAILED`
- `current_stage`：`DINO/VLM/SYNC`
- `total_images`
- `processed_images`
- `total_detections`
- `kept_detections`
- `discarded_detections`
- `dino_task_id`
- `vlm_task_id`
- `params_json`
- `error_message`
- `started_at`
- `updated_at`
- `completed_at`

表 `auto_annotation_job_steps` 字段建议：

- `job_id`
- `stage`
- `status`
- `started_at`
- `completed_at`
- `processed_count`
- `error_message`

### 7.3 新增接口

```http
POST /api/v1/auto-annotation/jobs
GET  /api/v1/auto-annotation/jobs/{jobId}
GET  /api/v1/projects/{projectId}/auto-annotation/jobs/latest
POST /api/v1/auto-annotation/jobs/{jobId}/cancel
POST /api/v1/auto-annotation/jobs/{jobId}/retry
```

启动接口可以保留旧的：

- `POST /auto-annotation/start/{projectId}`

但内部应创建 `AutoAnnotationJob`，并返回真实 `jobId`。

### 7.4 前端长任务机制

`AlgorithmTasks.vue` 需要：

1. 页面加载时查询最新 job。
2. 如果 job 是 RUNNING，自动恢复控制台和进度条。
3. 轮询 job 状态，而不是只轮询 project。
4. 展示：
   - 当前阶段
   - 已处理图片数 / 总图片数
   - 已保留框数 / 已舍弃框数
   - 已运行时长
   - 预计剩余时间
5. 提供取消按钮。
6. 失败时显示失败阶段和错误信息。
7. 支持“重试失败任务”，重试时跳过已完成阶段或已完成图片。

### 7.5 后端执行策略

建议把大任务拆批：

- 每批 20-50 张图调用一次算法服务。
- 每批完成后写 DB checkpoint。
- 失败只重试当前批次。

好处：

- 避免一次任务过大导致超时。
- 服务重启后可以从最后完成批次继续。
- 前端能看到更精确进度。

轮询超时建议改为动态：

```text
DINO 超时 = max(10 分钟, 图片数 * 单图估算秒数)
VLM 超时 = max(30 分钟, 检测框数 * 单框估算秒数)
```

更好的方案：

- 算法服务任务完成后回调 Spring Boot。
- Spring Boot 仍保留轮询作为兜底。

## 8. 推荐实施顺序

### 第 1 步：自动标注模式切换

改动：

- `AlgorithmTasks.vue`
- `AutoAnnotationController.java`
- `AutoAnnotationService.java`
- `DinoDetectRequest.java`
- `AlgorithmServiceImpl.java`

验收：

- 模式 A 仍保持原有 DINO + VLM 行为。
- 模式 B 不调用 VLM。
- 模式 B 只同步 `score >= 0.7` 的 DINO 框。

### 第 2 步：VLM 配置嵌入自动标注页

改动：

- `AlgorithmTasks.vue`
- `frontend-vue/src/api/index.js` 如需补方法则补。

验收：

- 自动标注页能预填当前 VLM URL、模型、脱敏 key。
- 用户能保存新配置。
- 用户能测试 VLM 连通性。

### 第 3 步：大文件上传容错

改动：

- `FileUpload.vue`
- `FileUploadController.java`
- `FileUploadServiceImpl.java`

验收：

- 模拟网络失败后 chunk 自动重试。
- 刷新页面后能继续未完成上传。
- 后端重启后仍能基于磁盘 chunk 恢复进度。
- 1GB ZIP 上传可以暂停、继续、合并。

### 第 4 步：自动标注长任务持久化

改动：

- 新增 `AutoAnnotationJob` 实体、Repository、Service。
- `AutoAnnotationController` 返回 jobId。
- `AlgorithmTasks.vue` 改为轮询 job。

验收：

- 页面关闭后重新进入仍能看到任务进度。
- 后端重启后任务不会永远卡在进行中；能标记为可重试或继续。
- 大批量图片可以按批次执行，失败可重试。

## 9. 测试计划

### 后端

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn clean package -DskipTests
```

重点接口：

- `POST /api/v1/auto-annotation/start/{projectId}`
- `GET /api/v1/auto-annotation/status/{taskId}`
- `GET /api/v1/api/user/model-config`
- `PUT /api/v1/api/user/model-config`
- `POST /api/v1/api/user/model-config/test-vlm`
- `/api/v1/upload/*`

### 前端

```bash
cd /root/autodl-fs/Annotation-Platform/frontend-vue
source /root/.nvm/nvm.sh
npm run build
```

### E2E

```bash
cd /root/autodl-fs/Annotation-Platform/tests_e2e
BACKEND_BASE_URL=http://127.0.0.1:8080/api/v1 \
ALGORITHM_BASE_URL=http://127.0.0.1:8001/api/v1 \
./run_tests.sh
```

新增测试建议：

- `test_auto_annotation_dino_vlm_mode`
- `test_auto_annotation_dino_threshold_mode`
- `test_upload_chunk_retry_resume`
- `test_auto_annotation_job_resume_after_page_reload`

## 10. 关键决策建议

1. 自动标注模式字段使用后端枚举，不使用自由字符串。
2. VLM API Key 只允许用户输入和保存，不在页面/日志/文档展示完整值。
3. DINO 阈值过滤结果另存为独立结果类型，保留原始 DINO 输出。
4. 大文件上传进度不能只存在内存里，至少要有 manifest 文件。
5. 长任务不能只靠 `Project.status`，需要独立任务表和 jobId。
6. 大批量自动标注应按批次 checkpoint，避免单个超长算法任务不可恢复。

