package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.ProjectImageResponse;
import com.annotation.platform.dto.request.project.CreateProjectRequest;
import com.annotation.platform.dto.request.project.UpdateProjectRequest;
import com.annotation.platform.dto.response.common.PageableResponse;
import com.annotation.platform.dto.response.project.ProjectDetailResponse;
import com.annotation.platform.entity.Organization;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.ProjectImage;
import com.annotation.platform.entity.User;
import com.annotation.platform.repository.OrganizationRepository;
import com.annotation.platform.repository.ProjectImageRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.repository.UserRepository;
import com.annotation.platform.service.labelstudio.LabelStudioProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;
import java.time.LocalDateTime;

import org.hibernate.Hibernate;

@Slf4j
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final ProjectImageRepository projectImageRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final LabelStudioProxyService labelStudioProxyService;
    private final com.annotation.platform.repository.DetectionResultRepository detectionResultRepository;
    private final com.annotation.platform.service.TrainingService trainingService;
    private final com.annotation.platform.service.ModelTestService modelTestService;
    private final com.annotation.platform.service.RoundService roundService;
    private final com.annotation.platform.service.ProjectConfigService projectConfigService;
    private final com.annotation.platform.repository.ModelTrainingRecordRepository modelTrainingRecordRepository;
    private final com.annotation.platform.repository.InferenceDataPointRepository inferenceDataPointRepository;
    private final com.annotation.platform.repository.EdgeDeploymentRepository edgeDeploymentRepository;
    private final com.annotation.platform.repository.IterationRoundRepository iterationRoundRepository;
    private final com.annotation.platform.repository.ProjectConfigRepository projectConfigRepository;
    private final com.annotation.platform.repository.AutoAnnotationJobRepository autoAnnotationJobRepository;
    private final com.annotation.platform.service.IncrementalProjectService incrementalProjectService;

    @Value("${app.file.upload.base-path}")
    private String uploadBasePath;

    @PostMapping
    public Result<ProjectDetailResponse> createProject(
            @Valid @RequestBody CreateProjectRequest request,
            HttpServletRequest httpRequest) {

        Long userId = (Long) httpRequest.getAttribute("userId");
        Long organizationId = (Long) httpRequest.getAttribute("organizationId");

        log.info("创建项目: name={}, userId={}, orgId={}", request.getName(), userId, organizationId);

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Organization", "id", organizationId));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("User", "id", userId));

        if (projectRepository.existsByNameAndOrganizationId(request.getName(), organizationId)) {
            throw new com.annotation.platform.exception.BusinessException("项目名称已存在");
        }

        Project project = Project.builder()
                .name(request.getName())
                .labels(request.getLabels())
                .organization(organization)
                .createdBy(user)
                .status(Project.ProjectStatus.DRAFT)
                .totalImages(0)
                .processedImages(0)
                .build();

        Project savedProject;
        try {
            savedProject = projectRepository.save(project);
        } catch (DataIntegrityViolationException e) {
            throw new com.annotation.platform.exception.BusinessException("项目名称已存在");
        }

        com.annotation.platform.entity.IterationRound round1 = roundService.ensureCurrentRound(savedProject);
        savedProject.setCurrentRoundId(round1.getId());
        savedProject.setProjectType(Project.ProjectType.ITERATIVE);
        savedProject = projectRepository.save(savedProject);
        projectConfigService.getOrCreate(savedProject.getId());

        labelStudioProxyService.syncProjectToLS(savedProject, userId);

        ProjectDetailResponse response = convertToDetailResponse(savedProject);
        return Result.success(response);
    }

    @GetMapping("/{id}")
    @Transactional
    public Result<ProjectDetailResponse> getProject(@PathVariable Long id, HttpServletRequest request) {
        log.info("获取项目详情: id={}, requestURI={}", id, request.getRequestURI());
        if (id == null) {
            log.error("项目ID为null，请求参数: {}", request.getParameterMap());
        }
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));

        ProjectDetailResponse response = convertToDetailResponse(project);
        return Result.success(response);
    }

    @GetMapping
    @Transactional
    public Result<List<ProjectDetailResponse>> getProjects(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            HttpServletRequest httpRequest) {

        Long organizationId = (Long) httpRequest.getAttribute("organizationId");
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Project> projects;
        if (organizationId != null) {
            if (status != null && !status.isBlank()) {
                projects = projectRepository.findByOrganizationIdAndStatusOptional(organizationId, 
                        Project.ProjectStatus.valueOf(status), pageable);
            } else {
                projects = projectRepository.findByOrganizationId(organizationId, pageable);
            }
        } else {
            projects = projectRepository.findAll(pageable);
        }

        List<ProjectDetailResponse> responses = projects.stream()
                .map(this::convertToDetailResponse)
                .collect(Collectors.toList());

        return Result.success(responses);
    }

    @PutMapping("/{id}")
    public Result<ProjectDetailResponse> updateProject(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProjectRequest request,
            HttpServletRequest httpRequest) {

        log.info("更新项目: id={}", id);

        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));

        // 记录原始标签列表，用于判断是否发生变化
        List<String> oldLabels = project.getLabels();
        boolean labelsChanged = false;

        if (request.getName() != null && !request.getName().isBlank()) {
            project.setName(request.getName());
        }

        if (request.getLabels() != null) {
            // 检查标签是否发生变化
            if (oldLabels == null || !oldLabels.equals(request.getLabels())) {
                labelsChanged = true;
                log.info("[DEBUG] 项目标签发生变化: projectId={}, 旧标签={}, 新标签={}", id, oldLabels, request.getLabels());
            }
            project.setLabels(request.getLabels());
        }

        if (request.getLabelDefinitions() != null) {
            project.setLabelDefinitions(request.getLabelDefinitions());
        }

        Project updatedProject = projectRepository.save(project);

        // 如果标签发生变化且项目已同步到 Label Studio，则更新 Label Studio 的 label_config
        if (labelsChanged && updatedProject.getLsProjectId() != null) {
            Long userId = (Long) httpRequest.getAttribute("userId");
            try {
                log.info("同步更新 Label Studio 项目配置: projectId={}, lsProjectId={}", id, updatedProject.getLsProjectId());
                labelStudioProxyService.updateProjectLabelConfig(
                    updatedProject.getLsProjectId(),
                    updatedProject.getLabels(),
                    userId
                );
            } catch (Exception e) {
                log.warn("同步 Label Studio label_config 失败，但项目更新成功: projectId={}, error={}", id, e.getMessage());
            }
        }

        ProjectDetailResponse response = convertToDetailResponse(updatedProject);
        return Result.success(response);
    }

    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteProject(@PathVariable Long id, HttpServletRequest httpRequest) {
        log.info("删除项目: id={}", id);

        Long userId = (Long) httpRequest.getAttribute("userId");

        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));

        // 1. 先删除 DetectionResult（因为它外键引用了 AnnotationTask 和 ProjectImage）
        long deletedResults = detectionResultRepository.countByProjectId(id);
        if (deletedResults > 0) {
            detectionResultRepository.deleteByProjectId(id);
            log.info("已删除项目检测结果: projectId={}, count={}", id, deletedResults);
        }

        // 2. 删除 ProjectImage（会级联删除，但显式删除更清晰）
        projectImageRepository.deleteByProjectId(id);

        // 2.5 清理迭代飞轮新增数据，避免 iteration_rounds 外键阻塞项目删除
        inferenceDataPointRepository.deleteByProjectId(id);
        edgeDeploymentRepository.deleteByProjectId(id);
        if (projectConfigRepository.existsById(id)) {
            projectConfigRepository.deleteById(id);
        }
        iterationRoundRepository.deleteByProjectId(id);

        // 2.6 清理自动标注任务，auto_annotation_jobs 外键指向 projects
        long autoJobCount = autoAnnotationJobRepository.countByProjectId(id);
        if (autoJobCount > 0) {
            autoAnnotationJobRepository.deleteByProjectId(id);
            log.info("已删除项目自动标注任务: projectId={}, count={}", id, autoJobCount);
        }

        // 3. 清理 Label Studio 端数据（必须先删 storage 再删 project）
        Long lsProjectId = project.getLsProjectId();
        if (lsProjectId != null) {
            try {
                // 先删除 local storage，避免产生孤立记录
                labelStudioProxyService.deleteLocalStorageByProject(lsProjectId, userId);
                // 再删除项目
                labelStudioProxyService.deleteProject(lsProjectId, userId);
            } catch (Exception e) {
                log.warn("Label Studio 删除失败，忽略此错误: id={}, lsId={}, error={}", id, lsProjectId, e.getMessage());
            }
        }

        // 4. 最后删除 Project（会级联删除 AnnotationTask 和 ModelTrainingRecord）
        projectRepository.delete(project);
        return Result.success();
    }

    @GetMapping("/{id}/images")
    @Transactional(rollbackFor = Exception.class)
    public Result<java.util.Map<String, Object>> getProjectImages(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer pageSize) {

        List<ProjectImage> images = projectImageRepository.findProjectImagesNativeByType(id);
        
        int effectiveSize = pageSize != null ? pageSize : size;
        int safePage = page <= 1 ? 0 : page - 1;
        int start = safePage * effectiveSize;
        int end = Math.min(start + effectiveSize, images.size());
        
        if (start >= images.size()) {
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("images", new java.util.ArrayList<>());
            result.put("total", images.size());
            return Result.success(result);
        }
        
        List<ProjectImage> paginatedImages = images.subList(start, end);
        
        // 获取所有图片的ID
        List<Long> imageIds = paginatedImages.stream()
                .map(ProjectImage::getId)
                .collect(Collectors.toList());
        
        // 查询 DINO 检测结果
        List<com.annotation.platform.entity.DetectionResult> dinoResults = 
            detectionResultRepository.findByImageIdInAndType(
                imageIds, 
                com.annotation.platform.entity.DetectionResult.ResultType.DINO_DETECTION
            );
        
        // 查询 VLM 清洗结果
        List<com.annotation.platform.entity.DetectionResult> vlmResults = 
            detectionResultRepository.findByImageIdInAndType(
                imageIds, 
                com.annotation.platform.entity.DetectionResult.ResultType.VLM_CLEANING
            );
        
        // 按图片ID分组
        java.util.Map<Long, List<com.annotation.platform.entity.DetectionResult>> dinoByImage = 
            dinoResults.stream().collect(Collectors.groupingBy(r -> r.getImage().getId()));
        java.util.Map<Long, List<com.annotation.platform.entity.DetectionResult>> vlmByImage = 
            vlmResults.stream().collect(Collectors.groupingBy(r -> r.getImage().getId()));
        
        // 构建响应
        List<java.util.Map<String, Object>> imageResponses = paginatedImages.stream()
                .map(image -> {
                    java.util.Map<String, Object> response = new java.util.HashMap<>();
                    response.put("id", image.getId());
                    response.put("name", image.getFileName());
                    response.put("path", image.getFilePath());
                    response.put("url", "/api/v1/files/" + image.getFilePath());
                    response.put("size", image.getFileSize());
                    response.put("uploadedAt", image.getUploadedAt());
                    response.put("status", image.getStatus().name());
                    
                    // 添加检测结果
                    List<java.util.Map<String, Object>> detections = new java.util.ArrayList<>();
                    if (dinoByImage.containsKey(image.getId())) {
                        for (com.annotation.platform.entity.DetectionResult dr : dinoByImage.get(image.getId())) {
                            java.util.Map<String, Object> resultData = dr.getResultData();
                            java.util.Map<String, Object> detection = new java.util.HashMap<>();
                            detection.put("label", resultData.get("label"));
                            detection.put("confidence", resultData.get("score"));
                            detection.put("bbox", resultData.get("bbox"));
                            detections.add(detection);
                        }
                    }
                    response.put("detections", detections);
                    
                    // 添加清洗结果
                    List<java.util.Map<String, Object>> cleaningResults = new java.util.ArrayList<>();
                    if (vlmByImage.containsKey(image.getId())) {
                        for (com.annotation.platform.entity.DetectionResult dr : vlmByImage.get(image.getId())) {
                            java.util.Map<String, Object> resultData = dr.getResultData();
                            java.util.Map<String, Object> cleaning = new java.util.HashMap<>();
                            
                            String decision = (String) resultData.get("vlm_decision");
                            String label = (String) resultData.get("label");
                            
                            cleaning.put("originalLabels", java.util.Arrays.asList(label));
                            cleaning.put("bbox", resultData.get("bbox"));
                            cleaning.put("confidence", resultData.get("score"));
                            
                            if ("keep".equals(decision)) {
                                cleaning.put("cleanedLabels", java.util.Arrays.asList(label));
                                cleaning.put("removedLabels", new java.util.ArrayList<>());
                            } else {
                                cleaning.put("cleanedLabels", new java.util.ArrayList<>());
                                cleaning.put("removedLabels", java.util.Arrays.asList(label));
                            }
                            
                            cleaning.put("reason", resultData.get("vlm_reasoning"));
                            cleaningResults.add(cleaning);
                        }
                    }
                    response.put("cleaningResults", cleaningResults);
                    
                    return response;
                })
                .collect(Collectors.toList());

        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("images", imageResponses);
        result.put("total", images.size());
        
        return Result.success(result);
    }

    @GetMapping("/{id}/stats")
    @Transactional(rollbackFor = Exception.class)
    public Result<java.util.Map<String, Object>> getProjectStats(@PathVariable Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));

        long totalImages = projectImageRepository.countByProjectId(id);
        long uploadedImages = projectImageRepository.countByProjectIdAndStatus(
                id, ProjectImage.ImageStatus.COMPLETED);
        List<com.annotation.platform.entity.DetectionResult> dinoResults =
                detectionResultRepository.findByProjectIdAndTypeWithImage(
                        id,
                        com.annotation.platform.entity.DetectionResult.ResultType.DINO_DETECTION
                );
        List<com.annotation.platform.entity.DetectionResult> vlmResults =
                detectionResultRepository.findByProjectIdAndTypeWithImage(
                        id,
                        com.annotation.platform.entity.DetectionResult.ResultType.VLM_CLEANING
                );
        List<com.annotation.platform.entity.DetectionResult> finalResults =
                vlmResults.isEmpty() ? dinoResults : vlmResults;

        java.util.Set<Long> processedImageIds = dinoResults.stream()
                .filter(result -> result.getImage() != null)
                .map(result -> result.getImage().getId())
                .collect(java.util.stream.Collectors.toSet());
        int processedImages = processedImageIds.size();
        java.util.Optional<com.annotation.platform.entity.AutoAnnotationJob> latestAutoJob =
                autoAnnotationJobRepository.findFirstByProjectIdOrderByCreatedAtDesc(id);
        if (latestAutoJob.isPresent()) {
            com.annotation.platform.entity.AutoAnnotationJob job = latestAutoJob.get();
            if (job.getProcessedImages() != null) {
                processedImages = Math.max(processedImages, job.getProcessedImages());
            }
            if (job.getStatus() == com.annotation.platform.entity.AutoAnnotationJob.JobStatus.COMPLETED
                    && job.getTotalImages() != null) {
                processedImages = Math.max(processedImages, job.getTotalImages());
            }
        }
        if (project.getStatus() == Project.ProjectStatus.COMPLETED && project.getProcessedImages() != null) {
            processedImages = Math.max(processedImages, project.getProcessedImages());
        }
        processedImages = (int) Math.min(totalImages, processedImages);

        double avgConfidence = finalResults.stream()
                .map(com.annotation.platform.entity.DetectionResult::getResultData)
                .map(data -> data.get("score"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToDouble(Number::doubleValue)
                .average()
                .orElse(0.0);

        java.util.Map<String, Long> labelDistribution = finalResults.stream()
                .map(com.annotation.platform.entity.DetectionResult::getResultData)
                .map(data -> data.get("label"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(java.util.stream.Collectors.groupingBy(label -> label, java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));

        java.util.List<java.util.Map<String, Object>> labelDistributionList = labelDistribution.entrySet().stream()
                .map(entry -> {
                    java.util.Map<String, Object> item = new java.util.HashMap<>();
                    item.put("label", entry.getKey());
                    item.put("count", entry.getValue());
                    return item;
                })
                .collect(java.util.stream.Collectors.toList());

        long[] confidenceBuckets = new long[5];
        for (com.annotation.platform.entity.DetectionResult result : finalResults) {
            Object scoreValue = result.getResultData().get("score");
            if (scoreValue instanceof Number score) {
                int bucket = Math.min(4, Math.max(0, (int) Math.floor(score.doubleValue() * 5)));
                confidenceBuckets[bucket]++;
            }
        }
        String[] ranges = {"0-20%", "20-40%", "40-60%", "60-80%", "80-100%"};
        java.util.List<java.util.Map<String, Object>> confidenceDistribution = new java.util.ArrayList<>();
        for (int i = 0; i < ranges.length; i++) {
            java.util.Map<String, Object> item = new java.util.HashMap<>();
            item.put("range", ranges[i]);
            item.put("count", confidenceBuckets[i]);
            confidenceDistribution.add(item);
        }

        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalImages", totalImages);
        stats.put("uploadedImages", uploadedImages);
        stats.put("processedImages", processedImages);
        stats.put("totalDetections", dinoResults.size());
        stats.put("totalFinalResults", finalResults.size());
        stats.put("cleanedDetections", vlmResults.size());
        stats.put("hasVlmCleaning", !vlmResults.isEmpty());
        stats.put("avgConfidence", avgConfidence * 100);
        stats.put("labelDistribution", labelDistributionList);
        stats.put("confidenceDistribution", confidenceDistribution);
        stats.put("status", project.getStatus());

        return Result.success(stats);
    }

    @GetMapping("/{id}/ls-health")
    public Result<java.util.Map<String, Object>> getLsHealth(@PathVariable Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));
        Long userId = project.getCreatedBy() != null ? project.getCreatedBy().getId() : null;
        boolean alive = incrementalProjectService.isLsProjectAlive(project.getLsProjectId(), userId, project);
        if (project.getLsProjectId() != null) {
            project.setLsProjectStatus(alive
                    ? (project.getLsProjectStatus() == Project.LsProjectStatus.REPAIRED
                    ? Project.LsProjectStatus.REPAIRED : Project.LsProjectStatus.ACTIVE)
                    : Project.LsProjectStatus.DEAD);
            projectRepository.save(project);
        }
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("projectId", id);
        result.put("lsProjectId", project.getLsProjectId());
        result.put("alive", alive);
        result.put("status", project.getLsProjectStatus() != null ? project.getLsProjectStatus().name() : "UNKNOWN");
        return Result.success(result);
    }

    @PostMapping("/{id}/repair-ls-binding")
    public Result<java.util.Map<String, Object>> repairLsBinding(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(incrementalProjectService.repairMainLsBinding(id, userId));
    }

    @GetMapping("/{id}/incrementals")
    public Result<java.util.Map<String, Object>> getIncrementalProjects(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(incrementalProjectService.getIncrementalProjectsStatus(id, userId));
    }

    @GetMapping("/{id}/training/preview")
    public Result<java.util.Map<String, Object>> trainingPreview(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(incrementalProjectService.trainingDatasetPreview(id, userId));
    }

    @PostMapping("/{id}/flywheel/sync")
    public Result<java.util.Map<String, Object>> syncFlywheelData(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(incrementalProjectService.syncPendingDataToLabelStudio(id, userId));
    }

    @GetMapping("/{id}/review-stats")
    public Result<java.util.Map<String, Object>> getReviewStats(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        
        log.info("获取审核统计: projectId={}", id);
        
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));
        
        if (project.getLsProjectId() == null) {
            throw new com.annotation.platform.exception.BusinessException(
                com.annotation.platform.common.ErrorCode.LS_003, 
                "项目尚未同步到 Label Studio"
            );
        }
        
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        try {
            java.util.Map<String, Object> reviewStats = labelStudioProxyService.getProjectReviewStats(
                project.getLsProjectId(), userId
            );
            return Result.success(reviewStats);
        } catch (Exception e) {
            log.warn("获取审核统计失败（可能项目尚无任务）: projectId={}, error={}", id, e.getMessage());
            java.util.Map<String, Object> emptyStats = new java.util.HashMap<>();
            emptyStats.put("totalTasks", 0);
            emptyStats.put("reviewedTasks", 0);
            emptyStats.put("pendingTasks", 0);
            return Result.success(emptyStats);
        }
    }

    @GetMapping("/{id}/review-results")
    public Result<java.util.Map<String, Object>> getReviewResults(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        
        log.info("获取审核结果: projectId={}", id);
        
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));
        
        if (project.getLsProjectId() == null) {
            throw new com.annotation.platform.exception.BusinessException(
                com.annotation.platform.common.ErrorCode.LS_003,
                "项目尚未同步到 Label Studio"
            );
        }
        
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        try {
            java.util.Map<String, Object> reviewResults = labelStudioProxyService.getProjectReviewResults(
                project.getLsProjectId(), userId
            );
            return Result.success(reviewResults);
        } catch (Exception e) {
            log.warn("获取审核结果失败（可能项目尚无任务）: projectId={}, error={}", id, e.getMessage());
            java.util.Map<String, Object> emptyResults = new java.util.HashMap<>();
            emptyResults.put("tasks", new java.util.ArrayList<>());
            return Result.success(emptyResults);
        }
    }

    @PostMapping("/{id}/export")
    public Result<java.util.Map<String, Object>> exportResults(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> request,
            HttpServletRequest httpRequest) {
        
        String format = (String) request.get("format");
        log.info("导出标注结果: projectId={}, format={}", id, format);
        
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));
        
        if (project.getLsProjectId() == null) {
            throw new com.annotation.platform.exception.BusinessException(
                com.annotation.platform.common.ErrorCode.LS_003,
                "项目尚未同步到 Label Studio"
            );
        }
        
        Long userId = (Long) httpRequest.getAttribute("userId");
        
        try {
            // 从 Label Studio 获取标注数据
            List<java.util.Map<String, Object>> annotations = labelStudioProxyService.exportAnnotations(
                project.getLsProjectId(), userId, format
            );
            annotations = mergePlatformPredictionsForExport(project, annotations);
            
            if (annotations.isEmpty()) {
                throw new com.annotation.platform.exception.BusinessException("没有可导出的标注数据");
            }
            
            // 根据格式转换数据
            String exportData = convertToFormat(annotations, format, project);
            
            // 生成下载URL（这里简化处理，实际应该保存文件并返回下载链接）
            java.util.Map<String, Object> response = new java.util.HashMap<>();
            response.put("downloadUrl", "data:application/json;charset=utf-8," + 
                java.net.URLEncoder.encode(exportData, "UTF-8"));
            response.put("itemCount", annotations.size());
            response.put("fileSize", exportData.length());
            response.put("format", format);
            
            log.info("导出成功: projectId={}, format={}, count={}", id, format, annotations.size());
            return Result.success(response);
        } catch (Exception e) {
            log.error("导出失败: projectId={}, error={}", id, e.getMessage(), e);
            throw new com.annotation.platform.exception.BusinessException("导出失败: " + e.getMessage());
        }
    }

    private List<java.util.Map<String, Object>> mergePlatformPredictionsForExport(
            Project project,
            List<java.util.Map<String, Object>> labelStudioAnnotations) {
        List<java.util.Map<String, Object>> merged = new java.util.ArrayList<>();
        if (labelStudioAnnotations != null) {
            merged.addAll(labelStudioAnnotations);
        }

        java.util.Set<String> exportedImages = merged.stream()
                .map(item -> item.get("image_name"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(java.util.stream.Collectors.toSet());

        Long projectId = project.getId();
        List<ProjectImage> images = projectImageRepository.findProjectImagesNativeByType(projectId);
        List<com.annotation.platform.entity.DetectionResult> dinoResults =
                detectionResultRepository.findByProjectIdAndTypeWithImage(
                        projectId,
                        com.annotation.platform.entity.DetectionResult.ResultType.DINO_DETECTION
                );
        List<com.annotation.platform.entity.DetectionResult> vlmResults =
                detectionResultRepository.findByProjectIdAndTypeWithImage(
                        projectId,
                        com.annotation.platform.entity.DetectionResult.ResultType.VLM_CLEANING
                );

        List<com.annotation.platform.entity.DetectionResult> finalResults =
                vlmResults.isEmpty() ? dinoResults : vlmResults.stream()
                        .filter(this::isKeptVlmResult)
                        .collect(java.util.stream.Collectors.toList());

        java.util.Map<Long, List<com.annotation.platform.entity.DetectionResult>> resultsByImage =
                finalResults.stream()
                        .filter(result -> result.getImage() != null)
                        .collect(java.util.stream.Collectors.groupingBy(result -> result.getImage().getId()));

        for (ProjectImage image : images) {
            String imageName = image.getFileName();
            if (imageName == null || exportedImages.contains(imageName)) {
                continue;
            }

            java.util.Map<String, Object> annotation = buildExportAnnotationFromPlatform(image, resultsByImage.get(image.getId()));
            merged.add(annotation);
            exportedImages.add(imageName);
        }

        return merged;
    }

    private boolean isKeptVlmResult(com.annotation.platform.entity.DetectionResult result) {
        java.util.Map<String, Object> data = result.getResultData();
        Object decision = data != null ? data.get("vlm_decision") : null;
        return decision == null || "keep".equals(String.valueOf(decision));
    }

    private java.util.Map<String, Object> buildExportAnnotationFromPlatform(
            ProjectImage image,
            List<com.annotation.platform.entity.DetectionResult> results) {
        java.util.Map<String, Object> annotation = new java.util.HashMap<>();
        annotation.put("image_name", image.getFileName());
        annotation.put("task_id", null);
        annotation.put("source", "platform_prediction");

        int[] size = readImageSize(image.getFilePath());
        annotation.put("image_width", size[0] > 0 ? size[0] : null);
        annotation.put("image_height", size[1] > 0 ? size[1] : null);

        List<java.util.Map<String, Object>> boxes = new java.util.ArrayList<>();
        if (results != null) {
            for (com.annotation.platform.entity.DetectionResult result : results) {
                java.util.Map<String, Object> data = result.getResultData();
                java.util.Map<String, Object> box = convertDetectionResultToExportBox(data, size[0], size[1]);
                if (box != null) {
                    boxes.add(box);
                }
            }
        }
        annotation.put("annotations", boxes);
        return annotation;
    }

    private java.util.Map<String, Object> convertDetectionResultToExportBox(
            java.util.Map<String, Object> data,
            int imageWidth,
            int imageHeight) {
        if (data == null || imageWidth <= 0 || imageHeight <= 0) {
            return null;
        }
        Object labelValue = data.get("label");
        Object bboxValue = data.get("bbox");
        if (!(labelValue instanceof String label) || !(bboxValue instanceof List<?> bbox) || bbox.size() < 4) {
            return null;
        }

        double x = toDouble(bbox.get(0), 0);
        double y = toDouble(bbox.get(1), 0);
        double width = toDouble(bbox.get(2), 0);
        double height = toDouble(bbox.get(3), 0);
        if (width <= 0 || height <= 0) {
            return null;
        }

        java.util.Map<String, Object> box = new java.util.HashMap<>();
        box.put("label", label);
        box.put("x", x / imageWidth * 100.0);
        box.put("y", y / imageHeight * 100.0);
        box.put("width", width / imageWidth * 100.0);
        box.put("height", height / imageHeight * 100.0);
        return box;
    }

    private double toDouble(Object value, double defaultValue) {
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    private int[] readImageSize(String filePath) {
        try {
            java.io.File file = new java.io.File(filePath != null && filePath.startsWith("/")
                    ? filePath
                    : uploadBasePath + "/" + filePath);
            if (!file.exists()) {
                return new int[]{0, 0};
            }
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file);
            if (image == null) {
                return new int[]{0, 0};
            }
            return new int[]{image.getWidth(), image.getHeight()};
        } catch (Exception e) {
            log.warn("读取导出图片尺寸失败: filePath={}, error={}", filePath, e.getMessage());
            return new int[]{0, 0};
        }
    }
    
    private String convertToFormat(List<java.util.Map<String, Object>> annotations, 
                                   String format, Project project) throws Exception {
        switch (format.toLowerCase()) {
            case "json":
                return com.alibaba.fastjson2.JSON.toJSONString(annotations, 
                    com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
            
            case "coco":
                return convertToCOCO(annotations, project);
            
            case "yolo":
                return convertToYOLO(annotations, project);
            
            case "voc":
                return convertToVOC(annotations, project);
            
            case "csv":
                return convertToCSV(annotations);
            
            default:
                throw new IllegalArgumentException("不支持的导出格式: " + format);
        }
    }
    
    private String convertToCOCO(List<java.util.Map<String, Object>> annotations, Project project) {
        java.util.Map<String, Object> coco = new java.util.HashMap<>();
        
        // COCO info
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        info.put("description", project.getName());
        info.put("version", "1.0");
        info.put("year", LocalDateTime.now().getYear());
        coco.put("info", info);
        
        // Categories
        List<java.util.Map<String, Object>> categories = new java.util.ArrayList<>();
        List<String> labels = project.getLabels();
        for (int i = 0; i < labels.size(); i++) {
            java.util.Map<String, Object> category = new java.util.HashMap<>();
            category.put("id", i + 1);
            category.put("name", labels.get(i));
            categories.add(category);
        }
        coco.put("categories", categories);
        
        // Images and annotations
        List<java.util.Map<String, Object>> images = new java.util.ArrayList<>();
        List<java.util.Map<String, Object>> cocoAnnotations = new java.util.ArrayList<>();
        int annotationId = 1;
        
        for (int imgId = 0; imgId < annotations.size(); imgId++) {
            java.util.Map<String, Object> ann = annotations.get(imgId);
            
            // Image
            java.util.Map<String, Object> image = new java.util.HashMap<>();
            image.put("id", imgId + 1);
            image.put("file_name", ann.get("image_name"));
            image.put("width", ann.get("image_width"));
            image.put("height", ann.get("image_height"));
            images.add(image);
            
            // Annotations
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> boxes = 
                (List<java.util.Map<String, Object>>) ann.get("annotations");
            
            if (boxes != null) {
                for (java.util.Map<String, Object> box : boxes) {
                    java.util.Map<String, Object> cocoAnn = new java.util.HashMap<>();
                    cocoAnn.put("id", annotationId++);
                    cocoAnn.put("image_id", imgId + 1);
                    
                    String label = (String) box.get("label");
                    int categoryId = labels.indexOf(label) + 1;
                    cocoAnn.put("category_id", categoryId);
                    
                    // Convert percentage to pixels
                    Integer imgWidth = (Integer) ann.get("image_width");
                    Integer imgHeight = (Integer) ann.get("image_height");
                    
                    if (imgWidth != null && imgHeight != null) {
                        double x = ((Number) box.get("x")).doubleValue() * imgWidth / 100.0;
                        double y = ((Number) box.get("y")).doubleValue() * imgHeight / 100.0;
                        double width = ((Number) box.get("width")).doubleValue() * imgWidth / 100.0;
                        double height = ((Number) box.get("height")).doubleValue() * imgHeight / 100.0;
                        
                        cocoAnn.put("bbox", java.util.Arrays.asList(x, y, width, height));
                        cocoAnn.put("area", width * height);
                    }
                    
                    cocoAnn.put("iscrowd", 0);
                    cocoAnnotations.add(cocoAnn);
                }
            }
        }
        
        coco.put("images", images);
        coco.put("annotations", cocoAnnotations);
        
        return com.alibaba.fastjson2.JSON.toJSONString(coco, 
            com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
    }
    
    private String convertToYOLO(List<java.util.Map<String, Object>> annotations, Project project) {
        StringBuilder result = new StringBuilder();
        List<String> labels = project.getLabels();
        
        for (java.util.Map<String, Object> ann : annotations) {
            String imageName = (String) ann.get("image_name");
            result.append("# ").append(imageName).append("\n");
            
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> boxes = 
                (List<java.util.Map<String, Object>>) ann.get("annotations");
            
            if (boxes != null) {
                for (java.util.Map<String, Object> box : boxes) {
                    String label = (String) box.get("label");
                    int classId = labels.indexOf(label);
                    
                    // YOLO format: class_id center_x center_y width height (normalized)
                    double x = ((Number) box.get("x")).doubleValue() / 100.0;
                    double y = ((Number) box.get("y")).doubleValue() / 100.0;
                    double width = ((Number) box.get("width")).doubleValue() / 100.0;
                    double height = ((Number) box.get("height")).doubleValue() / 100.0;
                    
                    double centerX = x + width / 2.0;
                    double centerY = y + height / 2.0;
                    
                    result.append(String.format("%d %.6f %.6f %.6f %.6f\n", 
                        classId, centerX, centerY, width, height));
                }
            }
            result.append("\n");
        }
        
        return result.toString();
    }
    
    private String convertToVOC(List<java.util.Map<String, Object>> annotations, Project project) {
        StringBuilder result = new StringBuilder();
        result.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        result.append("<annotations>\n");
        
        for (java.util.Map<String, Object> ann : annotations) {
            result.append("  <annotation>\n");
            result.append("    <filename>").append(ann.get("image_name")).append("</filename>\n");
            result.append("    <size>\n");
            result.append("      <width>").append(ann.get("image_width")).append("</width>\n");
            result.append("      <height>").append(ann.get("image_height")).append("</height>\n");
            result.append("    </size>\n");
            
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> boxes = 
                (List<java.util.Map<String, Object>>) ann.get("annotations");
            
            if (boxes != null) {
                Integer imgWidth = (Integer) ann.get("image_width");
                Integer imgHeight = (Integer) ann.get("image_height");
                
                for (java.util.Map<String, Object> box : boxes) {
                    result.append("    <object>\n");
                    result.append("      <name>").append(box.get("label")).append("</name>\n");
                    result.append("      <bndbox>\n");
                    
                    if (imgWidth != null && imgHeight != null) {
                        double x = ((Number) box.get("x")).doubleValue() * imgWidth / 100.0;
                        double y = ((Number) box.get("y")).doubleValue() * imgHeight / 100.0;
                        double width = ((Number) box.get("width")).doubleValue() * imgWidth / 100.0;
                        double height = ((Number) box.get("height")).doubleValue() * imgHeight / 100.0;
                        
                        result.append("        <xmin>").append((int)x).append("</xmin>\n");
                        result.append("        <ymin>").append((int)y).append("</ymin>\n");
                        result.append("        <xmax>").append((int)(x + width)).append("</xmax>\n");
                        result.append("        <ymax>").append((int)(y + height)).append("</ymax>\n");
                    }
                    
                    result.append("      </bndbox>\n");
                    result.append("    </object>\n");
                }
            }
            
            result.append("  </annotation>\n");
        }
        
        result.append("</annotations>\n");
        return result.toString();
    }
    
    private String convertToCSV(List<java.util.Map<String, Object>> annotations) {
        StringBuilder result = new StringBuilder();
        result.append("image_name,label,x,y,width,height\n");
        
        for (java.util.Map<String, Object> ann : annotations) {
            String imageName = (String) ann.get("image_name");
            
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> boxes = 
                (List<java.util.Map<String, Object>>) ann.get("annotations");
            
            if (boxes != null) {
                for (java.util.Map<String, Object> box : boxes) {
                    result.append(String.format("%s,%s,%.2f,%.2f,%.2f,%.2f\n",
                        imageName,
                        box.get("label"),
                        box.get("x"),
                        box.get("y"),
                        box.get("width"),
                        box.get("height")
                    ));
                }
            }
        }
        
        return result.toString();
    }

    @PostMapping("/{id}/training/start")
    public Result<java.util.Map<String, Object>> startTraining(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> config,
            HttpServletRequest httpRequest) {
        
        log.info("启动模型训练: projectId={}", id);
        
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));
        
        if (project.getLsProjectId() == null) {
            throw new com.annotation.platform.exception.BusinessException(
                com.annotation.platform.common.ErrorCode.LS_003,
                "项目尚未同步到 Label Studio"
            );
        }
        
        User user = (User) httpRequest.getAttribute("user");
        Long userId = user.getId();
        
        try {
            Integer epochs = config.get("epochs") != null ? ((Number) config.get("epochs")).intValue() : 100;
            Integer batchSize = config.get("batchSize") != null ? ((Number) config.get("batchSize")).intValue() : 16;
            Integer imageSize = config.get("imageSize") != null ? ((Number) config.get("imageSize")).intValue() : 640;
            String modelType = config.get("modelType") != null ? (String) config.get("modelType") : "yolov8n";
            String device = config.get("device") != null ? (String) config.get("device") : "0";
            boolean autoML = Boolean.TRUE.equals(config.get("autoML")) || Boolean.TRUE.equals(config.get("automl"));
            boolean forceRetrain = Boolean.TRUE.equals(config.get("forceRetrain"));
            java.util.Map<String, Object> autoMLConfig = null;
            if (autoML) {
                autoMLConfig = buildAutoMLTrainingConfig(project);
                epochs = ((Number) autoMLConfig.get("epochs")).intValue();
                batchSize = ((Number) autoMLConfig.get("batchSize")).intValue();
                imageSize = ((Number) autoMLConfig.get("imageSize")).intValue();
                modelType = (String) autoMLConfig.get("modelType");
                device = (String) autoMLConfig.get("device");
                log.info("AutoML training config selected: projectId={}, config={}", id, autoMLConfig);
            }
            
            com.annotation.platform.entity.ModelTrainingRecord record = trainingService.startTraining(
                    userId,
                    id,
                    String.valueOf(project.getLsProjectId()),
                    user.getLsToken(),
                    epochs,
                    batchSize,
                    imageSize,
                    modelType,
                    device,
                    com.annotation.platform.entity.ModelTrainingRecord.TrainingDataSource.INITIAL,
                    forceRetrain
            );
            
            java.util.Map<String, Object> trainingStatus = new java.util.HashMap<>();
            trainingStatus.put("status", "PREPARING");
            trainingStatus.put("message", "训练任务已创建，正在准备数据...");
            trainingStatus.put("modelName", config.get("modelName"));
            trainingStatus.put("totalEpochs", epochs);
            trainingStatus.put("currentEpoch", 0);
            trainingStatus.put("recordId", record.getId());
            trainingStatus.put("taskId", record.getTaskId());
            trainingStatus.put("autoML", autoML);
            if (autoMLConfig != null) {
                trainingStatus.put("autoMLConfig", autoMLConfig);
            }
            
            log.info("训练启动成功: projectId={}, modelName={}, taskId={}", id, config.get("modelName"), record.getTaskId());
            
            return Result.success(trainingStatus);
        } catch (Exception e) {
            log.error("启动训练失败: projectId={}, error={}", id, e.getMessage(), e);
            java.util.Map<String, Object> errorStatus = new java.util.HashMap<>();
            errorStatus.put("status", "FAILED");
            errorStatus.put("message", "启动训练失败: " + e.getMessage());
            errorStatus.put("errorMessage", e.getMessage());
            return Result.success(errorStatus);
        }
    }

    private java.util.Map<String, Object> buildAutoMLTrainingConfig(Project project) {
        int imageCount = project.getTotalImages() != null ? project.getTotalImages() : 0;
        int labelCount = project.getLabels() != null ? project.getLabels().size() : 1;

        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("device", "0");
        result.put("pretrained", true);
        result.put("strategy", "rule-based-yolo-baseline");

        if (imageCount < 300 || labelCount <= 2) {
            result.put("modelType", "yolov8n");
            result.put("epochs", 80);
            result.put("batchSize", 16);
            result.put("imageSize", 640);
            result.put("reason", "小数据集或少类别任务优先使用轻量预训练模型，降低过拟合和训练成本。");
        } else if (imageCount < 2000 && labelCount <= 10) {
            result.put("modelType", "yolov8s");
            result.put("epochs", 120);
            result.put("batchSize", 16);
            result.put("imageSize", 768);
            result.put("reason", "中等规模数据使用 yolov8s 和更高输入分辨率，平衡精度与训练时间。");
        } else {
            result.put("modelType", "yolov8m");
            result.put("epochs", 160);
            result.put("batchSize", 12);
            result.put("imageSize", 832);
            result.put("reason", "较大数据集使用更大模型容量，batch 保守设置以控制显存。");
        }

        return result;
    }

    @GetMapping("/{id}/training/status")
    public Result<java.util.Map<String, Object>> getTrainingStatus(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        
        log.debug("获取训练状态: projectId={}", id);
        
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));
        
        // 查询该项目最新的训练记录
        java.util.List<com.annotation.platform.entity.ModelTrainingRecord> records = 
                modelTrainingRecordRepository.findByProjectIdOrderByCreatedAtDesc(id);
        
        java.util.Map<String, Object> trainingStatus = new java.util.HashMap<>();
        
        if (records.isEmpty()) {
            trainingStatus.put("status", "IDLE");
            trainingStatus.put("message", "暂无训练任务");
            return Result.success(trainingStatus);
        }
        
        com.annotation.platform.entity.ModelTrainingRecord latest = trainingService.refreshTrainingRecord(records.get(0));
        java.util.Map<String, Object> algorithmStatus = trainingService.getAlgorithmTrainingStatus(latest.getTaskId());
        
        switch (latest.getStatus()) {
            case PENDING:
                trainingStatus.put("status", "PREPARING");
                trainingStatus.put("message", "正在准备训练数据...");
                break;
            case RUNNING:
                trainingStatus.put("status", "TRAINING");
                trainingStatus.put("message", "训练进行中...");
                trainingStatus.put("currentEpoch", toInt(algorithmStatus.getOrDefault("current_epoch", algorithmStatus.get("processed_images")), 0));
                putIfDouble(trainingStatus, "trainLoss", algorithmStatus.get("train_loss"));
                putIfDouble(trainingStatus, "valLoss", algorithmStatus.get("val_loss"));
                putIfDouble(trainingStatus, "boxLoss", algorithmStatus.get("box_loss"));
                putIfDouble(trainingStatus, "clsLoss", algorithmStatus.get("cls_loss"));
                putIfDouble(trainingStatus, "dflLoss", algorithmStatus.get("dfl_loss"));
                java.util.Map<String, Double> runningLoss = trainingService.getFinalLossSummary(latest);
                if (!trainingStatus.containsKey("trainLoss") && runningLoss.get("finalTrainLoss") != null) {
                    trainingStatus.put("trainLoss", runningLoss.get("finalTrainLoss"));
                }
                if (!trainingStatus.containsKey("valLoss") && runningLoss.get("finalValLoss") != null) {
                    trainingStatus.put("valLoss", runningLoss.get("finalValLoss"));
                }
                break;
            case COMPLETED:
                trainingStatus.put("status", "COMPLETED");
                trainingStatus.put("message", "训练已完成");
                trainingStatus.put("map50", latest.getMap50());
                trainingStatus.put("map5095", latest.getMap50_95());
                trainingStatus.put("precision", latest.getPrecision());
                trainingStatus.put("recall", latest.getRecall());
                trainingStatus.put("modelPath", latest.getBestModelPath());
                trainingStatus.putAll(trainingService.getFinalLossSummary(latest));
                if (latest.getStartedAt() != null && latest.getCompletedAt() != null) {
                    long durationSeconds = java.time.Duration.between(latest.getStartedAt(), latest.getCompletedAt()).getSeconds();
                    trainingStatus.put("trainingDuration", durationSeconds);
                }
                break;
            case FAILED:
                trainingStatus.put("status", "FAILED");
                trainingStatus.put("message", "训练失败");
                trainingStatus.put("errorMessage", latest.getErrorMessage());
                break;
            case CANCELLED:
                trainingStatus.put("status", "IDLE");
                trainingStatus.put("message", "训练已取消");
                break;
        }
        
        trainingStatus.put("recordId", latest.getId());
        trainingStatus.put("taskId", latest.getTaskId());
        trainingStatus.put("totalEpochs", latest.getEpochs());
        if (!trainingStatus.containsKey("currentEpoch")) {
            trainingStatus.put("currentEpoch", latest.getStatus() == com.annotation.platform.entity.ModelTrainingRecord.TrainingStatus.COMPLETED ? latest.getEpochs() : 0);
        }
        trainingStatus.put("totalImages", latest.getTotalImages());
        trainingStatus.put("totalAnnotations", latest.getTotalAnnotations());
        if (latest.getStartedAt() != null) {
            trainingStatus.put("startedAt", latest.getStartedAt().toString());
        }
        
        return Result.success(trainingStatus);
    }

    @PostMapping("/{id}/training/detect")
    public Result<java.util.Map<String, Object>> detectWithTrainedModel(
            @PathVariable Long id,
            @RequestParam("image") org.springframework.web.multipart.MultipartFile image,
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "modelPath", required = false) String requestedModelPath,
            HttpServletRequest httpRequest) {
        
        log.info("使用训练模型检测: projectId={}, modelId={}", id, modelId);
        
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));
        
        try {
            String modelPath = requestedModelPath;
            if (modelPath == null || modelPath.isBlank()) {
                java.util.List<com.annotation.platform.entity.ModelTrainingRecord> records =
                        modelTrainingRecordRepository.findByProjectIdOrderByCreatedAtDesc(id);
                modelPath = records.stream()
                        .filter(record -> record.getStatus() == com.annotation.platform.entity.ModelTrainingRecord.TrainingStatus.COMPLETED)
                        .map(record -> trainingService.hydrateCompletedRecordFromOutput(record).getBestModelPath())
                        .filter(path -> path != null && !path.isBlank())
                        .findFirst()
                        .orElse(null);
            }
            if (modelPath == null || modelPath.isBlank()) {
                throw new com.annotation.platform.exception.BusinessException("没有找到可用的已训练模型，请先完成一次训练");
            }

            java.nio.file.Path uploadDir = java.nio.file.Paths.get("/root/autodl-fs/Annotation-Platform/temp_uploads");
            java.nio.file.Files.createDirectories(uploadDir);
            String originalName = image.getOriginalFilename() != null ? image.getOriginalFilename() : "test.jpg";
            String suffix = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf(".")) : ".jpg";
            java.nio.file.Path tempFile = uploadDir.resolve(java.util.UUID.randomUUID() + suffix);
            image.transferTo(tempFile.toFile());

            String taskId = modelTestService.startTestWithUpload(
                    modelPath,
                    java.util.List.of(tempFile.toFile()),
                    0.25,
                    0.45,
                    "0"
            );

            java.util.Map<String, Object> status = java.util.Map.of();
            for (int i = 0; i < 60; i++) {
                Thread.sleep(1000);
                status = modelTestService.getTestStatus(taskId);
                String statusText = String.valueOf(status.get("status"));
                if ("completed".equalsIgnoreCase(statusText)) {
                    break;
                }
                if ("failed".equalsIgnoreCase(statusText)) {
                    throw new com.annotation.platform.exception.BusinessException("模型测试失败: " + status.get("error_message"));
                }
            }
            if (!"completed".equalsIgnoreCase(String.valueOf(status.get("status")))) {
                throw new com.annotation.platform.exception.BusinessException("模型测试超时，请稍后重试");
            }

            java.util.Map<String, Object> rawResults = modelTestService.getTestResults(taskId);
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            List<java.util.Map<String, Object>> detections = new java.util.ArrayList<>();
            extractDetections(rawResults, detections);
            
            result.put("detections", detections);
            result.put("modelPath", modelPath);
            result.put("taskId", taskId);
            
            log.info("检测完成: projectId={}, detectionCount={}", id, detections.size());
            
            return Result.success(result);
        } catch (Exception e) {
            log.error("检测失败: projectId={}, error={}", id, e.getMessage(), e);
            throw new com.annotation.platform.exception.BusinessException("检测失败: " + e.getMessage());
        }
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private void putIfDouble(java.util.Map<String, Object> target, String key, Object value) {
        Double parsed = toDouble(value);
        if (parsed != null) {
            target.put(key, parsed);
        }
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void extractDetections(java.util.Map<String, Object> rawResults, List<java.util.Map<String, Object>> detections) {
        Object resultsObj = rawResults.get("results");
        if (!(resultsObj instanceof List<?> taskResults) || taskResults.isEmpty()) {
            return;
        }
        Object first = taskResults.get(0);
        if (!(first instanceof java.util.Map<?, ?> firstResult)) {
            return;
        }
        Object imageResultsObj = firstResult.get("results");
        if (!(imageResultsObj instanceof List<?> imageResults) || imageResults.isEmpty()) {
            return;
        }
        Object imageResult = imageResults.get(0);
        if (!(imageResult instanceof java.util.Map<?, ?> imageResultMap)) {
            return;
        }
        Object detectionsObj = imageResultMap.get("detections");
        if (!(detectionsObj instanceof List<?> rawDetections)) {
            return;
        }
        for (Object item : rawDetections) {
            if (!(item instanceof java.util.Map<?, ?> rawDetection)) {
                continue;
            }
            java.util.Map<String, Object> detection = new java.util.HashMap<>();
            detection.put("label", rawDetection.get("label"));
            detection.put("confidence", rawDetection.get("confidence"));
            Object bbox = rawDetection.get("bbox");
            if (bbox instanceof java.util.Map<?, ?> bboxMap) {
                detection.put("bbox", java.util.Arrays.asList(
                        bboxMap.get("x1"),
                        bboxMap.get("y1"),
                        bboxMap.get("x2"),
                        bboxMap.get("y2")
                ));
            } else {
                detection.put("bbox", bbox);
            }
            detections.add(detection);
        }
    }

    private ProjectDetailResponse convertToDetailResponse(Project project) {
        // 初始化懒加载的属性
        if (project.getOrganization() != null) {
            Hibernate.initialize(project.getOrganization());
        }
        if (project.getCreatedBy() != null) {
            Hibernate.initialize(project.getCreatedBy());
        }
        
        return ProjectDetailResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .organization(project.getOrganization() != null ?
                        ProjectDetailResponse.OrganizationInfo.builder()
                                .id(project.getOrganization().getId())
                                .name(project.getOrganization().getName())
                                .build() : null)
                .createdBy(project.getCreatedBy() != null ?
                        ProjectDetailResponse.UserInfo.builder()
                                .id(project.getCreatedBy().getId())
                                .username(project.getCreatedBy().getUsername())
                                .build() : null)
                .status(ProjectDetailResponse.ProjectStatus.valueOf(project.getStatus().name()))
                .totalImages(project.getTotalImages())
                .processedImages(project.getProcessedImages())
                .labels(project.getLabels())
                .labelDefinitions(project.getLabelDefinitions())
                .currentRoundId(project.getCurrentRoundId())
                .projectType(project.getProjectType() != null ? project.getProjectType().name() : Project.ProjectType.LEGACY.name())
                .lsProjectStatus(project.getLsProjectStatus() != null ? project.getLsProjectStatus().name() : Project.LsProjectStatus.UNKNOWN.name())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .lsProjectId(project.getLsProjectId())
                .build();
    }
}
