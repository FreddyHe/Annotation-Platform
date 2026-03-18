package com.annotation.platform.repository;

import com.annotation.platform.entity.ResourceEstimation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResourceEstimationRepository extends JpaRepository<ResourceEstimation, Long> {

    List<ResourceEstimation> findByAssessmentId(Long assessmentId);

    List<ResourceEstimation> findByAssessmentIdAndCategoryName(Long assessmentId, String categoryName);
}
