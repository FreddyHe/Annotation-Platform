# 数据飞轮 MVP 开发任务书

> 本文档是一份**完整的、可独立执行的开发任务**。你(agent)需要严格按照本文档从头到尾执行，包括写代码、改代码、重启服务、跑验证脚本。每个阶段都有明确的验证标准,未通过**不得**进入下一阶段。

---

## 0. 背景与目标

### 0.1 项目基本信息

- 项目根目录: `/root/autodl-fs/Annotation-Platform`
- 后端: Spring Boot 3.2 + JPA + H2,端口 8080,context-path `/api/v1`
- 前端: Vue 3 + Element Plus,端口 6006
- Label Studio: 端口 5001, admin token `3dd84879dff6fd5949dc1dd76edbecccac3f8524`
- 算法服务: FastAPI 端口 8001, DINO 端口 5003
- 图片上传根目录: `/root/autodl-fs/uploads`
- 边端推理图片存放: `/root/autodl-fs/uploads/edge_inference/{deploymentId}/`
- LS sqlite: `/root/.local/share/label-studio/label_studio.sqlite3`

### 0.2 要解决的业务问题

训练时的数据来源逻辑是:"**有人工审核就用人工的,没有就用 predictions**" (见 `FormatConverterService.convertLabelStudioToYOLO()`)。这意味着 LS 主项目里会有大量未审 prediction。当边端不断回传新数据后,新老数据混在一起,人工无法识别哪些是新增量。

**解决方案**:通过 LS 子项目做物理隔离:

- 主项目 `<项目名>` (已存在):接收所有置信度较高的数据(HIGH + LOW_A)作为 **prediction**,可用可不审
- 增量项目 `<项目名>__增量001__待审`, `__增量002__待审` ...(新建):每批 100 张 LOW_B 数据,**必须全审**
- 审完的增量项目自动改名为 `__已审`
- 审核结果通过 LS webhook 回写到 `inference_data_points.human_reviewed = true`
- 训练时 `FormatConverterService.buildFeedbackDataset()` 从 `inference_data_points` 拿人审过的 LOW_B → 天然就实现了"审过的用,没审的跳过"

### 0.3 MVP 范围

本次任务**只做**以下内容,其他优化不做:

1. 新增实体 `LsSubProject` + Repository,用于记录主项目/增量项目的映射
2. 新增 `IncrementalProjectService`,负责:
   - HIGH/LOW_A 同步到主 LS 项目(作为 prediction,复用现有训练逻辑)
   - LOW_B 攒够批次大小后,新建"__待审"增量 LS 项目
   - 根据 LS API 实时查审核进度,满审时改项目名后缀为 `__已审`
3. 修改 `EdgeSimulatorService.runInference()`:推理落库后调用同步
4. 修改 `VlmJudgeService.judgeAndSplit()`:判定出 LOW_B 后触发同步
5. 新增 `LabelStudioWebhookController`:接收 LS 审核事件,回写 `inference_data_points`
6. `ProjectConfig` 新增字段 `lowBBatchSize` (默认 100)
7. `SecurityConfig` 放行 `/ls-webhook/**`
8. 完成编译 + 重启后端 + 端到端验证

### 0.4 本次**不做**的事

下面这些事一律不做,即使你觉得应该做:

- 不改 `FormatConverterService`。现有逻辑"annotation 优先 predictions fallback"本身就契合方案,不动它
- 不改前端。一行前端代码都不写
- 不加"训练前警告未审增量项目"的功能。当前凭 `human_reviewed` 字段就能过滤
- 不做 holdout 评估集、不做主动学习采样、不做真 AutoML、不做训练曲线可视化
- 不删除现有字段、不改任何现有 API 的签名
- 不重构 `EdgeSimulatorService` 的其他方法
- 不在 webhook 里做 JWT 校验(LS 那边不会带你的 JWT)
- 不去碰 LS SQLite 做写操作

如果你在执行中产生"顺手也改一下"的念头,**一律压下去**,记在本文档末尾的"后续建议"里交给人类决定。

---

## 1. 执行顺序总览

严格按下面的顺序来。每个阶段必须跑完该阶段的验证,通过后再进下一个阶段。

```
阶段 A: 环境预检                        (不改代码,只 cat/查询)
阶段 B: 建新文件                        (3 个新文件)
阶段 C: 改现有文件                      (4 处改动)
阶段 D: 编译                            (mvn clean package)
阶段 E: 重启后端 + 检查表结构           (确认 JPA 自动迁移成功)
阶段 F: 单元级 API 烟测                 (curl 直接打端点)
阶段 G: 端到端验证                      (模拟真实流程)
阶段 H: 配置 LS webhook                 (手动配 + 验证回调)
阶段 I: 产出验收报告                    (生成 report.md)
```

---

## 2. 阶段 A: 环境预检

### A.1 目标

确认当前环境和代码状态符合任务书假设。任何一项不符,**立即停止并报告**,不要擅自修改。

### A.2 检查项

运行以下命令,把输出保存到 `/tmp/flywheel_precheck.log`:

```bash
PROJECT_ROOT=/root/autodl-fs/Annotation-Platform
LOG=/tmp/flywheel_precheck.log
> $LOG

echo "===== A.1 项目路径是否存在 =====" >> $LOG
ls -la $PROJECT_ROOT/backend-springboot/src/main/java/com/annotation/platform/ >> $LOG 2>&1

echo "===== A.2 目标被改动的 Java 文件是否都在 =====" >> $LOG
for f in \
  service/EdgeSimulatorService.java \
  service/VlmJudgeService.java \
  service/labelstudio/LabelStudioProxyService.java \
  service/labelstudio/impl/LabelStudioProxyServiceImpl.java \
  entity/InferenceDataPoint.java \
  entity/ProjectConfig.java \
  entity/Project.java \
  repository/InferenceDataPointRepository.java \
  repository/ProjectConfigRepository.java ; do
  full=$PROJECT_ROOT/backend-springboot/src/main/java/com/annotation/platform/$f
  if [ -f "$full" ]; then echo "OK  $f"; else echo "MISSING  $f"; fi
done >> $LOG

echo "===== A.3 服务进程状态 =====" >> $LOG
ps -ef | grep -E "java -jar|label-studio|uvicorn|dino_model" | grep -v grep >> $LOG

echo "===== A.4 端口监听 =====" >> $LOG
ss -tlnp 2>/dev/null | grep -E ":8080|:6006|:5001|:8001|:5003" >> $LOG

echo "===== A.5 application.yml 关键配置 =====" >> $LOG
grep -E "admin-token|upload|base-path|db-path|label-studio" \
  $PROJECT_ROOT/backend-springboot/src/main/resources/application.yml >> $LOG 2>&1

echo "===== A.6 SecurityConfig 位置 =====" >> $LOG
find $PROJECT_ROOT/backend-springboot -name "SecurityConfig.java" -type f >> $LOG

echo "===== A.7 H2 表是否已有 ls_sub_projects =====" >> $LOG
echo "(若后端正在运行,表示 JPA 已建过表;若未运行,此检查跳过)" >> $LOG

cat $LOG
```

### A.3 通过标准

- A.2 所有文件都显示 `OK`。若有 `MISSING`,**停止,向人类报告**
- A.3 后端、LS、算法服务都在跑。如果后端没跑,先按 `/root/autodl-fs/Annotation-Platform/.context/SETUP.md` 把它拉起来
- A.6 能找到 `SecurityConfig.java` 的确切路径,记下来(阶段 C 要改)
- A.5 能看到 `app.label-studio.admin-token` 和 `app.file.upload.base-path`,记录实际值

### A.4 如果失败

