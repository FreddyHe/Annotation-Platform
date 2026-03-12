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
            @Valid @RequestBody UpdateProjectRequest request) {

        log.info("更新项目: id={}", id);

        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("Project", "id", id));

        if (request.getName() != null && !request.getName().isBlank()) {
            project.setName(request.getName());
        }

        if (request.getLabels() != null) {
            project.setLabels(request.getLabels());
        }

        if (request.getLabelDefinitions() != null) {
            project.setLabelDefinitions(request.getLabelDefinitions());
        }

        Project updatedProject = projectRepository.save(project);

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

        projectImageRepository.deleteByProjectId(id);

        Long lsProjectId = project.getLsProjectId();
        if (lsProjectId != null) {
            try {
                labelStudioProxyService.deleteProject(lsProjectId, userId);
            } catch (Exception e) {
                log.warn("Label Studio 删除失败，忽略此错误: id={}, lsId={}, error={}", id, lsProjectId, e.getMessage());
            }
        }

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
        
        List<ProjectImageResponse> imageResponses = paginatedImages.stream()
                .map(image -> ProjectImageResponse.builder()
                        .id(image.getId())
                        .fileName(image.getFileName())
                        .filePath(image.getFilePath())
                        .fileSize(image.getFileSize())
                        .uploadedAt(image.getUploadedAt())
                        .status(image.getStatus().name())
                        .build())
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
                .build();
    }
}
