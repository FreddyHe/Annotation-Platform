package com.annotation.platform.repository;

import com.annotation.platform.entity.VlmEvaluationDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VlmEvaluationDetailRepository extends JpaRepository<VlmEvaluationDetail, Long> {
    
    List<VlmEvaluationDetail> findByQualityScoreId(Long qualityScoreId);
    
    void deleteByQualityScoreId(Long qualityScoreId);
}
