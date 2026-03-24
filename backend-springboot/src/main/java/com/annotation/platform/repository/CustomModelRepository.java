package com.annotation.platform.repository;

import com.annotation.platform.entity.CustomModel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CustomModelRepository extends JpaRepository<CustomModel, Long> {
    List<CustomModel> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<CustomModel> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);
}
