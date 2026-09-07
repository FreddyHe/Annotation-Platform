package com.annotation.platform.repository;

import com.annotation.platform.entity.FusedPrediction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FusedPredictionRepository extends JpaRepository<FusedPrediction, Long> {

    List<FusedPrediction> findByJobIdOrderByCreatedAtAsc(Long jobId);

    List<FusedPrediction> findByJobIdOrderByCreatedAtAsc(Long jobId, Pageable pageable);

    long countByJobId(Long jobId);

    @Query("SELECT COUNT(DISTINCT p.imageId) FROM FusedPrediction p WHERE p.job.id = :jobId")
    long countDistinctImageIdByJobId(@Param("jobId") Long jobId);

    long countByJobIdAndSyncedToLabelStudio(Long jobId, Boolean syncedToLabelStudio);

    @Modifying
    void deleteByJobId(Long jobId);
}
