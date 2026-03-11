package com.annotation.platform.repository;

import com.annotation.platform.entity.ProjectLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectLabelRepository extends JpaRepository<ProjectLabel, Long> {

    List<ProjectLabel> findByProjectId(Long projectId);

    List<ProjectLabel> findByProjectIdAndIsActive(Long projectId, Boolean isActive);
}
