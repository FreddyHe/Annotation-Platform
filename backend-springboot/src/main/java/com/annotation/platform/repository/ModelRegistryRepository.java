package com.annotation.platform.repository;

import com.annotation.platform.entity.ModelRegistry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelRegistryRepository extends JpaRepository<ModelRegistry, Long> {

    Optional<ModelRegistry> findByModelId(String modelId);

    boolean existsByModelId(String modelId);

    List<ModelRegistry> findByStatusOrderByModelIdAsc(ModelRegistry.Status status);

    List<ModelRegistry> findByModelTypeOrderByModelIdAsc(String modelType);

    List<ModelRegistry> findAllByOrderByModelIdAsc();
}
