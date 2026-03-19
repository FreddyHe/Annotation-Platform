package com.annotation.platform.service;

import com.annotation.platform.dto.request.feasibility.CreateAssessmentRequest;
import com.annotation.platform.dto.response.feasibility.FeasibilityAssessmentResponse;
import com.annotation.platform.entity.AssessmentStatus;
import com.annotation.platform.entity.CategoryAssessment;
import com.annotation.platform.entity.FeasibilityAssessment;
import com.annotation.platform.entity.Organization;
import com.annotation.platform.entity.User;
import com.annotation.platform.exception.BusinessException;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.CategoryAssessmentRepository;
import com.annotation.platform.repository.FeasibilityAssessmentRepository;
import com.annotation.platform.repository.OvdTestResultRepository;
import com.annotation.platform.repository.OrganizationRepository;
import com.annotation.platform.repository.UserRepository;
import com.annotation.platform.service.user.UserModelConfigService;
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
    private final UserModelConfigService userModelConfigService;
    private final RestTemplate restTemplate;

    @Value("${app.algorithm.url:http://localhost:8001}")
    private String algorithmServiceUrl;

    public FeasibilityAssessmentResponse createAssessment(CreateAssessmentRequest request, Long userId, Long organizationId) {
        log.info("创建可行性评估: name={}, userId={}, orgId={}", request.getAssessmentName(), userId, organizationId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", organizationId));

        FeasibilityAssessment assessment = FeasibilityAssessment.builder()
                .assessmentName(request.getAssessmentName())
                .rawRequirement(request.getRawRequirement())
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

    private FeasibilityAssessmentResponse convertToResponse(FeasibilityAssessment assessment) {
        return FeasibilityAssessmentResponse.builder()
                .id(assessment.getId())
                .assessmentName(assessment.getAssessmentName())
                .rawRequirement(assessment.getRawRequirement())
                .structuredRequirement(assessment.getStructuredRequirement())
                .status(assessment.getStatus())
                .createdAt(assessment.getCreatedAt())
                .updatedAt(assessment.getUpdatedAt())
                .completedAt(assessment.getCompletedAt())
                .createdBy(assessment.getCreatedBy() != null ? assessment.getCreatedBy().getId() : null)
                .createdByUsername(assessment.getCreatedBy() != null ? assessment.getCreatedBy().getDisplayName() : null)
                .organizationId(assessment.getOrganization() != null ? assessment.getOrganization().getId() : null)
                .organizationName(assessment.getOrganization() != null ? assessment.getOrganization().getName() : null)
                .build();
    }
}
