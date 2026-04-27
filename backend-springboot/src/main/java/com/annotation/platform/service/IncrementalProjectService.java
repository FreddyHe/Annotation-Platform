package com.annotation.platform.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.annotation.platform.entity.InferenceDataPoint;
import com.annotation.platform.entity.LsSubProject;
import com.annotation.platform.entity.ModelTrainingRecord;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.ProjectConfig;
import com.annotation.platform.entity.User;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.InferenceDataPointRepository;
import com.annotation.platform.repository.LsSubProjectRepository;
import com.annotation.platform.repository.ModelTrainingRecordRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.repository.UserRepository;
import com.annotation.platform.service.labelstudio.LabelStudioProxyService;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IncrementalProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final LsSubProjectRepository lsSubProjectRepository;
    private final InferenceDataPointRepository inferenceDataPointRepository;
    private final ModelTrainingRecordRepository modelTrainingRecordRepository;
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

    @Value("${app.backend.webhook-url:http://localhost:8080/api/v1/ls-webhook/annotation}")
    private String webhookUrl;

    @Transactional
    public int syncTrustedPointsToMainProject(Long projectId, List<InferenceDataPoint> points, Long userId) {
        if (points == null || points.isEmpty()) {
            return 0;
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        if (project.getLsProjectId() == null) {
            log.warn("Project has no Label Studio project, skip trusted point sync: projectId={}", projectId);
            return 0;
        }
        String lsToken = resolveLsToken(userId, project);
        if (!isLsProjectAlive(project.getLsProjectId(), lsToken)) {
            project.setLsProjectStatus(Project.LsProjectStatus.DEAD);
            projectRepository.save(project);
            log.warn("Main Label Studio project is dead, skip trusted point sync: projectId={}, lsProjectId={}",
                    projectId, project.getLsProjectId());
            return 0;
        }
        if (project.getLsProjectStatus() != Project.LsProjectStatus.REPAIRED) {
            project.setLsProjectStatus(Project.LsProjectStatus.ACTIVE);
            projectRepository.save(project);
        }

        List<InferenceDataPoint> toSync = points.stream()
                .filter(point -> point.getLsTaskId() == null)
                .filter(point -> point.getPoolType() == InferenceDataPoint.PoolType.HIGH
                        || point.getPoolType() == InferenceDataPoint.PoolType.LOW_A)
                .toList();
        if (toSync.isEmpty()) {
            return 0;
        }

        ensureMainProjectStorage(project, userId);
        int synced = 0;
        for (InferenceDataPoint point : toSync) {
            try {
                Long lsTaskId = createTaskWithPrediction(
                        project.getLsProjectId(),
                        point,
                        project.getLabels(),
                        "edge_inference_" + point.getPoolType().name(),
                        lsToken);
                if (lsTaskId != null) {
                    point.setLsTaskId(lsTaskId);
                    point.setLsSubProjectId(null);
                    inferenceDataPointRepository.save(point);
                    synced++;
                }
            } catch (Exception e) {
                log.warn("Failed to sync trusted point to main Label Studio project: pointId={}, err={}",
                        point.getId(), e.getMessage());
            }
        }
        log.info("Trusted points synced to main Label Studio project: projectId={}, count={}", projectId, synced);
        return synced;
    }

    @Transactional
    public int syncUnsyncedTrustedPoints(Long projectId, Long userId) {
        List<InferenceDataPoint> points = inferenceDataPointRepository
                .findByProjectIdAndPoolTypeInAndLsTaskIdIsNullOrderByCreatedAtAsc(projectId, trustedPoolTypes());
        return syncTrustedPointsToMainProject(projectId, points, userId);
    }

    @Transactional
    public Map<String, Object> syncPendingDataToLabelStudio(Long projectId, Long userId) {
        int trustedSynced = syncUnsyncedTrustedPoints(projectId, userId);
        int lowBSynced = syncLowBToIncrementalProject(projectId, userId);
        ProjectConfig config = projectConfigService.getOrCreate(projectId);
        int batchSize = config.getLowBBatchSize() == null ? 100 : Math.max(1, config.getLowBBatchSize());
        List<InferenceDataPoint> trustedPoints = inferenceDataPointRepository
                .findByProjectIdAndPoolTypeIn(projectId, trustedPoolTypes());
        long trustedSyncedTotal = trustedPoints.stream()
                .filter(point -> point.getLsTaskId() != null)
                .count();
        long trustedPending = trustedPoints.size() - trustedSyncedTotal;
        int waitingLowB = inferenceDataPointRepository
                .findByProjectIdAndPoolTypeAndLsTaskIdIsNullOrderByCreatedAtAsc(
                        projectId, InferenceDataPoint.PoolType.LOW_B)
                .size();

        Map<String, Object> result = new HashMap<>();
        result.put("projectId", projectId);
        result.put("trustedSynced", trustedSynced);
        result.put("trustedSyncedThisTime", trustedSynced);
        result.put("trustedTotal", trustedPoints.size());
        result.put("trustedSyncedTotal", trustedSyncedTotal);
        result.put("trustedPending", trustedPending);
        result.put("lowBSynced", lowBSynced);
        result.put("lowBSyncedThisTime", lowBSynced);
        result.put("waitingLowB", waitingLowB);
        result.put("lowBBatchSize", batchSize);
        result.put("message", waitingLowB > 0 && waitingLowB < batchSize
                ? "LOW_B 数据未达到批次大小，暂不创建新的增量审核项目"
                : "飞轮数据同步完成");
        return result;
    }

    @Transactional
    public int syncLowBToIncrementalProject(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        ProjectConfig config = projectConfigService.getOrCreate(projectId);
        int batchSize = config.getLowBBatchSize() == null ? 100 : Math.max(1, config.getLowBBatchSize());
        String lsToken = resolveLsToken(userId, project);

        List<InferenceDataPoint> unsyncedLowB = inferenceDataPointRepository
                .findByProjectIdAndPoolTypeAndLsTaskIdIsNullOrderByCreatedAtAsc(
                        projectId, InferenceDataPoint.PoolType.LOW_B);
        if (unsyncedLowB.isEmpty()) {
            return 0;
        }

        Optional<LsSubProject> openBatch = lsSubProjectRepository
                .findFirstByProjectIdAndSubTypeAndStatusOrderByBatchNumberDesc(
                        projectId, LsSubProject.SubType.INCREMENTAL, LsSubProject.Status.PENDING_REVIEW);
        if (openBatch.isPresent() && !isLsProjectAlive(openBatch.get().getLsProjectId(), lsToken)) {
            log.warn("Pending incremental Label Studio project is not visible to project owner, skip batch: projectId={}, lsProjectId={}",
                    projectId, openBatch.get().getLsProjectId());
            openBatch = Optional.empty();
        }

        int synced = 0;
        int cursor = 0;

        int openExpectedTasks = openBatch
                .map(batch -> batch.getExpectedTasks() == null ? 0 : batch.getExpectedTasks())
                .orElse(0);
        if (openBatch.isPresent() && openExpectedTasks < batchSize) {
            LsSubProject currentBatch = openBatch.get();
            int remainingInBatch = batchSize - openExpectedTasks;
            if (unsyncedLowB.size() >= remainingInBatch) {
                synced += syncLowBPointsToBatch(project, currentBatch, unsyncedLowB.subList(0, remainingInBatch), userId);
                cursor += remainingInBatch;
            }
        }

        while (unsyncedLowB.size() - cursor >= batchSize) {
            LsSubProject currentBatch = createIncrementalProject(project, userId);
            if (currentBatch == null) {
                log.error("Failed to create incremental Label Studio project, stop LOW_B sync: projectId={}", projectId);
                return synced;
            }
            int end = cursor + batchSize;
            synced += syncLowBPointsToBatch(project, currentBatch, unsyncedLowB.subList(cursor, end), userId);
            cursor = end;
        }

        int waiting = unsyncedLowB.size() - cursor;
        if (waiting > 0) {
            log.info("LOW_B points waiting for full batch: projectId={}, waiting={}, batchSize={}",
                    projectId, waiting, batchSize);
        }

        log.info("LOW_B incremental sync finished: projectId={}, synced={}", projectId, synced);
        return synced;
    }

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

    @Transactional
    public void refreshReviewProgress(LsSubProject sub, Long userId) {
        try {
            Project project = projectRepository.findById(sub.getProjectId()).orElse(null);
            String lsToken = resolveLsToken(userId, project);
            ensureWebhookConfigured(sub.getLsProjectId(), lsToken);
            String url = String.format("%s/api/projects/%d", labelStudioUrl, sub.getLsProjectId());
            HttpHeaders headers = authHeaders(lsToken);
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JSONObject body = JSON.parseObject(resp.getBody());
            Integer taskNumber = body.getInteger("task_number");
            Integer withAnnotations = body.getInteger("num_tasks_with_annotations");
            if (taskNumber == null || withAnnotations == null) {
                return;
            }

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
                    renameSubProjectIfNeeded(sub, lsToken);
                }
            }
            lsSubProjectRepository.save(sub);
        } catch (Exception e) {
            log.warn("Failed to refresh Label Studio incremental review progress: lsProjectId={}, err={}",
                    sub.getLsProjectId(), e.getMessage());
        }
    }

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
            String lsToken = resolveLsToken(userId, project);
            HttpHeaders headers = jsonHeaders(lsToken);

            Map<String, Object> data = new HashMap<>();
            data.put("title", name);
            data.put("description", "增量审核批次 - 需全审");
            data.put("label_config", generateLabelConfig(project.getLabels()));
            Long lsOrgId = resolveLsOrgId(userId, project);
            if (lsOrgId != null) {
                data.put("organization", lsOrgId);
            }

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(JSON.toJSONString(data), headers), String.class);
            JSONObject created = JSON.parseObject(resp.getBody());
            Long lsProjectId = created.getLong("id");

            try {
                labelStudioProxyService.mountLocalStorage(lsProjectId, uploadBasePath, userId);
            } catch (Exception ex) {
                log.warn("Failed to mount storage for incremental Label Studio project: {}", ex.getMessage());
            }
            ensureWebhookConfigured(lsProjectId, lsToken);

            LsSubProject sub = LsSubProject.builder()
                    .projectId(project.getId())
                    .lsProjectId(lsProjectId)
                    .projectName(name)
                    .subType(LsSubProject.SubType.INCREMENTAL)
                    .batchNumber(nextBatch)
                    .status(LsSubProject.Status.PENDING_REVIEW)
                    .expectedTasks(0)
                    .reviewedTasks(0)
                    .roundId(project.getCurrentRoundId())
                    .build();
            sub = lsSubProjectRepository.save(sub);
            log.info("Created incremental Label Studio project: projectId={}, batch={}, lsProjectId={}, name={}",
                    project.getId(), nextBatch, lsProjectId, name);
            return sub;
        } catch (Exception e) {
            log.error("Failed to create incremental Label Studio project: projectId={}, err={}",
                    project.getId(), e.getMessage(), e);
            return null;
        }
    }

    private int syncLowBPointsToBatch(Project project, LsSubProject batch, List<InferenceDataPoint> points, Long userId) {
        String lsToken = resolveLsToken(userId, project);
        int synced = 0;
        for (InferenceDataPoint point : points) {
            try {
                Long lsTaskId = createTaskWithPrediction(
                        batch.getLsProjectId(),
                        point,
                        project.getLabels(),
                        "edge_inference_low_b",
                        lsToken);
                if (lsTaskId != null) {
                    point.setLsTaskId(lsTaskId);
                    point.setLsSubProjectId(batch.getId());
                    inferenceDataPointRepository.save(point);
                    batch.setExpectedTasks((batch.getExpectedTasks() == null ? 0 : batch.getExpectedTasks()) + 1);
                    synced++;
                }
            } catch (Exception e) {
                log.warn("Failed to sync LOW_B point to incremental Label Studio project: pointId={}, err={}",
                        point.getId(), e.getMessage());
            }
        }
        lsSubProjectRepository.save(batch);
        return synced;
    }

    private void ensureMainProjectStorage(Project project, Long userId) {
        try {
            labelStudioProxyService.mountLocalStorage(project.getLsProjectId(), uploadBasePath, userId);
        } catch (Exception e) {
            log.debug("Main project storage mount skipped or failed: {}", e.getMessage());
        }
    }

    public boolean isLsProjectAlive(Long lsProjectId) {
        if (lsProjectId == null) {
            return false;
        }
        return isLsProjectAlive(lsProjectId, adminToken);
    }

    public boolean isLsProjectAlive(Long lsProjectId, Long userId, Project project) {
        return isLsProjectAlive(lsProjectId, resolveLsToken(userId, project));
    }

    private boolean isLsProjectAlive(Long lsProjectId, String lsToken) {
        if (lsProjectId == null) {
            return false;
        }
        try {
            return fetchLsProject(lsProjectId, lsToken) != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    public Map<String, Object> repairMainLsBinding(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        Long oldLsProjectId = project.getLsProjectId();
        String lsToken = resolveLsToken(userId, project);
        if (oldLsProjectId != null && isLsProjectAlive(oldLsProjectId, lsToken)) {
            ensureWebhookConfigured(oldLsProjectId, lsToken);
            project.setLsProjectStatus(Project.LsProjectStatus.ACTIVE);
            projectRepository.save(project);
            int trustedSynced = syncUnsyncedTrustedPoints(projectId, userId);
            Map<String, Object> active = new HashMap<>();
            active.put("projectId", projectId);
            active.put("oldLsProjectId", oldLsProjectId);
            active.put("newLsProjectId", oldLsProjectId);
            active.put("trustedSynced", trustedSynced);
            active.put("status", "ACTIVE");
            active.put("message", "主 Label Studio 项目仍可访问，无需修复");
            return active;
        }

        Long visibleLsProjectId = findAccessibleMainProjectByTitle(project, userId);
        if (visibleLsProjectId != null) {
            project.setLsProjectId(visibleLsProjectId);
            project.setLsProjectStatus(Project.LsProjectStatus.ACTIVE);
            projectRepository.save(project);
            if (!Objects.equals(oldLsProjectId, visibleLsProjectId)) {
                resetLsTaskIdsForDeadProject(projectId, oldLsProjectId);
            }
            ensureWebhookConfigured(visibleLsProjectId, lsToken);
            int trustedSynced = syncUnsyncedTrustedPoints(projectId, userId);

            Map<String, Object> rebound = new HashMap<>();
            rebound.put("projectId", projectId);
            rebound.put("oldLsProjectId", oldLsProjectId);
            rebound.put("newLsProjectId", visibleLsProjectId);
            rebound.put("trustedSynced", trustedSynced);
            rebound.put("status", "ACTIVE");
            rebound.put("message", "已重新绑定到当前用户可见的同名主 Label Studio 项目");
            return rebound;
        }

        Long newLsProjectId = createMainProject(project, userId);
        project.setLsProjectId(newLsProjectId);
        project.setLsProjectStatus(Project.LsProjectStatus.REPAIRED);
        projectRepository.save(project);
        resetLsTaskIdsForDeadProject(projectId, oldLsProjectId);
        ensureWebhookConfigured(newLsProjectId, lsToken);
        int trustedSynced = syncUnsyncedTrustedPoints(projectId, userId);

        Map<String, Object> repaired = new HashMap<>();
        repaired.put("projectId", projectId);
        repaired.put("oldLsProjectId", oldLsProjectId);
        repaired.put("newLsProjectId", newLsProjectId);
        repaired.put("trustedSynced", trustedSynced);
        repaired.put("status", "REPAIRED");
        repaired.put("message", "已重建主 Label Studio 项目绑定，旧任务 ID 已清理");
        return repaired;
    }

    @Transactional
    public Map<String, Object> trainingDatasetPreview(Long projectId, Long userId) {
        Map<String, Object> syncResult = syncPendingDataToLabelStudio(projectId, userId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        String lsToken = resolveLsToken(userId, project);
        boolean mainAlive = isLsProjectAlive(project.getLsProjectId(), lsToken);
        if (!mainAlive && project.getLsProjectId() != null) {
            project.setLsProjectStatus(Project.LsProjectStatus.DEAD);
            projectRepository.save(project);
        }

        int mainTaskCount = mainAlive ? getLsProjectTaskCount(project.getLsProjectId(), lsToken) : 0;
        List<LsSubProject> subs = lsSubProjectRepository
                .findByProjectIdAndSubType(projectId, LsSubProject.SubType.INCREMENTAL);

        int reviewedTasks = 0;
        int pendingTasks = 0;
        List<Map<String, Object>> reviewed = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (LsSubProject sub : subs) {
            refreshReviewProgress(sub, userId);
            Map<String, Object> item = new HashMap<>();
            item.put("id", sub.getId());
            item.put("lsProjectId", sub.getLsProjectId());
            item.put("projectName", sub.getProjectName());
            item.put("status", sub.getStatus().name());
            item.put("expectedTasks", sub.getExpectedTasks());
            item.put("reviewedTasks", sub.getReviewedTasks());
            if (sub.getStatus() == LsSubProject.Status.REVIEWED) {
                reviewedTasks += sub.getReviewedTasks() == null ? 0 : sub.getReviewedTasks();
                reviewed.add(item);
            } else {
                pendingTasks += sub.getExpectedTasks() == null ? 0 : sub.getExpectedTasks();
                skipped.add(item);
            }
        }

        Map<String, Object> preview = new HashMap<>();
        preview.put("projectId", projectId);
        preview.put("mainLsProjectId", project.getLsProjectId());
        preview.put("mainProjectAlive", mainAlive);
        preview.put("mainTaskCount", mainTaskCount);
        preview.put("reviewedIncrementalTasks", reviewedTasks);
        preview.put("pendingIncrementalTasks", pendingTasks);
        preview.put("reviewedIncrementals", reviewed);
        preview.put("skippedIncrementals", skipped);
        preview.put("totalUsableTasks", mainTaskCount + reviewedTasks);
        preview.put("canStart", mainTaskCount + reviewedTasks > 0);
        preview.put("syncResult", syncResult);
        preview.put("currentRoundId", project.getCurrentRoundId());
        preview.put("currentRoundPoolStats", currentRoundPoolStats(project.getCurrentRoundId()));
        preview.put("latestTrainingData", latestTrainingData(projectId));
        preview.put("splitPolicy", "随机 80% 训练集 / 20% 验证集，至少保留 1 张验证图；不生成 test 集。");
        return preview;
    }

    private Map<String, Object> currentRoundPoolStats(Long roundId) {
        Map<String, Object> stats = new HashMap<>();
        if (roundId == null) {
            stats.put("roundId", null);
            stats.put("total", 0L);
            return stats;
        }
        long total = 0;
        stats.put("roundId", roundId);
        for (InferenceDataPoint.PoolType type : InferenceDataPoint.PoolType.values()) {
            long count = inferenceDataPointRepository.countByRoundIdAndPoolType(roundId, type);
            stats.put(type.name(), count);
            total += count;
        }
        stats.put("total", total);
        return stats;
    }

    private Map<String, Object> latestTrainingData(Long projectId) {
        List<ModelTrainingRecord> records = modelTrainingRecordRepository.findByProjectIdAndStatus(
                projectId, ModelTrainingRecord.TrainingStatus.COMPLETED);
        if (records.isEmpty()) {
            return Map.of("exists", false);
        }
        ModelTrainingRecord latest = records.get(0);
        Map<String, Object> data = new HashMap<>();
        data.put("exists", true);
        data.put("trainingRecordId", latest.getId());
        data.put("roundId", latest.getRoundId());
        data.put("totalImages", latest.getTotalImages());
        data.put("totalAnnotations", latest.getTotalAnnotations());
        data.put("datasetPath", latest.getDatasetPath());
        data.put("completedAt", latest.getCompletedAt());
        try {
            JsonNode root = parseStoredJson(latest.getTestResults());
            if (root == null) {
                return data;
            }
            JsonNode metadata = root.path("metadata");
            data.put("datasetFingerprint", metadata.path("datasetFingerprint").asText(""));
            data.put("sourceSummary", objectMapper.convertValue(metadata.path("sourceSummary"), Map.class));
            data.put("trainImages", root.path("trainImages").asInt(0));
            data.put("valImages", root.path("valImages").asInt(0));
        } catch (Exception ignored) {
        }
        return data;
    }

    private JsonNode parseStoredJson(String value) throws Exception {
        if (value == null || value.isBlank()) {
            return null;
        }
        JsonNode node = objectMapper.readTree(value);
        for (int i = 0; i < 8 && node != null && node.isTextual(); i++) {
            String text = node.asText();
            if (text == null || text.isBlank()) {
                return null;
            }
            node = objectMapper.readTree(text);
        }
        return node;
    }

    private int getLsProjectTaskCount(Long lsProjectId, String lsToken) {
        if (lsProjectId == null) {
            return 0;
        }
        try {
            JSONObject body = fetchLsProject(lsProjectId, lsToken);
            Integer taskNumber = body.getInteger("task_number");
            return taskNumber == null ? 0 : taskNumber;
        } catch (Exception e) {
            return 0;
        }
    }

    private Long createMainProject(Project project, Long userId) {
        try {
            String url = labelStudioUrl + "/api/projects";
            String lsToken = resolveLsToken(userId, project);
            HttpHeaders headers = jsonHeaders(lsToken);

            Map<String, Object> data = new HashMap<>();
            data.put("title", project.getName());
            data.put("description", "平台主标注项目（自动修复重建）");
            data.put("label_config", generateLabelConfig(project.getLabels()));
            Long lsOrgId = resolveLsOrgId(userId, project);
            if (lsOrgId != null) {
                data.put("organization", lsOrgId);
            }

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(JSON.toJSONString(data), headers), String.class);
            Long lsProjectId = JSON.parseObject(resp.getBody()).getLong("id");
            labelStudioProxyService.mountLocalStorage(lsProjectId, uploadBasePath, userId);
            return lsProjectId;
        } catch (Exception e) {
            throw new RuntimeException("修复主 Label Studio 绑定失败: " + e.getMessage(), e);
        }
    }

    private void resetLsTaskIdsForDeadProject(Long projectId, Long deadLsProjectId) {
        if (deadLsProjectId == null) {
            return;
        }
        List<InferenceDataPoint.PoolType> trusted = List.of(
                InferenceDataPoint.PoolType.HIGH,
                InferenceDataPoint.PoolType.LOW_A
        );
        List<InferenceDataPoint> points = inferenceDataPointRepository.findByProjectIdAndPoolTypeIn(projectId, trusted);
        int reset = 0;
        for (InferenceDataPoint point : points) {
            if (point.getLsTaskId() != null) {
                point.setLsTaskId(null);
                point.setLsSubProjectId(null);
                inferenceDataPointRepository.save(point);
                reset++;
            }
        }
        log.info("Reset stale main Label Studio task ids: projectId={}, deadLsProjectId={}, count={}",
                projectId, deadLsProjectId, reset);
    }

    private void ensureWebhookConfigured(Long lsProjectId) {
        ensureWebhookConfigured(lsProjectId, adminToken);
    }

    private void ensureWebhookConfigured(Long lsProjectId, String lsToken) {
        if (lsProjectId == null || webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        try {
            String listUrl = String.format("%s/api/webhooks?project=%d", labelStudioUrl, lsProjectId);
            HttpHeaders headers = jsonHeaders(lsToken);
            ResponseEntity<String> listResp = restTemplate.exchange(
                    listUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String body = listResp.getBody();
            if (body != null && body.contains(webhookUrl)) {
                return;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("project", lsProjectId);
            payload.put("url", webhookUrl);
            payload.put("actions", List.of("ANNOTATION_CREATED", "ANNOTATION_UPDATED"));
            payload.put("is_active", true);
            String createUrl = labelStudioUrl + "/api/webhooks";
            restTemplate.exchange(createUrl, HttpMethod.POST,
                    new HttpEntity<>(JSON.toJSONString(payload), headers), String.class);
            log.info("Configured Label Studio webhook: lsProjectId={}, url={}", lsProjectId, webhookUrl);
        } catch (Exception e) {
            log.warn("Failed to configure Label Studio webhook: lsProjectId={}, err={}", lsProjectId, e.getMessage());
        }
    }

    private Long createTaskWithPrediction(Long lsProjectId,
                                          InferenceDataPoint point,
                                          List<String> projectLabels,
                                          String modelVersion,
                                          String lsToken) throws Exception {
        int[] dims = readImageDimensions(point.getImagePath());
        int imgW = dims[0];
        int imgH = dims[1];
        if (imgW <= 0 || imgH <= 0) {
            log.warn("Cannot read image dimensions, skip Label Studio task sync: {}", point.getImagePath());
            return null;
        }

        String imageUrl = toLabelStudioLocalFileUrl(point.getImagePath());
        List<Map<String, Object>> predResults = convertDetectionsToLsResult(
                point.getInferenceBboxJson(), imgW, imgH, projectLabels);

        String url = String.format("%s/api/projects/%d/import?return_task_ids=true", labelStudioUrl, lsProjectId);
        HttpHeaders headers = jsonHeaders(lsToken);

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
        if (body == null) {
            return null;
        }

        try {
            if (body.trim().startsWith("{")) {
                JSONObject obj = JSON.parseObject(body);
                com.alibaba.fastjson2.JSONArray ids = obj.getJSONArray("task_ids");
                if (ids != null && !ids.isEmpty()) {
                    return ids.getLong(0);
                }
            } else if (body.trim().startsWith("[")) {
                com.alibaba.fastjson2.JSONArray arr = JSON.parseArray(body);
                if (!arr.isEmpty()) {
                    JSONObject first = arr.getJSONObject(0);
                    Long id = first.getLong("id");
                    if (id != null) {
                        return id;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String toLabelStudioLocalFileUrl(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return "/data/local-files/?d=";
        }

        String normalized = imagePath.replace("\\", "/");
        File uploadRoot = new File(uploadBasePath).getAbsoluteFile();
        File documentRoot = uploadRoot.getParentFile();
        if (documentRoot != null) {
            String documentRootPath = documentRoot.getAbsolutePath().replace("\\", "/");
            if (normalized.startsWith(documentRootPath + "/")) {
                normalized = normalized.substring(documentRootPath.length() + 1);
            }
        }

        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return "/data/local-files/?d=" + normalized;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertDetectionsToLsResult(String bboxJson,
                                                                  int imgW,
                                                                  int imgH,
                                                                  List<String> projectLabels) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (bboxJson == null || bboxJson.isBlank()) {
            return results;
        }
        try {
            List<Map<String, Object>> detections = objectMapper.readValue(bboxJson, List.class);
            for (int i = 0; i < detections.size(); i++) {
                Map<String, Object> det = detections.get(i);
                Object bboxObj = det.get("bbox");
                if (!(bboxObj instanceof Map<?, ?> rawBbox)) {
                    continue;
                }
                Map<String, Object> bbox = (Map<String, Object>) rawBbox;
                double x1 = toDouble(bbox.get("x1"));
                double y1 = toDouble(bbox.get("y1"));
                double x2 = toDouble(bbox.get("x2"));
                double y2 = toDouble(bbox.get("y2"));
                String label = String.valueOf(det.getOrDefault("label",
                        (projectLabels != null && !projectLabels.isEmpty()) ? projectLabels.get(0) : "object"));
                double score = toDouble(det.getOrDefault("confidence", 0.5));

                Map<String, Object> value = new HashMap<>();
                value.put("x", clampPct(x1 / imgW * 100.0));
                value.put("y", clampPct(y1 / imgH * 100.0));
                value.put("width", clampPct((x2 - x1) / imgW * 100.0));
                value.put("height", clampPct((y2 - y1) / imgH * 100.0));
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
            log.warn("Failed to parse detections for Label Studio sync: {}", e.getMessage());
        }
        return results;
    }

    private int[] readImageDimensions(String path) {
        try {
            BufferedImage img = ImageIO.read(new File(path));
            if (img != null) {
                return new int[]{img.getWidth(), img.getHeight()};
            }
        } catch (Exception ignored) {
        }
        return new int[]{0, 0};
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
                return 0.0;
        }
    }

    private List<InferenceDataPoint.PoolType> trustedPoolTypes() {
        return List.of(
                InferenceDataPoint.PoolType.HIGH,
                InferenceDataPoint.PoolType.LOW_A
        );
    }

    private Long findAccessibleMainProjectByTitle(Project project, Long userId) {
        String lsToken = resolveLsToken(userId, project);
        Long expectedOrgId = resolveLsOrgId(userId, project);
        String nextUrl = labelStudioUrl + "/api/projects?page_size=200";
        Long bestMatch = null;
        int bestTaskCount = -1;

        try {
            while (nextUrl != null && !nextUrl.isBlank()) {
                ResponseEntity<String> resp = restTemplate.exchange(
                        nextUrl, HttpMethod.GET, new HttpEntity<>(authHeaders(lsToken)), String.class);
                String body = resp.getBody();
                if (body == null || body.isBlank()) {
                    break;
                }

                com.alibaba.fastjson2.JSONArray results;
                if (body.trim().startsWith("[")) {
                    results = JSON.parseArray(body);
                    nextUrl = null;
                } else {
                    JSONObject page = JSON.parseObject(body);
                    results = page.getJSONArray("results");
                    nextUrl = page.getString("next");
                }
                if (results == null) {
                    break;
                }

                for (int i = 0; i < results.size(); i++) {
                    JSONObject item = results.getJSONObject(i);
                    if (!Objects.equals(project.getName(), item.getString("title"))) {
                        continue;
                    }
                    Long itemOrgId = item.getLong("organization");
                    if (expectedOrgId != null && itemOrgId != null && !Objects.equals(expectedOrgId, itemOrgId)) {
                        continue;
                    }
                    Integer taskNumber = item.getInteger("task_number");
                    int taskCount = taskNumber == null ? 0 : taskNumber;
                    if (taskCount > bestTaskCount) {
                        bestTaskCount = taskCount;
                        bestMatch = item.getLong("id");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to find visible Label Studio project by title: projectId={}, title={}, err={}",
                    project.getId(), project.getName(), e.getMessage());
        }

        if (bestMatch != null) {
            log.info("Found visible Label Studio main project by title: projectId={}, lsProjectId={}, taskCount={}",
                    project.getId(), bestMatch, bestTaskCount);
        }
        return bestMatch;
    }

    private JSONObject fetchLsProject(Long lsProjectId, String lsToken) {
        String url = String.format("%s/api/projects/%d", labelStudioUrl, lsProjectId);
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders(lsToken)), String.class);
        return JSON.parseObject(resp.getBody());
    }

    private HttpHeaders authHeaders(String lsToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + normalizeLsToken(lsToken));
        return headers;
    }

    private HttpHeaders jsonHeaders(String lsToken) {
        HttpHeaders headers = authHeaders(lsToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String normalizeLsToken(String lsToken) {
        if (lsToken != null && !lsToken.isBlank()) {
            return lsToken;
        }
        return adminToken;
    }

    private String resolveLsToken(Long userId, Project project) {
        User user = resolveLsUser(userId, project);
        String token = extractLsToken(user);
        if (token != null) {
            return token;
        }
        if (project != null && project.getCreatedBy() != null) {
            token = extractLsToken(userRepository.findById(project.getCreatedBy().getId()).orElse(project.getCreatedBy()));
            if (token != null) {
                return token;
            }
        }
        return adminToken;
    }

    private Long resolveLsOrgId(Long userId, Project project) {
        User user = resolveLsUser(userId, project);
        if (user != null && user.getLsOrgId() != null) {
            return user.getLsOrgId();
        }
        if (project != null && project.getOrganization() != null && project.getOrganization().getLsOrgId() != null) {
            return project.getOrganization().getLsOrgId();
        }
        if (user != null && user.getOrganization() != null && user.getOrganization().getLsOrgId() != null) {
            return user.getOrganization().getLsOrgId();
        }
        return null;
    }

    private User resolveLsUser(Long userId, Project project) {
        if (userId != null) {
            Optional<User> byRequestUser = userRepository.findById(userId);
            if (byRequestUser.isPresent()) {
                return byRequestUser.get();
            }
        }
        if (project != null && project.getCreatedBy() != null && project.getCreatedBy().getId() != null) {
            return userRepository.findById(project.getCreatedBy().getId()).orElse(project.getCreatedBy());
        }
        return null;
    }

    private String extractLsToken(User user) {
        if (user == null || user.getLsToken() == null || user.getLsToken().isBlank()) {
            return null;
        }
        return user.getLsToken();
    }

    private double clampPct(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 100.0) {
            return 100.0;
        }
        return value;
    }

    private String generateLabelConfig(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "<View></View>";
        }
        String[] colors = {"#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF", "#00FFFF",
                "#FFA500", "#800080", "#008000", "#FFC0CB", "#A52A2A", "#808080"};
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

    private void renameSubProjectIfNeeded(LsSubProject sub, String lsToken) {
        try {
            String newName = sub.getProjectName().replace("__待审", "__已审");
            if (newName.equals(sub.getProjectName())) {
                return;
            }
            String url = String.format("%s/api/projects/%d", labelStudioUrl, sub.getLsProjectId());
            HttpHeaders headers = jsonHeaders(lsToken);
            restTemplate.exchange(url, HttpMethod.PATCH,
                    new HttpEntity<>(JSON.toJSONString(Map.of("title", newName)), headers), String.class);
            sub.setProjectName(newName);
            log.info("Renamed incremental Label Studio project: lsProjectId={}, newName={}",
                    sub.getLsProjectId(), newName);
        } catch (Exception e) {
            log.warn("Failed to rename incremental Label Studio project: {}", e.getMessage());
        }
    }
}
