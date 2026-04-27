package com.annotation.platform.service;

import com.annotation.platform.entity.ProjectConfig;
import com.annotation.platform.repository.ProjectConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProjectConfigService {

    private final ProjectConfigRepository projectConfigRepository;

    @Transactional
    public ProjectConfig getOrCreate(Long projectId) {
        ProjectConfig config = projectConfigRepository.findById(projectId)
                .orElseGet(() -> projectConfigRepository.save(ProjectConfig.builder().projectId(projectId).build()));
        if (config.getLowBBatchSize() == null || config.getLowBBatchSize() < 1) {
            config.setLowBBatchSize(100);
            config = projectConfigRepository.save(config);
        }
        return config;
    }

    @Transactional
    public ProjectConfig update(Long projectId, Map<String, Object> request) {
        ProjectConfig config = getOrCreate(projectId);
        if (request.get("highPoolThreshold") != null) {
            config.setHighPoolThreshold(asDouble(request.get("highPoolThreshold")));
        }
        if (request.get("lowPoolThreshold") != null) {
            config.setLowPoolThreshold(asDouble(request.get("lowPoolThreshold")));
        }
        if (request.get("enableAutoVlmJudge") != null) {
            config.setEnableAutoVlmJudge(asBoolean(request.get("enableAutoVlmJudge")));
        }
        if (request.get("vlmQuotaPerRound") != null) {
            config.setVlmQuotaPerRound(asInteger(request.get("vlmQuotaPerRound")));
        }
        if (request.get("autoTriggerRetrain") != null) {
            config.setAutoTriggerRetrain(asBoolean(request.get("autoTriggerRetrain")));
        }
        if (request.get("retrainMinSamples") != null) {
            config.setRetrainMinSamples(asInteger(request.get("retrainMinSamples")));
        }
        if (request.get("lowBBatchSize") != null) {
            config.setLowBBatchSize(Math.max(1, asInteger(request.get("lowBBatchSize"))));
        }
        return projectConfigRepository.save(config);
    }

    private Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(value.toString());
    }

    private Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private Boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }
}
