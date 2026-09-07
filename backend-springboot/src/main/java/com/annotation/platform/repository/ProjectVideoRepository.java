package com.annotation.platform.repository;

import com.annotation.platform.entity.ProjectVideo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectVideoRepository extends JpaRepository<ProjectVideo, Long> {

    Optional<ProjectVideo> findByProjectIdAndSourceVideoId(Long projectId, String sourceVideoId);

    @Query(value = "SELECT * FROM project_videos WHERE project_id = :projectId ORDER BY created_at ASC", nativeQuery = true)
    List<ProjectVideo> findByProjectIdOrderByCreatedAtAsc(@Param("projectId") Long projectId);

    @Modifying
    @Query(value = "DELETE FROM project_videos WHERE project_id = :projectId", nativeQuery = true)
    void deleteByProjectId(@Param("projectId") Long projectId);
}
