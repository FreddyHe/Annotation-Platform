package com.annotation.platform.service;

import com.annotation.platform.entity.AutoAnnotationJob;
import com.annotation.platform.entity.ModelTrainingRecord;
import com.annotation.platform.entity.Project;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.AutoAnnotationJobRepository;
import com.annotation.platform.repository.ProjectRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    private final ProjectRepository projectRepository;
    private final AutoAnnotationJobRepository autoAnnotationJobRepository;

    @Transactional(readOnly = true)
    public Project requireProjectInCurrentOrg(Long projectId, HttpServletRequest request) {
        Long organizationId = currentOrganizationId(request);
        return projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
    }

    @Transactional(readOnly = true)
    public void requireProjectAccess(Long projectId, HttpServletRequest request) {
        requireProjectInCurrentOrg(projectId, request);
    }

    @Transactional(readOnly = true)
    public AutoAnnotationJob requireAutoAnnotationJobInCurrentOrg(Long jobId, HttpServletRequest request) {
        AutoAnnotationJob job = autoAnnotationJobRepository.findByIdWithProject(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("AutoAnnotationJob", "id", jobId));
        Project project = job.getProject();
        if (project == null || project.getId() == null) {
            throw new ResourceNotFoundException("Project", "jobId", jobId);
        }
        requireProjectAccess(project.getId(), request);
        return job;
    }

    @Transactional(readOnly = true)
    public void requireTrainingRecordInCurrentOrg(ModelTrainingRecord record, HttpServletRequest request) {
        if (record == null || record.getProjectId() == null) {
            throw new ResourceNotFoundException("ModelTrainingRecord", "id", record != null ? record.getId() : null);
        }
        requireProjectAccess(record.getProjectId(), request);
    }

    public Long currentUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId instanceof Long id) {
            return id;
        }
        throw new AccessDeniedException("当前请求没有有效用户");
    }

    public Long currentOrganizationId(HttpServletRequest request) {
        Object organizationId = request.getAttribute("organizationId");
        if (organizationId instanceof Long id) {
            return id;
        }
        throw new AccessDeniedException("当前用户没有组织，无法访问项目");
    }
}
