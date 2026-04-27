好的,代码和环境信息都收到了。我已经把所有关键文件过了一遍,下面给你一份**可直接指导开发的改造方案**。相较你原始文档,我做了以下重要调整,先列变更点,然后给详细方案。

---

## 一、相较原方案的关键调整

| # | 原方案 | 调整后 | 理由 |
|---|---|---|---|
| 1 | DINO 阈值字段叫 `dinoScoreThreshold` | 统一用 `scoreThreshold`(结果层面) + `boxThreshold`(DINO 调用层面),两者不混用 | 你的代码里 DINO 请求和最终过滤是两个不同语义,不区分会埋坑 |
| 2 | 新增 `DetectionResult.ResultType.DINO_THRESHOLD_FILTERED` | **不新增枚举**,复用 `VLM_CLEANING` + 在 `resultData` 里加 `source` 字段 | 下游 `syncPredictionsToLabelStudio` 只按 `VLM_CLEANING` 查询,新增枚举会让同步逻辑必须改动两处;用 source 标记零侵入 |
| 3 | 新增 `AutoAnnotationJob` + `AutoAnnotationJobStep` 两张表 | **第一阶段只做一张 `auto_annotation_jobs` 表**,Step 表放到第四阶段以后再说 | H2 + 单实例 + `@Async`,一张表足够承载 jobId、进度、取消、恢复 |
| 4 | 上传进度用 DB 表 `upload_sessions` | **第一阶段只用 `manifest.json`**,DB 表作为可选升级项 | 单实例 + H2 + 开发联调,manifest 足够;DB 表反而增加迁移负担 |
| 5 | `AutoAnnotationController` 直接改签名 | **保留旧签名,新增 DTO 入口**,内部共享实现 | 保证前端老调用不会立刻炸 |
| 6 | 启动确认弹窗直接调用 | **加一层"模式预检"**:DINO_VLM 模式启动前必须通过 VLM 连通性测试(或用户显式跳过) | 避免 DINO 跑完 1 小时后才在 VLM 阶段炸 |
| 7 | `@Async` + `@Transactional` 同方法 | **拆开**:`@Async` 方法不带事务,内部按阶段调用带事务的子方法 | 你现在的 `startAutoAnnotation` 同时标了 `@Async` 和 `@Transactional`,这是一个 Bug—— Spring 的 `@Transactional` 通过代理生效,`@Async` 方法内部再调自己的 `@Transactional` 方法是失效的,而且一个事务持续几十分钟会锁连接、HikariCP 会超时 |
| 8 | 原方案要算法服务回调 | **保留轮询主链路**,但把固定 60 次/120 次改成**动态超时 + 心跳停滞检测** | 回调链路要改 Python + Spring 双边,工作量大;动态超时 + 心跳就能解决大图片集超时问题 |

⚠️ 第 7 条是我看代码时发现的一个**存量 Bug**,不管做不做这次改造都建议修。下面会详细说。

---

## 二、第一阶段改造:自动标注模式切换(核心)

### 2.1 新增文件

#### `dto/request/algorithm/AutoAnnotationStartRequest.java`(新增)

```java
package com.annotation.platform.dto.request.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoAnnotationStartRequest {

    /** "all" 或 "unprocessed",默认 "unprocessed" */
    private String processRange;

    /** 标注模式,默认 DINO_VLM 以保持向后兼容 */
    private AnnotationMode mode;

    /**
     * 最终结果层面的 score 阈值,用于模式 B。
     * 只影响"哪些 DINO 框会被作为最终预标注保留"。
     * 默认 0.7,范围 [0.0, 1.0]。
     */
    private Double scoreThreshold;

    /**
     * DINO 调用层面的 box_threshold,建议保持较低(如 0.25-0.3),
     * 让 DINO 多检出候选,再由 scoreThreshold 或 VLM 做后处理。
     * null 表示使用算法服务默认值。
     */
    private Double boxThreshold;

    public enum AnnotationMode {
        /** 模式 A:DINO 检测 + VLM 清洗(原行为) */
        DINO_VLM,
        /** 模式 B:DINO 检测 + score 阈值过滤,不调用 VLM */
        DINO_THRESHOLD
    }
}
```

**设计说明**:
- `scoreThreshold` 和 `boxThreshold` 是两个不同层面的阈值,不要合并。`boxThreshold` 决定 DINO 返回多少候选,`scoreThreshold` 决定最终保留多少。模式 B 下两者都有用;模式 A 下 `scoreThreshold` 可以忽略。
- `mode` 为 `null` 时 Service 层默认回退到 `DINO_VLM`,保证旧前端调用不受影响。

