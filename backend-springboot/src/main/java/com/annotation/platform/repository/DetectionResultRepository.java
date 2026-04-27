package com.annotation.platform.repository;

import com.annotation.platform.entity.DetectionResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Query("""
            SELECT dr
            FROM DetectionResult dr
            JOIN FETCH dr.image img
            JOIN FETCH img.project p
            WHERE p.id = :projectId AND dr.type = :type
            """)
    List<DetectionResult> findByProjectIdAndTypeWithImage(
            @Param("projectId") Long projectId,
            @Param("type") DetectionResult.ResultType type);

    @Query("SELECT COUNT(dr) FROM DetectionResult dr WHERE dr.image.project.id = :projectId")
    long countByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT COUNT(dr) FROM DetectionResult dr WHERE dr.task.id = :taskId")
    long countByTaskId(@Param("taskId") Long taskId);

    @Query("SELECT dr FROM DetectionResult dr WHERE dr.image.id IN :imageIds AND dr.type = :type")
    List<DetectionResult> findByImageIdInAndType(@Param("imageIds") List<Long> imageIds, @Param("type") DetectionResult.ResultType type);

    // 根据 task ID 列表批量删除检测结果
    void deleteByTaskIdIn(List<Long> taskIds);

    // 根据项目 ID 删除所有检测结果（直接用 JPQL 更高效）
    @Modifying
    @Query("DELETE FROM DetectionResult dr WHERE dr.image.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
