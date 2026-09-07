package com.annotation.platform.repository;

import com.annotation.platform.entity.DatasetProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DatasetProfileRepository extends JpaRepository<DatasetProfile, Long> {

    Optional<DatasetProfile> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<DatasetProfile> findFirstByProjectIdAndDatasetIdOrderByCreatedAtDesc(Long projectId, String datasetId);

    List<DatasetProfile> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
