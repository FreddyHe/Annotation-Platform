package com.annotation.platform.repository;

import com.annotation.platform.entity.ImplementationPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImplementationPlanRepository extends JpaRepository<ImplementationPlan, Long> {

    List<ImplementationPlan> findByAssessmentIdOrderByPhaseOrderAsc(Long assessmentId);
}