### 2.2 修改 `DinoDetectRequest.java`

在现有字段基础上追加两个可选字段,保持向后兼容:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DinoDetectRequest {
    private Long projectId;
    private List<String> imagePaths;
    private List<String> labels;
    private String apiKey;
    private String endpoint;
    private String taskId;

    // 新增
    private Double boxThreshold;
    private Double textThreshold;
}
```

`AlgorithmServiceImpl`(这个文件在你的列表里标 `[FILE NOT FOUND]`,但从 `service/algorithm/impl` 目录存在可以推断它在 `impl/` 子目录下)的 `startDinoDetection` 方法内,往 Python 请求体里追加:

```java
if (request.getBoxThreshold() != null) {
    requestBody.put("box_threshold", request.getBoxThreshold());
}
if (request.getTextThreshold() != null) {
    requestBody.put("text_threshold", request.getTextThreshold());
}
```

⚠️ 这里需要你打开 `impl/AlgorithmServiceImpl.java` 确认 Python 端接受的字段名是 `box_threshold` 还是别的。如果 Python 端暂不认识这两个字段,可以先不传,仅在 Java 层用 `scoreThreshold` 做最终过滤。

### 2.3 修改 `AutoAnnotationController.java`

**保留旧方法不动**(向后兼容),**新增 DTO 入口**:

```java
@PostMapping("/start/{projectId}")
public Result<Map<String, Object>> startAutoAnnotation(
        @PathVariable Long projectId,
        @RequestBody(required = false) AutoAnnotationStartRequest request,
        HttpServletRequest httpRequest) {

    Long userId = (Long) httpRequest.getAttribute("userId");

    // 默认值兜底
    if (request == null) {
        request = AutoAnnotationStartRequest.builder()
                .processRange("unprocessed")
                .mode(AutoAnnotationStartRequest.AnnotationMode.DINO_VLM)
                .scoreThreshold(0.7)
                .build();
    }
    if (request.getMode() == null) {
        request.setMode(AutoAnnotationStartRequest.AnnotationMode.DINO_VLM);
    }
    if (request.getProcessRange() == null) {
        request.setProcessRange("unprocessed");
    }
    if (request.getScoreThreshold() == null) {
        request.setScoreThreshold(0.7);
    }

    try {
        String jobId = autoAnnotationService.startAutoAnnotation(projectId, userId, request);

        Map<String, Object> response = new HashMap<>();
        response.put("taskId", jobId);   // 保留 taskId 字段名,前端无需改
        response.put("jobId", jobId);
        response.put("mode", request.getMode().name());
        response.put("message", "Auto annotation started successfully");
        return Result.success(response);
    } catch (Exception e) {
        log.error("Failed to start auto annotation: {}", e.getMessage(), e);
        return Result.error("Failed to start auto annotation: " + e.getMessage());
    }
}
```

**注意**:Spring 的 `@RequestBody` 对老前端传 `{"processRange":"all"}` 这种 JSON **也能正确反序列化到 DTO**(`mode` 字段会是 null,由上面的兜底逻辑处理)。所以**不需要保留两个 `@PostMapping`**,一个 DTO 入口就够了。

### 2.4 修改 `AutoAnnotationService.java`(核心重构)

这块是重点。先解决存量 Bug,再加分支逻辑。

#### 2.4.1 存量 Bug 修复:拆分 `@Async` 和 `@Transactional`

当前代码:

```java
@Async("taskExecutor")
@Transactional   // ⚠️ 问题 1:长事务
public void startAutoAnnotation(Long projectId, Long userId, String processRange) {
    // 跑几十分钟的 DINO + VLM,整个方法都在一个事务里
}
```

问题:
1. **一个事务覆盖几十分钟的外部 HTTP 调用**,HikariCP 连接被长期占用,`max-lifetime: 1800000` (30 分钟)一到连接就被回收,事务异常。
2. `@Transactional` 依赖 Spring 代理,`@Async` 同样依赖代理,但它们是**两个不同的代理链**,组合行为并不保证"先异步、再在异步线程里开事务"。实际观察到的行为是:`@Async` 生效、`@Transactional` 在外层代理命中,但因为 `@Async` 立即返回,外层事务几乎没覆盖到实际工作。这是个"看起来有但其实没起作用"的事务。
3. 后续任何失败重试逻辑想做"事务内回滚某一步"都做不到。

**正确做法**:

```java
@Async("taskExecutor")
public void runJob(Long jobId) {
    // 不带事务。内部每次需要落库的小操作,调用独立的 @Transactional 方法。
}