- 如果文件缺失 → 停止,不要尝试创建缺失的文件来"修复"。让人类确认为什么文件位置和任务书不一致
- 如果服务没跑 → 按 SETUP.md 启动,日志留到 `/tmp/*.log`。如果起不来,停止,报告错误日志前 50 行

---

## 3. 阶段 B: 建新文件

### B.1 新文件 #1: `LsSubProject.java` (实体)

**绝对路径:**
`/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/entity/LsSubProject.java`

**完整内容:**

```java
package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Label Studio 子项目映射表。
 *
 * - sub_type=MAIN:  对应 Project.lsProjectId 的主项目。本任务中暂时不写入 MAIN 类型,
 *                   保留此枚举值以便未来扩展。当前所有插入均为 INCREMENTAL。
 * - sub_type=INCREMENTAL: 每攒满 N 张 LOW_B 数据新建一个 LS 项目,命名为
 *                        "<主项目名>__增量001__待审",审完后 rename 为 "__已审"。
 */
@Entity
@Table(name = "ls_sub_projects", indexes = {
        @Index(name = "idx_sub_project_project", columnList = "project_id"),
        @Index(name = "idx_sub_project_ls", columnList = "ls_project_id"),
        @Index(name = "idx_sub_project_type_status", columnList = "project_id, sub_type, status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LsSubProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "ls_project_id", nullable = false, unique = true)
    private Long lsProjectId;

    @Column(name = "project_name", nullable = false, length = 200)
    private String projectName;

    @Enumerated(EnumType.STRING)
    @Column(name = "sub_type", nullable = false, length = 20)
    private SubType subType;

    /** 增量批次号, 1 开始, MAIN 类型为 null */
    @Column(name = "batch_number")
    private Integer batchNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING_REVIEW;

    /** 已放入 LS 的 task 数量 (由本服务维护) */
    @Column(name = "expected_tasks")
    @Builder.Default
    private Integer expectedTasks = 0;

    /** 已被人工审核的 task 数量 (从 LS API 刷新) */
    @Column(name = "reviewed_tasks")
    @Builder.Default
    private Integer reviewedTasks = 0;

    /** 触发此增量项目创建的迭代轮次 id (可空) */
    @Column(name = "round_id")
    private Long roundId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    public enum SubType {
        MAIN,
        INCREMENTAL
    }

    public enum Status {
        PENDING_REVIEW,
        PARTIAL,
        REVIEWED
    }
}
```

### B.2 新文件 #2: `LsSubProjectRepository.java`

**绝对路径:**
`/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/repository/LsSubProjectRepository.java`

**完整内容:**

```java
package com.annotation.platform.repository;

import com.annotation.platform.entity.LsSubProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LsSubProjectRepository extends JpaRepository<LsSubProject, Long> {

    Optional<LsSubProject> findByLsProjectId(Long lsProjectId);

    List<LsSubProject> findByProjectIdOrderByBatchNumberAsc(Long projectId);

    List<LsSubProject> findByProjectIdAndSubType(Long projectId, LsSubProject.SubType subType);

    List<LsSubProject> findByProjectIdAndSubTypeAndStatus(Long projectId,
                                                          LsSubProject.SubType subType,
                                                          LsSubProject.Status status);

    /**
     * 找到当前最新的一个"待审"增量项目, 用于判断是否还能继续追加 LOW_B。
     */
    Optional<LsSubProject> findFirstByProjectIdAndSubTypeAndStatusOrderByBatchNumberDesc(
            Long projectId, LsSubProject.SubType subType, LsSubProject.Status status);
}
```

### B.3 新文件 #3: `IncrementalProjectService.java`

**绝对路径:**
`/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/service/IncrementalProjectService.java`

**完整内容:**

