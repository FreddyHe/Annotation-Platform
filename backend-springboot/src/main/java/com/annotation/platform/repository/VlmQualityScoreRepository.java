package com.annotation.platform.repository;

import com.annotation.platform.entity.VlmQualityScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VlmQualityScoreRepository extends JpaRepository<VlmQualityScore, Long> {

    List<VlmQualityScore> findByOvdTestResultIdOrderByCreatedAtDesc(Long ovdTestResultId);
}
