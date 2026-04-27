package com.annotation.platform.repository;

import com.annotation.platform.entity.IterationRound;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IterationRoundRepository extends JpaRepository<IterationRound, Long> {

    List<IterationRound> findByProjectIdOrderByRoundNumberDesc(Long projectId);

    Optional<IterationRound> findByProjectIdAndRoundNumber(Long projectId, Integer roundNumber);

    @Query("SELECT COALESCE(MAX(r.roundNumber), 0) FROM IterationRound r WHERE r.project.id = :projectId")
    Integer findMaxRoundNumber(@Param("projectId") Long projectId);

    @Modifying
    @Query("DELETE FROM IterationRound r WHERE r.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