```java
package com.annotation.platform.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.annotation.platform.entity.*;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.InferenceDataPointRepository;
import com.annotation.platform.repository.LsSubProjectRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.service.labelstudio.LabelStudioProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 增量审核项目管理服务。
 *
 * 三件事:
 * 1. 把 HIGH / LOW_A 作为 prediction 放到主 LS 项目, 使现有
 *    FormatConverterService.convertLabelStudioToYOLO() 在人工没审时自动使用。
 * 2. 把 LOW_B 按 projectConfig.lowBBatchSize 拆批, 每批建一个独立的 LS 项目
 *    "<项目名>__增量XXX__待审", 必须全审。
 * 3. 通过 LS API 查每个增量项目的审核进度, 满审时改名 __已审。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncrementalProjectService {

    private final ProjectRepository projectRepository;
    private final LsSubProjectRepository lsSubProjectRepository;
    private final InferenceDataPointRepository inferenceDataPointRepository;
    private final ProjectConfigService projectConfigService;
    private final LabelStudioProxyService labelStudioProxyService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.label-studio.url}")
    private String labelStudioUrl;

    @Value("${app.label-studio.admin-token}")
    private String adminToken;

    @Value("${app.file.upload.base-path:/root/autodl-fs/uploads}")
    private String uploadBasePath;

    // ==================== HIGH / LOW_A 同步主项目 ====================

    /**
     * 将本轮新增的 HIGH + LOW_A + LOW_A_CANDIDATE 池数据作为 prediction
     * 同步到主 LS 项目。
     *
     * 调用时机: edge inference 保存完所有 InferenceDataPoint 之后。
     * 幂等: 只同步 ls_task_id 为 null 的点。
     */
    @Transactional
    public void syncTrustedPointsToMainProject(Long projectId,
                                               List<InferenceDataPoint> points,
                                               Long userId) {
        if (points == null || points.isEmpty()) return;

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        if (project.getLsProjectId() == null) {
            log.warn("项目没有关联的 LS project, 跳过同步: projectId={}", projectId);
            return;
        }

        List<InferenceDataPoint> toSync = points.stream()
                .filter(p -> p.getLsTaskId() == null)
                .filter(p -> p.getPoolType() == InferenceDataPoint.PoolType.HIGH
                        || p.getPoolType() == InferenceDataPoint.PoolType.LOW_A
                        || p.getPoolType() == InferenceDataPoint.PoolType.LOW_A_CANDIDATE)
                .toList();
        if (toSync.isEmpty()) return;

        ensureMainProjectStorage(project, userId);

        for (InferenceDataPoint point : toSync) {
            try {
                Long lsTaskId = createTaskWithPrediction(
                        project.getLsProjectId(),
                        point,
                        project.getLabels(),
                        "edge_inference_" + point.getPoolType().name());
                if (lsTaskId != null) {
                    point.setLsTaskId(lsTaskId);
                    inferenceDataPointRepository.save(point);
                }
            } catch (Exception e) {
                log.warn("同步 HIGH/LOW_A 点到主项目失败: pointId={}, err={}",
                        point.getId(), e.getMessage());
            }
        }
        log.info("HIGH/LOW_A 同步完成: projectId={}, count={}", projectId, toSync.size());
    }

    // ==================== LOW_B 同步到增量项目 ====================

    /**
     * 将所有"未同步"的 LOW_B 数据批次化地放入增量审核项目。
     *
     * 调用时机: VlmJudgeService.judgeAndSplit() 把数据分到 LOW_B 之后。
     * 策略:
     *   - 找项目里最新的 PENDING_REVIEW 且 expectedTasks < batchSize 的增量项目, 追加
     *   - 否则新建 "<主项目名>__增量XXX__待审"
     */
    @Transactional
    public void syncLowBToIncrementalProject(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        ProjectConfig config = projectConfigService.getOrCreate(projectId);
        int batchSize = config.getLowBBatchSize() == null ? 100 : config.getLowBBatchSize();

        List<InferenceDataPoint> unsyncedLowB = inferenceDataPointRepository
                .findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(p -> p.getPoolType() == InferenceDataPoint.PoolType.LOW_B)
                .filter(p -> p.getLsTaskId() == null)
                .toList();
        if (unsyncedLowB.isEmpty()) return;

        log.info("LOW_B 待同步数据: projectId={}, count={}, batchSize={}",
                projectId, unsyncedLowB.size(), batchSize);

        Optional<LsSubProject> openBatch = lsSubProjectRepository
                .findFirstByProjectIdAndSubTypeAndStatusOrderByBatchNumberDesc(
                        projectId, LsSubProject.SubType.INCREMENTAL, LsSubProject.Status.PENDING_REVIEW);

        LsSubProject currentBatch = null;
        int remainingInBatch = 0;
        if (openBatch.isPresent() && openBatch.get().getExpectedTasks() < batchSize) {
            currentBatch = openBatch.get();
            remainingInBatch = batchSize - currentBatch.getExpectedTasks();
        }

        int synced = 0;
        for (InferenceDataPoint point : unsyncedLowB) {
            if (currentBatch == null || remainingInBatch <= 0) {
                currentBatch = createIncrementalProject(project, userId);
                if (currentBatch == null) {
                    log.error("创建增量项目失败, 中止同步: projectId={}", projectId);
                    return;
                }
                remainingInBatch = batchSize;
            }

            try {
                Long lsTaskId = createTaskWithPrediction(
                        currentBatch.getLsProjectId(),
                        point,
                        project.getLabels(),
                        "edge_inference_low_b");
                if (lsTaskId != null) {
                    point.setLsTaskId(lsTaskId);
                    inferenceDataPointRepository.save(point);
                    currentBatch.setExpectedTasks(currentBatch.getExpectedTasks() + 1);
                    remainingInBatch--;
                    synced++;
                }
            } catch (Exception e) {
                log.warn("同步 LOW_B 到增量项目失败: pointId={}, err={}",
                        point.getId(), e.getMessage());
            }
        }
        if (currentBatch != null) {
            lsSubProjectRepository.save(currentBatch);
        }
        log.info("LOW_B 同步完成: projectId={}, synced={}", projectId, synced);
    }

    // ==================== 审核进度查询 ====================

    /**
     * 查询项目所有增量子项目的审核状态。
     * 每次调用都会对每个子项目实时刷新 LS 审核进度。
     */
    @Transactional
    public Map<String, Object> getIncrementalProjectsStatus(Long projectId, Long userId) {
        List<LsSubProject> subs = lsSubProjectRepository
                .findByProjectIdAndSubType(projectId, LsSubProject.SubType.INCREMENTAL);

        List<Map<String, Object>> reviewed = new ArrayList<>();
        List<Map<String, Object>> pending = new ArrayList<>();

        for (LsSubProject sub : subs) {
            refreshReviewProgress(sub, userId);
            Map<String, Object> info = new HashMap<>();
            info.put("id", sub.getId());
            info.put("lsProjectId", sub.getLsProjectId());
            info.put("projectName", sub.getProjectName());
            info.put("batchNumber", sub.getBatchNumber());
            info.put("expectedTasks", sub.getExpectedTasks());
            info.put("reviewedTasks", sub.getReviewedTasks());
            info.put("status", sub.getStatus().name());
            if (sub.getStatus() == LsSubProject.Status.REVIEWED) {
                reviewed.add(info);
            } else {
                pending.add(info);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("reviewedIncrementals", reviewed);
        result.put("pendingIncrementals", pending);
        result.put("canUseAllIncrementals", pending.isEmpty());
        return result;
    }

    /**
     * 调用 LS GET /api/projects/{id} 刷新单个增量项目的审核进度,
     * 并维护 status (PENDING_REVIEW / PARTIAL / REVIEWED) 与改名。
     */
    @Transactional
    public void refreshReviewProgress(LsSubProject sub, Long userId) {
        try {
            String url = String.format("%s/api/projects/%d", labelStudioUrl, sub.getLsProjectId());
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Token " + adminToken);
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JSONObject body = JSON.parseObject(resp.getBody());
            Integer taskNumber = body.getInteger("task_number");
            Integer withAnnotations = body.getInteger("num_tasks_with_annotations");
            if (taskNumber == null || withAnnotations == null) return;

            sub.setExpectedTasks(taskNumber);
            sub.setReviewedTasks(withAnnotations);
            LsSubProject.Status newStatus;
            if (withAnnotations == 0) {
                newStatus = LsSubProject.Status.PENDING_REVIEW;
            } else if (withAnnotations >= taskNumber) {
                newStatus = LsSubProject.Status.REVIEWED;
            } else {
                newStatus = LsSubProject.Status.PARTIAL;
            }
            if (newStatus != sub.getStatus()) {
                sub.setStatus(newStatus);
                sub.setLastReviewedAt(LocalDateTime.now());
                if (newStatus == LsSubProject.Status.REVIEWED) {
                    renameSubProjectIfNeeded(sub);
                }
            }
            lsSubProjectRepository.save(sub);
        } catch (Exception e) {
            log.warn("刷新 LS 项目审核进度失败: lsProjectId={}, err={}",
                    sub.getLsProjectId(), e.getMessage());
        }
    }

    // ==================== 内部辅助方法 ====================

    private LsSubProject createIncrementalProject(Project project, Long userId) {
        List<LsSubProject> existing = lsSubProjectRepository
                .findByProjectIdAndSubType(project.getId(), LsSubProject.SubType.INCREMENTAL);
        int nextBatch = existing.stream()
                .map(LsSubProject::getBatchNumber)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        String name = String.format("%s__增量%03d__待审", project.getName(), nextBatch);

        try {
            String url = labelStudioUrl + "/api/projects";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + adminToken);

            Map<String, Object> data = new HashMap<>();
            data.put("title", name);
            data.put("description", "增量审核批次 - 需全审");
            data.put("label_config", generateLabelConfig(project.getLabels()));

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(JSON.toJSONString(data), headers), String.class);
            JSONObject created = JSON.parseObject(resp.getBody());
            Long lsProjectId = created.getLong("id");

            // 挂载 uploads 根目录, 让 LS 能直接服务所有图片
            try {
                labelStudioProxyService.mountLocalStorage(lsProjectId, uploadBasePath, userId);
            } catch (Exception ex) {
                log.warn("增量项目 storage 挂载失败, 图片可能无法显示: {}", ex.getMessage());
            }

            LsSubProject sub = LsSubProject.builder()
                    .projectId(project.getId())
                    .lsProjectId(lsProjectId)
                    .projectName(name)
                    .subType(LsSubProject.SubType.INCREMENTAL)
                    .batchNumber(nextBatch)
                    .status(LsSubProject.Status.PENDING_REVIEW)
                    .expectedTasks(0)
                    .reviewedTasks(0)
                    .build();
            sub = lsSubProjectRepository.save(sub);
            log.info("创建增量项目成功: projectId={}, batch={}, lsProjectId={}, name={}",
                    project.getId(), nextBatch, lsProjectId, name);
            return sub;
        } catch (Exception e) {
            log.error("创建 LS 增量项目失败: projectId={}, err={}",
                    project.getId(), e.getMessage(), e);
            return null;
        }
    }

    private void ensureMainProjectStorage(Project project, Long userId) {
        try {
            labelStudioProxyService.mountLocalStorage(
                    project.getLsProjectId(), uploadBasePath, userId);
        } catch (Exception e) {
            log.debug("主项目 storage 已挂载或挂载失败(可忽略): {}", e.getMessage());
        }
    }

    private Long createTaskWithPrediction(Long lsProjectId,
                                          InferenceDataPoint point,
                                          List<String> projectLabels,
                                          String modelVersion) throws Exception {
        int[] dims = readImageDimensions(point.getImagePath());
        int imgW = dims[0];
        int imgH = dims[1];
        if (imgW <= 0 || imgH <= 0) {
            log.warn("无法读取图片尺寸, 跳过: {}", point.getImagePath());
            return null;
        }

        String imageUrl = "/data/local-files/?d=" + point.getImagePath();

        List<Map<String, Object>> predResults = convertDetectionsToLsResult(
                point.getInferenceBboxJson(), imgW, imgH, projectLabels);

        String url = String.format("%s/api/projects/%d/import?return_task_ids=true",
                labelStudioUrl, lsProjectId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + adminToken);

        Map<String, Object> taskData = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        data.put("image", imageUrl);
        data.put("_source", point.getPoolType().name());
        data.put("_point_id", point.getId());
        data.put("_confidence", point.getAvgConfidence());
        taskData.put("data", data);

        if (!predResults.isEmpty()) {
            Map<String, Object> prediction = new HashMap<>();
            prediction.put("model_version", modelVersion);
            prediction.put("score", point.getAvgConfidence() != null ? point.getAvgConfidence() : 0.5);
            prediction.put("result", predResults);
            taskData.put("predictions", List.of(prediction));
        }

        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(JSON.toJSONString(List.of(taskData)), headers),
                String.class);

        String body = resp.getBody();
        if (body == null) return null;

        // LS import API 可能返回对象 {task_ids:[...]} 或数组 [...]
        try {
            if (body.trim().startsWith("{")) {
                JSONObject obj = JSON.parseObject(body);
                com.alibaba.fastjson2.JSONArray ids = obj.getJSONArray("task_ids");
                if (ids != null && !ids.isEmpty()) return ids.getLong(0);
            } else if (body.trim().startsWith("[")) {
                com.alibaba.fastjson2.JSONArray arr = JSON.parseArray(body);
                if (!arr.isEmpty()) {
                    JSONObject first = arr.getJSONObject(0);
                    Long id = first.getLong("id");
                    if (id != null) return id;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private List<Map<String, Object>> convertDetectionsToLsResult(String bboxJson,
                                                                  int imgW, int imgH,
                                                                  List<String> projectLabels) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (bboxJson == null || bboxJson.isBlank()) return results;
        try {
            List<Map<String, Object>> detections =
                    objectMapper.readValue(bboxJson, List.class);
            for (int i = 0; i < detections.size(); i++) {
                Map<String, Object> det = detections.get(i);
                Object bboxObj = det.get("bbox");
                if (!(bboxObj instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> bbox = (Map<String, Object>) bboxObj;
                double x1 = toDouble(bbox.get("x1"));
                double y1 = toDouble(bbox.get("y1"));
                double x2 = toDouble(bbox.get("x2"));
                double y2 = toDouble(bbox.get("y2"));
                String label = (String) det.getOrDefault("label",
                        (projectLabels != null && !projectLabels.isEmpty()) ? projectLabels.get(0) : "object");
                double score = toDouble(det.getOrDefault("confidence", 0.5));

                double xPct = clampPct(x1 / imgW * 100.0);
                double yPct = clampPct(y1 / imgH * 100.0);
                double wPct = clampPct((x2 - x1) / imgW * 100.0);
                double hPct = clampPct((y2 - y1) / imgH * 100.0);

                Map<String, Object> value = new HashMap<>();
                value.put("x", xPct);
                value.put("y", yPct);
                value.put("width", wPct);
                value.put("height", hPct);
                value.put("rotation", 0);
                value.put("rectanglelabels", List.of(label));

                Map<String, Object> result = new HashMap<>();
                result.put("original_width", imgW);
                result.put("original_height", imgH);
                result.put("image_rotation", 0);
                result.put("value", value);
                result.put("id", "det_" + i);
                result.put("from_name", "label");
                result.put("to_name", "image");
                result.put("type", "rectanglelabels");
                result.put("score", score);
                results.add(result);
            }
        } catch (Exception e) {
            log.warn("解析 detections 失败: {}", e.getMessage());
        }
        return results;
    }

    private int[] readImageDimensions(String path) {
        try {
            BufferedImage img = ImageIO.read(new File(path));
            if (img != null) return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception ignored) {}
        return new int[]{0, 0};
    }

    private double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0.0; }
    }

    private double clampPct(double v) {
        if (v < 0.0) return 0.0;
        if (v > 100.0) return 100.0;
        return v;
    }

    private String generateLabelConfig(List<String> labels) {
        if (labels == null || labels.isEmpty()) return "<View></View>";
        String[] colors = {"#FF0000","#00FF00","#0000FF","#FFFF00","#FF00FF","#00FFFF",
                           "#FFA500","#800080","#008000","#FFC0CB","#A52A2A","#808080"};
        StringBuilder sb = new StringBuilder();
        sb.append("<View>\n  <Image name=\"image\" value=\"$image\" zoom=\"true\"/>\n")
          .append("  <RectangleLabels name=\"label\" toName=\"image\">\n");
        for (int i = 0; i < labels.size(); i++) {
            sb.append("    <Label value=\"").append(labels.get(i))
              .append("\" background=\"").append(colors[i % colors.length]).append("\"/>\n");
        }
        sb.append("  </RectangleLabels>\n</View>");
        return sb.toString();
    }

    private void renameSubProjectIfNeeded(LsSubProject sub) {
        try {
            String newName = sub.getProjectName().replace("__待审", "__已审");
            if (newName.equals(sub.getProjectName())) return;
            String url = String.format("%s/api/projects/%d", labelStudioUrl, sub.getLsProjectId());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + adminToken);
            Map<String, Object> data = Map.of("title", newName);
            restTemplate.exchange(url, HttpMethod.PATCH,
                    new HttpEntity<>(JSON.toJSONString(data), headers), String.class);
            sub.setProjectName(newName);
            log.info("增量项目改名: lsProjectId={}, newName={}", sub.getLsProjectId(), newName);
        } catch (Exception e) {
            log.warn("改名失败(不影响功能): {}", e.getMessage());
        }
    }
}
```

