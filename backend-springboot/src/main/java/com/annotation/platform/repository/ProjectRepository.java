package com.annotation.platform.repository;

import com.annotation.platform.entity.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    boolean existsByNameAndOrganizationId(String name, Long organizationId);

    Optional<Project> findByNameAndOrganizationId(String name, Long organizationId);

    Page<Project> findByOrganizationId(Long organizationId, Pageable pageable);

    Page<Project> findByOrganizationIdAndStatus(Long organizationId, Project.ProjectStatus status, Pageable pageable);

    @Query("SELECT p FROM Project p WHERE p.organization.id = :orgId AND (:status IS NULL OR p.status = :status)")
    Page<Project> findByOrganizationIdAndStatusOptional(@Param("orgId") Long orgId, @Param("status") Project.ProjectStatus status, Pageable pageable);

    @Query("SELECT COUNT(p) FROM Project p WHERE p.organization.id = :orgId")
    long countByOrganizationId(@Param("orgId") Long orgId);
    
    @Query("SELECT COUNT(pi) FROM Project p JOIN p.images pi WHERE p.organization.id = :orgId")
    long countImagesByOrganizationId(@Param("orgId") Long orgId);
}
