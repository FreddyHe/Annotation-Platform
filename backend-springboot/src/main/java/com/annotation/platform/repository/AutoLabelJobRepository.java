package com.annotation.platform.repository;

import com.annotation.platform.entity.AutoLabelJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutoLabelJobRepository extends JpaRepository<AutoLabelJob, Long> {

    List<AutoLabelJob> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<AutoLabelJob> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);

    @Query("SELECT j FROM AutoLabelJob j JOIN FETCH j.project LEFT JOIN FETCH j.routePlan WHERE j.id = :id")
    Optional<AutoLabelJob> findByIdWithProjectAndRoutePlan(@Param("id") Long id);
}
