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
    private final com.annotation.platform.service.TrainingService trainingService;
    private final com.annotation.platform.service.ModelTestService modelTestService;
    private final com.annotation.platform.repository.ModelTrainingRecordRepository modelTrainingRecordRepository;

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
            
            com.annotation.platform.entity.ModelTrainingRecord record = trainingService.startTraining(
                    userId,
                    id,
                    String.valueOf(project.getLsProjectId()),
                    user.getLsToken(),
                    epochs,
                    batchSize,
                    imageSize,
                    modelType,
                    device
            );
            
            java.util.Map<String, Object> trainingStatus = new java.util.HashMap<>();
            trainingStatus.put("status", "PREPARING");
            trainingStatus.put("message", "训练任务已创建，正在准备数据...");
            trainingStatus.put("modelName", config.get("modelName"));
            trainingStatus.put("totalEpochs", epochs);
            trainingStatus.put("currentEpoch", 0);
            trainingStatus.put("recordId", record.getId());
            trainingStatus.put("taskId", record.getTaskId());
            
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
                trainingStatus.put("currentEpoch", toInt(algorithmStatus.get("processed_images"), 0));
                java.util.Map<String, Double> runningLoss = trainingService.getFinalLossSummary(latest);
                if (runningLoss.get("finalTrainLoss") != null) {
                    trainingStatus.put("trainLoss", runningLoss.get("finalTrainLoss"));
                }
                if (runningLoss.get("finalValLoss") != null) {
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
                    "cpu"
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
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .lsProjectId(project.getLsProjectId())
                .build();
    }
}