@Transactional
public AutoAnnotationJob createJob(...) { ... }

@Transactional
public void saveDinoResults(Project project, Object dinoResults, String dinoTaskId, Long jobId) { ... }

@Transactional
public void updateJobStage(Long jobId, JobStage stage) { ... }
```

重要:**`@Transactional` 方法必须是从外部调用才生效**。如果 `runJob` 内部直接 `this.saveDinoResults(...)`,事务不会生效。两种解法:
- **简单解**:把这些 `@Transactional` 方法抽到一个新的 `AutoAnnotationJobWriter` Service 里,`AutoAnnotationService` 注入它再调用。推荐这个方案。
- 或者 `@Autowired` 注入自己(self-injection),用 `self.saveDinoResults(...)`。不推荐,代码味道重。

#### 2.4.2 新主流程(伪代码级别)

```java
@Async("taskExecutor")
public void runJob(Long jobId) {
    AutoAnnotationJob job = jobWriter.loadJob(jobId);  // @Transactional(readOnly=true)
    try {
        jobWriter.markRunning(jobId);

        Project project = ...;
        List<ProjectImage> images = ...;
        List<String> imagePaths = ...;

        // ===== 阶段 1: DINO =====
        jobWriter.updateStage(jobId, JobStage.DINO);
        projectWriter.updateProjectStatus(project.getId(), ProjectStatus.DETECTING);

        DinoDetectRequest dinoReq = DinoDetectRequest.builder()
                .projectId(project.getId())
                .imagePaths(imagePaths)
                .labels(project.getLabels())
                .boxThreshold(job.getBoxThreshold())   // 来自 job
                .build();
        DinoDetectResponse dinoResp = algorithmService.startDinoDetection(dinoReq);
        checkSuccess(dinoResp);

        String dinoTaskId = dinoResp.getTaskId();
        jobWriter.saveDinoTaskId(jobId, dinoTaskId);

        Object dinoResults = waitForTaskCompletionWithDynamicTimeout(
                dinoTaskId, imagePaths.size(), JobStage.DINO, jobId);

        jobWriter.saveDinoResults(project, dinoResults, dinoTaskId);

        // 取消检查
        if (jobWriter.isCancelling(jobId)) {
            jobWriter.markCancelled(jobId);
            return;
        }

        // ===== 阶段 2: 分支 =====
        if (job.getMode() == AnnotationMode.DINO_VLM) {
            runVlmStage(job, project, imagePaths, dinoTaskId, dinoResults);
        } else {
            runThresholdStage(job, project, dinoTaskId, dinoResults);
        }

        // ===== 阶段 3: 同步 Label Studio =====
        jobWriter.updateStage(jobId, JobStage.SYNC);
        projectWriter.updateProjectStatus(project.getId(), ProjectStatus.SYNCING);
        syncPredictionsToLabelStudio(project, images, job.getUserId());

        // ===== 完成 =====
        jobWriter.markCompleted(jobId);
        projectWriter.updateProjectStatus(project.getId(), ProjectStatus.COMPLETED);

    } catch (Exception e) {
        log.error("Job {} failed", jobId, e);
        jobWriter.markFailed(jobId, e.getMessage());
        // Project.status 也置 FAILED
    }
}

private void runVlmStage(AutoAnnotationJob job, Project project, List<String> imagePaths,
                         String dinoTaskId, Object dinoResults) {
    jobWriter.updateStage(job.getId(), JobStage.VLM);
    projectWriter.updateProjectStatus(project.getId(), ProjectStatus.CLEANING);

    List<Map<String, Object>> detections = extractDetectionsFromResults(dinoResults);
    VlmCleanRequest vlmReq = VlmCleanRequest.builder()...build();
    VlmCleanResponse vlmResp = algorithmService.startVlmCleaning(vlmReq);
    checkSuccess(vlmResp);

    String vlmTaskId = vlmResp.getTaskId();
    jobWriter.saveVlmTaskId(job.getId(), vlmTaskId);

    Object vlmResults = waitForTaskCompletionWithDynamicTimeout(
            vlmTaskId, detections.size(), JobStage.VLM, job.getId());

    jobWriter.saveCleanedResults(project, vlmResults, dinoTaskId, vlmTaskId);
    // 这里写入 resultData.source = "vlm"
}

