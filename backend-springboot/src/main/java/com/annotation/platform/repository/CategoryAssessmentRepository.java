package com.annotation.platform.repository;

import com.annotation.platform.entity.CategoryAssessment;
import com.annotation.platform.entity.FeasibilityBucket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryAssessmentRepository extends JpaRepository<CategoryAssessment, Long> {

    List<CategoryAssessment> findByAssessmentIdOrderByCreatedAtAsc(Long assessmentId);

    List<CategoryAssessment> findByAssessmentIdAndFeasibilityBucket(Long assessmentId, FeasibilityBucket bucket);

    Optional<CategoryAssessment> findByIdAndAssessmentId(Long id, Long assessmentId);
}