### B.4 新文件 #4: `LabelStudioWebhookController.java`

**绝对路径:**
`/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/controller/LabelStudioWebhookController.java`

**完整内容:**

```java
package com.annotation.platform.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.annotation.platform.common.Result;
import com.annotation.platform.entity.InferenceDataPoint;
import com.annotation.platform.entity.LsSubProject;
import com.annotation.platform.repository.InferenceDataPointRepository;
import com.annotation.platform.repository.LsSubProjectRepository;
import com.annotation.platform.service.IncrementalProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Label Studio webhook 入口。
 *
 * LS 侧配置步骤(每个增量项目都需要配):
 *   Settings → Webhooks → Add Webhook
 *   URL: http://localhost:8080/api/v1/ls-webhook/annotation
 *   Send payload: enabled
 *   Events: ANNOTATION_CREATED, ANNOTATION_UPDATED
 *
 * 安全: 此端点不走 JWT (LS 无法带上你的 JWT),
 *       需在 SecurityConfig 里 permitAll。
 */
@Slf4j
@RestController
@RequestMapping("/ls-webhook")
@RequiredArgsConstructor
public class LabelStudioWebhookController {

    private final InferenceDataPointRepository inferenceDataPointRepository;
    private final LsSubProjectRepository lsSubProjectRepository;
    private final IncrementalProjectService incrementalProjectService;

    @PostMapping("/annotation")
    public Result<Void> onAnnotation(@RequestBody Map<String, Object> payload) {
        try {
            JSONObject body = JSON.parseObject(JSON.toJSONString(payload));
            String action = body.getString("action");
            JSONObject task = body.getJSONObject("task");
            JSONObject annotation = body.getJSONObject("annotation");
            if (task == null || annotation == null) {
                log.debug("Webhook payload 缺 task/annotation 字段, action={}", action);
                return Result.success();
            }

            Long lsTaskId = task.getLong("id");
            Long lsProjectId = task.getLong("project");
            if (lsTaskId == null) return Result.success();

            // 1. 找 InferenceDataPoint
            //    当前无索引字段, 所以遍历查找。由于单个项目的数据点数量一般在 10^4 量级,
            //    这里暂接受。若性能成问题, 未来给 ls_task_id 加索引或加 repository 方法。
            Optional<InferenceDataPoint> opt = inferenceDataPointRepository
                    .findAll().stream()
                    .filter(p -> lsTaskId.equals(p.getLsTaskId()))
                    .findFirst();
            if (opt.isEmpty()) {
                log.debug("Webhook: 未找到对应的 data point, lsTaskId={}", lsTaskId);
                return Result.success();
            }
            InferenceDataPoint point = opt.get();

            // 2. 把 annotation.result 转回 inference_bbox_json 并标记 humanReviewed
            com.alibaba.fastjson2.JSONArray result = annotation.getJSONArray("result");
            if (result != null) {
                String newBboxJson = convertLsResultToDetections(result);
                point.setInferenceBboxJson(newBboxJson);
            }
            point.setHumanReviewed(true);
            inferenceDataPointRepository.save(point);

            // 3. 刷新对应增量项目审核进度
            if (lsProjectId != null) {
                Optional<LsSubProject> subOpt = lsSubProjectRepository.findByLsProjectId(lsProjectId);
                subOpt.ifPresent(sub -> incrementalProjectService.refreshReviewProgress(sub, null));
            }
            log.info("Webhook 处理完成: action={}, lsTaskId={}, pointId={}",
                    action, lsTaskId, point.getId());
        } catch (Exception e) {
            log.error("Webhook 处理失败: {}", e.getMessage(), e);
        }
        return Result.success();
    }

    private String convertLsResultToDetections(com.alibaba.fastjson2.JSONArray lsResults) {
        com.alibaba.fastjson2.JSONArray detections = new com.alibaba.fastjson2.JSONArray();
        for (int i = 0; i < lsResults.size(); i++) {
            JSONObject r = lsResults.getJSONObject(i);
            JSONObject value = r.getJSONObject("value");
            if (value == null) continue;

            Integer ow = r.getInteger("original_width");
            Integer oh = r.getInteger("original_height");
            if (ow == null || oh == null) continue;

            double xPct = value.getDoubleValue("x");
            double yPct = value.getDoubleValue("y");
            double wPct = value.getDoubleValue("width");
            double hPct = value.getDoubleValue("height");
            double x1 = xPct / 100.0 * ow;
            double y1 = yPct / 100.0 * oh;
            double x2 = x1 + wPct / 100.0 * ow;
            double y2 = y1 + hPct / 100.0 * oh;

            com.alibaba.fastjson2.JSONArray labels = value.getJSONArray("rectanglelabels");
            String label = (labels != null && !labels.isEmpty()) ? labels.getString(0) : "object";

            JSONObject det = new JSONObject();
            det.put("label", label);
            det.put("confidence", 1.0);
            JSONObject bbox = new JSONObject();
            bbox.put("x1", x1);
            bbox.put("y1", y1);
            bbox.put("x2", x2);
            bbox.put("y2", y2);
            det.put("bbox", bbox);
            detections.add(det);
        }
        return detections.toJSONString();
    }
}
```

