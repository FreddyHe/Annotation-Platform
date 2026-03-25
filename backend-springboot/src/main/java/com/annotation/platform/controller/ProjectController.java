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
            @RequestParam(defaultValue = "20") int size) {

        List<ProjectImage> images = projectImageRepository.findProjectImagesNativeByType(id);
        
        int start = (page - 1) * size;
        int end = Math.min(start + size, images.size());
        
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

        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalImages", totalImages);
        stats.put("uploadedImages", uploadedImages);
        stats.put("processedImages", project.getProcessedImages());
        stats.put("status", project.getStatus());

        return Result.success(stats);
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
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .lsProjectId(project.getLsProjectId())
                .build();
    }
}
