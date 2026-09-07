package com.annotation.platform.repository;

import com.annotation.platform.entity.ModelRoutePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelRoutePlanRepository extends JpaRepository<ModelRoutePlan, Long> {

    Optional<ModelRoutePlan> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<ModelRoutePlan> findFirstByProjectIdAndDatasetIdOrderByCreatedAtDesc(Long projectId, String datasetId);

    List<ModelRoutePlan> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
