package com.annotation.platform.repository;

import com.annotation.platform.dto.ProjectImageResponse;
import com.annotation.platform.entity.ProjectImage;
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
public interface ProjectImageRepository extends JpaRepository<ProjectImage, Long> {

    Page<ProjectImage> findByProjectId(Long projectId, Pageable pageable);

    Page<ProjectImage> findByProjectIdAndStatus(Long projectId, ProjectImage.ImageStatus status, Pageable pageable);

    Optional<ProjectImage> findByProjectIdAndFilePath(Long projectId, String filePath);

    @Query("SELECT COUNT(pi) FROM ProjectImage pi WHERE pi.project.id = :projectId")
    long countByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT COUNT(pi) FROM ProjectImage pi WHERE pi.project.id = :projectId AND pi.status = :status")
    long countByProjectIdAndStatus(@Param("projectId") Long projectId, @Param("status") ProjectImage.ImageStatus status);

    List<ProjectImage> findByProjectIdAndStatusIn(Long projectId, List<ProjectImage.ImageStatus> statuses);

    @Modifying
    @Query(value = "DELETE FROM project_images WHERE project_id = :projectId", nativeQuery = true)
    void deleteByProjectId(@Param("projectId") Long projectId);

    @Query(value = "SELECT * FROM project_images WHERE project_id = :projectId ORDER BY uploaded_at DESC", nativeQuery = true)
    List<ProjectImage> findProjectImagesNative(@Param("projectId") Long projectId);
    
    @Query(value = "SELECT * FROM project_images WHERE project_id = :projectId AND file_path LIKE '%.jpg' OR file_path LIKE '%.jpeg' OR file_path LIKE '%.png' OR file_path LIKE '%.gif' OR file_path LIKE '%.bmp' OR file_path LIKE '%.webp' ORDER BY uploaded_at DESC", nativeQuery = true)
    List<ProjectImage> findProjectImagesNativeByType(@Param("projectId") Long projectId);

    @Query(value = "SELECT * FROM project_images WHERE project_id = :projectId AND status = :status ORDER BY uploaded_at DESC", nativeQuery = true)
    List<ProjectImage> findProjectImagesNativeByStatus(@Param("projectId") Long projectId, @Param("status") String status);
}