### B.5 检查

写完 4 个新文件后,运行:

```bash
ls -l \
  /root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/entity/LsSubProject.java \
  /root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/repository/LsSubProjectRepository.java \
  /root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/service/IncrementalProjectService.java \
  /root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/controller/LabelStudioWebhookController.java
```

4 个文件都能 `ls` 到即通过。

---

## 4. 阶段 C: 改现有文件

每处改动都用 `str_replace` 或等价的精确匹配,不要用模糊替换。改完后 diff 看一下确认只有目标区域变了。

### C.1 改动 #1: `ProjectConfig.java` 新增字段

**文件:** `/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/entity/ProjectConfig.java`

**在 `retrainMinSamples` 字段后面** 追加一个字段。精确定位:

找到这段(原文):

```java
    @Column(name = "retrain_min_samples")
    @Builder.Default
    private Integer retrainMinSamples = 200;
}
```

改为:

```java
    @Column(name = "retrain_min_samples")
    @Builder.Default
    private Integer retrainMinSamples = 200;

    /** LOW_B 增量审核项目的批次大小, 默认 100 */
    @Column(name = "low_b_batch_size")
    @Builder.Default
    private Integer lowBBatchSize = 100;
}
```

### C.2 改动 #2: `EdgeSimulatorService.java` 插入同步调用

**文件:** `/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/service/EdgeSimulatorService.java`

**C.2.1 注入 bean** — 找到这段:

```java
    private final VlmJudgeService vlmJudgeService;
    private final RestTemplate restTemplate;
```

改为:

```java
    private final VlmJudgeService vlmJudgeService;
    private final IncrementalProjectService incrementalProjectService;
    private final RestTemplate restTemplate;
```

**C.2.2 在 runInference 方法内插入同步逻辑** — 找到这段(精确匹配):

```java
        roundService.markRoundCollecting(deployment.getRoundId());
        if (Boolean.TRUE.equals(config.getEnableAutoVlmJudge())) {
            vlmJudgeService.judgeAndSplit(deployment.getProjectId(), deployment.getRoundId());
            saved = inferenceDataPointRepository.findAllById(savedIds).stream()
                    .map(this::toPointResponse)
                    .toList();
        }

        return Map.of(
```

改为:

```java
        roundService.markRoundCollecting(deployment.getRoundId());
        if (Boolean.TRUE.equals(config.getEnableAutoVlmJudge())) {
            vlmJudgeService.judgeAndSplit(deployment.getProjectId(), deployment.getRoundId());
            saved = inferenceDataPointRepository.findAllById(savedIds).stream()
                    .map(this::toPointResponse)
                    .toList();
        }

        // === 数据飞轮: 同步到 Label Studio ===
        // HIGH / LOW_A 作为 prediction 进主项目;
        // LOW_B (VLM 判定完成后才产生) 进增量项目。
        try {
            List<InferenceDataPoint> latestPoints = inferenceDataPointRepository.findAllById(savedIds);
            Long projectOwnerId = projectRepository.findById(deployment.getProjectId())
                    .map(p -> p.getCreatedBy() != null ? p.getCreatedBy().getId() : null)
                    .orElse(null);
            incrementalProjectService.syncTrustedPointsToMainProject(
                    deployment.getProjectId(), latestPoints, projectOwnerId);
            incrementalProjectService.syncLowBToIncrementalProject(
                    deployment.getProjectId(), projectOwnerId);
        } catch (Exception syncErr) {
            log.warn("Label Studio 同步失败(不影响推理主流程): {}", syncErr.getMessage());
        }

        return Map.of(
```

### C.3 改动 #3: `VlmJudgeService.java` 插入同步调用

**文件:** `/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/service/VlmJudgeService.java`

**C.3.1 注入 bean** — 找到这段:

```java
    private final InferenceDataPointRepository inferenceDataPointRepository;
    private final ProjectConfigService projectConfigService;
    private final RoundService roundService;
    private final RestTemplate restTemplate;
```

改为:

```java
    private final InferenceDataPointRepository inferenceDataPointRepository;
    private final ProjectConfigService projectConfigService;
    private final RoundService roundService;
    private final IncrementalProjectService incrementalProjectService;
    private final com.annotation.platform.repository.ProjectRepository projectRepository;
    private final RestTemplate restTemplate;
```

