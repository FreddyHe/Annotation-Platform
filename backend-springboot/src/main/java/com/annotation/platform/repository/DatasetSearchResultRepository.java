package com.annotation.platform.repository;

import com.annotation.platform.entity.DatasetSearchResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DatasetSearchResultRepository extends JpaRepository<DatasetSearchResult, Long> {

    List<DatasetSearchResult> findByAssessmentId(Long assessmentId);

    List<DatasetSearchResult> findByAssessmentIdOrderByRelevanceScoreDesc(Long assessmentId);

    List<DatasetSearchResult> findByAssessmentIdAndCategoryName(Long assessmentId, String categoryName);

    List<DatasetSearchResult> findByAssessmentIdAndSource(Long assessmentId, String source);

    void deleteByAssessmentId(Long assessmentId);
}
