package com.annotation.platform.service;

import com.annotation.platform.entity.InferenceDataPoint;
import com.annotation.platform.entity.ProjectConfig;
import com.annotation.platform.repository.InferenceDataPointRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class VlmJudgeService {

    private final InferenceDataPointRepository inferenceDataPointRepository;
    private final ProjectConfigService projectConfigService;
    private final RoundService roundService;
    private final IncrementalProjectService incrementalProjectService;
    private final ProjectRepository projectRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.algorithm.url:http://localhost:8001}")
    private String algorithmServiceUrl;

    @Transactional
    public Map<String, Object> judgeAndSplit(Long projectId, Long roundId) {
        ProjectConfig config = projectConfigService.getOrCreate(projectId);
        if (!Boolean.TRUE.equals(config.getEnableAutoVlmJudge())) {
            return Map.of("enabled", false, "processed", 0);
        }

        List<InferenceDataPoint> candidates = inferenceDataPointRepository.findByRoundIdAndPoolType(
                roundId, InferenceDataPoint.PoolType.LOW_A_CANDIDATE);
        int quota = config.getVlmQuotaPerRound() == null ? 500 : config.getVlmQuotaPerRound();
        if (candidates.size() > quota) {
            candidates = candidates.subList(0, quota);
        }
        if (candidates.isEmpty()) {
            return Map.of("enabled", true, "processed", 0);
        }

        Map<String, Object> request = new HashMap<>();
        request.put("project_id", projectId);
        request.put("round_id", roundId);
        List<Map<String, Object>> points = new ArrayList<>();
        for (InferenceDataPoint point : candidates) {
            points.add(Map.of(
                    "id", point.getId(),
                    "image_path", point.getImagePath(),
                    "detections", parseDetections(point.getInferenceBboxJson()),
                    "avg_confidence", point.getAvgConfidence() == null ? 0.0 : point.getAvgConfidence()
            ));
        }
        request.put("candidates", points);

        List<Map<String, Object>> results = callAlgorithmJudge(request);
        int lowA = 0;
        int lowB = 0;
        int discarded = 0;
        for (Map<String, Object> result : results) {
            Long id = asLong(result.get("id"));
            if (id == null) {
                continue;
            }
            Optional<InferenceDataPoint> optionalPoint = inferenceDataPointRepository.findById(id);
            if (optionalPoint.isEmpty()) {
                continue;
            }
            InferenceDataPoint point = optionalPoint.get();
            String decision = String.valueOf(result.getOrDefault("decision", "uncertain")).toLowerCase(Locale.ROOT);
            String reasoning = String.valueOf(result.getOrDefault("reasoning", ""));
            if ("keep".equals(decision)) {
                point.setPoolType(InferenceDataPoint.PoolType.LOW_A);
                point.setVlmDecision(InferenceDataPoint.VlmDecision.KEEP);
                lowA++;
            } else if ("discard".equals(decision)) {
                point.setPoolType(InferenceDataPoint.PoolType.DISCARDED);
                point.setVlmDecision(InferenceDataPoint.VlmDecision.DISCARD);
                discarded++;
            } else {
                point.setPoolType(InferenceDataPoint.PoolType.LOW_B);
                point.setVlmDecision(InferenceDataPoint.VlmDecision.UNCERTAIN);
                point.setHumanReviewed(false);
                lowB++;
            }
            point.setVlmReasoning(reasoning);
            point.setVlmProcessedAt(LocalDateTime.now());
            inferenceDataPointRepository.save(point);
        }

        roundService.markRoundReviewing(roundId);
        if (lowB > 0) {
            try {
                Long ownerId = projectRepository.findById(projectId)
                        .map(project -> project.getCreatedBy() != null ? project.getCreatedBy().getId() : null)
                        .orElse(null);
                incrementalProjectService.syncLowBToIncrementalProject(projectId, ownerId);
            } catch (Exception e) {
                log.warn("LOW_B incremental Label Studio sync failed: {}", e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("processed", results.size());
        response.put("lowA", lowA);
        response.put("lowB", lowB);
        response.put("discarded", discarded);
        return response;
    }

    public InferenceDataPoint manualReview(Long id, boolean reviewed) {
        InferenceDataPoint point = inferenceDataPointRepository.findById(id)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("InferenceDataPoint", "id", id));
        point.setHumanReviewed(reviewed);
        return inferenceDataPointRepository.save(point);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callAlgorithmJudge(Map<String, Object> request) {
        try {
            String url = algorithmServiceUrl + "/api/v1/algo/reinference/vlm-judge";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(request, headers), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            return objectMapper.convertValue(root.get("results"), List.class);
        } catch (Exception e) {
            log.warn("VLM judge service unavailable, using deterministic confidence fallback: {}", e.getMessage());
            List<Map<String, Object>> fallback = new ArrayList<>();
            List<Map<String, Object>> points = (List<Map<String, Object>>) request.getOrDefault("candidates", List.of());
            for (Map<String, Object> point : points) {
                fallback.add(Map.of(
                        "id", point.get("id"),
                        "decision", "uncertain",
                        "reasoning", "VLM service unavailable, marked for human review"
                ));
            }
            return fallback;
        }
    }

    private Object parseDetections(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