private void runThresholdStage(AutoAnnotationJob job, Project project,
                               String dinoTaskId, Object dinoResults) {
    double threshold = job.getScoreThreshold() != null ? job.getScoreThreshold() : 0.7;
    jobWriter.saveThresholdFilteredResults(project, dinoResults, dinoTaskId, threshold);
    // 这里写入 resultData.source = "dino_threshold",type 仍然用 VLM_CLEANING
}
```

#### 2.4.3 `saveThresholdFilteredResults` 详细实现

```java
@Transactional
public void saveThresholdFilteredResults(Project project, Object dinoResults,
                                         String dinoTaskId, double threshold) {
    Map<String, Object> resultsMap = objectMapper.convertValue(dinoResults, Map.class);
    List<Map<String, Object>> resultsList = (List<Map<String, Object>>) resultsMap.get("results");
    if (resultsList == null) return;

    // 复用一个 VLM_CLEANING 类型的 Task 记录(也可以新建 DINO_DETECTION 类型,看你当前 TaskType 枚举)
    AnnotationTask task = new AnnotationTask();
    task.setProject(project);
    task.setType(AnnotationTask.TaskType.VLM_CLEANING);  // 或者加一个 DINO_THRESHOLD 类型
    task.setStatus(AnnotationTask.TaskStatus.COMPLETED);
    task.setStartedAt(LocalDateTime.now());
    task.setCompletedAt(LocalDateTime.now());
    task.setParameters(Map.of(
            "task_id", dinoTaskId,
            "mode", "DINO_THRESHOLD",
            "score_threshold", threshold
    ));
    task = annotationTaskRepository.save(task);

    int kept = 0, discarded = 0;
    for (Map<String, Object> result : resultsList) {
        String imagePath = (String) result.get("image_path");
        String fileName = new File(imagePath).getName();

        List<Map<String, Object>> detections = (List<Map<String, Object>>) result.get("detections");
        if (detections == null || detections.isEmpty()) continue;

        ProjectImage image = findImage(project.getId(), imagePath, fileName);
        if (image == null) continue;

        for (Map<String, Object> det : detections) {
            Double score = toDouble(det.get("score"));
            if (score == null || score < threshold) {
                discarded++;
                continue;
            }
            kept++;

            Map<String, Object> detData = new HashMap<>();
            detData.put("label", det.get("label"));
            detData.put("bbox", toDoubleList(det.get("bbox")));
            detData.put("score", score);
            detData.put("image_path", imagePath);
            detData.put("image_name", fileName);
            // 关键:源标记
            detData.put("source", "dino_threshold");
            detData.put("vlm_decision", "keep");   // 让下游 syncPredictionsToLabelStudio 无需改动
            detData.put("vlm_reasoning", "Kept by DINO score >= " + threshold);

            DetectionResult dr = DetectionResult.builder()
                    .image(image)
                    .task(task)
                    .type(DetectionResult.ResultType.VLM_CLEANING)  // 复用 VLM_CLEANING
                    .resultData(detData)
                    .build();
            detectionResultRepository.save(dr);
        }
    }
    log.info("DINO 阈值过滤完成: kept={}, discarded={}, threshold={}", kept, discarded, threshold);

    jobWriter.updateJobCounts(job.getId(), kept, discarded);
}
```

**关键设计点**:
- `type` 仍然存 `VLM_CLEANING`。原因:`syncPredictionsToLabelStudio` 里的查询 `detectionResultRepository.findByProjectIdAndType(projectId, VLM_CLEANING)` **完全不用改**。
- `resultData.source` 标记真实来源,前端"执行控制台"展示时可以读这个字段区分。
- `vlm_decision = "keep"` 是为了让 `saveCleanedResults` 和后续逻辑的 "if keep then 保存" 判断在阈值模式下也自然通过,但在阈值模式我们**直接写库**而不走 `saveCleanedResults`。

这样同步层零侵入。

### 2.5 `waitForTaskCompletion` 超时动态化

当前固定 `60 * 5s = 5 分钟` 和 `120 * 5s = 10 分钟`。大图片集会超时。

```java
/**
 * 动态超时 + 心跳停滞检测。
 * - 基础超时:按 itemCount 估算
 * - 心跳:如果算法服务 60 秒内连续返回同一个 running 状态且 progress 不变,视为卡死
 */
