package com.annotation.platform.repository;

import com.annotation.platform.entity.DetectionResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DetectionResultRepository extends JpaRepository<DetectionResult, Long> {

    Optional<DetectionResult> findByIdAndImageId(Long resultId, Long imageId);

    Page<DetectionResult> findByImageId(Long imageId, Pageable pageable);

    Page<DetectionResult> findByTaskId(Long taskId, Pageable pageable);

    List<DetectionResult> findByImageIdAndType(Long imageId, DetectionResult.ResultType type);

    List<DetectionResult> findByTaskIdAndType(Long taskId, DetectionResult.ResultType type);

    @Query("SELECT dr FROM DetectionResult dr WHERE dr.image.project.id = :projectId AND dr.type = :type")
    List<DetectionResult> findByProjectIdAndType(@Param("projectId") Long projectId, @Param("type") DetectionResult.ResultType type);

    @Query("SELECT COUNT(dr) FROM DetectionResult dr WHERE dr.image.project.id = :projectId")
    long countByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT COUNT(dr) FROM DetectionResult dr WHERE dr.task.id = :taskId")
    long countByTaskId(@Param("taskId") Long taskId);
}
