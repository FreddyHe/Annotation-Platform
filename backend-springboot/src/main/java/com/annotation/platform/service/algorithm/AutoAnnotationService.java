package com.annotation.platform.service.algorithm;

import com.annotation.platform.dto.request.algorithm.DinoDetectRequest;
import com.annotation.platform.dto.request.algorithm.VlmCleanRequest;
import com.annotation.platform.dto.response.algorithm.DinoDetectResponse;
import com.annotation.platform.dto.response.algorithm.VlmCleanResponse;
import com.annotation.platform.entity.AnnotationTask;
import com.annotation.platform.entity.DetectionResult;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.ProjectImage;
import com.annotation.platform.repository.AnnotationTaskRepository;
import com.annotation.platform.repository.DetectionResultRepository;
import com.annotation.platform.repository.ProjectImageRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.service.labelstudio.LabelStudioProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoAnnotationService {

    private static final int POLLING_INTERVAL_MS = 5000; // 轮询间隔 5 秒
    private static final int MAX_POLLING_ATTEMPTS = 60; // 最大轮询次数 (5 分钟)
    private static final int MAX_POLLING_ATTEMPTS_VLM = 120; // VLM 最大轮询次数 (10 分钟)

    private final ProjectRepository projectRepository;
    private final ProjectImageRepository projectImageRepository;
    private final AnnotationTaskRepository annotationTaskRepository;
    private final DetectionResultRepository detectionResultRepository;
    private final AlgorithmService algorithmService;
    private final LabelStudioProxyService labelStudioProxyService;
    private final ObjectMapper objectMapper;

    /**
     * 启动自动标注流程（异步执行）
     */
    @Async("taskExecutor")
    @Transactional
    public void startAutoAnnotation(Long projectId, Long userId, String processRange) {
        log.info("Starting auto annotation for project: {}, userId: {}, processRange: {}", projectId, userId, processRange);
        
        try {
            // 获取项目信息
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found: " + projectId));
            
            // ===== 重新执行时清理旧数据 =====
            if ("all".equals(processRange)) {
                log.info("processRange=all, 清理项目旧数据: projectId={}", projectId);
                
                // 1. 先删除 DetectionResult（因为它外键引用了 AnnotationTask）
                long deletedResults = detectionResultRepository.countByProjectId(projectId);
                if (deletedResults > 0) {
                    detectionResultRepository.deleteByProjectId(projectId);
                    log.info("已删除旧检测结果: count={}", deletedResults);
                }
                
                // 2. 再删除 AnnotationTask
                long deletedTasks = annotationTaskRepository.countByProjectId(projectId);
                if (deletedTasks > 0) {
                    annotationTaskRepository.deleteByProjectId(projectId);
                    log.info("已删除旧标注任务: count={}", deletedTasks);
                }
                
                // 3. 清理 Label Studio 端的旧 tasks（避免 LS 端也重复）
                Long lsProjectId = project.getLsProjectId();
                if (lsProjectId != null) {
                    try {
                        // TODO: 实现 labelStudioProxyService.deleteAllTasks(lsProjectId, userId);
                        log.info("Label Studio 旧 tasks 清理功能待实现: lsProjectId={}", lsProjectId);
                    } catch (Exception e) {
                        log.warn("清理 Label Studio 旧 tasks 失败（可忽略）: {}", e.getMessage());
                    }
                }
            }
            
            // 获取项目图片列表
            List<ProjectImage> images = projectImageRepository.findByProjectId(projectId, org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)).getContent();
            if (images.isEmpty()) {
                log.warn("No images found for project: {}", projectId);
                throw new RuntimeException("项目没有图片: " + projectId);
            }
            
            // 获取图片路径列表
            List<String> imagePaths = images.stream()
                    .map(ProjectImage::getFilePath)
                    .collect(Collectors.toList());
            
            // 获取标签定义
            List<String> labels = project.getLabels();
            Map<String, String> labelDefinitions = project.getLabelDefinitions();
            
            if (labels == null || labels.isEmpty()) {
                log.error("No labels defined for project: {}", projectId);
                throw new RuntimeException("项目未定义标签: " + projectId);
            }
            
            // 如果 labelDefinitions 为空，根据 labels 构建默认定义
            if (labelDefinitions == null || labelDefinitions.isEmpty()) {
                labelDefinitions = new java.util.HashMap<>();
                for (String label : labels) {
                    labelDefinitions.put(label, "标准定义的 " + label);
                }
                log.info("Auto-generated label definitions for project {}: {}", projectId, labelDefinitions);
            }
            
            log.info("Project: {}, Images: {}, Labels: {}", projectId, imagePaths.size(), labels);
            
            // 更新项目状态为 DETECTING
            project.setStatus(Project.ProjectStatus.DETECTING);
            projectRepository.save(project);
            
            // 步骤 1: 调用 DINO 检测
            log.info("Step 1: Starting DINO detection...");
            DinoDetectRequest dinoRequest = DinoDetectRequest.builder()
                    .projectId(projectId)
                    .imagePaths(imagePaths)
                    .labels(labels)
                    .build();
            
            DinoDetectResponse dinoResponse = algorithmService.startDinoDetection(dinoRequest);
            
            if (!Boolean.TRUE.equals(dinoResponse.getSuccess())) {
                log.error("DINO detection failed: {}", dinoResponse.getMessage());
                throw new RuntimeException("DINO 检测失败: " + dinoResponse.getMessage());
            }
            
            String dinoTaskId = dinoResponse.getTaskId();
            log.info("DINO task started: {}", dinoTaskId);
            
            // 轮询等待 DINO 任务完成
            Object dinoResults = waitForTaskCompletion(dinoTaskId, MAX_POLLING_ATTEMPTS);
            log.info("DINO results received: {}", dinoResults);
            
            if (dinoResults == null) {
                log.error("Failed to get DINO results for task: {}", dinoTaskId);
                throw new RuntimeException("无法获取 DINO 结果: " + dinoTaskId);
            }
            
            // 保存 DINO 检测结果到数据库
            log.info("Saving DINO detection results to database...");
            saveDinoResults(project, dinoResults, dinoTaskId);
            
            // 步骤 2: 调用 VLM 清洗
            log.info("Step 2: Starting VLM cleaning...");
            
            // 更新项目状态为 CLEANING
            project.setStatus(Project.ProjectStatus.CLEANING);
            projectRepository.save(project);
            
            // 构建 VLM 清洗请求
            List<Map<String, Object>> detections = extractDetectionsFromResults(dinoResults);
            VlmCleanRequest vlmRequest = VlmCleanRequest.builder()
                    .projectId(projectId)
                    .userId(userId)
                    .detections(detections)
                    .labelDefinitions(labelDefinitions)
                    .imagePaths(imagePaths)  // 添加 image_paths 列表
                    .build();
            
            VlmCleanResponse vlmResponse = algorithmService.startVlmCleaning(vlmRequest);
            
            if (!Boolean.TRUE.equals(vlmResponse.getSuccess())) {
                log.error("VLM cleaning failed: {}", vlmResponse.getMessage());
                throw new RuntimeException("VLM 清洗失败: " + vlmResponse.getMessage());
            }
            
            String vlmTaskId = vlmResponse.getTaskId();
            log.info("VLM task started: {}", vlmTaskId);
            
            // 轮询等待 VLM 任务完成
            Object vlmResults = waitForTaskCompletion(vlmTaskId, MAX_POLLING_ATTEMPTS_VLM);
            log.info("VLM results received: {}", vlmResults);
            
            if (vlmResults == null) {
                log.error("Failed to get VLM results for task: {}", vlmTaskId);
                throw new RuntimeException("无法获取 VLM 结果: " + vlmTaskId);
            }
            
            // 步骤 3: 保存清洗后的结果到数据库
            log.info("Step 3: Saving cleaned results to database...");
            saveCleanedResults(project, vlmResults, dinoTaskId, vlmTaskId);
            
            // 步骤 4: 同步预测结果到 Label Studio
            log.info("Step 4: Syncing predictions to Label Studio...");
            
            // 更新项目状态为 SYNCING
            project.setStatus(Project.ProjectStatus.SYNCING);
            projectRepository.save(project);
            
            syncPredictionsToLabelStudio(project, images, userId);
            
            // 更新项目状态为 COMPLETED
            project.setStatus(Project.ProjectStatus.COMPLETED);
            projectRepository.save(project);
            
            log.info("Auto annotation completed for project: {}", projectId);
            
        } catch (Exception e) {
            log.error("Auto annotation failed for project: {}", projectId, e);
            throw new RuntimeException("Auto annotation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 轮询等待任务完成（工业级实现）
     * @param taskId 任务 ID
     * @param maxAttempts 最大轮询次数
     * @return 任务完成后的结果
     * @throws RuntimeException 如果轮询失败或任务超时
     */
    private Object waitForTaskCompletion(String taskId, int maxAttempts) {
        int attempts = 0;
        
        while (attempts < maxAttempts) {
            attempts++;
            
            try {
                // 查询任务状态
                Object statusResponse = algorithmService.getTaskStatus(taskId);
                log.debug("Polling attempt {}/{} for task {}: status={}", attempts, maxAttempts, taskId, statusResponse);
                
                // 解析状态响应
                Map<String, Object> statusMap = objectMapper.convertValue(statusResponse, Map.class);
                String status = (String) statusMap.get("status");
                
                // 状态比较使用小写，确保大小写不敏感
                if ("completed".equalsIgnoreCase(status)) {
                    // 任务完成，获取结果
                    log.info("Task {} completed successfully after {} attempts", taskId, attempts);
                    return algorithmService.getTaskResults(taskId);
                } else if ("failed".equalsIgnoreCase(status)) {
                    // 任务失败
                    String errorMessage = (String) statusMap.get("message");
                    log.error("Task {} failed after {} attempts: {}", taskId, attempts, errorMessage);
                    throw new RuntimeException("任务失败: " + errorMessage);
                } else if ("running".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(status)) {
                    // 任务还在进行中，等待后继续轮询
                    Thread.sleep(POLLING_INTERVAL_MS);
                } else {
                    // 未知状态
                    log.warn("Task {} has unknown status: {}", taskId, status);
                    Thread.sleep(POLLING_INTERVAL_MS);
                }
                
            } catch (HttpClientErrorException e) {
                log.error("Task {} status check failed (HTTP {}): {}", taskId, e.getStatusCode(), e.getResponseBodyAsString());
                throw new RuntimeException("API请求失败: " + e.getResponseBodyAsString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Task {} polling interrupted", taskId);
                throw new RuntimeException("任务轮询被中断");
            } catch (Exception e) {
                log.error("Error polling task {} status: {}", taskId, e.getMessage(), e);
                throw new RuntimeException("轮询任务状态失败: " + e.getMessage());
            }
        }
        
        log.error("Task {} timed out after {} attempts", taskId, maxAttempts);
        throw new RuntimeException("任务轮询超时: " + taskId);
    }

    /**
     * 从 DINO 结果中提取检测框
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDetectionsFromResults(Object dinoResults) {
        if (dinoResults == null) {
            return new ArrayList<>();
        }
        
        try {
            // 将结果转换为 Map
            Map<String, Object> resultsMap = objectMapper.convertValue(dinoResults, Map.class);
            List<Map<String, Object>> resultsList = (List<Map<String, Object>>) resultsMap.get("results");
            
            if (resultsList == null) {
                return new ArrayList<>();
            }
            
            List<Map<String, Object>> detections = new ArrayList<>();
            
            for (Map<String, Object> result : resultsList) {
                List<Map<String, Object>> detectionsList = (List<Map<String, Object>>) result.get("detections");
                
                if (detectionsList != null) {
                    for (Map<String, Object> detection : detectionsList) {
                        Map<String, Object> detMap = new HashMap<>();
                        detMap.put("image_path", result.get("image_path"));
                        detMap.put("image_name", result.get("image_name"));
                        detMap.put("label", detection.get("label"));
                        
                        // 处理 bbox
                        Object bboxObj = detection.get("bbox");
                        if (bboxObj instanceof List) {
                            List<Double> bboxList = new ArrayList<>();
                            for (Object item : (List<?>) bboxObj) {
                                if (item instanceof Number) {
                                    bboxList.add(((Number) item).doubleValue());
                                }
                            }
                            detMap.put("bbox", bboxList);
                        }
                        
                        Object scoreObj = detection.get("score");
                        if (scoreObj instanceof Number) {
                            detMap.put("score", ((Number) scoreObj).doubleValue());
                        }
                        
                        detections.add(detMap);
                    }
                }
            }
            
            return detections;
            
        } catch (Exception e) {
            log.error("Error extracting detections: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 保存 DINO 检测结果到数据库
     */
    @SuppressWarnings("unchecked")
    private void saveDinoResults(Project project, Object dinoResults, String dinoTaskId) {
        if (dinoResults == null) {
            return;
        }
        
        try {
            // 将结果转换为 Map
            Map<String, Object> resultsMap = objectMapper.convertValue(dinoResults, Map.class);
            List<Map<String, Object>> resultsList = (List<Map<String, Object>>) resultsMap.get("results");
            
            if (resultsList == null) {
                return;
            }
            
            // 创建 DINO 检测任务记录
            AnnotationTask dinoTask = new AnnotationTask();
            dinoTask.setProject(project);
            dinoTask.setType(AnnotationTask.TaskType.DINO_DETECTION);
            dinoTask.setStatus(AnnotationTask.TaskStatus.COMPLETED);
            dinoTask.setStartedAt(LocalDateTime.now());
            dinoTask.setCompletedAt(LocalDateTime.now());
            dinoTask.setParameters(Collections.singletonMap("task_id", dinoTaskId));
            dinoTask = annotationTaskRepository.save(dinoTask);
            
            // 保存每个检测结果
            for (Map<String, Object> result : resultsList) {
                String imagePath = (String) result.get("image_path");
                String fileName = new File(imagePath).getName();
                
                List<Map<String, Object>> detectionsList = (List<Map<String, Object>>) result.get("detections");
                if (detectionsList == null || detectionsList.isEmpty()) {
                    continue;
                }
                
                // 查找对应的图片
                ProjectImage image = projectImageRepository.findByProjectIdAndFilePath(
                        project.getId(), imagePath
                ).orElse(null);
                
                if (image == null) {
                    // 如果完整路径匹配失败，尝试用文件名匹配
                    List<ProjectImage> allImages = projectImageRepository.findByProjectId(
                        project.getId(), 
                        org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)
                    ).getContent();
                    for (ProjectImage img : allImages) {
                        if (img.getFileName().equals(fileName)) {
                            image = img;
                            break;
                        }
                    }
                }
                
                if (image != null) {
                    // 保存每个检测框
                    for (Map<String, Object> detection : detectionsList) {
                        String label = (String) detection.get("label");
                        
                        // 处理 bbox
                        List<Double> bbox = new ArrayList<>();
                        Object bboxObj = detection.get("bbox");
                        if (bboxObj instanceof List) {
                            for (Object item : (List<?>) bboxObj) {
                                if (item instanceof Number) {
                                    bbox.add(((Number) item).doubleValue());
                                }
                            }
                        }
                        
                        Double score = null;
                        Object scoreObj = detection.get("score");
                        if (scoreObj instanceof Number) {
                            score = ((Number) scoreObj).doubleValue();
                        }
                        
                        Map<String, Object> detectionData = new HashMap<>();
                        detectionData.put("label", label);
                        detectionData.put("bbox", bbox);
                        detectionData.put("score", score);
                        detectionData.put("image_path", imagePath);
                        detectionData.put("image_name", fileName);
                        
                        DetectionResult detectionResult = DetectionResult.builder()
                                .image(image)
                                .task(dinoTask)
                                .type(DetectionResult.ResultType.DINO_DETECTION)
                                .resultData(detectionData)
                                .build();
                        
                        detectionResultRepository.save(detectionResult);
                        
                        log.info("Saved DINO detection: image={}, label={}, score={}", 
                                fileName, label, score);
                    }
                } else {
                    log.warn("找不到图片: {}", imagePath);
                }
            }
            
            log.info("DINO 检测结果保存完成");
            
        } catch (Exception e) {
            log.error("保存 DINO 检测结果失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 保存清洗后的结果到数据库
     */
    @SuppressWarnings("unchecked")
    private void saveCleanedResults(Project project, Object vlmResults, String dinoTaskId, String vlmTaskId) {
        if (vlmResults == null) {
            return;
        }
        
        try {
            // 将结果转换为 Map
            Map<String, Object> resultsMap = objectMapper.convertValue(vlmResults, Map.class);
            List<Map<String, Object>> resultsList = (List<Map<String, Object>>) resultsMap.get("results");
            
            if (resultsList == null) {
                return;
            }
            
            // 创建 VLM 清洗任务记录
            AnnotationTask vlmTask = new AnnotationTask();
            vlmTask.setProject(project);
            vlmTask.setType(AnnotationTask.TaskType.VLM_CLEANING);
            vlmTask.setStatus(AnnotationTask.TaskStatus.COMPLETED);
            vlmTask.setStartedAt(LocalDateTime.now());
            vlmTask.setCompletedAt(LocalDateTime.now());
            vlmTask.setParameters(Collections.singletonMap("task_id", vlmTaskId));
            vlmTask = annotationTaskRepository.save(vlmTask);
            
            // 保存每个清洗结果
            for (Map<String, Object> result : resultsList) {
                String decision = (String) result.get("vlm_decision");
                String reasoning = (String) result.get("vlm_reasoning");
                String label = (String) result.get("original_label");
                
                // 从完整路径中提取文件名
                String imagePath = (String) result.get("image_path");
                String fileName = new File(imagePath).getName();
                
                log.info("查找图片: 完整路径={}, 文件名={}", imagePath, fileName);
                
                // 处理 bbox
                List<Double> bbox = new ArrayList<>();
                Object bboxObj = result.get("bbox");
                if (bboxObj instanceof List) {
                    for (Object item : (List<?>) bboxObj) {
                        if (item instanceof Number) {
                            bbox.add(((Number) item).doubleValue());
                        }
                    }
                }
                
                Double score = null;
                Object scoreObj = result.get("score");
                if (scoreObj instanceof Number) {
                    score = ((Number) scoreObj).doubleValue();
                }
                
                // 查找对应的图片（先用文件名匹配）
                ProjectImage image = projectImageRepository.findByProjectIdAndFilePath(
                        project.getId(), imagePath
                ).orElse(null);
                
                if (image == null) {
                    // 如果完整路径匹配失败，尝试用文件名匹配
                    log.info("完整路径匹配失败，尝试用文件名匹配: {}", fileName);
                    List<ProjectImage> allImages = projectImageRepository.findByProjectId(project.getId(), org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)).getContent();
                    for (ProjectImage img : allImages) {
                        if (img.getFileName().equals(fileName)) {
                            image = img;
                            log.info("找到匹配的图片: {}", img.getFilePath());
                            break;
                        }
                    }
                }
                
                if (image != null) {
                    log.info("处理图片: {}, decision={}, label={}", imagePath, decision, label);
                    
                    if ("keep".equals(decision)) {
                        Map<String, Object> detectionData = new HashMap<>();
                        detectionData.put("vlm_decision", decision);
                        detectionData.put("vlm_reasoning", reasoning);
                        detectionData.put("label", label);
                        detectionData.put("bbox", bbox);
                        detectionData.put("score", score);
                        detectionData.put("image_path", imagePath);
                        detectionData.put("image_name", result.get("image_name"));

                        DetectionResult detectionResult = DetectionResult.builder()
                                .image(image)
                                .task(vlmTask)
                                .type(DetectionResult.ResultType.VLM_CLEANING)
                                .resultData(detectionData)
                                .build();

                        detectionResultRepository.save(detectionResult);

                        log.info("Saved detection: image={}, label={}, decision={}", 
                                imagePath, label, decision);
                    } else {
                        log.info("跳过保存: image={}, decision={}", imagePath, decision);
                    }
                } else {
                    log.warn("未找到图片: {}", imagePath);
                }
            }
            
        } catch (Exception e) {
            log.error("Error saving cleaned results: {}", e.getMessage(), e);
        }
    }

    /**
     * 同步预测结果到 Label Studio
     * 对应旧版 Python: mount_local_storage -> sync_local_storage -> get_project_tasks -> add_predictions_batch
     */
    @SuppressWarnings("unchecked")
    private void syncPredictionsToLabelStudio(Project project, List<ProjectImage> images, Long userId) {
        try {
            Long lsProjectId = project.getLsProjectId();

            if (userId == null) {
                log.error("无法获取项目创建者ID，跳过预测同步: projectId={}", project.getId());
                return;
            }

            if (lsProjectId == null) {
                log.info("项目未同步到 Label Studio，尝试自动同步: projectId={}", project.getId());
                lsProjectId = labelStudioProxyService.syncProjectToLS(project, userId);
                if (lsProjectId == null) {
                    log.warn("项目自动同步失败，跳过预测同步: projectId={}", project.getId());
                    return;
                }
            }

            String localPath = getLocalImagePath(images);
            if (localPath == null) {
                log.warn("无法获取本地图片路径，跳过预测同步: projectId={}", project.getId());
                return;
            }

            log.info("Mounting local storage: lsProjectId={}, localPath={}", lsProjectId, localPath);
            Long storageId = labelStudioProxyService.mountLocalStorage(lsProjectId, localPath, userId);
            if (storageId == null) {
                log.warn("挂载本地存储失败，跳过预测同步: lsProjectId={}", lsProjectId);
                return;
            }

            log.info("Syncing local storage: storageId={}", storageId);
            boolean syncSuccess = labelStudioProxyService.syncLocalStorage(storageId, userId);
            if (!syncSuccess) {
                log.warn("同步本地存储失败，跳过预测同步: storageId={}", storageId);
                return;
            }

            log.info("Waiting for tasks to be created...");
            int maxRetries = 10;
            int taskCount = 0;
            for (int i = 0; i < maxRetries; i++) {
                Thread.sleep(3000);
                taskCount = getProjectTaskCount(lsProjectId, userId);
                log.info("等待 task 创建: 第{}次检查, task数量={}", i + 1, taskCount);
                if (taskCount > 0) {
                    break;
                }
            }
            
            if (taskCount == 0) {
                log.warn("等待超时，LS项目中仍无task: lsProjectId={}", lsProjectId);
                return;
            }

            List<DetectionResult> keptDetections = detectionResultRepository
                    .findByProjectIdAndType(project.getId(), DetectionResult.ResultType.VLM_CLEANING);

            log.info("查询 VLM 清洗结果: projectId={}, count={}", project.getId(), keptDetections.size());
            
            for (DetectionResult dr : keptDetections) {
                log.info("检测结果: id={}, type={}, resultData={}", dr.getId(), dr.getType(), dr.getResultData());
            }
            
            if (keptDetections.isEmpty()) {
                log.info("没有需要同步的检测结果: projectId={}", project.getId());
                return;
            }

            List<Map<String, Object>> predictions = preparePredictions(keptDetections);

            log.info("Importing predictions to Label Studio: lsProjectId={}, count={}", lsProjectId, predictions.size());
            Map<String, Object> stats = labelStudioProxyService.importPredictions(lsProjectId, predictions, userId);

            log.info("Label Studio 预测同步完成: success={}, failed={}, skipped={}",
                    stats.get("success"), stats.get("failed"), stats.get("skipped"));

        } catch (Exception e) {
            log.error("同步预测到 Label Studio 失败: projectId={}, error={}", project.getId(), e.getMessage(), e);
        }
    }

    /**
     * 获取本地图片目录的绝对路径
     */
    private String getLocalImagePath(List<ProjectImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }

        Long projectId = images.get(0).getProject().getId();
        return String.format("/root/autodl-fs/uploads/%d", projectId);
    }

    /**
     * 准备预测数据
     * 对应旧版 Python: prepare_predictions_from_vlm
     * 坐标转换: x = bx/w*100, y = by/h*100, width = bw/w*100, height = bh/h*100
     */
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
                if (imageName == null) {
                    continue;
                }

                List<Double> bbox = (List<Double>) resultData.get("bbox");
                String label = (String) resultData.get("label");
                Double score = resultData.get("score") != null ? 
                        ((Number) resultData.get("score")).doubleValue() : 1.0;

                if (bbox == null || bbox.size() != 4) {
                    continue;
                }

                Double bx = bbox.get(0);
                Double by = bbox.get(1);
                Double bw = bbox.get(2);
                Double bh = bbox.get(3);

                String fullImagePath;
                if (imagePath.startsWith("/")) {
                    fullImagePath = imagePath;
                } else {
                    fullImagePath = "/root/autodl-fs/uploads/" + imagePath;
                }
                
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
                value.put("x", bx / imageWidth * 100);
                value.put("y", by / imageHeight * 100);
                value.put("width", bw / imageWidth * 100);
                value.put("height", bh / imageHeight * 100);
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
        for (Map.Entry<String, Map<String, Object>> entry : predictionsMap.entrySet()) {
            Map<String, Object> prediction = entry.getValue();
            List<Double> scores = (List<Double>) prediction.get("scores");

            double avgScore = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.95);
            prediction.put("avg_score", avgScore);
            prediction.remove("scores");

            predictions.add(prediction);
        }

        return predictions;
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
