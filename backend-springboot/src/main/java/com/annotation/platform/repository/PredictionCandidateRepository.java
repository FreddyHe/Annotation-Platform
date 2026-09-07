package com.annotation.platform.repository;

import com.annotation.platform.entity.PredictionCandidate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PredictionCandidateRepository extends JpaRepository<PredictionCandidate, Long> {

    List<PredictionCandidate> findByJobIdOrderByCreatedAtAsc(Long jobId);

    List<PredictionCandidate> findByJobIdOrderByCreatedAtAsc(Long jobId, Pageable pageable);

    long countByJobId(Long jobId);

    @Modifying
    void deleteByJobId(Long jobId);
}
