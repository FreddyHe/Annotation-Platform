package com.annotation.platform.config;

import com.annotation.platform.entity.AssessmentStatus;
import com.annotation.platform.entity.FeasibilityAssessment;
import com.annotation.platform.repository.FeasibilityAssessmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DevBootstrap implements CommandLineRunner {

    private final FeasibilityAssessmentRepository assessmentRepository;

    @Override
    public void run(String... args) {
        if (assessmentRepository.count() == 0) {
            FeasibilityAssessment a = FeasibilityAssessment.builder()
                    .assessmentName("Dev Assessment")
                    .rawRequirement("开发环境预置评估")
                    .status(AssessmentStatus.CREATED)
                    .build();
            FeasibilityAssessment saved = assessmentRepository.save(a);
            log.info("Dev 预置评估创建完成: id={}", saved.getId());
        }
    }
}
