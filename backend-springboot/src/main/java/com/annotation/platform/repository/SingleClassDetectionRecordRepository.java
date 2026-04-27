package com.annotation.platform.repository;

import com.annotation.platform.entity.SingleClassDetectionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SingleClassDetectionRecordRepository extends JpaRepository<SingleClassDetectionRecord, Long> {
    List<SingleClassDetectionRecord> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
    void deleteByUserId(Long userId);
}