**C.3.2 在 judgeAndSplit 末尾触发同步** — 找到这段(精确匹配):

```java
        roundService.markRoundReviewing(roundId);
        Map<String, Object> response = new HashMap<>();
        response.put("processed", results.size());
        response.put("lowA", lowA);
        response.put("lowB", lowB);
        response.put("discarded", discarded);
        return response;
    }
```

改为:

```java
        roundService.markRoundReviewing(roundId);

        // VLM 分池完成后, 若新增了 LOW_B 则触发增量项目同步
        if (lowB > 0) {
            try {
                Long ownerId = projectRepository.findById(projectId)
                        .map(p -> p.getCreatedBy() != null ? p.getCreatedBy().getId() : null)
                        .orElse(null);
                incrementalProjectService.syncLowBToIncrementalProject(projectId, ownerId);
            } catch (Exception e) {
                log.warn("LOW_B 增量项目同步失败: {}", e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("processed", results.size());
        response.put("lowA", lowA);
        response.put("lowB", lowB);
        response.put("discarded", discarded);
        return response;
    }
```

### C.4 改动 #4: `SecurityConfig` 放行 webhook

**文件路径**:阶段 A.6 已经定位到。**如果路径不是** `backend-springboot/src/main/java/com/annotation/platform/config/SecurityConfig.java`,以实际路径为准。

**操作**:打开该文件,找到配置 `authorizeHttpRequests` 或 `authorizeRequests` 的代码块,找到现有的 `permitAll()` 规则列表(例如放行 `/auth/login`、`/auth/register` 的地方),在同一位置**追加**一条:

```java
                        .requestMatchers("/ls-webhook/**").permitAll()
```

确保语法与上下文现有 permitAll 规则一致(比如现有代码用的是 `antMatchers` 就用 `antMatchers`,用的是 `requestMatchers` 就用 `requestMatchers`)。

**如果 SecurityConfig 使用了 CSRF 保护**,同时确认 `/ls-webhook/**` 在 CSRF ignoring 列表里(如果有配置 CSRF 的话)。LS 发过来的请求不会带 CSRF token。

### C.5 验证改动

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
grep -n "IncrementalProjectService" \
  src/main/java/com/annotation/platform/service/EdgeSimulatorService.java \
  src/main/java/com/annotation/platform/service/VlmJudgeService.java
grep -n "low_b_batch_size\|lowBBatchSize" \
  src/main/java/com/annotation/platform/entity/ProjectConfig.java
grep -rn "ls-webhook" src/main/java/com/annotation/platform/config/
```

三组命令都应该有非空输出。

---

## 5. 阶段 D: 编译

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn clean package -DskipTests 2>&1 | tee /tmp/flywheel_build.log
tail -n 30 /tmp/flywheel_build.log
```

### D.1 通过标准

- 日志末尾出现 `BUILD SUCCESS`
- 生成文件 `target/platform-backend-1.0.0.jar`

### D.2 如果失败

常见失败类型及处理:

| 报错 | 原因 | 动作 |
|---|---|---|
| `cannot find symbol: class ResourceNotFoundException` | 该异常类在本项目里 package 可能不是 `exception.` 而是其他 | 在 `IncrementalProjectService.java` 顶部用实际的完整路径 import, 先 grep 项目里 `ResourceNotFoundException` 看实际 package |
| `package com.annotation.platform.service.labelstudio does not exist` | 目录结构没刷新 | 重试一次 mvn |
| 中文字符在 `__待审` 处报错 | 编码问题 | 检查 `pom.xml` 里 maven-compiler-plugin 是否配了 `<encoding>UTF-8</encoding>`, 若没有**停止报告**,不要擅自改 pom |
| `cannot resolve symbol: Result` | Result 工具类的 package 与预期不同 | grep 实际 package, 修正 `LabelStudioWebhookController.java` 的 import |

**编译不通过不得进下一阶段**。最多尝试 3 次修复,3 次都不行就停止汇报。

---

## 6. 阶段 E: 重启后端 + 检查表结构

### E.1 重启

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
kill $(lsof -ti:8080) 2>/dev/null; sleep 3
nohup java -jar target/platform-backend-1.0.0.jar --server.port=8080 \
  > /tmp/springboot.log 2>&1 &
sleep 15
grep -E "Started|ERROR" /tmp/springboot.log | tail -n 20
curl -s http://localhost:8080/api/v1/actuator/health
echo
```

### E.2 通过标准

- 日志有 `Started PlatformBackendApplication in ...`
- `/actuator/health` 返回 `{"status":"UP"...}`
- `/tmp/springboot.log` 里没有新增的 ERROR 或 Exception

### E.3 检查 H2 表结构

H2 数据库被后端运行时锁定,只能通过日志间接验证。运行:

```bash
grep -E "create table ls_sub_projects|alter table project_config|low_b_batch_size" \
  /tmp/springboot.log | head -n 20
```

JPA 的 `ddl-auto: update` 策略,如果没输出不一定是问题(取决于日志级别)。更可靠的验证是通过 API 间接触发(下一阶段)。

### E.4 失败处理

如果后端启动失败:

- 把 `/tmp/springboot.log` 最后 100 行贴出来看是什么异常
- 如果是 `BeanCreationException` 通常是构造注入的 bean 缺失 → 检查改动 #2、#3 是否字段名拼写正确
- 如果是表结构冲突 → 这种情况对应原有表有同名字段冲突,**停止并报告**,不要删数据库

---

## 7. 阶段 F: 单元级 API 烟测

### F.1 目标

在不触发真实边端推理的情况下,先验证几个关键行为:

1. Webhook 端点能被 POST 到(验证放行和参数解析)
2. 新表能读写(间接通过查询端点验证)

### F.2 准备登录 token

```bash
# 登录获取 JWT, 用现有账号
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"<填入可用账号>","password":"<填入密码>"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('token',''))")
echo "TOKEN=$TOKEN"
```

**如果没有可用账号**,停止并报告。不要自己创建新账号污染数据库。

### F.3 烟测 1: webhook 端点可达

```bash
curl -i -X POST http://localhost:8080/api/v1/ls-webhook/annotation \
  -H "Content-Type: application/json" \
  -d '{"action":"ANNOTATION_CREATED","task":{"id":999999,"project":999999},"annotation":{"result":[]}}'
```

**通过标准**:返回 HTTP 200,body 是 `{"code":"0000","message":"success","data":null}` 或类似成功结构。
后端日志 `/tmp/springboot.log` 应该有 `Webhook: 未找到对应的 data point, lsTaskId=999999` 这一行(debug 级别可能看不到,视日志配置)。

**失败处理**:如果返回 401/403,说明 SecurityConfig 没放行成功,回到改动 #4 检查。

### F.4 烟测 2: 烟雾式验证表能读

选一个已存在的 projectId(一般是 1),通过一个现有的 controller(如 pool-stats)验证数据流向正常:

```bash
# 现有接口, 不依赖新代码, 仅确认后端 up
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/edge-simulator/deployments?projectId=1"
```

能返回 JSON(空数组或含数据)即通过。

---

## 8. 阶段 G: 端到端验证

### G.1 准备测试数据

在项目根目录下准备:

- 一个已经有 `lsProjectId`(主项目)的 Project,从 H2 查不到的话就从前端已创建的项目里挑一个最简单的。把 projectId 记为 `$PID`,对应 lsProjectId 记为 `$LSPID`
- 该项目已有一个 COMPLETED 状态的 `ModelTrainingRecord`,它的 `bestModelPath` 指向一个能加载的 `.pt` 文件。把 record id 记为 `$MID`

通过 SQL 查询:H2 被锁,改用 API 查:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/training/completed" \
  | python3 -c "
import sys,json
for r in json.load(sys.stdin)['data'][:5]:
    print(f\"mid={r['id']} projectId={r['projectId']} bestModel={r['bestModelPath']}\")
"
```

