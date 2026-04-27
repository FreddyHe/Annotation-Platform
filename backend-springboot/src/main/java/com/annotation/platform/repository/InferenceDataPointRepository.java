package com.annotation.platform.repository;

import com.annotation.platform.entity.InferenceDataPoint;
import com.annotation.platform.entity.InferenceDataPoint.PoolType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InferenceDataPointRepository extends JpaRepository<InferenceDataPoint, Long> {

    List<InferenceDataPoint> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<InferenceDataPoint> findByRoundIdOrderByCreatedAtDesc(Long roundId);

    List<InferenceDataPoint> findByDeploymentIdOrderByCreatedAtDesc(Long deploymentId);

    List<InferenceDataPoint> findByRoundIdAndPoolType(Long roundId, PoolType poolType);

    List<InferenceDataPoint> findByRoundIdAndPoolTypeAndHumanReviewed(Long roundId, PoolType poolType, Boolean humanReviewed);

    Optional<InferenceDataPoint> findByLsTaskId(Long lsTaskId);

    List<InferenceDataPoint> findByProjectIdAndPoolTypeAndHumanReviewed(Long projectId, PoolType poolType, Boolean humanReviewed);

    List<InferenceDataPoint> findByProjectIdAndPoolTypeIn(Long projectId, List<PoolType> poolTypes);

    List<InferenceDataPoint> findByProjectIdAndPoolTypeInAndLsTaskIdIsNullOrderByCreatedAtAsc(Long projectId, List<PoolType> poolTypes);

    List<InferenceDataPoint> findByProjectIdAndPoolTypeAndLsTaskIdIsNullOrderByCreatedAtAsc(Long projectId, PoolType poolType);

    long countByProjectIdAndPoolType(Long projectId, PoolType poolType);

    long countByRoundIdAndPoolType(Long roundId, PoolType poolType);

    long countByRoundIdAndPoolTypeAndHumanReviewedFalse(Long roundId, PoolType poolType);

    @Modifying
    @Query("UPDATE InferenceDataPoint d SET d.poolType = :newType WHERE d.id IN :ids")
    int batchUpdatePoolType(@Param("ids") List<Long> ids, @Param("newType") PoolType newType);

    @Modifying
    @Query("UPDATE InferenceDataPoint d SET d.usedInRoundId = :usedInRoundId WHERE d.roundId = :roundId AND d.poolType IN :poolTypes")
    int markUsedInRound(@Param("roundId") Long roundId, @Param("usedInRoundId") Long usedInRoundId, @Param("poolTypes") List<PoolType> poolTypes);

    @Modifying
    @Query("DELETE FROM InferenceDataPoint d WHERE d.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
