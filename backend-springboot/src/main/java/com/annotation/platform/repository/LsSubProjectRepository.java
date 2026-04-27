package com.annotation.platform.repository;

import com.annotation.platform.entity.LsSubProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LsSubProjectRepository extends JpaRepository<LsSubProject, Long> {

    Optional<LsSubProject> findByLsProjectId(Long lsProjectId);

    List<LsSubProject> findByProjectIdOrderByBatchNumberAsc(Long projectId);

    List<LsSubProject> findByProjectIdAndSubType(Long projectId, LsSubProject.SubType subType);

    List<LsSubProject> findByProjectIdAndSubTypeAndStatus(Long projectId,
                                                          LsSubProject.SubType subType,
                                                          LsSubProject.Status status);

    Optional<LsSubProject> findFirstByProjectIdAndSubTypeAndStatusOrderByBatchNumberDesc(
            Long projectId, LsSubProject.SubType subType, LsSubProject.Status status);
}