从输出里选一条 `bestModelPath` 文件实际存在的记录,文件是否存在用 `ls` 检查。

### G.2 部署模型

```bash
curl -s -X POST http://localhost:8080/api/v1/edge-simulator/deploy \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"projectId\":$PID,\"modelRecordId\":$MID,\"edgeNodeName\":\"flywheel-test\"}"
```

记下返回的 `data.id` 为 `$DID` (deployment id)。

### G.3 推理一小批图片

准备至少 10 张测试图(可以从 `/root/autodl-fs/uploads/` 下找),然后:

```bash
# 用第一张图重复多次模拟 10 张;实际用时请换成真正的多张图
IMG=/root/autodl-fs/uploads/...某张存在的图片路径...
CURL_ARGS="-H \"Authorization: Bearer $TOKEN\" -F deploymentId=$DID"
FILES_ARG=""
for i in 1 2 3 4 5 6 7 8 9 10; do
  FILES_ARG="$FILES_ARG -F files=@$IMG"
done
eval curl -s -X POST http://localhost:8080/api/v1/edge-simulator/inference $CURL_ARGS $FILES_ARG \
  | python3 -m json.tool | head -n 60
```

注意返回体里关注 `poolStats`。如果全部进了 DISCARDED 或 LOW_A_CANDIDATE,是正常的,因为同一张图置信度可能偏中等,这取决于你的模型。

### G.4 验证点 1: HIGH/LOW_A 进了主项目

```bash
curl -s -H "Authorization: Token 3dd84879dff6fd5949dc1dd76edbecccac3f8524" \
  "http://localhost:5001/api/projects/$LSPID" \
  | python3 -c "
import sys,json
p=json.load(sys.stdin)
print('task_number=',p.get('task_number'),'num_tasks_with_annotations=',p.get('num_tasks_with_annotations'))
"
```

推理前记下 task_number,推理后再查一次。如果主项目 task_number 增加了 → HIGH 同步生效。注意: 如果所有推理结果都进了 LOW_A_CANDIDATE (还没跑 VLM 判定),那 HIGH 可能是 0,也不会进主项目,这也是正常的。查一下数据池:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/edge-simulator/inference-history?deploymentId=$DID" \
  | python3 -c "
import sys,json
from collections import Counter
pts=json.load(sys.stdin)['data']
c=Counter(p['poolType'] for p in pts)
print('pool stats:', dict(c))
with_ls=sum(1 for p in pts if p.get('lsTaskId'))
print('points with lsTaskId:', with_ls, '/', len(pts))
"
```

**通过标准**:
- 若有 HIGH 或 LOW_A 池数据: `with_ls > 0`,且主项目 task_number 增加
- 若全是 LOW_A_CANDIDATE/DISCARDED: with_ls 可能是 0,这是正常的——尚未触发 VLM 判定

### G.5 验证点 2: 触发 VLM 判定 → LOW_B 建增量项目

推理完成后,如果项目配置开启了 `enableAutoVlmJudge`,VLM 会自动把 LOW_A_CANDIDATE 分派到 LOW_A/LOW_B/DISCARDED。如果没有自动触发,可以手动调(注意: VlmJudgeService 无直接 REST 端点,通常是内部调用)。

等待 30 秒再查:

```bash
sleep 30
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/edge-simulator/inference-history?deploymentId=$DID" \
  | python3 -c "
import sys,json
from collections import Counter
pts=json.load(sys.stdin)['data']
c=Counter(p['poolType'] for p in pts)
print('pool stats after VLM:', dict(c))
"

# 检查 LS 里有没有新建的增量项目
curl -s -H "Authorization: Token 3dd84879dff6fd5949dc1dd76edbecccac3f8524" \
  "http://localhost:5001/api/projects" \
  | python3 -c "
import sys,json
data=json.load(sys.stdin)
projects=data if isinstance(data,list) else data.get('results',[])
for p in projects:
    if '增量' in p.get('title',''):
        print(p['id'], p['title'], 'tasks:', p.get('task_number'))
"
```

**通过标准**: 如果 pool 中有 LOW_B, 那么 LS 里应该出现对应的 `__增量XXX__待审` 项目。

**如果本次测试没产生 LOW_B**(所有数据都进了 HIGH 或 LOW_A),这是测试数据的问题,不是代码 bug。可以换一批低置信度图片再试,或调低 `project_config.highPoolThreshold` 临时把更多数据推到 LOW_A_CANDIDATE。

### G.6 验证点 3: 在 LS 主项目里看到 prediction 框

浏览器打开 `http://localhost:5001/projects/<LSPID>/data`,进入主项目,点开一个刚推理进来的 task,应该能看到:

- 图片正常显示(说明 local storage 挂载 OK)
- 红色的 prediction 框(说明 bbox 坐标转换成功)
- 框上的 label 名称正确
- 任务标记为 "Prediction" 状态

**如果图片显示不出来**:大概率是 LS 没挂载 `/root/autodl-fs/uploads`。查 LS 前端 project settings → Cloud Storage → Local Storage 看挂载路径。

### G.7 失败处理

| 现象 | 可能原因 | 动作 |
|---|---|---|
| HIGH 数据的 `lsTaskId` 全部为 null | `IncrementalProjectService.syncTrustedPointsToMainProject` 没被调用 | 检查 `/tmp/springboot.log` 有没有 `HIGH/LOW_A 同步完成` 日志 |
| LS 项目 task 数没变 | `createTaskWithPrediction` 抛异常 | 查 springboot.log 找 `同步 HIGH/LOW_A 点到主项目失败` 错误栈 |
| 增量项目没建 | LOW_B 为 0, 或创建 LS 项目失败 | 先确认有 LOW_B 数据, 再查 `创建 LS 增量项目失败` 日志 |
| LS 里图片打不开 | local storage 没挂载, 或路径错 | 去 LS 前端手动挂载 `/root/autodl-fs/uploads` |

---

## 9. 阶段 H: 配置 LS webhook

### H.1 目标

让 LS 在人工标注完成时,把事件推到 `POST /api/v1/ls-webhook/annotation`,触发 `human_reviewed=true` 回写。

### H.2 方式: LS API 一键配置(推荐)

LS 支持用 API 创建 webhook。对**每一个已经创建的增量项目** (sub_type=INCREMENTAL),配置 webhook:

```bash
# 先查出所有增量项目的 lsProjectId
LS_TOKEN=3dd84879dff6fd5949dc1dd76edbecccac3f8524
for LSID in $(curl -s -H "Authorization: Token $LS_TOKEN" \
  "http://localhost:5001/api/projects?page_size=200" \
  | python3 -c "
import sys,json
d=json.load(sys.stdin)
projs=d if isinstance(d,list) else d.get('results',[])
for p in projs:
    if '增量' in p.get('title',''):
        print(p['id'])
"); do
  echo "Configuring webhook for LS project $LSID ..."
  curl -s -X POST "http://localhost:5001/api/webhooks/" \
    -H "Authorization: Token $LS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"project\": $LSID,
      \"url\": \"http://localhost:8080/api/v1/ls-webhook/annotation\",
      \"send_payload\": true,
      \"send_for_all_actions\": false,
      \"actions\": [\"ANNOTATION_CREATED\", \"ANNOTATION_UPDATED\"]
    }"
  echo
done
```

**注意**:未来每次 `IncrementalProjectService.createIncrementalProject()` 新建项目都需要再跑一次这个脚本。**本任务不在代码里自动加 webhook 配置**(MVP 范围外),留到后续。

### H.3 验证 webhook 回写

