package com.annotation.platform.repository;

import com.annotation.platform.entity.ModelTrainingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ModelTrainingRecordRepository extends JpaRepository<ModelTrainingRecord, Long> {

    Optional<ModelTrainingRecord> findByTaskId(String taskId);

    List<ModelTrainingRecord> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<ModelTrainingRecord> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<ModelTrainingRecord> findByStatus(ModelTrainingRecord.TrainingStatus status);

    @Query("SELECT m FROM ModelTrainingRecord m WHERE m.projectId = :projectId AND m.status = :status ORDER BY m.createdAt DESC")
    List<ModelTrainingRecord> findByProjectIdAndStatus(@Param("projectId") Long projectId, @Param("status") ModelTrainingRecord.TrainingStatus status);

    @Query("SELECT m FROM ModelTrainingRecord m WHERE m.userId = :userId AND m.status = :status ORDER BY m.createdAt DESC")
    List<ModelTrainingRecord> findByUserIdAndStatus(@Param("userId") Long userId, @Param("status") ModelTrainingRecord.TrainingStatus status);

    @Query("SELECT m FROM ModelTrainingRecord m WHERE m.startedAt BETWEEN :startTime AND :endTime ORDER BY m.createdAt DESC")
    List<ModelTrainingRecord> findByStartedAtBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Query("SELECT m FROM ModelTrainingRecord m WHERE m.status = 'COMPLETED' ORDER BY m.map50 DESC")
    List<ModelTrainingRecord> findCompletedOrderByMap50Desc();

    @Query("SELECT COUNT(m) FROM ModelTrainingRecord m WHERE m.projectId = :projectId")
    long countByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT COUNT(m) FROM ModelTrainingRecord m WHERE m.userId = :userId")
    long countByUserId(@Param("userId") Long userId);
}
