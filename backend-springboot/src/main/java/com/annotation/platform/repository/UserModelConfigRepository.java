package com.annotation.platform.repository;

import com.annotation.platform.entity.UserModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserModelConfigRepository extends JpaRepository<UserModelConfig, Long> {
    Optional<UserModelConfig> findByUserId(Long userId);
}