在 LS 前端打开一个 `__待审` 增量项目,随便给一个 task 提交一条 annotation(画个框)。然后查:

```bash
# 等 2 秒让 webhook 到达
sleep 2

# 查后端日志
grep "Webhook 处理完成" /tmp/springboot.log | tail -n 5

# 查对应 data point 的 human_reviewed 是否变成 true
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/edge-simulator/inference-history?deploymentId=$DID" \
  | python3 -c "
import sys,json
for p in json.load(sys.stdin)['data']:
    if p.get('humanReviewed'):
        print('reviewed:', p['id'], p['poolType'], p.get('lsTaskId'))
"
```

**通过标准**:`grep Webhook 处理完成` 有记录;至少有一条数据点的 `humanReviewed` 为 true。

---

## 10. 阶段 I: 产出验收报告

完成以上所有阶段后,**不要继续开发**,生成 `/tmp/flywheel_acceptance.md`,内容如下:

```markdown
# 数据飞轮 MVP 验收报告

## 执行时间
开始: <ISO 时间>
结束: <ISO 时间>

## 阶段执行状态
- [x] A 环境预检 通过
- [x] B 建新文件 4 个
- [x] C 改现有文件 4 处
- [x] D 编译 BUILD SUCCESS
- [x] E 重启 & 表结构 OK
- [x] F 烟测 通过
- [x] G 端到端验证 通过(或跳过项的说明)
- [x] H webhook 配置 通过

## 改动清单
新文件:
1. entity/LsSubProject.java
2. repository/LsSubProjectRepository.java
3. service/IncrementalProjectService.java
4. controller/LabelStudioWebhookController.java

改动文件:
1. entity/ProjectConfig.java 新增 lowBBatchSize 字段
2. service/EdgeSimulatorService.java 注入 IncrementalProjectService, runInference 插入同步
3. service/VlmJudgeService.java 注入 bean, judgeAndSplit 末尾触发 LOW_B 同步
4. config/SecurityConfig.java 放行 /ls-webhook/**

## 验证数据
- 测试 projectId: ?
- 测试 lsProjectId (主): ?
- 部署 id: ?
- 推理图片数: ?
- 池分布: HIGH=?  LOW_A=?  LOW_B=?  LOW_A_CANDIDATE=?  DISCARDED=?
- 带 lsTaskId 的数据点: ? / ?
- 新建增量 LS 项目数: ?
- webhook 回写 humanReviewed=true 的数据点数: ?

## 已知问题 / 后续建议
(记录在这里, 不自动执行)
- ...

## 回滚步骤
如需回滚:
1. kill 后端进程
2. 回退 4 个改动文件为原状 (用 git)
3. 删除 4 个新文件
4. 重新 mvn package 并重启
5. H2 里可遗留 ls_sub_projects 表和 project_config.low_b_batch_size 字段 - 无害, 可不清理

## 未做但人类可能想做的事
- 自动给新建的增量项目配 webhook (当前需手工跑 H.2 脚本)
- FormatConverterService 改造: 增量项目整项审完后也从 LS API 直接抓 annotation, 而不是仅依赖 inference_data_points 的回写
- 前端 Training.vue 加"数据预览卡片", 展示哪些增量项目已审 / 未审
- ls_task_id 加索引避免 webhook 的全表扫描
```

把该报告提交给人类,**停止所有开发**。

---

## 11. 关键注意事项 (务必遵守)

### 11.1 必须守住的边界

- **不要改 FormatConverterService**。现有 "annotation > predictions" 的 fallback 逻辑就是飞轮闭环的核心,改了等于破坏现有行为
- **不要改前端**。一行都不要
- **不要删除或改任何现有数据库字段**。只能加字段、加表
- **不要在任何地方新建用户账号或组织**。测试账号从现有账号里选
- **不要改 SecurityConfig 已有的规则**,只追加一条 permitAll
- **不要改 pom.xml**
- **不要写单元测试文件**。本任务的验证靠 curl 烟测和端到端验证完成
- **不要加 @Scheduled 任何定时任务**。本任务不需要后台轮询

### 11.2 命名与约定

- 增量项目命名格式严格:`{主项目名}__增量XXX__待审` / `__已审`,双下划线、三位补零。将来可能有程序按 split("__") 解析,**不要改成其他格式**
- `pool_type=LOW_A_CANDIDATE` 也会被同步到主项目。因为 VLM 判定完成之前它们的确是"可用的 prediction",只是偶尔会被 VLM 后续降级

### 11.3 错误处理原则

**所有 LS 同步错误都是 warn 级,不能抛到主流程**。理由:边端推理是核心链路,绝不能因为 LS 挂了就推理失败。本任务的两个改动点(C.2 和 C.3)都已经用 try/catch 包了,这个必须保留。

### 11.4 幂等与重复

- `syncTrustedPointsToMainProject` 和 `syncLowBToIncrementalProject` 都只处理 `ls_task_id=null` 的点,多次调用安全
- `mountLocalStorage` 内部有 `getExistingLocalStorage` 检查,多次挂载同一路径会复用已有 storage
- webhook 多次回写同一 task 无害,只是覆盖 bbox

### 11.5 数据库 schema 变化

只会自动增加:
- 新表 `ls_sub_projects`
- `project_config.low_b_batch_size` 字段

**JPA ddl-auto: update 不会删字段也不会改类型**,所以这次变更是绝对安全的,即使回滚也不需要处理数据库。

### 11.6 性能提示(不用处理,但要知道)

`LabelStudioWebhookController` 里用 `findAll().stream().filter(...)` 查 data point——全表扫描。本 MVP 阶段接受这个实现,上线前应该在 `inference_data_points` 表加 `ls_task_id` 索引并补一个 repository 方法 `findByLsTaskId`。把这条写进验收报告的"后续建议"。

### 11.7 何时必须停止向人类报告

以下任何一条触发,**立即停止,不要尝试自行修复**:

1. 阶段 A 任何文件缺失
2. 阶段 D 编译失败且 3 次修复尝试后仍失败
3. 阶段 E 后端启动失败并报 `BeanCreationException` 或 `DataSourceException`
4. 阶段 F webhook 返回 401/403 且检查 SecurityConfig 后仍找不到问题
5. 任何时刻发现本任务书的假设与现实严重不符(比如 `app.label-studio.admin-token` 配置不存在,或 Project 实体已经被重构)
6. 任何需要修改 pom.xml、application.yml、前端代码的需求(本任务不覆盖这些)
7. 运行中发现数据库里已经有一个叫 `ls_sub_projects` 的表且结构不一致

报告时提供:
- 当前在哪个阶段
- 完整的错误输出(日志、堆栈)
- 已经执行成功的步骤列表
- 你认为可能的原因(可选)

---

## 12. 最终 DoD (Definition of Done)

全部以下项必须成立,本任务才算完成:

- [ ] 4 个新 Java 文件存在且都被编译进 jar
- [ ] 4 处改动都已生效(grep 可见)
- [ ] `mvn clean package` BUILD SUCCESS
- [ ] 后端 `/actuator/health` 返回 UP
- [ ] webhook 端点 `POST /api/v1/ls-webhook/annotation` 返回 200(允许 payload 找不到 task 对应的 data point)
- [ ] 至少完成一次端到端推理测试,`inference_data_points` 里至少 1 条记录有非空 `ls_task_id`
- [ ] 至少 1 次人工在 LS 增量项目里标注,触发 webhook 将对应 `human_reviewed` 从 false 改为 true
- [ ] 至少 1 个 LS 增量项目被创建,名字符合 `<项目名>__增量XXX__待审` 格式
- [ ] 产出 `/tmp/flywheel_acceptance.md` 验收报告

全部打勾,任务结束。任何一项未打勾,继续调试或报告。