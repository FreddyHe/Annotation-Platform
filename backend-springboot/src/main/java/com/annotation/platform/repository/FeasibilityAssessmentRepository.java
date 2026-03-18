package com.annotation.platform.repository;

import com.annotation.platform.entity.AssessmentStatus;
import com.annotation.platform.entity.FeasibilityAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeasibilityAssessmentRepository extends JpaRepository<FeasibilityAssessment, Long> {

    List<FeasibilityAssessment> findByStatusOrderByCreatedAtDesc(AssessmentStatus status);

    List<FeasibilityAssessment> findAllByOrderByCreatedAtDesc();

    Optional<FeasibilityAssessment> findByCreatedBy_IdAndOrganization_Id(Long userId, Long organizationId);

    List<FeasibilityAssessment> findByOrganization_IdOrderByCreatedAtDesc(Long organizationId);
}