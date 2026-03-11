package com.annotation.platform.repository;

import com.annotation.platform.entity.AnnotationTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnnotationTaskRepository extends JpaRepository<AnnotationTask, Long> {

    Optional<AnnotationTask> findByIdAndProjectId(Long taskId, Long projectId);

    Page<AnnotationTask> findByProjectId(Long projectId, Pageable pageable);

    List<AnnotationTask> findByProjectIdOrderByStartedAtDesc(Long projectId);

    Page<AnnotationTask> findByProjectIdAndStatus(Long projectId, AnnotationTask.TaskStatus status, Pageable pageable);

    @Query("SELECT COUNT(t) FROM AnnotationTask t WHERE t.project.id = :projectId")
    long countByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT COUNT(t) FROM AnnotationTask t WHERE t.project.id = :projectId AND t.status = :status")
    long countByProjectIdAndStatus(@Param("projectId") Long projectId, @Param("status") AnnotationTask.TaskStatus status);
}
