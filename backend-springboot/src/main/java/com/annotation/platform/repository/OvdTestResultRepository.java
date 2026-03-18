package com.annotation.platform.repository;

import com.annotation.platform.entity.OvdTestResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OvdTestResultRepository extends JpaRepository<OvdTestResult, Long> {

    List<OvdTestResult> findByAssessmentIdOrderByTestTimeDesc(Long assessmentId);

    List<OvdTestResult> findByAssessmentIdAndCategoryName(Long assessmentId, String categoryName);
}