private Object waitForTaskCompletionWithDynamicTimeout(
        String taskId, int itemCount, JobStage stage, Long jobId) {

    long baseTimeoutMs;
    long perItemMs;
    switch (stage) {
        case DINO -> { baseTimeoutMs = 10 * 60 * 1000L; perItemMs = 2000L; }   // 每张图 2s
        case VLM  -> { baseTimeoutMs = 30 * 60 * 1000L; perItemMs = 5000L; }   // 每个框 5s
        default   -> { baseTimeoutMs = 10 * 60 * 1000L; perItemMs = 1000L; }
    }
    long totalTimeoutMs = baseTimeoutMs + itemCount * perItemMs;
    long deadline = System.currentTimeMillis() + totalTimeoutMs;

    long lastProgressChangeMs = System.currentTimeMillis();
    Object lastProgressSnapshot = null;

    while (System.currentTimeMillis() < deadline) {
        // 每次轮询都检查取消
        if (jobWriter.isCancelling(jobId)) {
            throw new RuntimeException("Job cancelled: " + jobId);
        }

        Map<String, Object> status = objectMapper.convertValue(
                algorithmService.getTaskStatus(taskId), Map.class);
        String s = String.valueOf(status.get("status")).toLowerCase();

        if ("completed".equals(s)) {
            return algorithmService.getTaskResults(taskId);
        }
        if ("failed".equals(s)) {
            throw new RuntimeException("任务失败: " + status.get("message"));
        }

        // 心跳停滞检测
        Object progress = status.get("progress");
        if (!Objects.equals(progress, lastProgressSnapshot)) {
            lastProgressSnapshot = progress;
            lastProgressChangeMs = System.currentTimeMillis();
            // 顺便把进度写回 job
            if (progress instanceof Number n) {
                jobWriter.updateJobProgress(jobId, n.doubleValue());
            }
        } else if (System.currentTimeMillis() - lastProgressChangeMs > 5 * 60 * 1000L) {
            // 5 分钟无进度变化 → 视为卡死
            throw new RuntimeException("任务心跳停滞超过 5 分钟: " + taskId);
        }

        try { Thread.sleep(5000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException("轮询被中断"); }
    }
    throw new RuntimeException("任务超时: " + taskId + ", timeout=" + totalTimeoutMs + "ms");
}
```

### 2.6 新增 `AutoAnnotationJob` 实体(第一阶段最小版本)

```java
package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "auto_annotation_jobs", indexes = {
    @Index(name = "idx_aaj_project_id", columnList = "project_id"),
    @Index(name = "idx_aaj_status", columnList = "status")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AutoAnnotationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private AnnotationMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", length = 20)
    private JobStage currentStage;

    @Column(name = "score_threshold")
    private Double scoreThreshold;

    @Column(name = "box_threshold")
    private Double boxThreshold;

    @Column(name = "total_images")
    private Integer totalImages;

    @Column(name = "processed_images")
    private Integer processedImages;

    @Column(name = "kept_detections")
    private Integer keptDetections;

    @Column(name = "discarded_detections")
    private Integer discardedDetections;

    @Column(name = "dino_task_id", length = 100)
    private String dinoTaskId;

    @Column(name = "vlm_task_id", length = 100)
    private String vlmTaskId;

    @Column(name = "progress_percent")
    private Double progressPercent;

    @Column(name = "cancel_requested")
    @Builder.Default
    private Boolean cancelRequested = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_json", columnDefinition = "json")
    private Map<String, Object> paramsJson;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum AnnotationMode { DINO_VLM, DINO_THRESHOLD }
    public enum JobStatus { PENDING, RUNNING, CANCELLING, CANCELLED, COMPLETED, FAILED }
    public enum JobStage { INIT, DINO, VLM, THRESHOLD_FILTER, SYNC }
}
```

因为你用 H2 + `spring.jpa.hibernate.ddl-auto: update`,这张表**不需要手写 migration SQL**,启动时 Hibernate 自动建。第一阶段这样最快。

### 2.7 前端 `AlgorithmTasks.vue` 改动

核心改动点(保持现有 UI 骨架不动,只在"操作"卡片前后加东西):

```vue
<!-- 新增:模式切换 -->
<el-card class="panel">
  <template #header><span class="card-title">标注模式</span></template>
  <el-radio-group v-model="mode" size="large">
    <el-radio-button value="DINO_VLM">DINO + VLM 清洗</el-radio-button>
    <el-radio-button value="DINO_THRESHOLD">DINO 置信度过滤</el-radio-button>
  </el-radio-group>

  <!-- 模式 A: VLM 配置区 -->
  <div v-if="mode === 'DINO_VLM'" class="mode-config">
    <el-form label-width="110px" size="small">
      <el-form-item label="Base URL">
        <el-input v-model="vlmConfig.baseUrl" placeholder="https://..." />
      </el-form-item>
      <el-form-item label="Model">
        <el-input v-model="vlmConfig.modelName" />
      </el-form-item>
      <el-form-item label="API Key">
        <el-input v-model="vlmConfig.apiKey" type="password" show-password
                  placeholder="当前已保存(脱敏显示),留空或不修改即复用" />
      </el-form-item>
      <el-form-item>
        <el-button @click="saveVlmConfig" :loading="savingVlm">保存配置</el-button>
        <el-button @click="testVlmConnectivity" :loading="testingVlm" type="primary" plain>
          测试连通性
        </el-button>
        <el-tag v-if="vlmTestResult === 'ok'" type="success" size="small">连通正常</el-tag>
        <el-tag v-else-if="vlmTestResult === 'fail'" type="danger" size="small">连通失败</el-tag>
      </el-form-item>
    </el-form>
  </div>

  <!-- 模式 B: 阈值配置区 -->
  <div v-else class="mode-config">
    <el-form label-width="110px" size="small">
      <el-form-item label="Score 阈值">
        <el-input-number v-model="scoreThreshold" :min="0" :max="1" :step="0.05" :precision="2" />
        <span class="hint">低于该阈值的 DINO 检测框不会同步到 Label Studio</span>
      </el-form-item>
    </el-form>
  </div>
</el-card>
```

script 层关键逻辑:

```js
import { autoAnnotationAPI, userAPI } from '@/api'

const mode = ref('DINO_VLM')
const scoreThreshold = ref(0.7)
const vlmConfig = ref({ baseUrl: '', modelName: '', apiKey: '' })
const vlmTestResult = ref(null)   // null / 'ok' / 'fail'

// 挂载时预填 VLM 配置
onMounted(async () => {
  await loadVlmConfig()
})

const loadVlmConfig = async () => {
  try {
    const res = await userAPI.getModelConfig()
    vlmConfig.value = {
      baseUrl: res.data.vlmBaseUrl || '',
      modelName: res.data.vlmModelName || '',
      apiKey: res.data.vlmApiKey || ''   // 后端返回的已经是脱敏值
    }
  } catch (e) { /* silent */ }
}

const saveVlmConfig = async () => {
  savingVlm.value = true
  try {
    // 如果用户没改 apiKey(还是脱敏样),直接把脱敏值传回去
    // 后端 shouldUpdateSecret() 会识别带 * 的值并跳过更新
    await userAPI.updateModelConfig({
      vlmBaseUrl: vlmConfig.value.baseUrl,
      vlmModelName: vlmConfig.value.modelName,
      vlmApiKey: vlmConfig.value.apiKey
    })
    ElMessage.success('VLM 配置已保存')
    vlmTestResult.value = null
  } finally { savingVlm.value = false }
}

const testVlmConnectivity = async () => {
  testingVlm.value = true
  try {
    const res = await userAPI.testVlmModelConfig()
    vlmTestResult.value = res.data?.success ? 'ok' : 'fail'
    if (vlmTestResult.value === 'ok') ElMessage.success('VLM 连通正常')
    else ElMessage.error('VLM 连通失败,请检查配置')
  } finally { testingVlm.value = false }
}

// 启动时带上模式参数
const startAutoAnnotation = async () => {
  // 模式 A 强制要求 VLM 测试通过(或用户确认跳过)
  if (mode.value === 'DINO_VLM' && vlmTestResult.value !== 'ok') {
    try {
      await ElMessageBox.confirm(
        'VLM 尚未通过连通性测试,直接启动可能在 VLM 阶段失败。是否继续?',
        '提示',
        { type: 'warning' }
      )
    } catch { return }
  }

  const confirmMsg = mode.value === 'DINO_VLM'
    ? `模式: DINO + VLM 清洗\n项目: ${props.project.name}\n类别: ${props.project.labels?.length || 0}`
    : `模式: DINO 置信度过滤\n阈值: score >= ${scoreThreshold.value}\n项目: ${props.project.name}`

  await ElMessageBox.confirm(confirmMsg, '确认启动', { type: 'warning' })

  consoleLogs.value = []
  addLog(`模式: ${mode.value === 'DINO_VLM' ? 'DINO + VLM 清洗' : 'DINO 置信度过滤'}`, 'info', '⚙️')

  await autoAnnotationAPI.startAutoAnnotation(props.project.id, {
    processRange: 'all',
    mode: mode.value,
    scoreThreshold: scoreThreshold.value
  })

  startPolling()
  emit('refresh')
}
```

### 2.8 第一阶段验收标准

| 场景 | 预期 |
|---|---|
| 老前端(不传 mode) | 后端默认 DINO_VLM,行为与改造前完全一致 |
| 模式 A,VLM 配置正常 | DINO + VLM 完整流程 |
| 模式 A,VLM Key 错 | VLM 阶段失败,`project.status=FAILED`,控制台显示错误 |
| 模式 B,threshold=0.7 | 不调用 VLM,score<0.7 的框不进 Label Studio |
| 模式 B,threshold=0 | 所有 DINO 框都进 Label Studio |
| 模式 B,threshold=1.0 | 通常没有框能保留,同步空 |
| 1000 张图片模式 A | 不再被固定 10 分钟超时卡死 |

---

## 三、第二阶段改造:大文件上传容错

先说最容易被忽略的一条:**你 application.yml 里 `spring.servlet.multipart.max-file-size: 500MB`**,但 `app.file.upload.max-file-size: 5368709120`(5GB)。如果单个 chunk 大小是 5MB 没问题,但如果后续允许大 chunk 或合并后再次用 multipart 传,会被 Spring 拦截。这次改造**只用 5MB chunk,问题不会暴露**,但要记下来。

### 3.1 前端 `FileUpload.vue` 改动清单

按优先级做,可以分成"MVP"和"完整版"两步走。

**MVP(一天内能上线)**:
1. **稳定 fileId**:改成基于文件属性的 hash
   ```js
   const makeFileId = async (file, projectId) => {
     const raw = `${projectId}::${file.name}::${file.size}::${file.lastModified}`
     const buf = new TextEncoder().encode(raw)
     const hash = await crypto.subtle.digest('SHA-256', buf)
     return Array.from(new Uint8Array(hash)).map(b => b.toString(16).padStart(2,'0')).join('').slice(0, 32)
   }
   ```
2. **chunk 重试**:每个 chunk 失败重试 3 次,指数退避
   ```js
   const uploadChunkWithRetry = async (formData, maxRetries = 3) => {
     let lastErr
     for (let i = 0; i < maxRetries; i++) {
       try { return await uploadAPI.uploadChunk(formData) }
       catch (e) { lastErr = e; await sleep(1000 * Math.pow(2, i)) }
     }
     throw lastErr
   }
   ```
3. **上传前查询已有进度**,跳过已完成 chunk(依赖后端 3.2 新增接口)。

**完整版**(后续迭代):
4. 并发控制(3 个并发 chunk):用一个简单的 promise pool。
5. 暂停/继续/取消按钮。
6. `localStorage` 持久化 `{fileId, projectId, filename, size, totalChunks, uploadedChunks, updatedAt}`。
7. 页面挂载时扫描 localStorage,弹"发现未完成上传"对话框。

### 3.2 后端改动清单

**MVP 必须做**:

1. **新增 `GET /upload/chunks/{fileId}`**:返回已完成 chunk 列表
   ```java
   @GetMapping("/chunks/{fileId}")
   public Result<ChunksResponse> getUploadedChunks(@PathVariable String fileId) {
       return Result.success(fileUploadService.listUploadedChunks(fileId));
   }
   ```
   实现方式:**扫描磁盘目录 `/tmp/upload_chunks/{fileId}/`,以磁盘为准**,不依赖内存 map。这一步把内存 map 从"事实来源"降级为"缓存"。

2. **`uploadChunk` 幂等**:chunk 文件存在时允许覆盖或跳过,不要报错。当前代码 `file.transferTo(chunkFile)` 在目标存在时会失败,需要加 `if (chunkFile.exists()) chunkFile.delete();`。

3. **`mergeChunks` 改用磁盘扫描**:
   ```java
   // 不再检查 uploadProgressMap,直接检查磁盘
   for (int i = 0; i < totalChunks; i++) {
       File chunk = new File(chunkDir, String.format("%s_%d", filename, i));
       if (!chunk.exists()) {
           throw new BusinessException(ErrorCode.FILE_006, "缺少 chunk: " + i);
       }
   }
   ```

4. **`manifest.json`**:每次 chunk 上传成功后,写入 `/tmp/upload_chunks/{fileId}/manifest.json`:
   ```json
   {
     "fileId": "...",
     "filename": "...",
     "projectId": 123,
     "totalChunks": 200,
     "fileSize": 1048576000,
     "uploadedChunks": [0,1,2,3,...],
     "createdAt": "...",
     "updatedAt": "..."
   }
   ```
   服务重启后可从 manifest 恢复 `uploadProgressMap`。

**完整版**:

5. `DELETE /upload/session/{fileId}`:清理 chunk 目录和 manifest。
6. 启动时扫描 `/tmp/upload_chunks/` 恢复所有未合并的 session 到内存 map(一个 `@PostConstruct` 方法)。
7. 可选:合并后 SHA-256 校验。

### 3.3 数据库表(可选,强烈建议**先跳过**)

你是 H2 单实例,manifest 已经够用。`upload_sessions` 表要到你准备迁 MySQL + 多实例时再加。

### 3.4 第二阶段验收标准

| 场景 | 预期 |
|---|---|
| 1GB ZIP 上传中网络断一下 | chunk 自动重试 3 次 |
| 上传到 50% 刷新页面 | 可以从 51% 继续(通过 manifest 恢复) |
| 后端重启 | manifest 存在,前端查 `/upload/chunks/{fileId}` 得到真实进度 |
| 同一个文件重复上传 | fileId 稳定,复用已完成 chunk |

---

## 四、第三阶段改造:长任务可观测 + 可取消

这阶段其实在**第一阶段**我已经把 `AutoAnnotationJob` 表建起来了,所以第三阶段就是**把前端对接上去**。

### 4.1 新增接口

```http
GET  /auto-annotation/jobs/{jobId}
GET  /projects/{projectId}/auto-annotation/jobs/latest
POST /auto-annotation/jobs/{jobId}/cancel
```

返回结构:

```json
{
  "jobId": 42,
  "projectId": 123,
  "mode": "DINO_THRESHOLD",
  "status": "RUNNING",
  "currentStage": "DINO",
  "progressPercent": 35.5,
  "totalImages": 500,
  "processedImages": 180,
  "keptDetections": 420,
  "discardedDetections": 88,
  "startedAt": "...",
  "updatedAt": "...",
  "errorMessage": null
}
```

### 4.2 前端轮询切换

`AlgorithmTasks.vue` 原来轮询 `props.project.status`,改为:

1. 挂载时调 `GET /projects/{projectId}/auto-annotation/jobs/latest`
2. 如果 `status=RUNNING`,恢复控制台、进度条、开始轮询 jobId
3. 轮询改为 `GET /auto-annotation/jobs/{jobId}`
4. 展示 `currentStage` / `processedImages / totalImages` / `keptDetections`
5. 新增"取消"按钮,调用 `POST /auto-annotation/jobs/{jobId}/cancel`
   - 后端只是把 `cancelRequested=true` 写库
   - 异步任务在轮询算法状态的循环里每次检查,命中后抛异常并置 `status=CANCELLED`

### 4.3 服务重启恢复策略(简化版)

你现在是单实例 + H2,重启不频繁。最小可用的恢复策略:

```java
@Component
@RequiredArgsConstructor
public class JobStartupRecovery {
    private final AutoAnnotationJobRepository repo;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        // 启动时把所有 RUNNING 的 job 标记为 FAILED(因为进程已重启,原异步线程没了)
        // 前端拿到 FAILED 后可以点"重试"重新创建一个新 job
        List<AutoAnnotationJob> orphans = repo.findByStatus(JobStatus.RUNNING);
        for (AutoAnnotationJob j : orphans) {
            j.setStatus(JobStatus.FAILED);
            j.setErrorMessage("Server restarted, job orphaned");
            repo.save(j);
        }
    }
}
```

**不要**试图在重启后自动续跑任务。DINO/VLM 的算法侧 task 也丢了,续跑要重做算法侧 task ID 管理,成本远大于收益。

### 4.4 批次 checkpoint

文档里写的"每批 20-50 张图"。这个改动**涉及算法服务协议**(目前算法服务是一次请求所有图片、返回所有结果),工作量和风险都比较大。

**建议第三阶段暂不做这个**。先用"动态超时 + 心跳停滞检测"(2.5 节)兜底大任务。真要做批次,放到第四阶段,那时你应该已经有 MySQL,可以改动算法侧协议。

---

## 五、推荐实施顺序(修订版)

| 阶段 | 内容 | 预估 | 风险 |
|---|---|---|---|
| **阶段 1** | 模式切换 + VLM 配置嵌入 + `@Async` bug 修复 + `AutoAnnotationJob` 表 + 动态超时 | 2-3 天 | 低 |
| **阶段 2** | 上传 fileId 稳定化 + chunk 重试 + 已上传 chunk 查询 + manifest | 1-2 天 | 低 |
| **阶段 3** | jobId 前端对接 + 取消能力 + 启动恢复 | 1-2 天 | 中(依赖阶段 1) |

**不要一次性做 1+2+3**,按阶段灰度,每阶段独立验收、独立发布。

