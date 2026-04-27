package com.annotation.platform.repository;

import com.annotation.platform.entity.AutoAnnotationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutoAnnotationJobRepository extends JpaRepository<AutoAnnotationJob, Long> {

    Optional<AutoAnnotationJob> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<AutoAnnotationJob> findByStatus(AutoAnnotationJob.JobStatus status);

    long countByProjectId(Long projectId);

    @Modifying
    @Transactional
    @Query("DELETE FROM AutoAnnotationJob j WHERE j.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
