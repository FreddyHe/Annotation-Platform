package com.annotation.platform.repository;

import com.annotation.platform.entity.ImplementationPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ImplementationPlanRepository extends JpaRepository<ImplementationPlan, Long> {

    List<ImplementationPlan> findByAssessmentIdOrderByPhaseOrderAsc(Long assessmentId);

    @Transactional
    @Modifying
    @Query("DELETE FROM ImplementationPlan ip WHERE ip.assessment.id = :assessmentId")
    void deleteByAssessmentId(Long assessmentId);
}
