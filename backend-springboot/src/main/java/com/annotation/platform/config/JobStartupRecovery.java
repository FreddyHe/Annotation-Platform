package com.annotation.platform.config;

import com.annotation.platform.entity.AutoAnnotationJob;
import com.annotation.platform.repository.AutoAnnotationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobStartupRecovery {

    private final AutoAnnotationJobRepository autoAnnotationJobRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanJobs() {
        List<AutoAnnotationJob> jobs = autoAnnotationJobRepository.findByStatus(AutoAnnotationJob.JobStatus.RUNNING);
        for (AutoAnnotationJob job : jobs) {
            job.setStatus(AutoAnnotationJob.JobStatus.FAILED);
            job.setErrorMessage("Server restarted, job orphaned");
            job.setCompletedAt(LocalDateTime.now());
            autoAnnotationJobRepository.save(job);
            log.warn("Marked orphan auto annotation job as failed: jobId={}", job.getId());
        }
    }
}
