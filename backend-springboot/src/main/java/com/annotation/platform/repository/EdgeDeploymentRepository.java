package com.annotation.platform.repository;

import com.annotation.platform.entity.EdgeDeployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EdgeDeploymentRepository extends JpaRepository<EdgeDeployment, Long> {

    List<EdgeDeployment> findByProjectIdOrderByDeployedAtDesc(Long projectId);

    List<EdgeDeployment> findByRoundIdOrderByDeployedAtDesc(Long roundId);

    @Query("SELECT d FROM EdgeDeployment d WHERE d.projectId = :projectId AND d.status = 'ACTIVE' ORDER BY d.deployedAt DESC")
    List<EdgeDeployment> findActiveByProjectId(@Param("projectId") Long projectId);

    default Optional<EdgeDeployment> findLatestActiveByProjectId(Long projectId) {
        List<EdgeDeployment> active = findActiveByProjectId(projectId);
        return active.isEmpty() ? Optional.empty() : Optional.of(active.get(0));
    }

    @Modifying
    @Query("DELETE FROM EdgeDeployment d WHERE d.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
