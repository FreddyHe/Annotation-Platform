package com.annotation.platform.service.algorithm;

import com.annotation.platform.dto.request.AutoAnnotationStartRequest;
import com.annotation.platform.dto.request.algorithm.DinoDetectRequest;
import com.annotation.platform.dto.request.algorithm.VlmCleanRequest;
import com.annotation.platform.dto.response.algorithm.DinoDetectResponse;
import com.annotation.platform.dto.response.algorithm.VlmCleanResponse;
import com.annotation.platform.entity.AnnotationTask;
import com.annotation.platform.entity.AutoAnnotationJob;
import com.annotation.platform.entity.DetectionResult;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.ProjectImage;
import com.annotation.platform.repository.AnnotationTaskRepository;
import com.annotation.platform.repository.AutoAnnotationJobRepository;
import com.annotation.platform.repository.DetectionResultRepository;
import com.annotation.platform.repository.ProjectImageRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.service.labelstudio.LabelStudioProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoAnnotationService {

    private static final int POLLING_INTERVAL_MS = 5000;
    private static final int MIN_DINO_POLLING_ATTEMPTS = 60;
    private static final int MIN_VLM_POLLING_ATTEMPTS = 120;

    private final ProjectRepository projectRepository;
    private final ProjectImageRepository projectImageRepository;
    private final AnnotationTaskRepository annotationTaskRepository;
    private final DetectionResultRepository detectionResultRepository;
    private final AutoAnnotationJobRepository autoAnnotationJobRepository;
    private final AlgorithmService algorithmService;
    private final LabelStudioProxyService labelStudioProxyService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    @Lazy
    private AutoAnnotationService self;

    @Transactional
    public AutoAnnotationJob createJob(Long projectId, Long userId, AutoAnnotationStartRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found: " + projectId));

        Double scoreThreshold = clamp(request.getScoreThreshold() == null ? 0.7 : request.getScoreThreshold(), 0.0, 1.0);
        AutoAnnotationJob job = AutoAnnotationJob.builder()
                .project(project)
                .userId(userId)
                .processRange(request.getProcessRange())
                .mode(request.getMode() == null ? AutoAnnotationJob.AnnotationMode.DINO_VLM : request.getMode())
                .status(AutoAnnotationJob.JobStatus.PENDING)
                .currentStage(AutoAnnotationJob.JobStage.INIT)
                .scoreThreshold(scoreThreshold)
                .boxThreshold(request.getBoxThreshold())
                .textThreshold(request.getTextThreshold())
                .progressPercent(0.0)
                .paramsJson(buildParamsJson(request, scoreThreshold))
                .build();
        return autoAnnotationJobRepository.save(job);
    }

    @Async("taskExecutor")
    public void startAutoAnnotationJob(Long jobId) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Auto annotation job not found: " + jobId));
        Long projectId = job.getProject().getId();
        log.info("Starting auto annotation job: jobId={}, projectId={}, mode={}", jobId, projectId, job.getMode());

        try {
            markJobRunning(jobId);

            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found: " + projectId));

            if ("all".equals(job.getProcessRange())) {
                cleanupOldResults(project);
            }

            List<ProjectImage> images = projectImageRepository
                    .findByProjectId(projectId, org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE))
                    .getContent();
            if (images.isEmpty()) {
                throw new RuntimeException("项目没有图片: " + projectId);
            }
            updateJobCounts(jobId, images.size(), 0, 0, 0, 5.0);

            List<String> imagePaths = images.stream().map(ProjectImage::getFilePath).collect(Collectors.toList());
            List<String> labels = project.getLabels();
            Map<String, String> labelDefinitions = project.getLabelDefinitions();

            if (labels == null || labels.isEmpty()) {
                throw new RuntimeException("项目未定义标签: " + projectId);
            }
            if (labelDefinitions == null || labelDefinitions.isEmpty()) {
                labelDefinitions = new HashMap<>();
                for (String label : labels) {
                    labelDefinitions.put(label, "标准定义的 " + label);
                }
            }

            updateProjectStatus(projectId, Project.ProjectStatus.DETECTING);
            updateJobStage(jobId, AutoAnnotationJob.JobStage.DINO, 10.0);

            DinoDetectRequest dinoRequest = DinoDetectRequest.builder()
                    .projectId(projectId)
                    .imagePaths(imagePaths)
                    .labels(labels)
                    .boxThreshold(job.getBoxThreshold())
                    .textThreshold(job.getTextThreshold())
                    .build();
            DinoDetectResponse dinoResponse = algorithmService.startDinoDetection(dinoRequest);
            if (!Boolean.TRUE.equals(dinoResponse.getSuccess())) {
                throw new RuntimeException("DINO 检测失败: " + dinoResponse.getMessage());
            }

            String dinoTaskId = dinoResponse.getTaskId();
            updateJobTaskId(jobId, dinoTaskId, null);
            Object dinoResults = waitForTaskCompletion(jobId, dinoTaskId, dynamicAttemptsForDino(images.size()), 45.0, true);
            if (dinoResults == null) {
                throw new RuntimeException("无法获取 DINO 结果: " + dinoTaskId);
            }

            saveDinoResults(project, dinoResults, dinoTaskId);
            List<Map<String, Object>> detections = extractDetectionsFromResults(dinoResults);
            updateJobCounts(jobId, images.size(), images.size(), 0, detections.size(), 50.0);

            if (job.getMode() == AutoAnnotationJob.AnnotationMode.DINO_THRESHOLD) {
                runThresholdMode(jobId, project, images, detections, dinoTaskId);
            } else {
                runVlmMode(jobId, project, images, labelDefinitions, imagePaths, detections, dinoTaskId);
            }

            updateProjectStatus(projectId, Project.ProjectStatus.COMPLETED);
            markJobCompleted(jobId);
            log.info("Auto annotation job completed: jobId={}, projectId={}", jobId, projectId);
        } catch (Exception e) {
            if (isCancelled(jobId)) {
                markJobCancelled(jobId);
                updateProjectStatus(projectId, Project.ProjectStatus.FAILED);
                log.warn("Auto annotation job cancelled: jobId={}", jobId);
            } else {
                markJobFailed(jobId, e.getMessage());
                updateProjectStatus(projectId, Project.ProjectStatus.FAILED);
                log.error("Auto annotation job failed: jobId={}, projectId={}", jobId, projectId, e);
            }
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getJobStatus(Long jobId) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Auto annotation job not found: " + jobId));
        return toJobStatus(job);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getLatestJobStatus(Long projectId) {
        Optional<AutoAnnotationJob> latest = autoAnnotationJobRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId);
        return latest.map(this::toJobStatus).orElseGet(() -> {
            Map<String, Object> empty = new HashMap<>();
            empty.put("jobId", null);
            empty.put("projectId", projectId);
            empty.put("status", "NONE");
            return empty;
        });
    }

    @Transactional
    public Map<String, Object> cancelJob(Long jobId) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Auto annotation job not found: " + jobId));
        if (job.getStatus() == AutoAnnotationJob.JobStatus.RUNNING || job.getStatus() == AutoAnnotationJob.JobStatus.PENDING) {
            job.setCancelRequested(true);
            job.setStatus(AutoAnnotationJob.JobStatus.CANCELLING);
            autoAnnotationJobRepository.save(job);
        }
        return toJobStatus(job);
    }

    private void runThresholdMode(Long jobId, Project project, List<ProjectImage> images,
                                  List<Map<String, Object>> detections, String dinoTaskId) {
        updateProjectStatus(project.getId(), Project.ProjectStatus.SYNCING);
        updateJobStage(jobId, AutoAnnotationJob.JobStage.THRESHOLD_FILTER, 65.0);

        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Auto annotation job not found: " + jobId));
        int kept = saveThresholdResults(project, detections, dinoTaskId, job.getScoreThreshold());
        int discarded = Math.max(0, detections.size() - kept);
        updateJobCounts(jobId, images.size(), images.size(), kept, discarded, 80.0);

        updateJobStage(jobId, AutoAnnotationJob.JobStage.SYNC, 85.0);
        self.syncPredictionsToLabelStudio(project, images, job.getUserId());
    }

    private void runVlmMode(Long jobId, Project project, List<ProjectImage> images,
                            Map<String, String> labelDefinitions, List<String> imagePaths,
                            List<Map<String, Object>> detections, String dinoTaskId) {
        updateProjectStatus(project.getId(), Project.ProjectStatus.CLEANING);
        updateJobStage(jobId, AutoAnnotationJob.JobStage.VLM, 55.0);

        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Auto annotation job not found: " + jobId));
        VlmCleanRequest vlmRequest = VlmCleanRequest.builder()
                .projectId(project.getId())
                .userId(job.getUserId())
                .detections(detections)
                .labelDefinitions(labelDefinitions)
                .imagePaths(imagePaths)
                .build();

        VlmCleanResponse vlmResponse = algorithmService.startVlmCleaning(vlmRequest);
        if (!Boolean.TRUE.equals(vlmResponse.getSuccess())) {
            throw new RuntimeException("VLM 清洗失败: " + vlmResponse.getMessage());
        }

        String vlmTaskId = vlmResponse.getTaskId();
        updateJobTaskId(jobId, dinoTaskId, vlmTaskId);
        Object vlmResults = waitForTaskCompletion(jobId, vlmTaskId, dynamicAttemptsForVlm(detections.size()), 78.0, false);
        if (vlmResults == null) {
            throw new RuntimeException("无法获取 VLM 结果: " + vlmTaskId);
        }

        int kept = saveCleanedResults(project, vlmResults, dinoTaskId, vlmTaskId);
        int discarded = Math.max(0, detections.size() - kept);
        updateJobCounts(jobId, images.size(), images.size(), kept, discarded, 82.0);

        updateProjectStatus(project.getId(), Project.ProjectStatus.SYNCING);
        updateJobStage(jobId, AutoAnnotationJob.JobStage.SYNC, 85.0);
        self.syncPredictionsToLabelStudio(project, images, job.getUserId());
    }

    private Object waitForTaskCompletion(Long jobId, String taskId, int maxAttempts, double completedProgress, boolean updateImageProgress) {
        int attempts = 0;
        int unchangedAttempts = 0;
        String lastStatus = null;

        while (attempts < maxAttempts) {
            attempts++;
            throwIfCancelRequested(jobId);

            try {
                Object statusResponse = algorithmService.getTaskStatus(taskId);
                Map<String, Object> statusMap = objectMapper.convertValue(statusResponse, Map.class);
                String status = (String) statusMap.get("status");
                Integer processed = firstInteger(statusMap, "processed_images", "processed");
                Integer total = firstInteger(statusMap, "total_images", "total");
                Double algorithmProgress = firstDouble(statusMap, "progress");

                if (status != null && status.equals(lastStatus)) {
                    unchangedAttempts++;
                } else {
                    unchangedAttempts = 0;
                    lastStatus = status;
                }

                updatePollingProgress(jobId, attempts, maxAttempts, completedProgress, processed, total, algorithmProgress, updateImageProgress);

                if ("completed".equalsIgnoreCase(status)) {
                    updateJobProgress(jobId, completedProgress);
                    return algorithmService.getTaskResults(taskId);
                } else if ("failed".equalsIgnoreCase(status)) {
                    String errorMessage = (String) statusMap.get("message");
                    throw new RuntimeException("任务失败: " + errorMessage);
                }

                if (unchangedAttempts >= Math.max(60, maxAttempts / 2)) {
                    throw new RuntimeException("任务状态长时间无变化: " + taskId);
                }

                Thread.sleep(POLLING_INTERVAL_MS);
            } catch (HttpClientErrorException e) {
                throw new RuntimeException("API请求失败: " + e.getResponseBodyAsString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("任务轮询被中断");
            }
        }

        throw new RuntimeException("任务轮询超时: " + taskId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDetectionsFromResults(Object dinoResults) {
        if (dinoResults == null) {
            return new ArrayList<>();
        }

        try {
            Map<String, Object> resultsMap = objectMapper.convertValue(dinoResults, Map.class);
            List<Map<String, Object>> resultsList = (List<Map<String, Object>>) resultsMap.get("results");
            if (resultsList == null) {
                return new ArrayList<>();
            }

            List<Map<String, Object>> detections = new ArrayList<>();
            for (Map<String, Object> result : resultsList) {
                String imagePath = (String) result.get("image_path");
                String fileName = imagePath == null ? null : new File(imagePath).getName();
                List<Map<String, Object>> detectionsList = (List<Map<String, Object>>) result.get("detections");
                if (detectionsList == null) {
                    continue;
                }
                for (Map<String, Object> detection : detectionsList) {
                    Map<String, Object> detMap = new HashMap<>();
                    detMap.put("image_path", imagePath);
                    detMap.put("image_name", firstNonBlank((String) result.get("image_name"), fileName));
                    detMap.put("label", detection.get("label"));
                    detMap.put("bbox", normalizeBbox(detection.get("bbox")));
                    Object scoreObj = detection.get("score");
                    if (scoreObj instanceof Number) {
                        detMap.put("score", ((Number) scoreObj).doubleValue());
                    }
                    detections.add(detMap);
                }
            }
            return detections;
        } catch (Exception e) {
            log.error("Error extracting detections: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private void saveDinoResults(Project project, Object dinoResults, String dinoTaskId) {
        if (dinoResults == null) {
            return;
        }

        try {
            Map<String, Object> resultsMap = objectMapper.convertValue(dinoResults, Map.class);
            List<Map<String, Object>> resultsList = (List<Map<String, Object>>) resultsMap.get("results");
            if (resultsList == null) {
                return;
            }

            AnnotationTask dinoTask = new AnnotationTask();
            dinoTask.setProject(project);
            dinoTask.setType(AnnotationTask.TaskType.DINO_DETECTION);
            dinoTask.setStatus(AnnotationTask.TaskStatus.COMPLETED);
            dinoTask.setStartedAt(LocalDateTime.now());
            dinoTask.setCompletedAt(LocalDateTime.now());
            dinoTask.setParameters(Collections.singletonMap("task_id", dinoTaskId));
            dinoTask = annotationTaskRepository.save(dinoTask);

            for (Map<String, Object> result : resultsList) {
                String imagePath = (String) result.get("image_path");
                String fileName = new File(imagePath).getName();
                List<Map<String, Object>> detectionsList = (List<Map<String, Object>>) result.get("detections");
                if (detectionsList == null || detectionsList.isEmpty()) {
                    continue;
                }

                ProjectImage image = findProjectImage(project.getId(), imagePath, fileName);
                if (image == null) {
                    log.warn("找不到图片: {}", imagePath);
                    continue;
                }

                for (Map<String, Object> detection : detectionsList) {
                    Map<String, Object> detectionData = new HashMap<>();
                    detectionData.put("label", detection.get("label"));
                    detectionData.put("bbox", normalizeBbox(detection.get("bbox")));
                    detectionData.put("score", asDouble(detection.get("score")));
                    detectionData.put("image_path", imagePath);
                    detectionData.put("image_name", fileName);

                    DetectionResult detectionResult = DetectionResult.builder()
                            .image(image)
                            .task(dinoTask)
                            .type(DetectionResult.ResultType.DINO_DETECTION)
                            .resultData(detectionData)
                            .build();
                    detectionResultRepository.save(detectionResult);
                }
            }
        } catch (Exception e) {
            log.error("保存 DINO 检测结果失败: {}", e.getMessage(), e);
            throw new RuntimeException("保存 DINO 检测结果失败: " + e.getMessage(), e);
        }
    }

    private int saveThresholdResults(Project project, List<Map<String, Object>> detections, String dinoTaskId, Double threshold) {
        AnnotationTask thresholdTask = new AnnotationTask();
        thresholdTask.setProject(project);
        thresholdTask.setType(AnnotationTask.TaskType.VLM_CLEANING);
        thresholdTask.setStatus(AnnotationTask.TaskStatus.COMPLETED);
        thresholdTask.setStartedAt(LocalDateTime.now());
        thresholdTask.setCompletedAt(LocalDateTime.now());
        Map<String, Object> params = new HashMap<>();
        params.put("source_task_id", dinoTaskId);
        params.put("source", "dino_threshold");
        params.put("score_threshold", threshold);
        thresholdTask.setParameters(params);
        thresholdTask = annotationTaskRepository.save(thresholdTask);

        int kept = 0;
        for (Map<String, Object> detection : detections) {
            Double score = asDouble(detection.get("score"));
            if (score == null || score < threshold) {
                continue;
            }

            String imagePath = (String) detection.get("image_path");
            String fileName = new File(imagePath).getName();
            ProjectImage image = findProjectImage(project.getId(), imagePath, fileName);
            if (image == null) {
                continue;
            }

            Map<String, Object> detectionData = new HashMap<>();
            detectionData.put("vlm_decision", "keep");
            detectionData.put("vlm_reasoning", "score >= " + threshold);
            detectionData.put("source", "dino_threshold");
            detectionData.put("label", detection.get("label"));
            detectionData.put("bbox", normalizeBbox(detection.get("bbox")));
            detectionData.put("score", score);
            detectionData.put("image_path", imagePath);
            detectionData.put("image_name", firstNonBlank((String) detection.get("image_name"), fileName));

            DetectionResult detectionResult = DetectionResult.builder()
                    .image(image)
                    .task(thresholdTask)
                    .type(DetectionResult.ResultType.VLM_CLEANING)
                    .resultData(detectionData)
                    .build();
            detectionResultRepository.save(detectionResult);
            kept++;
        }
        return kept;
    }

    @SuppressWarnings("unchecked")
    private int saveCleanedResults(Project project, Object vlmResults, String dinoTaskId, String vlmTaskId) {
        if (vlmResults == null) {
            return 0;
        }

        int kept = 0;
        try {
            Map<String, Object> resultsMap = objectMapper.convertValue(vlmResults, Map.class);
            List<Map<String, Object>> resultsList = (List<Map<String, Object>>) resultsMap.get("results");
            if (resultsList == null) {
                return 0;
            }

            AnnotationTask vlmTask = new AnnotationTask();
            vlmTask.setProject(project);
            vlmTask.setType(AnnotationTask.TaskType.VLM_CLEANING);
            vlmTask.setStatus(AnnotationTask.TaskStatus.COMPLETED);
            vlmTask.setStartedAt(LocalDateTime.now());
            vlmTask.setCompletedAt(LocalDateTime.now());
            Map<String, Object> params = new HashMap<>();
            params.put("task_id", vlmTaskId);
            params.put("source_task_id", dinoTaskId);
            vlmTask.setParameters(params);
            vlmTask = annotationTaskRepository.save(vlmTask);

            for (Map<String, Object> result : resultsList) {
                String decision = (String) result.get("vlm_decision");
                String imagePath = (String) result.get("image_path");
                String fileName = new File(imagePath).getName();
                ProjectImage image = findProjectImage(project.getId(), imagePath, fileName);
                if (image == null || !"keep".equals(decision)) {
                    continue;
                }

                Map<String, Object> detectionData = new HashMap<>();
                detectionData.put("vlm_decision", decision);
                detectionData.put("vlm_reasoning", result.get("vlm_reasoning"));
                detectionData.put("label", result.get("original_label"));
                detectionData.put("bbox", normalizeBbox(result.get("bbox")));
                detectionData.put("score", asDouble(result.get("score")));
                detectionData.put("image_path", imagePath);
                detectionData.put("image_name", firstNonBlank((String) result.get("image_name"), fileName));

                DetectionResult detectionResult = DetectionResult.builder()
                        .image(image)
                        .task(vlmTask)
                        .type(DetectionResult.ResultType.VLM_CLEANING)
                        .resultData(detectionData)
                        .build();
                detectionResultRepository.save(detectionResult);
                kept++;
            }
        } catch (Exception e) {
            log.error("Error saving cleaned results: {}", e.getMessage(), e);
            throw new RuntimeException("保存 VLM 清洗结果失败: " + e.getMessage(), e);
        }
        return kept;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public void syncPredictionsToLabelStudio(Project project, List<ProjectImage> images, Long userId) {
        try {
            Long lsProjectId = project.getLsProjectId();
            if (userId == null) {
                log.error("无法获取项目创建者ID，跳过预测同步: projectId={}", project.getId());
                return;
            }

            if (lsProjectId == null) {
                lsProjectId = labelStudioProxyService.syncProjectToLS(project, userId);
                if (lsProjectId == null) {
                    log.warn("项目自动同步失败，跳过预测同步: projectId={}", project.getId());
                    return;
                }
            }

            String localPath = getLocalImagePath(project.getId(), images);
            if (localPath == null) {
                log.warn("无法获取本地图片路径，跳过预测同步: projectId={}", project.getId());
                return;
            }

            Long storageId = labelStudioProxyService.mountLocalStorage(lsProjectId, localPath, userId);
            if (storageId == null) {
                log.warn("挂载本地存储失败，跳过预测同步: lsProjectId={}", lsProjectId);
                return;
            }

            boolean syncSuccess = labelStudioProxyService.syncLocalStorage(storageId, userId);
            if (!syncSuccess) {
                log.warn("同步本地存储失败，跳过预测同步: storageId={}", storageId);
                return;
            }

            int taskCount = 0;
            for (int i = 0; i < 10; i++) {
                Thread.sleep(3000);
                taskCount = getProjectTaskCount(lsProjectId, userId);
                if (taskCount > 0) {
                    break;
                }
            }
            if (taskCount == 0) {
                log.warn("等待超时，LS项目中仍无task: lsProjectId={}", lsProjectId);
                return;
            }

            List<DetectionResult> keptDetections = detectionResultRepository
                    .findByProjectIdAndTypeWithImage(project.getId(), DetectionResult.ResultType.VLM_CLEANING);
            if (keptDetections.isEmpty()) {
                log.info("没有需要同步的检测结果: projectId={}", project.getId());
                return;
            }

            List<Map<String, Object>> predictions = preparePredictions(keptDetections);
            Map<String, Object> stats = labelStudioProxyService.importPredictions(lsProjectId, predictions, userId);
            log.info("Label Studio 预测同步完成: success={}, failed={}, skipped={}",
                    stats.get("success"), stats.get("failed"), stats.get("skipped"));
        } catch (Exception e) {
            log.error("同步预测到 Label Studio 失败: projectId={}, error={}", project.getId(), e.getMessage(), e);
        }
    }

    private String getLocalImagePath(Long projectId, List<ProjectImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return String.format("/root/autodl-fs/uploads/%d", projectId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> preparePredictions(List<DetectionResult> detections) {
        Map<String, Map<String, Object>> predictionsMap = new HashMap<>();

        for (DetectionResult detection : detections) {
            ProjectImage image = detection.getImage();
            String imagePath = image.getFilePath();
            try {
                Map<String, Object> resultData = detection.getResultData();
                if (resultData == null) {
                    continue;
                }

                String imageName = (String) resultData.get("image_name");
                List<Double> bbox = (List<Double>) resultData.get("bbox");
                String label = (String) resultData.get("label");
                Double score = resultData.get("score") != null ? ((Number) resultData.get("score")).doubleValue() : 1.0;
                imageName = firstNonBlank(imageName, image.getFileName(), new File(imagePath).getName());
                if (imageName == null || bbox == null || bbox.size() != 4) {
                    continue;
                }

                String fullImagePath = imagePath.startsWith("/") ? imagePath : "/root/autodl-fs/uploads/" + imagePath;
                File imageFile = new File(fullImagePath);
                if (!imageFile.exists()) {
                    log.warn("图片文件不存在: {}", fullImagePath);
                    continue;
                }

                BufferedImage bufferedImage = ImageIO.read(imageFile);
                int imageWidth = bufferedImage.getWidth();
                int imageHeight = bufferedImage.getHeight();

                if (!predictionsMap.containsKey(imageName)) {
                    predictionsMap.put(imageName, new HashMap<>());
                    predictionsMap.get(imageName).put("image_name", imageName);
                    predictionsMap.get(imageName).put("results", new ArrayList<>());
                    predictionsMap.get(imageName).put("scores", new ArrayList<>());
                }

                Map<String, Object> prediction = predictionsMap.get(imageName);
                List<Map<String, Object>> results = (List<Map<String, Object>>) prediction.get("results");
                List<Double> scores = (List<Double>) prediction.get("scores");

                Map<String, Object> resultItem = new HashMap<>();
                resultItem.put("original_width", imageWidth);
                resultItem.put("original_height", imageHeight);
                resultItem.put("image_rotation", 0);

                Map<String, Object> value = new HashMap<>();
                value.put("x", bbox.get(0) / imageWidth * 100);
                value.put("y", bbox.get(1) / imageHeight * 100);
                value.put("width", bbox.get(2) / imageWidth * 100);
                value.put("height", bbox.get(3) / imageHeight * 100);
                value.put("rotation", 0);
                value.put("rectanglelabels", Collections.singletonList(label));

                resultItem.put("value", value);
                resultItem.put("id", imageName + "_" + results.size());
                resultItem.put("from_name", "label");
                resultItem.put("to_name", "image");
                resultItem.put("type", "rectanglelabels");
                resultItem.put("score", score);

                results.add(resultItem);
                scores.add(score);
            } catch (Exception e) {
                log.error("准备预测数据失败: imagePath={}, error={}", imagePath, e.getMessage());
            }
        }

        List<Map<String, Object>> predictions = new ArrayList<>();
        for (Map<String, Object> prediction : predictionsMap.values()) {
            List<Double> scores = (List<Double>) prediction.get("scores");
            double avgScore = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.95);
            prediction.put("avg_score", avgScore);
            prediction.remove("scores");
            predictions.add(prediction);
        }
        return predictions;
    }

    private void cleanupOldResults(Project project) {
        transactionTemplate.executeWithoutResult(status -> {
            Long projectId = project.getId();
            long deletedResults = detectionResultRepository.countByProjectId(projectId);
            if (deletedResults > 0) {
                detectionResultRepository.deleteByProjectId(projectId);
            }
            long deletedTasks = annotationTaskRepository.countByProjectId(projectId);
            if (deletedTasks > 0) {
                annotationTaskRepository.deleteByProjectId(projectId);
            }
        });
    }

    private ProjectImage findProjectImage(Long projectId, String imagePath, String fileName) {
        ProjectImage image = projectImageRepository.findByProjectIdAndFilePath(projectId, imagePath).orElse(null);
        if (image != null) {
            return image;
        }
        List<ProjectImage> allImages = projectImageRepository
                .findByProjectId(projectId, org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE))
                .getContent();
        for (ProjectImage img : allImages) {
            if (img.getFileName().equals(fileName)) {
                return img;
            }
        }
        return null;
    }

    private List<Double> normalizeBbox(Object bboxObj) {
        List<Double> bbox = new ArrayList<>();
        if (bboxObj instanceof List) {
            for (Object item : (List<?>) bboxObj) {
                if (item instanceof Number) {
                    bbox.add(((Number) item).doubleValue());
                }
            }
        }
        return bbox;
    }

    private Double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Integer firstInteger(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }
        return null;
    }

    private Double firstDouble(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        }
        return null;
    }

    private double clamp(Double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dynamicAttemptsForDino(int imageCount) {
        return Math.max(MIN_DINO_POLLING_ATTEMPTS, imageCount * 6);
    }

    private int dynamicAttemptsForVlm(int detectionCount) {
        return Math.max(MIN_VLM_POLLING_ATTEMPTS, Math.max(1, detectionCount) * 3);
    }

    private Map<String, Object> buildParamsJson(AutoAnnotationStartRequest request, Double scoreThreshold) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processRange", request.getProcessRange());
        params.put("mode", request.getMode());
        params.put("scoreThreshold", scoreThreshold);
        params.put("boxThreshold", request.getBoxThreshold());
        params.put("textThreshold", request.getTextThreshold());
        return params;
    }

    private Map<String, Object> toJobStatus(AutoAnnotationJob job) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("jobId", job.getId());
        status.put("taskId", "job-" + job.getId());
        status.put("projectId", job.getProject().getId());
        status.put("mode", job.getMode());
        status.put("status", job.getStatus());
        status.put("currentStage", job.getCurrentStage());
        status.put("progressPercent", job.getProgressPercent());
        status.put("stageProgressPercent", job.getProgressPercent());
        status.put("totalImages", job.getTotalImages());
        status.put("processedImages", job.getProcessedImages());
        Integer processed = job.getProcessedImages() == null ? 0 : job.getProcessedImages();
        Integer total = job.getTotalImages() == null ? 0 : job.getTotalImages();
        double imageProgress = total > 0 ? (processed * 100.0 / total) : 0.0;
        status.put("imageProgressPercent", imageProgress);
        status.put("keptDetections", job.getKeptDetections());
        status.put("discardedDetections", job.getDiscardedDetections());
        status.put("dinoTaskId", job.getDinoTaskId());
        status.put("vlmTaskId", job.getVlmTaskId());
        status.put("cancelRequested", job.getCancelRequested());
        status.put("startedAt", job.getStartedAt());
        status.put("updatedAt", job.getUpdatedAt());
        status.put("errorMessage", job.getErrorMessage());
        return status;
    }

    private void markJobRunning(Long jobId) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId).orElseThrow();
        job.setStatus(AutoAnnotationJob.JobStatus.RUNNING);
        job.setCurrentStage(AutoAnnotationJob.JobStage.INIT);
        job.setStartedAt(LocalDateTime.now());
        job.setProgressPercent(1.0);
        autoAnnotationJobRepository.save(job);
    }

    private void updateProjectStatus(Long projectId, Project.ProjectStatus status) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.setStatus(status);
        projectRepository.save(project);
    }

    private void updateJobStage(Long jobId, AutoAnnotationJob.JobStage stage, double progress) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId).orElseThrow();
        job.setCurrentStage(stage);
        job.setProgressPercent(Math.max(job.getProgressPercent() == null ? 0.0 : job.getProgressPercent(), progress));
        autoAnnotationJobRepository.save(job);
    }

    private void updateJobTaskId(Long jobId, String dinoTaskId, String vlmTaskId) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId).orElseThrow();
        if (dinoTaskId != null) {
            job.setDinoTaskId(dinoTaskId);
        }
        if (vlmTaskId != null) {
            job.setVlmTaskId(vlmTaskId);
        }
        autoAnnotationJobRepository.save(job);
    }

    private void updateJobCounts(Long jobId, int totalImages, int processedImages, int kept, int discarded, double progress) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId).orElseThrow();
        job.setTotalImages(totalImages);
        job.setProcessedImages(processedImages);
        job.setKeptDetections(kept);
        job.setDiscardedDetections(discarded);
        job.setProgressPercent(Math.max(job.getProgressPercent() == null ? 0.0 : job.getProgressPercent(), progress));
        autoAnnotationJobRepository.save(job);
    }

    private void updatePollingProgress(Long jobId, int attempts, int maxAttempts, double completedProgress,
                                       Integer processed, Integer total, Double algorithmProgress,
                                       boolean updateImageProgress) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId).orElseThrow();
        double floor = job.getProgressPercent() == null ? 0.0 : job.getProgressPercent();
        double stageStart = stageStartProgress(job.getCurrentStage());
        double ratio;
        if (algorithmProgress != null) {
            ratio = algorithmProgress / 100.0;
        } else if (processed != null && total != null && total > 0) {
            ratio = (double) processed / total;
        } else {
            ratio = (double) attempts / Math.max(1, maxAttempts);
        }
        double progress = stageStart + ((completedProgress - stageStart) * Math.min(0.99, Math.max(0.0, ratio)));
        job.setProgressPercent(Math.min(completedProgress - 0.5, Math.max(floor, progress)));
        if (updateImageProgress) {
            if (total != null && total > 0) {
                job.setTotalImages(total);
            }
            if (processed != null) {
                job.setProcessedImages(processed);
            }
        }
        autoAnnotationJobRepository.save(job);
    }

    private double stageStartProgress(AutoAnnotationJob.JobStage stage) {
        if (stage == AutoAnnotationJob.JobStage.DINO) {
            return 10.0;
        }
        if (stage == AutoAnnotationJob.JobStage.VLM) {
            return 55.0;
        }
        if (stage == AutoAnnotationJob.JobStage.THRESHOLD_FILTER) {
            return 65.0;
        }
        if (stage == AutoAnnotationJob.JobStage.SYNC) {
            return 85.0;
        }
        return 1.0;
    }

    private void updateJobProgress(Long jobId, double progress) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId).orElseThrow();
        job.setProgressPercent(progress);
        autoAnnotationJobRepository.save(job);
    }

    private void markJobCompleted(Long jobId) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId).orElseThrow();
        job.setStatus(AutoAnnotationJob.JobStatus.COMPLETED);
        job.setProgressPercent(100.0);
        job.setCompletedAt(LocalDateTime.now());
        autoAnnotationJobRepository.save(job);
    }

    private void markJobFailed(Long jobId, String errorMessage) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId).orElseThrow();
        job.setStatus(AutoAnnotationJob.JobStatus.FAILED);
        job.setErrorMessage(errorMessage);
        job.setCompletedAt(LocalDateTime.now());
        autoAnnotationJobRepository.save(job);
    }

    private void markJobCancelled(Long jobId) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId).orElseThrow();
        job.setStatus(AutoAnnotationJob.JobStatus.CANCELLED);
        job.setCancelRequested(true);
        job.setCompletedAt(LocalDateTime.now());
        autoAnnotationJobRepository.save(job);
    }

    private boolean isCancelled(Long jobId) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findById(jobId).orElseThrow();
        return Boolean.TRUE.equals(job.getCancelRequested()) || job.getStatus() == AutoAnnotationJob.JobStatus.CANCELLING;
    }

    private void throwIfCancelRequested(Long jobId) {
        if (isCancelled(jobId)) {
            throw new RuntimeException("任务已取消");
        }
    }

    private int getProjectTaskCount(Long lsProjectId, Long userId) {
        try {
            return labelStudioProxyService.getProjectTaskCount(lsProjectId, userId);
        } catch (Exception e) {
            log.error("获取项目 task 数量失败: lsProjectId={}, error={}", lsProjectId, e.getMessage());
            return 0;
        }
    }
}
