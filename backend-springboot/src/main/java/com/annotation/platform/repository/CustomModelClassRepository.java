package com.annotation.platform.repository;

import com.annotation.platform.entity.CustomModelClass;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CustomModelClassRepository extends JpaRepository<CustomModelClass, Long> {
    List<CustomModelClass> findByModelIdOrderByClassIdAsc(Long modelId);
    void deleteByModelId(Long modelId);
}
