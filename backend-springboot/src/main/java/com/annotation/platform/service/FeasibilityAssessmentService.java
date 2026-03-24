package com.annotation.platform.service;

import com.annotation.platform.dto.request.feasibility.CreateAssessmentRequest;
import com.annotation.platform.dto.request.feasibility.UserJudgmentRequest;
import com.annotation.platform.dto.response.feasibility.FeasibilityAssessmentResponse;
import com.annotation.platform.entity.AssessmentStatus;
import com.annotation.platform.entity.CategoryAssessment;
import com.annotation.platform.entity.DatasetMatchLevel;
import com.annotation.platform.entity.FeasibilityAssessment;
import com.annotation.platform.entity.FeasibilityBucket;
import com.annotation.platform.entity.Organization;
import com.annotation.platform.entity.ResourceEstimation;
import com.annotation.platform.entity.User;
import com.annotation.platform.exception.BusinessException;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.CategoryAssessmentRepository;
import com.annotation.platform.repository.DatasetSearchResultRepository;
import com.annotation.platform.repository.FeasibilityAssessmentRepository;
import com.annotation.platform.repository.ImplementationPlanRepository;
import com.annotation.platform.repository.OvdTestResultRepository;
import com.annotation.platform.repository.OrganizationRepository;
import com.annotation.platform.repository.ResourceEstimationRepository;
import com.annotation.platform.repository.UserRepository;
import com.annotation.platform.service.user.UserModelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeasibilityAssessmentService {

    private final FeasibilityAssessmentRepository assessmentRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final CategoryAssessmentRepository categoryAssessmentRepository;
    private final OvdTestResultRepository ovdTestResultRepository;
    private final DatasetSearchResultRepository datasetSearchResultRepository;
    private final ResourceEstimationRepository resourceEstimationRepository;
    private final ImplementationPlanRepository implementationPlanRepository;
    private final UserModelConfigService userModelConfigService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.algorithm.url:http://localhost:8001}")
    private String algorithmServiceUrl;

    @Value("${app.file.upload.base-path}")
    private String basePath;

    public FeasibilityAssessmentResponse createAssessment(CreateAssessmentRequest request, Long userId, Long organizationId) {
        log.info("创建可行性评估: name={}, userId={}, orgId={}", request.getAssessmentName(), userId, organizationId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", organizationId));

        String imageUrlsJson = null;
        if (request.getImageUrls() != null && !request.getImageUrls().isEmpty()) {
            try {
                imageUrlsJson = objectMapper.writeValueAsString(request.getImageUrls());
            } catch (Exception e) {
                log.error("序列化imageUrls失败: {}", e.getMessage());
            }
        }

        FeasibilityAssessment assessment = FeasibilityAssessment.builder()
                .assessmentName(request.getAssessmentName())
                .rawRequirement(request.getRawRequirement())
                .imageUrls(imageUrlsJson)
                .status(AssessmentStatus.CREATED)
                .createdBy(user)
                .organization(organization)
                .build();

        FeasibilityAssessment savedAssessment = assessmentRepository.save(assessment);
        log.info("可行性评估创建成功: id={}", savedAssessment.getId());

        return convertToResponse(savedAssessment);
    }

    public FeasibilityAssessmentResponse getAssessment(Long id) {
        log.info("查询可行性评估详情: id={}", id);
        FeasibilityAssessment assessment = assessmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", id));
        return convertToResponse(assessment);
    }

    public List<FeasibilityAssessmentResponse> listAssessments(Long organizationId) {
        log.info("查询可行性评估列表: orgId={}", organizationId);
        List<FeasibilityAssessment> assessments;
        if (organizationId != null) {
            assessments = assessmentRepository.findByOrganization_IdOrderByCreatedAtDesc(organizationId);
        } else {
            assessments = assessmentRepository.findAllByOrderByCreatedAtDesc();
        }
        return assessments.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public FeasibilityAssessmentResponse updateStatus(Long id, AssessmentStatus status) {
        log.info("更新可行性评估状态: id={}, status={}", id, status);
        FeasibilityAssessment assessment = assessmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", id));

        assessment.setStatus(status);

        if (status == AssessmentStatus.COMPLETED) {
            assessment.setCompletedAt(java.time.LocalDateTime.now());
        }

        FeasibilityAssessment updatedAssessment = assessmentRepository.save(assessment);
        log.info("可行性评估状态更新成功: id={}, newStatus={}", id, status);

        return convertToResponse(updatedAssessment);
    }

    @Transactional
    public void deleteAssessment(Long id) {
        log.info("删除可行性评估: id={}", id);
        if (!assessmentRepository.existsById(id)) {
            throw new ResourceNotFoundException("FeasibilityAssessment", "id", id);
        }
        assessmentRepository.deleteById(id);
        log.info("可行性评估删除成功: id={}", id);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public FeasibilityAssessmentResponse parseAssessment(Long assessmentId, Long currentUserId) {
        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        assessment.setStatus(AssessmentStatus.PARSING);
        assessmentRepository.save(assessment);

        try {
            var config = userModelConfigService.getEffectiveConfig(currentUserId);

            String url = algorithmServiceUrl + "/api/v1/feasibility/parse-requirement";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = Map.of(
                    "raw_requirement", assessment.getRawRequirement(),
                    "llm_api_key", config.getLlmApiKey(),
                    "llm_base_url", config.getLlmBaseUrl(),
                    "llm_model_name", config.getLlmModelName()
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("算法服务返回非 2xx 或空响应");
            }

            Map<String, Object> parsed = response.getBody();
            assessment.setStructuredRequirement(com.alibaba.fastjson2.JSON.toJSONString(parsed));

            categoryAssessmentRepository.deleteByAssessmentId(assessmentId);

            Object categoriesObj = parsed.get("categories");
            if (!(categoriesObj instanceof List<?> categories)) {
                throw new IllegalStateException("算法服务响应缺少 categories 或格式不正确");
            }

            List<CategoryAssessment> toSave = categories.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> (Map<String, Object>) item)
                    .map(c -> CategoryAssessment.builder()
                            .assessment(assessment)
                            .categoryName(asString(c.get("categoryName")))
                            .categoryNameEn(asString(c.get("categoryNameEn")))
                            .categoryType(asString(c.get("categoryType")))
                            .sceneDescription(asString(c.get("sceneDescription")))
                            .viewAngle(asString(c.get("viewAngle")))
                            .build())
                    .filter(c -> c.getCategoryName() != null && !c.getCategoryName().isBlank())
                    .collect(Collectors.toList());

            categoryAssessmentRepository.saveAll(toSave);

            var ovdResults = ovdTestResultRepository.findByAssessmentIdOrderByTestTimeDesc(assessmentId);
            if (!ovdResults.isEmpty() && !toSave.isEmpty()) {
                var analysisMap = new java.util.LinkedHashMap<String, Object>();
                for (var ovd : ovdResults) {
                    String categoryName = ovd.getCategoryName();
                    String sceneDesc = toSave.stream()
                            .filter(c -> categoryName != null && categoryName.equals(c.getCategoryName()))
                            .map(CategoryAssessment::getSceneDescription)
                            .findFirst()
                            .orElse(toSave.get(0).getSceneDescription());

                    Object analysis = analyzeImageViaAlgorithmService(
                            ovd.getImagePath(),
                            categoryName != null ? categoryName : toSave.get(0).getCategoryName(),
                            sceneDesc,
                            config.getVlmApiKey(),
                            config.getVlmBaseUrl(),
                            config.getVlmModelName()
                    );
                    analysisMap.put(String.valueOf(ovd.getId()), analysis);
                }
                var enriched = new java.util.LinkedHashMap<String, Object>(parsed);
                enriched.put("imageAnalysisByOvdResultId", analysisMap);
                assessment.setStructuredRequirement(com.alibaba.fastjson2.JSON.toJSONString(enriched));
            }

            assessment.setStatus(AssessmentStatus.PARSED);
            FeasibilityAssessment saved = assessmentRepository.save(assessment);
            return convertToResponse(saved);
        } catch (Exception e) {
            log.error("需求解析失败: assessmentId={}, userId={}, error={}", assessmentId, currentUserId, e.getMessage(), e);
            assessment.setStatus(AssessmentStatus.FAILED);
            assessmentRepository.save(assessment);
            throw new BusinessException("FEASIBILITY_PARSE_FAILED", "需求解析失败: " + e.getMessage());
        }
    }

    @SneakyThrows
    private Object analyzeImageViaAlgorithmService(
            String imagePath,
            String categoryName,
            String sceneDescription,
            String vlmApiKey,
            String vlmBaseUrl,
            String vlmModelName
    ) {
        byte[] bytes = Files.readAllBytes(Path.of(imagePath));
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return Path.of(imagePath).getFileName().toString();
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", resource);
        body.add("category_name", categoryName);
        body.add("scene_description", sceneDescription);
        body.add("vlm_api_key", vlmApiKey);
        body.add("vlm_base_url", vlmBaseUrl);
        body.add("vlm_model_name", vlmModelName);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        String url = algorithmServiceUrl + "/api/v1/feasibility/analyze-image";
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("算法服务 analyze-image 返回非 2xx");
        }
        return response.getBody();
    }

    private String asString(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public FeasibilityAssessmentResponse runOvdTest(Long assessmentId, List<String> imagePaths, Long currentUserId) {
        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        ovdTestResultRepository.deleteByAssessmentId(assessmentId);
        log.info("已清除评估 {} 的旧OVD测试结果", assessmentId);

        assessment.setStatus(AssessmentStatus.OVD_TESTING);
        assessmentRepository.save(assessment);

        try {
            var config = userModelConfigService.getEffectiveConfig(currentUserId);
            
            List<CategoryAssessment> categories = categoryAssessmentRepository.findByAssessmentIdOrderByCreatedAtAsc(assessmentId);
            if (categories.isEmpty()) {
                throw new BusinessException("NO_CATEGORIES", "评估中没有类别，请先执行需求解析");
            }

            List<Map<String, Object>> allCategoryPrompts = new java.util.ArrayList<>();
            
            for (CategoryAssessment category : categories) {
                String categoryNameEn = category.getCategoryNameEn();
                
                Map<String, Object> categoryPrompt = new java.util.HashMap<>();
                categoryPrompt.put("categoryNameEn", categoryNameEn);
                categoryPrompt.put("prompts", List.of(categoryNameEn));
                allCategoryPrompts.add(categoryPrompt);
            }

            List<String> absoluteImagePaths = imagePaths.stream()
                    .map(path -> path.startsWith("/") ? path : basePath + "/" + path)
                    .collect(Collectors.toList());

            List<Map<String, Object>> ovdResults = runOvdTestViaAlgorithmService(allCategoryPrompts, absoluteImagePaths);

            for (Map<String, Object> result : ovdResults) {
                String categoryNameEn = asString(result.get("categoryNameEn"));
                String imagePath = asString(result.get("imagePath"));
                String bestPrompt = asString(result.get("bestPrompt"));
                Integer detectedCount = result.get("detectedCount") instanceof Number 
                        ? ((Number) result.get("detectedCount")).intValue() : 0;
                Double averageConfidence = result.get("averageConfidence") instanceof Number 
                        ? ((Number) result.get("averageConfidence")).doubleValue() : 0.0;
                String bboxJson = asString(result.get("bboxJson"));
                String annotatedImagePath = asString(result.get("annotatedImagePath"));

                if (annotatedImagePath != null && annotatedImagePath.startsWith(basePath + "/")) {
                    annotatedImagePath = annotatedImagePath.substring(basePath.length() + 1);
                }

                String categoryName = categories.stream()
                        .filter(c -> categoryNameEn != null && categoryNameEn.equals(c.getCategoryNameEn()))
                        .map(CategoryAssessment::getCategoryName)
                        .findFirst()
                        .orElse(categoryNameEn);

                var ovdTestResult = com.annotation.platform.entity.OvdTestResult.builder()
                        .assessment(assessment)
                        .categoryName(categoryName)
                        .imagePath(imagePath)
                        .promptUsed(bestPrompt)
                        .detectedCount(detectedCount)
                        .averageConfidence(averageConfidence)
                        .bboxJson(bboxJson != null ? bboxJson : "{}")
                        .annotatedImagePath(annotatedImagePath)
                        .build();
                
                ovdTestResultRepository.save(ovdTestResult);
            }

            assessment.setStatus(AssessmentStatus.OVD_TESTED);
            FeasibilityAssessment saved = assessmentRepository.save(assessment);
            log.info("OVD测试完成: assessmentId={}, 结果数={}", assessmentId, ovdResults.size());
            return convertToResponse(saved);

        } catch (Exception e) {
            log.error("OVD测试失败: assessmentId={}, error={}", assessmentId, e.getMessage(), e);
            assessment.setStatus(AssessmentStatus.FAILED);
            assessmentRepository.save(assessment);
            throw new BusinessException("OVD_TEST_FAILED", "OVD测试失败: " + e.getMessage());
        }
    }

    private List<String> generatePromptsViaAlgorithmService(
            String categoryNameEn,
            String sceneDescription,
            String llmApiKey,
            String llmBaseUrl,
            String llmModelName
    ) {
        String url = algorithmServiceUrl + "/api/v1/feasibility/generate-prompts";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("categoryNameEn", categoryNameEn);
        requestBody.put("sceneDescription", sceneDescription);
        requestBody.put("llm_api_key", llmApiKey);
        requestBody.put("llm_base_url", llmBaseUrl);
        requestBody.put("llm_model_name", llmModelName);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object promptsObj = response.getBody().get("prompts");
                if (promptsObj instanceof List<?> promptsList) {
                    return promptsList.stream()
                            .map(String::valueOf)
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.warn("生成prompts失败，使用默认值: {}", e.getMessage());
        }
        
        return List.of(categoryNameEn);
    }

    private List<Map<String, Object>> runOvdTestViaAlgorithmService(
            List<Map<String, Object>> categories,
            List<String> imagePaths
    ) {
        String url = algorithmServiceUrl + "/api/v1/feasibility/run-ovd-test";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("categories", categories);
        requestBody.put("imagePaths", imagePaths);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("算法服务 run-ovd-test 返回非 2xx 或空响应");
        }

        Object resultsObj = response.getBody().get("results");
        if (!(resultsObj instanceof List<?> resultsList)) {
            throw new IllegalStateException("算法服务响应缺少 results 或格式不正确");
        }

        return resultsList.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<String, Object>) item)
                .collect(Collectors.toList());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public Map<String, Object> evaluateAssessment(Long assessmentId, Long currentUserId) {
        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        assessment.setStatus(AssessmentStatus.EVALUATING);
        assessmentRepository.save(assessment);

        try {
            var config = userModelConfigService.getEffectiveConfig(currentUserId);
            
            var ovdResults = ovdTestResultRepository.findByAssessmentIdOrderByTestTimeDesc(assessmentId);
            if (ovdResults.isEmpty()) {
                throw new BusinessException("NO_OVD_RESULTS", "评估中没有OVD测试结果，请先执行OVD测试");
            }

            for (var ovdResult : ovdResults) {
                Map<String, Object> vlmEvaluation = evaluateOvdResultViaVlm(
                        ovdResult.getImagePath(),
                        ovdResult.getAnnotatedImagePath(),
                        ovdResult.getPromptUsed(),
                        ovdResult.getBboxJson(),
                        config.getVlmApiKey(),
                        config.getVlmBaseUrl(),
                        config.getVlmModelName()
                );

                var qualityScore = com.annotation.platform.entity.VlmQualityScore.builder()
                        .ovdTestResult(ovdResult)
                        .totalGtEstimated(getIntValue(vlmEvaluation, "totalGtEstimated"))
                        .detected(getIntValue(vlmEvaluation, "detected"))
                        .falsePositive(getIntValue(vlmEvaluation, "falsePositive"))
                        .precisionEstimate(getDoubleValue(vlmEvaluation, "precisionEstimate"))
                        .recallEstimate(getDoubleValue(vlmEvaluation, "recallEstimate"))
                        .bboxQuality(asString(vlmEvaluation.get("bboxQuality")))
                        .overallVerdict(asString(vlmEvaluation.get("overallVerdict")))
                        .notes(asString(vlmEvaluation.get("notes")))
                        .build();
                
                Object detailResultsObj = vlmEvaluation.get("detailResults");
                if (detailResultsObj instanceof java.util.List) {
                    java.util.List<?> detailResults = (java.util.List<?>) detailResultsObj;
                    for (Object detailObj : detailResults) {
                        if (detailObj instanceof Map) {
                            Map<?, ?> detail = (Map<?, ?>) detailObj;
                            
                            String croppedPath = asString(detail.get("cropped_image_path"));
                            if (croppedPath != null && croppedPath.startsWith(basePath + "/")) {
                                croppedPath = croppedPath.substring(basePath.length() + 1);
                            }
                            
                            var evaluationDetail = com.annotation.platform.entity.VlmEvaluationDetail.builder()
                                    .qualityScore(qualityScore)
                                    .bboxIdx(getIntValue(detail, "bbox_idx"))
                                    .isCorrect(getBooleanValue(detail, "is_correct"))
                                    .croppedImagePath(croppedPath)
                                    .question(asString(detail.get("question")))
                                    .vlmAnswer(asString(detail.get("vlm_answer")))
                                    .bboxJson(asString(detail.get("bbox")) != null ? 
                                            new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail.get("bbox")) : null)
                                    .errorReason(asString(detail.get("reason")))
                                    .build();
                            
                            qualityScore.getEvaluationDetails().add(evaluationDetail);
                        }
                    }
                }
                
                ovdResult.getQualityScores().add(qualityScore);
                ovdTestResultRepository.save(ovdResult);
            }

            List<CategoryAssessment> categories = categoryAssessmentRepository.findByAssessmentIdOrderByCreatedAtAsc(assessmentId);
            List<Map<String, Object>> bucketResults = new java.util.ArrayList<>();

            for (CategoryAssessment category : categories) {
                var categoryOvdResults = ovdResults.stream()
                        .filter(ovd -> category.getCategoryName().equals(ovd.getCategoryName()))
                        .collect(Collectors.toList());

                if (categoryOvdResults.isEmpty()) {
                    category.setFeasibilityBucket(com.annotation.platform.entity.FeasibilityBucket.PENDING);
                    category.setConfidence(0.5);
                    category.setReasoning("无OVD测试结果，需进行数据集检索后判断");
                    categoryAssessmentRepository.save(category);
                    continue;
                }

                double avgPrecision = categoryOvdResults.stream()
                        .flatMap(ovd -> ovd.getQualityScores().stream())
                        .mapToDouble(com.annotation.platform.entity.VlmQualityScore::getPrecisionEstimate)
                        .average()
                        .orElse(0.0);

                long totalDetected = categoryOvdResults.stream()
                        .mapToLong(ovd -> ovd.getDetectedCount() != null ? ovd.getDetectedCount() : 0)
                        .sum();

                String categoryType = category.getCategoryType() != null ? category.getCategoryType() : "";
                
                com.annotation.platform.entity.FeasibilityBucket bucket;
                double confidence;
                String reasoning;

                if (avgPrecision > 0.7) {
                    bucket = com.annotation.platform.entity.FeasibilityBucket.OVD_AVAILABLE;
                    confidence = avgPrecision;
                    reasoning = String.format("OVD检测效果良好，准确率%.1f%%，可直接使用", avgPrecision * 100);
                } else {
                    bucket = com.annotation.platform.entity.FeasibilityBucket.PENDING;
                    confidence = avgPrecision;
                    reasoning = String.format("需要定制训练，当前准确率%.1f%%，需进行数据集检索后由用户判断", avgPrecision * 100);
                }

                category.setFeasibilityBucket(bucket);
                category.setConfidence(confidence);
                category.setReasoning(reasoning);
                categoryAssessmentRepository.save(category);

                Map<String, Object> bucketResult = new java.util.HashMap<>();
                bucketResult.put("categoryName", category.getCategoryName());
                bucketResult.put("bucket", bucket.name());
                bucketResult.put("confidence", Math.round(confidence * 100) / 100.0);
                bucketResults.add(bucketResult);
            }

            boolean allBucketA = categories.stream()
                    .allMatch(c -> c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.OVD_AVAILABLE);

            if (allBucketA) {
                assessment.setStatus(AssessmentStatus.EVALUATED);
            } else {
                assessment.setStatus(AssessmentStatus.EVALUATED);
            }
            assessmentRepository.save(assessment);
            
            log.info("VLM评估完成: assessmentId={}, 分类结果数={}", assessmentId, bucketResults.size());

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("buckets", bucketResults);
            return result;

        } catch (Exception e) {
            log.error("VLM评估失败: assessmentId={}, error={}", assessmentId, e.getMessage(), e);
            assessment.setStatus(AssessmentStatus.FAILED);
            assessmentRepository.save(assessment);
            throw new BusinessException("VLM_EVALUATION_FAILED", "VLM评估失败: " + e.getMessage());
        }
    }

    private Map<String, Object> evaluateOvdResultViaVlm(
            String imagePath,
            String annotatedImagePath,
            String promptUsed,
            String bboxJson,
            String vlmApiKey,
            String vlmBaseUrl,
            String vlmModelName
    ) {
        String url = algorithmServiceUrl + "/api/v1/feasibility/vlm-evaluate";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("imagePath", imagePath);
        requestBody.put("annotatedImagePath", annotatedImagePath);
        requestBody.put("categoryName", promptUsed);
        requestBody.put("bboxJson", bboxJson != null ? bboxJson : "[]");
        requestBody.put("vlm_api_key", vlmApiKey);
        requestBody.put("vlm_base_url", vlmBaseUrl);
        requestBody.put("vlm_model_name", vlmModelName);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.warn("VLM评估调用失败，使用默认值: {}", e.getMessage());
        }
        
        Map<String, Object> defaultEval = new java.util.HashMap<>();
        defaultEval.put("totalGtEstimated", 1);
        defaultEval.put("detected", 0);
        defaultEval.put("falsePositive", 0);
        defaultEval.put("precisionEstimate", 0.5);
        defaultEval.put("recallEstimate", 0.5);
        defaultEval.put("bboxQuality", "FAIR");
        defaultEval.put("overallVerdict", "partially_feasible");
        defaultEval.put("notes", "VLM服务不可用");
        return defaultEval;
    }

    private Integer getIntValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private Double getDoubleValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    private Boolean getBooleanValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return false;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public FeasibilityAssessmentResponse searchDatasets(Long assessmentId, Long currentUserId) {
        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        try {
            List<CategoryAssessment> categories = categoryAssessmentRepository.findByAssessmentIdOrderByCreatedAtAsc(assessmentId);
            if (categories.isEmpty()) {
                throw new BusinessException("NO_CATEGORIES", "评估中没有类别，请先执行需求解析");
            }

            datasetSearchResultRepository.deleteByAssessmentId(assessmentId);
            log.info("已清除评估 {} 的旧数据集检索结果", assessmentId);

            List<Map<String, Object>> categoryDataForSearch = categories.stream()
                    .filter(cat -> cat.getFeasibilityBucket() != com.annotation.platform.entity.FeasibilityBucket.OVD_AVAILABLE)
                    .map(cat -> {
                        Map<String, Object> catMap = new java.util.HashMap<>();
                        catMap.put("categoryNameEn", cat.getCategoryNameEn());
                        catMap.put("categoryName", cat.getCategoryName());
                        return catMap;
                    })
                    .collect(Collectors.toList());

            if (!categoryDataForSearch.isEmpty()) {
                List<Map<String, Object>> datasetSearchResults = searchDatasetsViaAlgorithmService(categoryDataForSearch);

                for (Map<String, Object> result : datasetSearchResults) {
                    String categoryName = asString(result.get("categoryName"));
                    String searchUrl = asString(result.get("searchUrl"));
                    Object datasetsObj = result.get("datasets");
                    
                    if (datasetsObj instanceof List<?>) {
                        List<?> datasets = (List<?>) datasetsObj;
                        
                        if (datasets.isEmpty() && searchUrl != null) {
                            // 即使没有数据集结果，也保存searchUrl以便前端显示Roboflow跳转按钮
                            var placeholderResult = com.annotation.platform.entity.DatasetSearchResult.builder()
                                    .assessment(assessment)
                                    .categoryName(categoryName)
                                    .searchUrl(searchUrl)
                                    .source("Roboflow")
                                    .datasetName("未找到公开数据集")
                                    .relevanceScore(0.0)
                                    .build();
                            datasetSearchResultRepository.save(placeholderResult);
                            log.info("保存searchUrl占位记录: categoryName={}, searchUrl={}", categoryName, searchUrl);
                        } else {
                            for (Object datasetObj : datasets) {
                                if (datasetObj instanceof Map) {
                                    Map<String, Object> dataset = (Map<String, Object>) datasetObj;
                                    
                                    var datasetSearchResult = com.annotation.platform.entity.DatasetSearchResult.builder()
                                            .assessment(assessment)
                                            .categoryName(categoryName)
                                            .searchUrl(searchUrl)
                                            .source(asString(dataset.get("source")))
                                            .datasetName(asString(dataset.get("datasetName")))
                                            .datasetUrl(asString(dataset.get("datasetUrl")))
                                            .sampleCount(getIntValue(dataset, "sampleCount"))
                                            .annotationFormat(asString(dataset.get("annotationFormat")))
                                            .license(asString(dataset.get("license")))
                                            .relevanceScore(getDoubleValue(dataset, "relevanceScore"))
                                            .build();
                                    
                                    datasetSearchResultRepository.save(datasetSearchResult);
                                }
                            }
                        }
                    }
                }
            }

            assessment.setStatus(AssessmentStatus.DATASET_SEARCHED);
            FeasibilityAssessment saved = assessmentRepository.save(assessment);
            
            log.info("数据集检索完成: assessmentId={}, 等待用户判断数据集匹配度", assessmentId);
            
            return convertToResponse(saved);

        } catch (Exception e) {
            log.error("数据集检索失败: assessmentId={}, error={}", assessmentId, e.getMessage(), e);
            assessment.setStatus(AssessmentStatus.FAILED);
            assessmentRepository.save(assessment);
            throw new BusinessException("DATASET_SEARCH_FAILED", "数据集检索失败: " + e.getMessage());
        }
    }

    @Transactional
    public FeasibilityAssessmentResponse submitUserJudgment(Long assessmentId, UserJudgmentRequest request) {
        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        if (assessment.getStatus() != AssessmentStatus.DATASET_SEARCHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不允许提交用户判断");
        }

        assessment.setDatasetMatchLevel(request.getDatasetMatchLevel());
        assessment.setUserJudgmentNotes(request.getUserNotes());

        FeasibilityBucket targetBucket = mapBucketByMatchLevel(request.getDatasetMatchLevel());
        List<CategoryAssessment> categories = categoryAssessmentRepository.findByAssessmentIdOrderByCreatedAtAsc(assessmentId);
        categories.stream()
                .filter(category -> category.getFeasibilityBucket() != FeasibilityBucket.OVD_AVAILABLE)
                .forEach(category -> {
                    category.setFeasibilityBucket(targetBucket);
                    categoryAssessmentRepository.save(category);
                });

        assessment.setStatus(AssessmentStatus.AWAITING_USER_JUDGMENT);
        FeasibilityAssessment saved = assessmentRepository.save(assessment);
        return convertToResponse(saved);
    }

    private FeasibilityBucket mapBucketByMatchLevel(DatasetMatchLevel datasetMatchLevel) {
        return switch (datasetMatchLevel) {
            case ALMOST_MATCH -> FeasibilityBucket.CUSTOM_LOW;
            case PARTIAL_MATCH -> FeasibilityBucket.CUSTOM_MEDIUM;
            case NOT_USABLE -> FeasibilityBucket.CUSTOM_HIGH;
        };
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public FeasibilityAssessmentResponse estimateResources(Long assessmentId, Long currentUserId) {
        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        if (!AssessmentStatus.AWAITING_USER_JUDGMENT.equals(assessment.getStatus())) {
            throw new BusinessException("INVALID_STATUS", "只能在AWAITING_USER_JUDGMENT状态下进行资源估算");
        }

        try {
            List<CategoryAssessment> categories = categoryAssessmentRepository.findByAssessmentIdOrderByCreatedAtAsc(assessmentId);
            if (categories.isEmpty()) {
                throw new BusinessException("NO_CATEGORIES", "评估中没有类别，请先执行需求解析");
            }

            assessment.setStatus(AssessmentStatus.ESTIMATING);
            assessmentRepository.save(assessment);

            // 删除旧的资源估算记录
            resourceEstimationRepository.deleteByAssessmentId(assessmentId);

            // 为每个类别准备资源估算请求数据，包含数据集信息
            List<Map<String, Object>> categoryDataForEstimation = new java.util.ArrayList<>();
            for (CategoryAssessment category : categories) {
                Map<String, Object> catMap = new java.util.HashMap<>();
                catMap.put("categoryName", category.getCategoryName());
                catMap.put("feasibilityBucket", category.getFeasibilityBucket().name());
                // 使用默认复杂度和多样性值
                catMap.put("sceneComplexity", "medium");
                catMap.put("sceneDiversity", "medium");
                
                // 添加数据集匹配度和可用样本数
                if (assessment.getDatasetMatchLevel() != null) {
                    catMap.put("datasetMatchLevel", assessment.getDatasetMatchLevel().name());
                } else {
                    catMap.put("datasetMatchLevel", null);
                }
                
                // 查询该类别的数据集总样本数
                int totalSamples = datasetSearchResultRepository.findByAssessmentId(assessmentId).stream()
                        .filter(ds -> category.getCategoryName().equals(ds.getCategoryName()))
                        .mapToInt(ds -> ds.getSampleCount() != null ? ds.getSampleCount() : 0)
                        .sum();
                catMap.put("availablePublicSamples", totalSamples);
                
                categoryDataForEstimation.add(catMap);
            }

            // 调用算法服务进行资源估算
            List<Map<String, Object>> estimationResults = estimateResourcesViaAlgorithmService(categoryDataForEstimation);

            // 保存资源估算结果
            for (Map<String, Object> result : estimationResults) {
                String categoryName = asString(result.get("categoryName"));
                CategoryAssessment category = categories.stream()
                        .filter(c -> c.getCategoryName().equals(categoryName))
                        .findFirst()
                        .orElse(null);

                if (category != null) {
                    var resourceEstimation = ResourceEstimation.builder()
                            .assessment(assessment)
                            .categoryName(categoryName)
                            .feasibilityBucket(category.getFeasibilityBucket())
                            .estimatedImages(getIntValue(result, "estimatedImages"))
                            .estimatedManDays(getIntValue(result, "estimatedManDays"))
                            .gpuHours(getIntValue(result, "gpuHours"))
                            .iterationCount(getIntValue(result, "iterationCount"))
                            .estimatedTotalDays(getIntValue(result, "estimatedTotalDays"))
                            .publicDatasetImages(getIntValue(result, "publicDatasetImages"))
                            .trainingApproach(asString(result.get("trainingApproach")))
                            .notes(asString(result.get("notes")))
                            .build();

                    resourceEstimationRepository.save(resourceEstimation);
                }
            }

            assessment.setStatus(AssessmentStatus.COMPLETED);
            assessment.setCompletedAt(java.time.LocalDateTime.now());
            FeasibilityAssessment saved = assessmentRepository.save(assessment);
            
            log.info("资源估算完成: assessmentId={}, 估算类别数={}", assessmentId, estimationResults.size());
            
            return convertToResponse(saved);

        } catch (Exception e) {
            log.error("资源估算失败: assessmentId={}, error={}", assessmentId, e.getMessage(), e);
            assessment.setStatus(AssessmentStatus.FAILED);
            assessmentRepository.save(assessment);
            throw new BusinessException("RESOURCE_ESTIMATION_FAILED", "资源估算失败: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> searchDatasetsViaAlgorithmService(List<Map<String, Object>> categories) {
        String url = algorithmServiceUrl + "/api/v1/feasibility/search-datasets";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("categories", categories);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object resultsObj = response.getBody().get("results");
                if (resultsObj instanceof List<?>) {
                    return ((List<?>) resultsObj).stream()
                            .filter(item -> item instanceof Map)
                            .map(item -> (Map<String, Object>) item)
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.warn("数据集检索调用失败: {}", e.getMessage());
        }
        
        return new java.util.ArrayList<>();
    }

    private List<Map<String, Object>> estimateResourcesViaAlgorithmService(List<Map<String, Object>> categories) {
        String url = algorithmServiceUrl + "/api/v1/feasibility/estimate-resources";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("categories", categories);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("算法服务 estimate-resources 返回非 2xx 或空响应");
        }

        Object resultsObj = response.getBody().get("results");
        if (!(resultsObj instanceof List<?>)) {
            throw new IllegalStateException("算法服务响应缺少 results 或格式不正确");
        }

        return ((List<?>) resultsObj).stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<String, Object>) item)
                .collect(Collectors.toList());
    }

    private FeasibilityAssessmentResponse convertToResponse(FeasibilityAssessment assessment) {
        List<String> imageUrls = null;
        if (assessment.getImageUrls() != null && !assessment.getImageUrls().isEmpty()) {
            try {
                imageUrls = objectMapper.readValue(assessment.getImageUrls(), 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception e) {
                log.error("反序列化imageUrls失败: {}", e.getMessage());
            }
        }

        return FeasibilityAssessmentResponse.builder()
                .id(assessment.getId())
                .assessmentName(assessment.getAssessmentName())
                .rawRequirement(assessment.getRawRequirement())
                .structuredRequirement(assessment.getStructuredRequirement())
                .imageUrls(imageUrls)
                .status(assessment.getStatus())
                .createdAt(assessment.getCreatedAt())
                .updatedAt(assessment.getUpdatedAt())
                .completedAt(assessment.getCompletedAt())
                .datasetMatchLevel(assessment.getDatasetMatchLevel())
                .userJudgmentNotes(assessment.getUserJudgmentNotes())
                .createdBy(assessment.getCreatedBy() != null ? assessment.getCreatedBy().getId() : null)
                .createdByUsername(assessment.getCreatedBy() != null ? assessment.getCreatedBy().getDisplayName() : null)
                .organizationId(assessment.getOrganization() != null ? assessment.getOrganization().getId() : null)
                .organizationName(assessment.getOrganization() != null ? assessment.getOrganization().getName() : null)
                .build();
    }

    @Transactional
    public Map<String, Object> generateImplementationPlan(Long assessmentId, Long currentUserId) {
        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        List<CategoryAssessment> categories = categoryAssessmentRepository.findByAssessmentIdOrderByCreatedAtAsc(assessmentId);
        if (categories.isEmpty()) {
            throw new BusinessException("NO_CATEGORIES", "评估中没有类别，请先执行需求解析");
        }

        List<ResourceEstimation> resources = resourceEstimationRepository.findByAssessmentId(assessmentId);

        boolean hasBucketA = categories.stream()
                .anyMatch(c -> c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.OVD_AVAILABLE);
        boolean hasCustomBuckets = categories.stream()
                .anyMatch(c -> c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_LOW
                        || c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_MEDIUM
                        || c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_HIGH);

        implementationPlanRepository.deleteByAssessmentId(assessmentId);

        List<com.annotation.platform.entity.ImplementationPlan> plans = new java.util.ArrayList<>();
        int phaseOrder = 1;
        List<String> allPhaseNames = new java.util.ArrayList<>();

        if (hasCustomBuckets) {
            String phaseName1 = "数据采集方案设计";
            plans.add(com.annotation.platform.entity.ImplementationPlan.builder()
                    .assessment(assessment)
                    .phaseOrder(phaseOrder++)
                    .phaseName(phaseName1)
                    .description("针对需定制训练的类别，设计数据采集方案，确定采集设备、场景覆盖和采集量目标")
                    .estimatedDays(12)
                    .tasks("[\"确定采集设备(相机型号/分辨率/帧率)\",\"规划采集场景覆盖(不同时段/光照/工况)\",\"制定采集量目标\"]")
                    .deliverables("数据采集方案文档、设备清单")
                    .dependencies("")
                    .build());
            allPhaseNames.add(phaseName1);

            int maxManDays = resources.stream()
                    .filter(r -> categories.stream()
                            .anyMatch(c -> c.getCategoryName().equals(r.getCategoryName()) 
                                    && (c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_LOW
                                        || c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_MEDIUM
                                        || c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_HIGH)))
                    .mapToInt(r -> r.getEstimatedManDays() != null ? r.getEstimatedManDays() : 0)
                    .max()
                    .orElse(10);
            int annotationDays = maxManDays + 7;

            String phaseName2 = "数据标注";
            plans.add(com.annotation.platform.entity.ImplementationPlan.builder()
                    .assessment(assessment)
                    .phaseOrder(phaseOrder++)
                    .phaseName(phaseName2)
                    .description("对采集的数据进行标注，包括制定标注规范、选择标注工具、批量标注和质检")
                    .estimatedDays(annotationDays)
                    .tasks("[\"制定标注规范(类别定义/边界框标准)\",\"选择标注工具(CVAT/LabelStudio)\",\"批量标注\",\"交叉标注质检\"]")
                    .deliverables("标注完成的数据集")
                    .dependencies(phaseName1)
                    .build());
            allPhaseNames.add(phaseName2);

            int maxGpuHours = resources.stream()
                    .filter(r -> categories.stream()
                            .anyMatch(c -> c.getCategoryName().equals(r.getCategoryName()) 
                                    && (c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_LOW
                                        || c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_MEDIUM
                                        || c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_HIGH)))
                    .mapToInt(r -> r.getGpuHours() != null ? r.getGpuHours() : 0)
                    .max()
                    .orElse(10);
            int trainingDays = (maxGpuHours / 8) + 5;

            String phaseName3 = "模型训练与调优";
            plans.add(com.annotation.platform.entity.ImplementationPlan.builder()
                    .assessment(assessment)
                    .phaseOrder(phaseOrder++)
                    .phaseName(phaseName3)
                    .description("选择基础模型，配置训练参数，进行训练和调优")
                    .estimatedDays(trainingDays)
                    .tasks("[\"选择基础模型(YOLOv8/RT-DETR)\",\"配置训练参数\",\"首轮训练\",\"评估与bad case分析\",\"数据补充与再训练\"]")
                    .deliverables("训练好的模型权重、评估报告")
                    .dependencies(phaseName2)
                    .build());
            allPhaseNames.add(phaseName3);
        }

        if (hasBucketA) {
            String phaseName4 = "OVD配置与校验";
            plans.add(com.annotation.platform.entity.ImplementationPlan.builder()
                    .assessment(assessment)
                    .phaseOrder(phaseOrder++)
                    .phaseName(phaseName4)
                    .description("配置GroundingDINO检测参数，校准prompt，搭建VLM校验流程")
                    .estimatedDays(4)
                    .tasks("[\"配置GroundingDINO检测参数\",\"校准prompt\",\"VLM校验流程搭建\",\"人工抽检验证\"]")
                    .deliverables("OVD检测配置、校验报告")
                    .dependencies("")
                    .build());
            allPhaseNames.add(phaseName4);
        }


        String integrationDeps = String.join(",", allPhaseNames);
        String phaseName6 = "系统集成测试";
        plans.add(com.annotation.platform.entity.ImplementationPlan.builder()
                .assessment(assessment)
                .phaseOrder(phaseOrder++)
                .phaseName(phaseName6)
                .description("端到端流程测试，性能压力测试，边界条件测试")
                .estimatedDays(7)
                .tasks("[\"端到端流程测试\",\"性能压力测试\",\"边界条件测试\",\"异常处理验证\"]")
                .deliverables("测试报告")
                .dependencies(integrationDeps)
                .build());
        allPhaseNames.add(phaseName6);

        String phaseName7 = "部署上线";
        plans.add(com.annotation.platform.entity.ImplementationPlan.builder()
                .assessment(assessment)
                .phaseOrder(phaseOrder++)
                .phaseName(phaseName7)
                .description("模型部署(边缘/云端)，推理优化，系统集成")
                .estimatedDays(4)
                .tasks("[\"模型部署(边缘/云端)\",\"推理优化(TensorRT/量化)\",\"系统集成\",\"上线验收\"]")
                .deliverables("部署文档、上线报告")
                .dependencies(phaseName6)
                .build());
        allPhaseNames.add(phaseName7);

        String phaseName8 = "持续运维";
        plans.add(com.annotation.platform.entity.ImplementationPlan.builder()
                .assessment(assessment)
                .phaseOrder(phaseOrder++)
                .phaseName(phaseName8)
                .description("持续监控模型漂移，增量学习机制，误报/漏报反馈闭环")
                .estimatedDays(0)
                .tasks("[\"模型漂移监控\",\"增量学习机制\",\"误报/漏报反馈闭环\",\"定期评估\"]")
                .deliverables("运维SOP文档")
                .dependencies(phaseName7)
                .build());

        implementationPlanRepository.saveAll(plans);

        int totalEstimatedDays = plans.stream()
                .filter(p -> p.getEstimatedDays() != null && p.getEstimatedDays() > 0)
                .mapToInt(com.annotation.platform.entity.ImplementationPlan::getEstimatedDays)
                .sum();

        log.info("实施计划生成完成: assessmentId={}, totalPhases={}, totalDays={}", 
                assessmentId, plans.size(), totalEstimatedDays);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("assessmentId", assessmentId);
        result.put("totalPhases", plans.size());
        result.put("totalEstimatedDays", totalEstimatedDays);
        result.put("phases", plans.stream().map(this::convertPlanToMap).collect(Collectors.toList()));

        return result;
    }

    private Map<String, Object> convertPlanToMap(com.annotation.platform.entity.ImplementationPlan plan) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", plan.getId());
        map.put("phaseOrder", plan.getPhaseOrder());
        map.put("phaseName", plan.getPhaseName());
        map.put("description", plan.getDescription());
        map.put("estimatedDays", plan.getEstimatedDays());
        map.put("tasks", plan.getTasks());
        map.put("deliverables", plan.getDeliverables());
        map.put("dependencies", plan.getDependencies());
        return map;
    }

    public List<com.annotation.platform.entity.ImplementationPlan> getImplementationPlans(Long assessmentId) {
        return implementationPlanRepository.findByAssessmentIdOrderByPhaseOrderAsc(assessmentId);
    }

    @Transactional
    public Map<String, Object> generateReport(Long assessmentId, Long currentUserId) {
        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        List<CategoryAssessment> categories = categoryAssessmentRepository.findByAssessmentIdOrderByCreatedAtAsc(assessmentId);
        List<com.annotation.platform.entity.OvdTestResult> ovdResults = ovdTestResultRepository.findByAssessmentIdOrderByTestTimeDesc(assessmentId);
        List<com.annotation.platform.entity.DatasetSearchResult> datasets = datasetSearchResultRepository.findByAssessmentIdOrderByRelevanceScoreDesc(assessmentId);
        List<ResourceEstimation> resources = resourceEstimationRepository.findByAssessmentId(assessmentId);
        List<com.annotation.platform.entity.ImplementationPlan> plans = implementationPlanRepository.findByAssessmentIdOrderByPhaseOrderAsc(assessmentId);

        Map<String, Object> report = new java.util.HashMap<>();

        Map<String, Object> assessmentInfo = new java.util.HashMap<>();
        assessmentInfo.put("id", assessment.getId());
        assessmentInfo.put("assessmentName", assessment.getAssessmentName());
        assessmentInfo.put("rawRequirement", assessment.getRawRequirement());
        assessmentInfo.put("structuredRequirement", assessment.getStructuredRequirement());
        assessmentInfo.put("status", assessment.getStatus());
        assessmentInfo.put("createdAt", assessment.getCreatedAt());
        assessmentInfo.put("completedAt", assessment.getCompletedAt());
        report.put("assessmentInfo", assessmentInfo);

        List<Map<String, Object>> categoryReports = new java.util.ArrayList<>();
        for (CategoryAssessment category : categories) {
            Map<String, Object> categoryReport = new java.util.HashMap<>();
            categoryReport.put("categoryName", category.getCategoryName());
            categoryReport.put("categoryNameEn", category.getCategoryNameEn());
            categoryReport.put("categoryType", category.getCategoryType());
            categoryReport.put("feasibilityBucket", category.getFeasibilityBucket());
            categoryReport.put("confidence", category.getConfidence());
            categoryReport.put("reasoning", category.getReasoning());

            List<Map<String, Object>> ovdResultsList = ovdResults.stream()
                    .filter(r -> r.getCategoryName().equals(category.getCategoryName()))
                    .map(r -> {
                        Map<String, Object> ovdMap = new java.util.HashMap<>();
                        ovdMap.put("imagePath", r.getImagePath());
                        ovdMap.put("promptUsed", r.getPromptUsed());
                        ovdMap.put("detectedCount", r.getDetectedCount());
                        ovdMap.put("averageConfidence", r.getAverageConfidence());
                        ovdMap.put("annotatedImagePath", r.getAnnotatedImagePath());
                        return ovdMap;
                    })
                    .collect(Collectors.toList());
            categoryReport.put("ovdResults", ovdResultsList);

            List<Map<String, Object>> qualityScoresList = ovdResults.stream()
                    .filter(r -> r.getCategoryName().equals(category.getCategoryName()))
                    .flatMap(r -> r.getQualityScores().stream())
                    .map(score -> {
                        Map<String, Object> scoreMap = new java.util.HashMap<>();
                        scoreMap.put("totalGtEstimated", score.getTotalGtEstimated());
                        scoreMap.put("detected", score.getDetected());
                        scoreMap.put("falsePositive", score.getFalsePositive());
                        scoreMap.put("precisionEstimate", score.getPrecisionEstimate());
                        scoreMap.put("recallEstimate", score.getRecallEstimate());
                        scoreMap.put("bboxQuality", score.getBboxQuality());
                        scoreMap.put("overallVerdict", score.getOverallVerdict());
                        return scoreMap;
                    })
                    .collect(Collectors.toList());
            categoryReport.put("qualityScores", qualityScoresList);

            List<Map<String, Object>> datasetsList = datasets.stream()
                    .filter(d -> d.getCategoryName().equals(category.getCategoryName()))
                    .map(d -> {
                        Map<String, Object> datasetMap = new java.util.HashMap<>();
                        datasetMap.put("source", d.getSource());
                        datasetMap.put("datasetName", d.getDatasetName());
                        datasetMap.put("datasetUrl", d.getDatasetUrl());
                        datasetMap.put("sampleCount", d.getSampleCount());
                        datasetMap.put("relevanceScore", d.getRelevanceScore());
                        return datasetMap;
                    })
                    .collect(Collectors.toList());
            categoryReport.put("datasets", datasetsList);

            Map<String, Object> resourceEstimation = resources.stream()
                    .filter(r -> r.getCategoryName().equals(category.getCategoryName()))
                    .findFirst()
                    .map(r -> {
                        Map<String, Object> resMap = new java.util.HashMap<>();
                        resMap.put("estimatedImages", r.getEstimatedImages());
                        resMap.put("estimatedManDays", r.getEstimatedManDays());
                        resMap.put("gpuHours", r.getGpuHours());
                        resMap.put("estimatedTotalDays", r.getEstimatedTotalDays());
                        resMap.put("notes", r.getNotes());
                        return resMap;
                    })
                    .orElse(null);
            categoryReport.put("resourceEstimation", resourceEstimation);

            categoryReports.add(categoryReport);
        }
        report.put("categories", categoryReports);

        List<Map<String, Object>> plansList = plans.stream()
                .map(p -> {
                    Map<String, Object> planMap = new java.util.HashMap<>();
                    planMap.put("phaseOrder", p.getPhaseOrder());
                    planMap.put("phaseName", p.getPhaseName());
                    planMap.put("estimatedDays", p.getEstimatedDays());
                    planMap.put("tasks", p.getTasks());
                    planMap.put("deliverables", p.getDeliverables());
                    return planMap;
                })
                .collect(Collectors.toList());
        report.put("implementationPlan", plansList);

        Map<String, Object> summary = new java.util.HashMap<>();
        summary.put("totalCategories", categories.size());
        
        long bucketA = categories.stream()
                .filter(c -> c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.OVD_AVAILABLE)
                .count();
        long bucketBLow = categories.stream()
                .filter(c -> c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_LOW)
                .count();
        long bucketBMedium = categories.stream()
                .filter(c -> c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_MEDIUM)
                .count();
        long bucketCHigh = categories.stream()
                .filter(c -> c.getFeasibilityBucket() == com.annotation.platform.entity.FeasibilityBucket.CUSTOM_HIGH)
                .count();
        
        summary.put("bucketA", bucketA);
        summary.put("bucketBLow", bucketBLow);
        summary.put("bucketBMedium", bucketBMedium);
        summary.put("bucketCHigh", bucketCHigh);

        int totalEstimatedDays = plans.stream()
                .filter(p -> p.getEstimatedDays() != null && p.getEstimatedDays() > 0)
                .mapToInt(com.annotation.platform.entity.ImplementationPlan::getEstimatedDays)
                .sum();
        summary.put("totalEstimatedDays", totalEstimatedDays);

        double totalEstimatedCost = resources.stream()
                .filter(r -> r.getEstimatedCost() != null)
                .mapToDouble(ResourceEstimation::getEstimatedCost)
                .sum();
        summary.put("totalEstimatedCost", totalEstimatedCost);

        report.put("summary", summary);

        if (assessment.getStatus() != AssessmentStatus.COMPLETED) {
            assessment.setStatus(AssessmentStatus.COMPLETED);
            assessment.setCompletedAt(java.time.LocalDateTime.now());
            assessmentRepository.save(assessment);
        }

        log.info("报告生成完成: assessmentId={}, categories={}, plans={}", 
                assessmentId, categories.size(), plans.size());

        return report;
    }

    @Transactional
    public Map<String, Object> generateAIReport(Long assessmentId, Long currentUserId) {
        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        List<CategoryAssessment> categories = categoryAssessmentRepository.findByAssessmentIdOrderByCreatedAtAsc(assessmentId);
        List<com.annotation.platform.entity.OvdTestResult> ovdResults = ovdTestResultRepository.findByAssessmentIdOrderByTestTimeDesc(assessmentId);
        List<ResourceEstimation> resources = resourceEstimationRepository.findByAssessmentId(assessmentId);

        // Prepare data for algorithm service
        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("assessmentName", assessment.getAssessmentName());
        requestBody.put("rawRequirement", assessment.getRawRequirement());
        requestBody.put("structuredRequirement", assessment.getStructuredRequirement());
        requestBody.put("datasetMatchLevel", assessment.getDatasetMatchLevel() != null ? assessment.getDatasetMatchLevel().name() : null);
        requestBody.put("userJudgmentNotes", assessment.getUserJudgmentNotes());

        // Prepare categories data
        List<Map<String, Object>> categoriesData = categories.stream()
                .map(cat -> {
                    Map<String, Object> catMap = new java.util.HashMap<>();
                    catMap.put("categoryName", cat.getCategoryName());
                    catMap.put("categoryNameEn", cat.getCategoryNameEn());
                    catMap.put("feasibilityBucket", cat.getFeasibilityBucket() != null ? cat.getFeasibilityBucket().name() : null);
                    catMap.put("confidence", cat.getConfidence());
                    catMap.put("reasoning", cat.getReasoning());
                    return catMap;
                })
                .collect(Collectors.toList());
        requestBody.put("categories", categoriesData);

        // Prepare OVD results data
        List<Map<String, Object>> ovdData = ovdResults.stream()
                .map(ovd -> {
                    Map<String, Object> ovdMap = new java.util.HashMap<>();
                    ovdMap.put("categoryName", ovd.getCategoryName());
                    ovdMap.put("detectedCount", ovd.getDetectedCount());
                    ovdMap.put("averageConfidence", ovd.getAverageConfidence());
                    return ovdMap;
                })
                .collect(Collectors.toList());
        requestBody.put("ovdResults", ovdData);

        // Prepare VLM results data
        List<Map<String, Object>> vlmData = ovdResults.stream()
                .flatMap(ovd -> ovd.getQualityScores().stream())
                .map(score -> {
                    Map<String, Object> vlmMap = new java.util.HashMap<>();
                    vlmMap.put("categoryName", score.getOvdTestResult().getCategoryName());
                    vlmMap.put("precisionEstimate", score.getPrecisionEstimate());
                    vlmMap.put("recallEstimate", score.getRecallEstimate());
                    vlmMap.put("overallVerdict", score.getOverallVerdict());
                    return vlmMap;
                })
                .collect(Collectors.toList());
        requestBody.put("vlmResults", vlmData);

        // Prepare resource estimations data
        List<Map<String, Object>> resourcesData = resources.stream()
                .map(res -> {
                    Map<String, Object> resMap = new java.util.HashMap<>();
                    resMap.put("categoryName", res.getCategoryName());
                    resMap.put("estimatedImages", res.getEstimatedImages());
                    resMap.put("estimatedManDays", res.getEstimatedManDays());
                    resMap.put("gpuHours", res.getGpuHours());
                    resMap.put("estimatedTotalDays", res.getEstimatedTotalDays());
                    resMap.put("publicDatasetImages", res.getPublicDatasetImages());
                    resMap.put("trainingApproach", res.getTrainingApproach());
                    return resMap;
                })
                .collect(Collectors.toList());
        requestBody.put("resourceEstimations", resourcesData);

        // Call algorithm service to generate AI report
        String url = algorithmServiceUrl + "/api/v1/feasibility/generate-report";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("算法服务 generate-report 返回非 2xx 或空响应");
            }

            String reportContent = asString(response.getBody().get("report"));
            
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("assessmentId", assessmentId);
            result.put("assessmentName", assessment.getAssessmentName());
            result.put("report", reportContent);
            result.put("generatedAt", java.time.LocalDateTime.now());

            log.info("AI报告生成完成: assessmentId={}, reportLength={}", assessmentId, reportContent.length());
            
            return result;
            
        } catch (Exception e) {
            log.error("AI报告生成失败: assessmentId={}, error={}", assessmentId, e.getMessage(), e);
            throw new BusinessException("AI_REPORT_GENERATION_FAILED", "AI报告生成失败: " + e.getMessage());
        }
    }
}
