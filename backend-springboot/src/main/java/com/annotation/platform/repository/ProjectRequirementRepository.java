package com.annotation.platform.repository;

import com.annotation.platform.entity.ProjectRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRequirementRepository extends JpaRepository<ProjectRequirement, Long> {

    Optional<ProjectRequirement> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<ProjectRequirement> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
