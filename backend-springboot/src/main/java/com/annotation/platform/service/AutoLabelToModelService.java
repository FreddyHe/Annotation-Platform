package com.annotation.platform.service;

import com.annotation.platform.entity.AutoLabelJob;
import com.annotation.platform.entity.DatasetProfile;
import com.annotation.platform.entity.FusedPrediction;
import com.annotation.platform.entity.ModelRegistry;
import com.annotation.platform.entity.ModelTrainingRecord;
import com.annotation.platform.entity.ModelRoutePlan;
import com.annotation.platform.entity.PredictionCandidate;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.ProjectImage;
import com.annotation.platform.entity.ProjectLabel;
import com.annotation.platform.entity.ProjectRequirement;
import com.annotation.platform.entity.ProjectVideo;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.AutoLabelJobRepository;
import com.annotation.platform.repository.DatasetProfileRepository;
import com.annotation.platform.repository.FusedPredictionRepository;
import com.annotation.platform.repository.ModelRegistryRepository;
import com.annotation.platform.repository.ModelTrainingRecordRepository;
import com.annotation.platform.repository.ModelRoutePlanRepository;
import com.annotation.platform.repository.PredictionCandidateRepository;
import com.annotation.platform.repository.ProjectImageRepository;
import com.annotation.platform.repository.ProjectLabelRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.repository.ProjectRequirementRepository;
import com.annotation.platform.repository.ProjectVideoRepository;
import com.annotation.platform.service.labelstudio.LabelStudioProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoLabelToModelService {

    public static final String PIPELINE = "AUTO_LABEL_TO_MODEL";
    public static final String STRATEGY = "HYBRID_TEACHER_QUALITY";
    public static final String PRIORITY_MODE = "quality_first";

    private static final String MODEL_TYPE_XINGMU = "XINGMU_SCENARIO";
    private static final String MODEL_TYPE_BORDER_VENDOR = "BORDER_VENDOR";
    private static final String GROUNDING_DINO_MODEL_ID = "grounding-dino";
    private static final String LOCAL_LOCATE_ANYTHING_MODEL_ID = "local-locate-anything-3b";
    private static final String LOCAL_VLM_MODEL_ID = "local-qwen3-vl-4b";
    private static final String YOLO_WORLD_MODEL_ID = "yolo-world-placeholder";
    private static final int DEFAULT_PREDICTION_PREVIEW_LIMIT = 120;
    private static final int MAX_PREDICTION_PREVIEW_LIMIT = 500;

    private final ProjectRepository projectRepository;
    private final ProjectLabelRepository projectLabelRepository;
    private final ProjectImageRepository projectImageRepository;
    private final ProjectVideoRepository projectVideoRepository;
    private final ModelRegistryRepository modelRegistryRepository;
    private final ModelTrainingRecordRepository modelTrainingRecordRepository;
    private final ProjectRequirementRepository projectRequirementRepository;
    private final DatasetProfileRepository datasetProfileRepository;
    private final ModelRoutePlanRepository modelRoutePlanRepository;
    private final AutoLabelJobRepository autoLabelJobRepository;
    private final PredictionCandidateRepository predictionCandidateRepository;
    private final FusedPredictionRepository fusedPredictionRepository;
    private final LabelStudioProxyService labelStudioProxyService;
    private final TrainingService trainingService;
    private final RestTemplate restTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.algorithm.url:http://localhost:8001}")
    private String algorithmServiceUrl;

    @Value("${app.file.upload.base-path}")
    private String uploadBasePath;

    @Transactional
    public Map<String, Object> syncModelRegistry() {
        int created = 0;
        int updated = 0;
        List<RegistrySeed> seeds = dynamicRegistrySeedsFromAlgorithmService();
        boolean dynamicSync = !seeds.isEmpty();
        if (!dynamicSync) {
            seeds = registrySeeds();
        }
        for (RegistrySeed seed : seeds) {
            ModelRegistry model = modelRegistryRepository.findByModelId(seed.modelId()).orElseGet(ModelRegistry::new);
            boolean isNew = model.getId() == null;
            applySeed(model, seed);
            modelRegistryRepository.save(model);
            if (isNew) {
                created++;
            } else {
                updated++;
            }
        }
        int runtimeUpdated = refreshLocalModelRuntimeStatus();

        Map<String, Object> data = baseResponse();
        data.put("created", created);
        data.put("updated", updated);
        data.put("runtimeUpdated", runtimeUpdated);
        data.put("total", modelRegistryRepository.count());
        data.put("syncSource", dynamicSync ? "algorithm-service:/internal/models/registry" : "static-java-seeds");
        data.put("xingmuDisplayedCapabilities", countXingmuSeeds(seeds));
        data.put("borderVendorCapabilities", countSeedsByType(seeds, MODEL_TYPE_BORDER_VENDOR));
        data.put("xingmuHiddenExcluded", hiddenXingmuScenarios());
        data.put("strategy", STRATEGY);
        data.put("priorityMode", PRIORITY_MODE);
        data.put("largeModelPolicy", "local_gpu_only");
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listModelRegistry(String status, String modelType) {
        List<ModelRegistry> models;
        if (status != null && !status.isBlank()) {
            ModelRegistry.Status parsed = ModelRegistry.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
            models = modelRegistryRepository.findByStatusOrderByModelIdAsc(parsed);
        } else if (modelType != null && !modelType.isBlank()) {
            models = modelRegistryRepository.findByModelTypeOrderByModelIdAsc(modelType.trim());
        } else {
            models = modelRegistryRepository.findAllByOrderByModelIdAsc();
        }

        Map<String, Object> data = baseResponse();
        data.put("items", models.stream().map(this::modelToMap).toList());
        data.put("total", models.size());
        data.put("policy", Map.of(
                "xingmuOnlyDisplayedCapabilities", true,
                "hiddenXingmuScenariosExcluded", true,
                "largeModels", "local_gpu_only"
        ));
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getModel(String modelId) {
        ModelRegistry model = modelRegistryRepository.findByModelId(modelId)
                .orElseThrow(() -> new ResourceNotFoundException("ModelRegistry", "modelId", modelId));
        Map<String, Object> data = baseResponse();
        data.put("item", modelToMap(model));
        return data;
    }

    @Transactional
    public Map<String, Object> parseRequirement(Project project, Long userId, Map<String, Object> request) {
        inspectAndIgnorePriorityMode(project.getId(), request);
        ensureRegistrySeeded();

        String rawText = firstString(request, "raw_user_text", "rawUserText", "requirement", "text", "description");
        List<Map<String, Object>> submittedLabelSchema = extractLabelSchema(request);
        List<String> explicitLabels = extractLabels(request);
        LinkedHashSet<String> requestedLabels = new LinkedHashSet<>(explicitLabels);
        if (requestedLabels.isEmpty()) {
            requestedLabels.addAll(inferLabelsFromText(rawText));
        }
        if (requestedLabels.isEmpty()) {
            requestedLabels.addAll(projectLabels(project));
        }

        Map<String, Object> algorithmRequirementBody = Collections.emptyMap();
        if (submittedLabelSchema.isEmpty()) {
            Map<String, Object> parsePayload = new LinkedHashMap<>();
            parsePayload.put("project_id", project.getId());
            parsePayload.put("raw_user_text", rawText);
            parsePayload.put("labels", new ArrayList<>(requestedLabels));
            Map<String, Object> algorithmResult = callAlgorithmOrchestrator("/internal/requirements/parse", parsePayload);
            algorithmRequirementBody = algorithmBody(algorithmResult);
        }

        List<Map<String, Object>> algorithmLabelSchema = normalizeLabelSchemaItems(algorithmRequirementBody.get("label_schema"));
        List<Map<String, Object>> labelSchema = !submittedLabelSchema.isEmpty()
                ? submittedLabelSchema
                : (!algorithmLabelSchema.isEmpty() ? algorithmLabelSchema : buildLabelSchema(new ArrayList<>(requestedLabels)));
        if (requestedLabels.isEmpty()) {
            requestedLabels.addAll(labelNamesFromSchema(labelSchema));
        }
        String parserStatus = firstString(algorithmRequirementBody, "parser_status", "parserStatus");
        if (parserStatus == null || parserStatus.isBlank()) {
            parserStatus = labelSchema.isEmpty() ? "NEED_USER_LABEL_CONFIRMATION" : "RULE_FALLBACK_READY";
        }
        Map<String, Object> parsedSchema = new LinkedHashMap<>();
        parsedSchema.put("task_type", "object_detection");
        parsedSchema.put("domain", "auto_label_to_model");
        parsedSchema.put("parser", Optional.ofNullable(firstString(algorithmRequirementBody, "parser")).orElse("local_rule_fallback"));
        parsedSchema.put("parser_status", parserStatus);
        parsedSchema.put("llm_policy", Optional.ofNullable(firstString(algorithmRequirementBody, "llm_policy", "llmPolicy")).orElse("local_rule_fallback"));
        parsedSchema.put("external_api_used", Boolean.TRUE.equals(algorithmRequirementBody.get("external_api_used")));
        Object parserModel = firstObject(algorithmRequirementBody, "parser_model", "parserModel");
        if (parserModel != null) {
            parsedSchema.put("parser_model", parserModel);
        }
        Object parseWarning = firstObject(algorithmRequirementBody, "warning", "parse_warning", "parseWarning");
        if (parseWarning != null) {
            parsedSchema.put("warning", parseWarning);
        }
        parsedSchema.put("priority_mode", PRIORITY_MODE);
        parsedSchema.put("strategy", STRATEGY);
        if (labelSchema.isEmpty()) {
            parsedSchema.put("next_action", "请补充明确类别列表后再创建自动标注作业");
        }

        Map<String, Object> promptPack = buildPromptPack(labelSchema);
        ProjectRequirement requirement = ProjectRequirement.builder()
                .project(project)
                .createdBy(userId)
                .rawUserText(rawText)
                .rawUserLabelsJson(new ArrayList<>(requestedLabels))
                .parsedSchemaJson(parsedSchema)
                .labelSchemaJson(labelSchema)
                .promptPackJson(promptPack)
                .build();
        requirement = projectRequirementRepository.save(requirement);

        Map<String, Object> data = baseResponse();
        data.put("item", requirementToMap(requirement));
        data.put("available", !labelSchema.isEmpty());
        data.put("message", labelSchema.isEmpty()
                ? "本地规则未能从需求中提取类别，需要用户确认标签体系"
                : "已生成标签体系；模型推理将使用英文 prompt，页面保留中文标签");
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getLatestRequirement(Long projectId) {
        ProjectRequirement requirement = projectRequirementRepository
                .findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElse(null);
        Map<String, Object> data = baseResponse();
        data.put("item", requirement == null ? null : requirementToMap(requirement));
        return data;
    }

    @Transactional
    public Map<String, Object> createDatasetProfile(Project project, Map<String, Object> request) {
        String datasetId = datasetId(project.getId(), request);
        long imageCount = projectImageRepository.countByProjectId(project.getId());
        int sampleCount = (int) Math.min(Math.max(imageCount, 0), 50);

        List<Map<String, Object>> warnings = new ArrayList<>();
        if (imageCount == 0) {
            warnings.add(warning("NO_IMAGES", "项目当前没有可画像图片，后续作业只能创建为待运行状态"));
        }
        if (imageCount > 0 && imageCount < 20) {
            warnings.add(warning("SMALL_DATASET", "图片数量较少，建议人工复核比例提高"));
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("dataset_id", datasetId);
        profile.put("project_id", project.getId());
        profile.put("num_images", imageCount);
        profile.put("sample_count", sampleCount);
        profile.put("scene_guess", "unknown");
        profile.put("profiler", "backend_lightweight");
        profile.put("vlm_policy", "local_gpu_only");
        profile.put("external_api_used", false);
        profile.put("quality_first", true);

        DatasetProfile datasetProfile = DatasetProfile.builder()
                .project(project)
                .datasetId(datasetId)
                .numImages((int) imageCount)
                .sampleCount(sampleCount)
                .profileJson(profile)
                .qualityWarningsJson(warnings)
                .build();
        datasetProfile = datasetProfileRepository.save(datasetProfile);

        Map<String, Object> data = baseResponse();
        data.put("item", datasetProfileToMap(datasetProfile));
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getLatestDatasetProfile(Long projectId) {
        DatasetProfile profile = datasetProfileRepository
                .findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElse(null);
        Map<String, Object> data = baseResponse();
        data.put("item", profile == null ? null : datasetProfileToMap(profile));
        return data;
    }

    @Transactional
    public Map<String, Object> previewRoute(Project project, Map<String, Object> request) {
        inspectAndIgnorePriorityMode(project.getId(), request);
        ensureRegistrySeeded();
        refreshLocalModelRuntimeStatus();

        ProjectRequirement requirement = resolveRequirement(project, request);
        List<Map<String, Object>> labelSchema = requirement.getLabelSchemaJson() == null
                ? Collections.emptyList()
                : requirement.getLabelSchemaJson();
        if (labelSchema.isEmpty()) {
            throw new IllegalArgumentException("当前项目没有已确认标签体系，不能创建模型路由");
        }

        String datasetId = datasetId(project.getId(), request);
        List<String> labels = labelSchema.stream()
                .map(item -> String.valueOf(item.getOrDefault("display_name", item.getOrDefault("canonical_name", ""))))
                .filter(value -> !value.isBlank())
                .toList();
        List<String> modelPromptLabels = modelPromptLabels(labelSchema);
        List<String> groundingDinoLabels = groundingDinoPromptLabels(labelSchema);
        String primaryModelId = LOCAL_LOCATE_ANYTHING_MODEL_ID;
        LinkedHashSet<String> auxiliary = new LinkedHashSet<>();
        auxiliary.add(GROUNDING_DINO_MODEL_ID);

        boolean locateAnythingAvailable = modelRegistryRepository.findByModelId(LOCAL_LOCATE_ANYTHING_MODEL_ID)
                .map(model -> model.getStatus() == ModelRegistry.Status.AVAILABLE)
                .orElse(false);
        List<String> unavailableQualityModels = locateAnythingAvailable
                ? Collections.emptyList()
                : List.of(LOCAL_LOCATE_ANYTHING_MODEL_ID);
        Map<String, Object> score = new LinkedHashMap<>();
        score.put("labels", labels);
        score.put("display_labels", labels);
        score.put("label_schema", labelSchema);
        score.put("model_prompt_labels", modelPromptLabels);
        score.put("grounding_dino_labels", groundingDinoLabels);
        score.put("locate_anything_labels", modelPromptLabels);
        if (isWaterStainSchema(labelSchema)) {
            score.put("grounding_dino_options", Map.of(
                    "box_threshold", 0.22,
                    "text_threshold", 0.18
            ));
        }
        score.put("route_policy", "locate_anything_grounding_dino_only");
        score.put("allowed_model_ids", List.of(LOCAL_LOCATE_ANYTHING_MODEL_ID, GROUNDING_DINO_MODEL_ID));
        score.put("auxiliary_policy", "primary_empty_or_sampled");
        score.put("auxiliary_sample_interval", 25);
        score.put("disabled_model_families", List.of("xingmu_model", "border_vendor", "local-qwen3-vl-4b", "yolo-world"));
        score.put("locate_anything_available", locateAnythingAvailable);
        score.put("local_quality_models_unavailable", unavailableQualityModels);
        score.put("quality_first", true);
        score.put("priority_mode", PRIORITY_MODE);
        score.put("strategy", STRATEGY);

        String reason = locateAnythingAvailable
                ? "按当前路由策略仅使用 LocateAnything 与 Grounding DINO；LocateAnything 负责开放词汇主候选，Grounding DINO 按空候选兜底和周期抽样补充。"
                : "按当前路由策略仅使用 LocateAnything 与 Grounding DINO；LocateAnything 当前不可用，运行时会保留不可用报告并由 Grounding DINO 继续出候选。";

        ModelRoutePlan routePlan = ModelRoutePlan.builder()
                .project(project)
                .datasetId(datasetId)
                .requirement(requirement)
                .primaryModelId(primaryModelId)
                .auxiliaryModelIdsJson(new ArrayList<>(auxiliary))
                .strategy(STRATEGY)
                .priorityMode(PRIORITY_MODE)
                .reason(reason)
                .scoreJson(score)
                .build();
        routePlan = modelRoutePlanRepository.save(routePlan);

        Map<String, Object> data = baseResponse();
        data.put("item", modelRoutePlanToMap(routePlan));
        data.put("available", true);
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getLatestRoute(Long projectId) {
        ModelRoutePlan routePlan = modelRoutePlanRepository
                .findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElse(null);
        Map<String, Object> data = baseResponse();
        data.put("item", routePlan == null ? null : modelRoutePlanToMap(routePlan));
        return data;
    }

    @Transactional
    public Map<String, Object> createAutoLabelJob(Project project, Long userId, Map<String, Object> request) {
        inspectAndIgnorePriorityMode(project.getId(), request);
        ModelRoutePlan routePlan = resolveRoutePlan(project, request);
        List<Long> selectedImageIds = selectedImageIds(project.getId(), request);
        long imageCount = selectedImageIds.isEmpty()
                ? projectImageRepository.countByProjectId(project.getId())
                : selectedImageIds.size();

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("strategy", STRATEGY);
        runtime.put("priority_mode", PRIORITY_MODE);
        runtime.put("created_from", "backend_auto_label_to_model");
        runtime.put("algorithm_service", algorithmServiceUrl);
        runtime.put("status_detail", "created; waiting for auto-label run");
        boolean semanticVerificationEnabled = semanticVerificationEnabled(request);
        runtime.put("semantic_verification_enabled", semanticVerificationEnabled);
        runtime.put("video_variant", semanticVerificationEnabled ? "quality_checked" : "raw_detector_only");
        if (!selectedImageIds.isEmpty()) {
            runtime.put("selected_image_ids", selectedImageIds);
        }

        AutoLabelJob job = AutoLabelJob.builder()
                .project(project)
                .datasetId(routePlan.getDatasetId())
                .routePlan(routePlan)
                .status(AutoLabelJob.Status.CREATED)
                .pipeline(PIPELINE)
                .priorityMode(PRIORITY_MODE)
                .totalImages((int) imageCount)
                .processedImages(0)
                .failedImages(0)
                .createdBy(userId)
                .runtimeJson(runtime)
                .build();
        job = autoLabelJobRepository.save(job);

        Map<String, Object> data = baseResponse();
        data.put("item", autoLabelJobToMap(job));
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listJobs(Long projectId) {
        List<AutoLabelJob> jobs = autoLabelJobRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        Map<String, Object> data = baseResponse();
        data.put("items", jobs.stream().map(this::autoLabelJobToMap).toList());
        data.put("total", jobs.size());
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getJob(Long projectId, Long jobId) {
        AutoLabelJob job = jobInProject(projectId, jobId);
        Map<String, Object> item = autoLabelJobToMap(job);
        Map<String, Object> algorithmProgress = fetchAlgorithmJobProgress(job);
        if (!algorithmProgress.isEmpty()) {
            Map<String, Object> runtime = new LinkedHashMap<>(objectMap(item.get("runtime")));
            runtime.put("auto_label_progress", algorithmProgress);
            item.put("runtime", publicMap(runtime));
            int progressTotal = asInt(firstObject(algorithmProgress, "total"), 0);
            int progressProcessed = asInt(firstObject(algorithmProgress, "processed"), 0);
            if (progressTotal > 0) {
                item.put("totalImages", progressTotal);
            }
            if (progressProcessed > asInt(item.get("processedImages"), 0)) {
                item.put("processedImages", progressProcessed);
            }
        }
        Map<String, Object> data = baseResponse();
        data.put("item", item);
        data.put("progress", algorithmProgress);
        data.put("candidateCount", predictionCandidateRepository.countByJobId(jobId));
        data.put("fusedPredictionCount", fusedPredictionRepository.countByJobId(jobId));
        return data;
    }

    @Transactional
    public Map<String, Object> probeJob(Long projectId, Long jobId) {
        AutoLabelJob job = jobInProject(projectId, jobId);
        job.setStatus(AutoLabelJob.Status.PROBING);
        job.setRuntimeJson(mergeRuntime(job.getRuntimeJson(), "last_action", "probe"));
        autoLabelJobRepository.save(job);

        Map<String, Object> algorithmResult = callAlgorithmOrchestrator("/internal/auto-label/probe", jobPayload(job));
        if (Boolean.FALSE.equals(algorithmResult.get("available"))) {
            job.setStatus(AutoLabelJob.Status.CREATED);
            job.setErrorMessage(String.valueOf(algorithmResult.get("reason")));
            autoLabelJobRepository.save(job);
        } else if (isDryRunAlgorithmResult(algorithmResult)) {
            job.setStatus(AutoLabelJob.Status.CREATED);
            job.setErrorMessage(null);
            job.setRuntimeJson(mergeRuntime(job.getRuntimeJson(), "algorithm_status", algorithmStatus(algorithmResult)));
            autoLabelJobRepository.save(job);
        }

        Map<String, Object> data = baseResponse();
        data.put("item", autoLabelJobToMap(job));
        data.put("algorithm", algorithmSummary(algorithmResult));
        return data;
    }

    public Map<String, Object> runJob(Long projectId, Long jobId) {
        Map<String, Object> start = transactionTemplate.execute(status -> {
            AutoLabelJob job = jobInProject(projectId, jobId);
            if (List.of(AutoLabelJob.Status.RUNNING, AutoLabelJob.Status.PROBING, AutoLabelJob.Status.SYNCING).contains(job.getStatus())) {
                Map<String, Object> data = baseResponse();
                data.put("item", autoLabelJobToMap(job));
                data.put("skipped", true);
                data.put("message", "任务正在处理中，请等待当前动作完成");
                data.put("candidateCount", predictionCandidateRepository.countByJobId(jobId));
                data.put("fusedPredictionCount", fusedPredictionRepository.countByJobId(jobId));
                data.put("shouldRun", false);
                return data;
            }
            long existingFused = fusedPredictionRepository.countByJobId(jobId);
            if (job.getStatus() == AutoLabelJob.Status.COMPLETED && existingFused > 0) {
                Map<String, Object> data = baseResponse();
                data.put("item", autoLabelJobToMap(job));
                data.put("skipped", true);
                data.put("message", "任务已有自动标注结果，未重复执行推理");
                data.put("candidateCount", predictionCandidateRepository.countByJobId(jobId));
                data.put("fusedPredictionCount", existingFused);
                data.put("shouldRun", false);
                return data;
            }

            job.setStatus(AutoLabelJob.Status.RUNNING);
            job.setStartedAt(LocalDateTime.now());
            Map<String, Object> runtime = mergeRuntime(job.getRuntimeJson(), "last_action", "run");
            runtime.put("progress_stage", "正在识别目标");
            runtime.put("progress_started_at", LocalDateTime.now().toString());
            job.setRuntimeJson(runtime);
            autoLabelJobRepository.saveAndFlush(job);

            Map<String, Object> data = baseResponse();
            data.put("shouldRun", true);
            data.put("payload", jobPayload(job));
            return data;
        });

        if (start == null) {
            Map<String, Object> data = baseResponse();
            data.put("available", false);
            data.put("reason", "无法启动智能标注任务");
            return data;
        }

        Object shouldRun = start.remove("shouldRun");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = start.get("payload") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Collections.emptyMap();
        start.remove("payload");
        if (!Boolean.TRUE.equals(shouldRun)) {
            return start;
        }

        Map<String, Object> algorithmResult = callAlgorithmOrchestrator("/internal/auto-label/run", payload);
        if (Boolean.FALSE.equals(algorithmResult.get("available"))) {
            return transactionTemplate.execute(status -> {
                AutoLabelJob job = jobInProject(projectId, jobId);
                job.setStatus(AutoLabelJob.Status.FAILED);
                job.setErrorMessage(String.valueOf(algorithmResult.get("reason")));
                job.setStartedAt(null);
                autoLabelJobRepository.save(job);
                Map<String, Object> data = baseResponse();
                data.put("item", autoLabelJobToMap(job));
                data.put("algorithm", algorithmSummary(algorithmResult));
                data.put("candidateCount", predictionCandidateRepository.countByJobId(jobId));
                data.put("fusedPredictionCount", fusedPredictionRepository.countByJobId(jobId));
                return data;
            });
        } else if (isDryRunAlgorithmResult(algorithmResult)) {
            return transactionTemplate.execute(status -> {
                AutoLabelJob job = jobInProject(projectId, jobId);
                job.setStatus(AutoLabelJob.Status.FAILED);
                job.setErrorMessage("本地算法服务未返回真实推理结果，未生成可复核标注");
                job.setStartedAt(null);
                job.setRuntimeJson(mergeRuntime(job.getRuntimeJson(), "algorithm_status", algorithmStatus(algorithmResult)));
                autoLabelJobRepository.save(job);
                Map<String, Object> data = baseResponse();
                data.put("item", autoLabelJobToMap(job));
                data.put("algorithm", algorithmSummary(algorithmResult));
                data.put("candidateCount", predictionCandidateRepository.countByJobId(jobId));
                data.put("fusedPredictionCount", fusedPredictionRepository.countByJobId(jobId));
                return data;
            });
        }

        Map<String, Object> runBody = algorithmBody(algorithmResult);
        Map<String, Object> fusionSetup = transactionTemplate.execute(status -> {
            AutoLabelJob job = jobInProject(projectId, jobId);
            int savedCandidates = savePredictionCandidates(job, runBody);
            List<String> labels = jobLabels(job);
            Map<String, Object> fusionPayload = new LinkedHashMap<>();
            fusionPayload.put("job_id", job.getId());
            fusionPayload.put("labels", labels);
            fusionPayload.put("allowed_labels", labels);
            fusionPayload.put("candidates", runBody.getOrDefault("candidates", Collections.emptyList()));
            fusionPayload.put("iou_threshold", 0.55);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("savedCandidates", savedCandidates);
            data.put("fusionPayload", fusionPayload);
            return data;
        });

        int savedCandidates = asInt(fusionSetup == null ? null : fusionSetup.get("savedCandidates"), 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> fusionPayload = fusionSetup != null && fusionSetup.get("fusionPayload") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Collections.emptyMap();
        Map<String, Object> fusionResult = callAlgorithmOrchestrator("/internal/fusion/merge", fusionPayload);

        return transactionTemplate.execute(status -> {
            AutoLabelJob job = jobInProject(projectId, jobId);
            int savedFused = Boolean.TRUE.equals(fusionResult.get("available"))
                    ? saveFusedPredictions(job, algorithmBody(fusionResult))
                    : 0;
            job.setStatus(AutoLabelJob.Status.COMPLETED);
            job.setCompletedAt(LocalDateTime.now());
            job.setProcessedImages(asInt(runBody.get("image_count"), job.getTotalImages()));
            job.setFailedImages(0);
            job.setErrorMessage(null);
            Map<String, Object> runtime = mergeRuntime(job.getRuntimeJson(), "algorithm_status", algorithmStatus(algorithmResult));
            runtime.put("candidate_count", savedCandidates);
            runtime.put("fused_prediction_count", savedFused);
            runtime.put("fusion_status", algorithmStatus(fusionResult));
            if (Boolean.FALSE.equals(fusionResult.get("available"))) {
                runtime.put("fusion_error", fusionResult.get("reason"));
            }
            job.setRuntimeJson(runtime);
            autoLabelJobRepository.save(job);

            Map<String, Object> data = baseResponse();
            data.put("item", autoLabelJobToMap(job));
            data.put("algorithm", algorithmSummary(algorithmResult));
            data.put("candidateCount", predictionCandidateRepository.countByJobId(jobId));
            data.put("fusedPredictionCount", fusedPredictionRepository.countByJobId(jobId));
            return data;
        });
    }

    @Transactional
    public Map<String, Object> syncLabelStudio(Long projectId, Long jobId, Long userId) {
        AutoLabelJob job = jobInProject(projectId, jobId);
        job.setStatus(AutoLabelJob.Status.SYNCING);
        job.setRuntimeJson(mergeRuntime(job.getRuntimeJson(), "last_action", "sync_label_studio"));
        autoLabelJobRepository.save(job);

        Project project = job.getProject();
        Long lsProjectId = ensureLabelStudioProject(project, userId);
        Map<String, Object> formatPayload = jobPayload(job);
        List<String> labels = jobLabels(job);
        if (lsProjectId != null && !labels.isEmpty()) {
            labelStudioProxyService.updateProjectLabelConfig(lsProjectId, labels, userId);
        }
        formatPayload.put("fused_predictions", fusedPredictionsForFormatter(job));
        formatPayload.put("labels", labels);
        formatPayload.put("label_config", generateLabelConfig(labels));
        formatPayload.put("model_version", STRATEGY + "/" + job.getId());

        Map<String, Object> algorithmResult = callAlgorithmOrchestrator("/internal/label-studio/format-predictions", formatPayload);
        Map<String, Object> formatterBody = algorithmBody(algorithmResult);
        Map<String, Object> importStats = Collections.emptyMap();
        Map<String, Object> reviewStats = Collections.emptyMap();
        Map<String, Object> reviewResults = Collections.emptyMap();

        if (Boolean.FALSE.equals(algorithmResult.get("available"))) {
            job.setStatus(AutoLabelJob.Status.FAILED);
            job.setErrorMessage(String.valueOf(algorithmResult.get("reason")));
            autoLabelJobRepository.save(job);
        } else if (isDryRunAlgorithmResult(algorithmResult)) {
            job.setStatus(AutoLabelJob.Status.FAILED);
            job.setErrorMessage("本地格式化服务未返回真实 Label Studio prediction，未执行同步");
            job.setRuntimeJson(mergeRuntime(job.getRuntimeJson(), "algorithm_status", algorithmStatus(algorithmResult)));
            autoLabelJobRepository.save(job);
        } else if (lsProjectId == null) {
            job.setStatus(AutoLabelJob.Status.FAILED);
            job.setErrorMessage("Label Studio 项目不可用，无法导入 prediction");
            job.setRuntimeJson(mergeRuntime(job.getRuntimeJson(), "label_studio_status", "LS_PROJECT_UNAVAILABLE"));
            autoLabelJobRepository.save(job);
        } else {
            prepareLabelStudioTasks(project, lsProjectId, userId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> predictions = formatterBody.get("predictions") instanceof List<?> list
                    ? (List<Map<String, Object>>) list
                    : Collections.emptyList();
            importStats = importPredictionsWithRetry(lsProjectId, predictions, userId);

            int imported = asInt(importStats.get("success"), 0);
            int skipped = asInt(importStats.get("skipped"), 0);
            int taskCount = labelStudioProxyService.getProjectTaskCount(lsProjectId, userId);
            int formattedPredictions = asInt(formatterBody.get("prediction_count"), 0);
            int formattedResults = asInt(formatterBody.get("result_count"), 0);
            reviewStats = quickReviewStats(taskCount, Math.max(imported, skipped), formattedResults);
            reviewResults = Map.of("tasks", Collections.emptyList(), "deferred", true);
            int existingPredictions = asInt(reviewStats.get("totalPredictions"), 0);
            if (imported > 0 || existingPredictions > 0) {
                markFusedPredictionsSynced(jobId);
                job.setStatus(AutoLabelJob.Status.COMPLETED);
                job.setErrorMessage(null);
            } else {
                job.setStatus(AutoLabelJob.Status.FAILED);
                job.setErrorMessage("Label Studio 未导入任何 prediction，请检查任务图片名是否与项目图片匹配");
            }
            Map<String, Object> runtime = mergeRuntime(job.getRuntimeJson(), "algorithm_status", algorithmStatus(algorithmResult));
            runtime.put("label_studio_project_id", lsProjectId);
            runtime.put("label_studio_import_stats", importStats);
            runtime.put("label_studio_review_stats", reviewStats);
            runtime.put("label_studio_formatted_prediction_count", formattedPredictions);
            runtime.put("label_studio_formatted_result_count", formattedResults);
            runtime.put("label_studio_formatter_stats", formatterBody.get("stats"));
            runtime.put("label_studio_formatter_warnings", formatterBody.get("warnings"));
            job.setRuntimeJson(runtime);
            autoLabelJobRepository.save(job);
        }

        Map<String, Object> data = baseResponse();
        data.put("item", autoLabelJobToMap(job));
        data.put("algorithm", algorithmSummary(algorithmResult));
        data.put("labelStudioProjectId", lsProjectId);
        data.put("importStats", importStats);
        data.put("reviewStats", reviewStats);
        data.put("reviewResults", reviewResults);
        data.put("syncedCount", fusedPredictionRepository.countByJobIdAndSyncedToLabelStudio(jobId, true));
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listCandidates(
            Long projectId,
            Long jobId,
            Integer page,
            Integer size,
            Integer limit,
            Integer offset) {
        jobInProject(projectId, jobId);
        long total = predictionCandidateRepository.countByJobId(jobId);
        PageSpec pageSpec = predictionPageSpec(page, size, limit, offset);
        List<PredictionCandidate> candidates = predictionCandidateRepository.findByJobIdOrderByCreatedAtAsc(
                jobId,
                pageSpec.pageable());
        Map<String, Object> data = baseResponse();
        data.put("items", candidates.stream().map(this::predictionCandidateToMap).toList());
        data.put("total", total);
        data.put("page", pageSpec.page());
        data.put("size", pageSpec.size());
        data.put("hasMore", ((long) (pageSpec.page() + 1) * pageSpec.size()) < total);
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listFusedPredictions(
            Long projectId,
            Long jobId,
            Integer page,
            Integer size,
            Integer limit,
            Integer offset) {
        jobInProject(projectId, jobId);
        long total = fusedPredictionRepository.countByJobId(jobId);
        PageSpec pageSpec = predictionPageSpec(page, size, limit, offset);
        List<FusedPrediction> predictions = fusedPredictionRepository.findByJobIdOrderByCreatedAtAsc(
                jobId,
                pageSpec.pageable());
        Map<String, Object> data = baseResponse();
        data.put("items", predictions.stream().map(this::fusedPredictionToMap).toList());
        data.put("total", total);
        data.put("page", pageSpec.page());
        data.put("size", pageSpec.size());
        data.put("hasMore", ((long) (pageSpec.page() + 1) * pageSpec.size()) < total);
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAnnotatedVideo(Long projectId, Long jobId) {
        AutoLabelJob job = jobInProject(projectId, jobId);
        Map<String, Object> data = baseResponse();
        Map<String, Object> annotatedVideo = annotatedVideoFromRuntime(job);
        List<Map<String, Object>> annotatedVideos = annotatedVideosFromRuntime(job);
        data.put("available", !annotatedVideos.isEmpty() || (annotatedVideo != null && !annotatedVideo.isEmpty()));
        data.put("item", autoLabelJobToMap(job));
        data.put("annotatedVideo", annotatedVideo);
        data.put("annotatedVideos", annotatedVideos);
        return data;
    }

    @Transactional
    public Map<String, Object> renderAnnotatedVideo(Long projectId, Long jobId) {
        AutoLabelJob job = jobInProject(projectId, jobId);
        if (fusedPredictionRepository.countByJobId(jobId) == 0) {
            Map<String, Object> data = baseResponse();
            data.put("available", false);
            data.put("item", autoLabelJobToMap(job));
            data.put("reason", "当前任务还没有融合标注结果，无法生成标注视频");
            return data;
        }

        List<Map<String, Object>> existingVideos = annotatedVideosFromRuntime(job);
        if (!existingVideos.isEmpty()
                && existingVideos.stream().allMatch(this::currentSampledAnnotatedVideoExists)) {
            Map<String, Object> data = baseResponse();
            data.put("available", true);
            data.put("item", autoLabelJobToMap(job));
            data.put("annotatedVideo", existingVideos.get(0));
            data.put("annotatedVideos", existingVideos);
            data.put("skipped", true);
            data.put("message", "标注视频已生成，未重复渲染");
            return data;
        }

        List<Map<String, Object>> payloads = annotatedVideoPayloads(job);
        if (payloads.isEmpty()) {
            Map<String, Object> data = baseResponse();
            data.put("available", false);
            data.put("item", autoLabelJobToMap(job));
            data.put("reason", "当前项目没有可回填的原视频，图片压缩包项目不展示视频结果");
            return data;
        }

        List<Map<String, Object>> renderedVideos = new ArrayList<>();
        List<Map<String, Object>> algorithmBodies = new ArrayList<>();
        for (Map<String, Object> payload : payloads) {
            Map<String, Object> algorithmResult = callAlgorithmOrchestrator("/internal/annotated-video/render", payload);
            Map<String, Object> body = algorithmBody(algorithmResult);
            algorithmBodies.add(body.isEmpty() ? algorithmResult : body);
            if (!Boolean.TRUE.equals(algorithmResult.get("available")) || Boolean.FALSE.equals(body.get("available"))) {
                Map<String, Object> data = baseResponse();
                data.put("available", false);
                data.put("item", autoLabelJobToMap(job));
                data.put("algorithm", algorithmSummary(algorithmResult));
                data.put("reason", body.getOrDefault("reason", algorithmResult.getOrDefault("reason", "标注视频生成失败")));
                return data;
            }

            Map<String, Object> annotatedVideo = new LinkedHashMap<>();
            annotatedVideo.put("status", body.get("status"));
            annotatedVideo.put("url", body.get("url"));
            annotatedVideo.put("relativePath", body.get("output_relative_path"));
            annotatedVideo.put("frameCount", body.get("frame_count"));
            annotatedVideo.put("predictionCount", body.get("prediction_count"));
            annotatedVideo.put("fps", body.get("fps"));
            annotatedVideo.put("width", body.get("width"));
            annotatedVideo.put("height", body.get("height"));
            annotatedVideo.put("sourceVideoId", payload.get("source_video_id"));
            annotatedVideo.put("sourceVideoName", payload.get("source_video_name"));
            annotatedVideo.put("mode", body.getOrDefault("mode", payload.get("mode")));
            annotatedVideo.put("generatedAt", LocalDateTime.now().toString());
            renderedVideos.add(annotatedVideo);
        }

        Map<String, Object> runtime = mergeRuntime(job.getRuntimeJson(), "annotated_videos", renderedVideos);
        runtime.put("annotated_video", renderedVideos.isEmpty() ? null : renderedVideos.get(0));
        job.setRuntimeJson(runtime);
        job = autoLabelJobRepository.save(job);
        updateProjectVideoRenderedOutputs(job.getProject().getId(), renderedVideos);

        Map<String, Object> data = baseResponse();
        data.put("available", true);
        data.put("item", autoLabelJobToMap(job));
        data.put("annotatedVideo", renderedVideos.isEmpty() ? null : renderedVideos.get(0));
        data.put("annotatedVideos", renderedVideos);
        data.put("algorithm", algorithmBodies);
        return data;
    }

    @Transactional
    public Map<String, Object> trainFromReviewedLabels(Long projectId, Map<String, Object> request, Long userId) {
        Project project = project(projectId);
        Long jobId = asLong(firstObject(request, "job_id", "jobId"));
        AutoLabelJob job = jobId == null ? null : jobInProject(projectId, jobId);
        boolean startTraining = Boolean.TRUE.equals(firstObject(request, "start_training", "startTraining"));
        int epochs = Math.max(1, asInt(firstObject(request, "epochs"), 1));
        int batchSize = Math.max(1, asInt(firstObject(request, "batch_size", "batchSize"), 2));
        int imageSize = Math.max(320, asInt(firstObject(request, "image_size", "imageSize"), 640));
        String modelType = firstString(request, "model_type", "modelType");
        if (modelType == null || modelType.isBlank()) {
            modelType = "yolov8n";
        }
        String device = firstString(request, "device");
        if (device == null || device.isBlank()) {
            device = "0";
        }

        List<Map<String, Object>> reviewed = Collections.emptyList();
        String reviewedExportStatus = "SKIPPED_NO_LABEL_STUDIO_PROJECT";
        String reviewedExportError = null;
        ReviewedDatasetResult dataset = null;
        String datasetSource = "reviewed-labels";
        if (project.getLsProjectId() != null) {
            try {
                reviewed = labelStudioProxyService.exportAnnotations(project.getLsProjectId(), userId, "reviewed-json");
                dataset = buildReviewedYoloDataset(project, reviewed, job);
                reviewedExportStatus = "EXPORTED";
            } catch (Exception e) {
                reviewedExportStatus = "FAILED";
                reviewedExportError = e.getMessage();
                log.warn("Unable to build reviewed dataset for project {}: {}", projectId, e.getMessage());
            }
        }
        if ((dataset == null || dataset.totalImages() <= 0 || dataset.totalAnnotations() <= 0) && job != null) {
            dataset = buildFusedPredictionYoloDataset(project, job);
            datasetSource = "unreviewed-fused-predictions";
        }
        if (dataset == null || dataset.totalImages() <= 0 || dataset.totalAnnotations() <= 0) {
            Map<String, Object> data = baseResponse();
            data.put("available", false);
            data.put("status", "NO_TRAINING_BOXES");
            data.put("projectId", projectId);
            data.put("jobId", jobId);
            data.put("reviewedItems", reviewed.size());
            data.put("reviewedExportStatus", reviewedExportStatus);
            data.put("reviewedExportError", reviewedExportError);
            data.put("reason", "当前没有可用于训练的已复核矩形标注，也没有可回退使用的智能标注融合框");
            data.put("nextAction", "请先运行智能标注生成框，或在 Label Studio 完成人工复核后重新提交训练");
            return data;
        }

        ModelTrainingRecord record;
        String trainingMode;
        if (startTraining) {
            try {
                FormatConverterService.DatasetConversionResult conversion = new FormatConverterService.DatasetConversionResult();
                conversion.setOutputPath(dataset.outputDir().toString());
                conversion.setTrainImages(dataset.trainImages());
                conversion.setValImages(dataset.valImages());
                conversion.setTotalAnnotations(dataset.totalAnnotations());
                conversion.setLabelMap(dataset.labelMap());
                record = trainingService.startTrainingFromDataset(
                        userId,
                        projectId,
                        project.getCurrentRoundId(),
                        dataset.outputDir().toString(),
                        conversion,
                        epochs,
                        batchSize,
                        imageSize,
                        modelType,
                        device,
                        ModelTrainingRecord.TrainingDataSource.INITIAL
                );
                trainingMode = "STARTED";
            } catch (Exception e) {
                record = createReviewedDryRunTrainingRecord(projectId, userId, dataset, epochs, batchSize, imageSize,
                        modelType, device, "真实训练启动失败，已保留 dry-run 数据集和训练记录: " + e.getMessage());
                trainingMode = "DRY_RUN_AFTER_START_FAILURE";
            }
        } else {
            record = createReviewedDryRunTrainingRecord(projectId, userId, dataset, epochs, batchSize, imageSize,
                    modelType, device, "已完成训练数据准备；默认不启动长时间训练，可传 startTraining=true 启动真实训练。");
            trainingMode = "DRY_RUN_PREPARED";
        }

        Map<String, Object> data = baseResponse();
        data.put("available", true);
        data.put("status", trainingMode);
        data.put("projectId", projectId);
        data.put("jobId", jobId);
        data.put("datasetSource", datasetSource);
        data.put("reviewedItems", reviewed.size());
        data.put("reviewedExportStatus", reviewedExportStatus);
        data.put("reviewedExportError", reviewedExportError);
        data.put("dataset", dataset.toMap());
        data.put("trainingRecord", trainingRecordToMap(record));
        data.put("modelCard", dataset.modelCard());
        data.put("nextAction", startTraining ? "通过训练记录接口轮询训练状态" : "检查数据集后按需传 startTraining=true 启动真实训练");
        return data;
    }

    private void ensureRegistrySeeded() {
        if (modelRegistryRepository.count() == 0) {
            syncModelRegistry();
        }
    }

    private Project project(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
    }

    private ProjectRequirement latestRequirement(Long projectId) {
        return projectRequirementRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectRequirement", "projectId", projectId));
    }

    private ProjectRequirement resolveRequirement(Project project, Map<String, Object> request) {
        Long requirementId = asLong(firstObject(request, "requirement_id", "requirementId", "projectRequirementId"));
        if (requirementId == null) {
            return latestRequirement(project.getId());
        }
        ProjectRequirement requirement = projectRequirementRepository.findById(requirementId)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectRequirement", "id", requirementId));
        if (requirement.getProject() == null || !project.getId().equals(requirement.getProject().getId())) {
            throw new ResourceNotFoundException("ProjectRequirement", "id", requirementId);
        }
        return requirement;
    }

    private DatasetProfile latestDatasetProfile(Long projectId) {
        return datasetProfileRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("DatasetProfile", "projectId", projectId));
    }

    private ModelRoutePlan latestRoutePlan(Long projectId) {
        return modelRoutePlanRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("ModelRoutePlan", "projectId", projectId));
    }

    private ModelRoutePlan resolveRoutePlan(Project project, Map<String, Object> request) {
        Object routePlanIdValue = request == null ? null : request.get("route_plan_id");
        if (routePlanIdValue == null && request != null) {
            routePlanIdValue = request.get("routePlanId");
        }
        if (routePlanIdValue instanceof Number number) {
            ModelRoutePlan routePlan = modelRoutePlanRepository.findById(number.longValue())
                    .orElseThrow(() -> new ResourceNotFoundException("ModelRoutePlan", "id", number.longValue()));
            if (routePlan.getProject() == null || !project.getId().equals(routePlan.getProject().getId())) {
                throw new ResourceNotFoundException("ModelRoutePlan", "id", number.longValue());
            }
            return routePlan;
        }
        return latestRoutePlan(project.getId());
    }

    private AutoLabelJob jobInProject(Long projectId, Long jobId) {
        AutoLabelJob job = autoLabelJobRepository.findByIdWithProjectAndRoutePlan(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("AutoLabelJob", "id", jobId));
        if (job.getProject() == null || !projectId.equals(job.getProject().getId())) {
            throw new ResourceNotFoundException("AutoLabelJob", "id", jobId);
        }
        return job;
    }

    private List<String> projectLabels(Project project) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if (project.getLabels() != null) {
            labels.addAll(project.getLabels());
        }
        List<ProjectLabel> activeLabels = projectLabelRepository.findByProjectIdAndIsActive(project.getId(), true);
        for (ProjectLabel label : activeLabels) {
            if (label.getName() != null && !label.getName().isBlank()) {
                labels.add(label.getName().trim());
            }
        }
        return new ArrayList<>(labels);
    }

    @SuppressWarnings("unchecked")
    private List<String> jobLabels(AutoLabelJob job) {
        if (job == null) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        ModelRoutePlan routePlan = job.getRoutePlan();
        if (routePlan != null && routePlan.getRequirement() != null) {
            labels.addAll(labelNamesFromSchema(Optional.ofNullable(routePlan.getRequirement().getLabelSchemaJson())
                    .orElse(Collections.emptyList())));
        }
        if (labels.isEmpty() && routePlan != null && routePlan.getScoreJson() != null) {
            Object routeLabels = routePlan.getScoreJson().get("labels");
            if (routeLabels instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        labels.add(String.valueOf(item).trim());
                    }
                }
            }
        }
        if (labels.isEmpty() && job.getProject() != null) {
            labels.addAll(projectLabels(job.getProject()));
        }
        return new ArrayList<>(labels);
    }

    private List<String> extractLabels(Map<String, Object> request) {
        if (request == null) {
            return new ArrayList<>();
        }
        Object value = firstObject(request, "raw_user_labels_json", "rawUserLabelsJson", "raw_user_labels", "labels", "label_schema", "labelSchema");
        return asStringList(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractLabelSchema(Map<String, Object> request) {
        Object value = firstObject(request, "label_schema", "labelSchema");
        return normalizeLabelSchemaItems(value);
    }

    private List<Map<String, Object>> normalizeLabelSchemaItems(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> schema = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    Map<String, Object> fallback = buildLabelSchema(List.of(String.valueOf(item).trim())).stream()
                            .findFirst()
                            .orElse(null);
                    if (fallback != null && seen.add(fallback.get("canonical_name") + "::" + fallback.get("display_name"))) {
                        schema.add(fallback);
                    }
                }
                continue;
            }
            Map<?, ?> map = rawMap;
            String displayName = firstNonBlank(map, "display_name", "displayName", "display_name_zh", "displayNameZh", "name", "label", "canonical_name", "canonicalName");
            if (displayName == null) {
                continue;
            }
            String modelPrompt = firstNonBlank(map, "model_prompt", "modelPrompt", "english_prompt", "englishPrompt", "english_name", "englishName");
            List<String> positivePromptsEn = asStringList(firstObject(map, "positive_prompts_en", "positivePromptsEn"));
            List<String> positives = asStringList(firstObject(map, "positive_prompts", "positivePrompts", "positive_prompts_text", "positivePromptsText"));
            if (modelPrompt == null && !positivePromptsEn.isEmpty()) {
                modelPrompt = positivePromptsEn.get(0);
            }
            if (modelPrompt == null && !positives.isEmpty() && looksEnglish(positives.get(0))) {
                modelPrompt = positives.get(0);
            }
            if (modelPrompt == null) {
                modelPrompt = englishPromptForLabel(displayName);
            }
            if (positives.isEmpty()) {
                positives.add(modelPrompt);
            }
            if (positivePromptsEn.isEmpty()) {
                positivePromptsEn.addAll(englishPromptAliases(displayName, modelPrompt));
            }
            String canonical = Optional.ofNullable(firstNonBlank(map, "canonical_name", "canonicalName"))
                    .orElse(canonicalName(modelPrompt));
            if (!seen.add(canonical + "::" + displayName)) {
                continue;
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("canonical_name", canonical);
            normalized.put("display_name", displayName);
            normalized.put("english_name", modelPrompt);
            normalized.put("model_prompt", modelPrompt);
            normalized.put("description", Optional.ofNullable(firstNonBlank(map, "description"))
                    .orElse("用户确认的自动标注类别：" + displayName));
            normalized.put("positive_prompts", positives);
            normalized.put("positive_prompts_en", positivePromptsEn);
            normalized.put("negative_prompts", asStringList(firstObject(map, "negative_prompts", "negativePrompts", "negative_prompts_text", "negativePromptsText")));
            normalized.put("requires_relation_reasoning", Boolean.TRUE.equals(firstObject(map, "requires_relation_reasoning", "requiresRelationReasoning")));
            schema.add(normalized);
        }
        return schema;
    }

    private List<String> labelNamesFromSchema(List<Map<String, Object>> labelSchema) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (Map<String, Object> item : labelSchema) {
            Object displayName = firstObject(item, "display_name", "displayName", "name", "canonical_name", "canonicalName");
            if (displayName != null && !String.valueOf(displayName).isBlank()) {
                labels.add(String.valueOf(displayName).trim());
            }
        }
        return new ArrayList<>(labels);
    }

    private List<String> inferLabelsFromText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new ArrayList<>();
        }
        String normalizedText = normalize(rawText);
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if (normalizedText.contains("重物下的人")
                || normalizedText.contains("吊物下的人")
                || normalizedText.contains("悬挂物下的人")
                || normalizedText.contains("suspendedload")) {
            labels.add("重物下的人");
        }
        Map<String, List<String>> builtinAliases = new LinkedHashMap<>();
        builtinAliases.put("人", List.of("人", "行人", "人员", "person", "people", "pedestrian", "human"));
        builtinAliases.put("车", List.of("车", "车辆", "汽车", "机动车", "car", "vehicle", "truck", "van"));
        builtinAliases.put("自行车", List.of("自行车", "单车", "脚踏车", "bicycle", "bike"));
        builtinAliases.put("电动车", List.of("电动车", "电瓶车", "电动自行车", "e-bike", "ebike", "electricbike", "electricscooter"));
        builtinAliases.put("摩托车", List.of("摩托车", "摩托", "机车", "motorcycle", "motorbike", "scooter"));
        builtinAliases.put("安全帽", List.of("安全帽", "头盔", "helmet", "hardhat"));
        builtinAliases.put("反光衣", List.of("反光衣", "反光背心", "安全背心", "reflectivevest", "safetyvest", "hi-vis"));
        for (Map.Entry<String, List<String>> entry : builtinAliases.entrySet()) {
            if ("人".equals(entry.getKey()) && labels.contains("重物下的人")) {
                continue;
            }
            for (String alias : entry.getValue()) {
                if (!alias.isBlank() && normalizedText.contains(normalize(alias))) {
                    labels.add(entry.getKey());
                    break;
                }
            }
        }
        if (!labels.isEmpty()) {
            return new ArrayList<>(labels);
        }
        for (ModelRegistry model : modelRepositoryModelsForRequirementInference()) {
            for (String alias : aliasesForMatch(model)) {
                if (!alias.isBlank() && normalizedText.contains(normalize(alias))) {
                    labels.add(model.getName());
                    break;
                }
            }
        }
        return new ArrayList<>(labels);
    }

    private List<ModelRegistry> modelRepositoryModelsForRequirementInference() {
        return modelRegistryRepository.findAllByOrderByModelIdAsc().stream()
                .filter(model -> MODEL_TYPE_XINGMU.equals(model.getModelType())
                        || MODEL_TYPE_BORDER_VENDOR.equals(model.getModelType()))
                .toList();
    }

    private List<Map<String, Object>> buildLabelSchema(List<String> labels) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String label : labels) {
            if (label != null && !label.isBlank()) {
                unique.add(label.trim());
            }
        }
        List<Map<String, Object>> schema = new ArrayList<>();
        for (String label : unique) {
            String modelPrompt = englishPromptForLabel(label);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("canonical_name", canonicalName(modelPrompt));
            item.put("display_name", label);
            item.put("english_name", modelPrompt);
            item.put("model_prompt", modelPrompt);
            item.put("description", "用户确认的自动标注类别：" + label);
            item.put("positive_prompts", englishPromptAliases(label, modelPrompt));
            item.put("positive_prompts_en", englishPromptAliases(label, modelPrompt));
            item.put("negative_prompts", List.of());
            item.put("requires_relation_reasoning", false);
            schema.add(item);
        }
        return schema;
    }

    private Map<String, Object> buildPromptPack(List<Map<String, Object>> labelSchema) {
        List<String> displayLabels = labelSchema.stream()
                .map(item -> String.valueOf(item.getOrDefault("display_name", "")))
                .filter(value -> !value.isBlank())
                .toList();
        List<String> modelPrompts = modelPromptLabels(labelSchema);
        List<String> groundingDinoPrompts = groundingDinoPromptLabels(labelSchema);
        Map<String, Object> promptPack = new LinkedHashMap<>();
        promptPack.put("grounding_dino", String.join(" . ", groundingDinoPrompts) + (groundingDinoPrompts.isEmpty() ? "" : " ."));
        promptPack.put("grounding_dino_labels", groundingDinoPrompts);
        if (isWaterStainSchema(labelSchema)) {
            promptPack.put("grounding_dino_options", Map.of(
                    "box_threshold", 0.22,
                    "text_threshold", 0.18
            ));
        }
        promptPack.put("locate_anything", labelSchema.stream()
                .map(item -> {
                    String displayName = String.valueOf(item.getOrDefault("display_name", item.getOrDefault("canonical_name", "")));
                    String modelPrompt = modelPromptForItem(item);
                    return Map.of(
                            "label", canonicalName(modelPrompt),
                            "display_label", displayName,
                            "text", "Locate all " + modelPrompt + " objects."
                    );
                })
                .toList());
        promptPack.put("locate_anything_labels", modelPrompts);
        promptPack.put("xingmu", Map.of("candidate_labels", displayLabels, "only_displayed_32_capabilities", true));
        promptPack.put("review_policy", Map.of(
                "priority_mode", PRIORITY_MODE,
                "auto_accept_threshold", 0.85,
                "manual_review_threshold", 0.45
        ));
        return promptPack;
    }

    private List<String> modelPromptLabels(List<Map<String, Object>> labelSchema) {
        LinkedHashSet<String> prompts = new LinkedHashSet<>();
        for (Map<String, Object> item : labelSchema) {
            String prompt = modelPromptForItem(item);
            if (prompt != null && !prompt.isBlank()) {
                prompts.add(prompt);
            }
        }
        return new ArrayList<>(prompts);
    }

    private List<String> groundingDinoPromptLabels(List<Map<String, Object>> labelSchema) {
        if (isWaterStainSchema(labelSchema)) {
            return List.of("water stain", "wet patch", "puddle");
        }
        return modelPromptLabels(labelSchema);
    }

    private boolean isWaterStainSchema(List<Map<String, Object>> labelSchema) {
        for (Map<String, Object> item : labelSchema) {
            String joined = String.join(" ",
                    String.valueOf(item.getOrDefault("display_name", "")),
                    String.valueOf(item.getOrDefault("canonical_name", "")),
                    String.valueOf(item.getOrDefault("model_prompt", "")),
                    String.valueOf(item.getOrDefault("english_name", ""))
            );
            String normalized = normalize(joined);
            if (normalized.contains("水渍")
                    || normalized.contains("水迹")
                    || normalized.contains("湿斑")
                    || normalized.contains("积水")
                    || normalized.contains("waterstain")
                    || normalized.contains("wetpatch")
                    || normalized.contains("puddle")) {
                return true;
            }
        }
        return false;
    }

    private String modelPromptForItem(Map<String, Object> item) {
        String prompt = firstNonBlank(item, "model_prompt", "modelPrompt", "english_prompt", "englishPrompt", "english_name", "englishName");
        if (prompt != null) {
            return prompt;
        }
        List<String> positivePromptsEn = asStringList(firstObject(item, "positive_prompts_en", "positivePromptsEn"));
        if (!positivePromptsEn.isEmpty()) {
            return positivePromptsEn.get(0);
        }
        List<String> positivePrompts = asStringList(firstObject(item, "positive_prompts", "positivePrompts"));
        for (String value : positivePrompts) {
            if (looksEnglish(value)) {
                return value;
            }
        }
        String displayName = firstNonBlank(item, "display_name", "displayName", "name", "canonical_name", "canonicalName");
        return englishPromptForLabel(displayName);
    }

    private List<String> englishPromptAliases(String label, String modelPrompt) {
        LinkedHashSet<String> prompts = new LinkedHashSet<>();
        if (modelPrompt != null && !modelPrompt.isBlank()) {
            prompts.add(modelPrompt.trim());
        }
        String normalized = normalize(label);
        String prompt = modelPrompt == null ? "" : modelPrompt.toLowerCase(Locale.ROOT).trim();
        if ("person".equals(prompt) || normalized.equals("人") || normalized.contains("行人")) {
            prompts.add("pedestrian");
            prompts.add("people");
            prompts.add("human");
        } else if ("car".equals(prompt) || normalized.equals("车") || normalized.contains("车辆")) {
            prompts.add("vehicle");
            prompts.add("automobile");
            prompts.add("van");
            prompts.add("truck");
        } else if ("bicycle".equals(prompt) || normalized.contains("自行车")) {
            prompts.add("bike");
        } else if ("electric bicycle".equals(prompt) || normalized.contains("电动车") || normalized.contains("电瓶车")) {
            prompts.add("e-bike");
            prompts.add("electric scooter");
        } else if ("motorcycle".equals(prompt) || normalized.contains("摩托")) {
            prompts.add("motorbike");
            prompts.add("scooter");
        } else if (prompt.contains("helmet") || normalized.contains("安全帽") || normalized.contains("头盔")) {
            if (prompt.contains("without")) {
                prompts.add("worker without safety helmet");
                prompts.add("person no helmet");
            } else if (prompt.contains("wearing")) {
                prompts.add("worker wearing safety helmet");
            } else {
                prompts.add("hard hat");
            }
        } else if (prompt.contains("vest") || normalized.contains("反光衣") || normalized.contains("背心")) {
            if (prompt.contains("without")) {
                prompts.add("worker without safety vest");
                prompts.add("person no reflective vest");
            } else {
                prompts.add("safety vest");
                prompts.add("hi-vis vest");
                prompts.add("reflective vest");
            }
        } else if (prompt.contains("water stain") || normalized.contains("水渍") || normalized.contains("水迹")
                || normalized.contains("湿斑") || normalized.contains("积水")) {
            prompts.add("water stain");
            prompts.add("wet patch");
            prompts.add("puddle");
        }
        return new ArrayList<>(prompts);
    }

    private String englishPromptForLabel(String label) {
        if (label == null || label.isBlank()) {
            return "object";
        }
        String normalized = normalize(label);
        if (normalized.contains("水渍") || normalized.contains("水迹") || normalized.contains("湿斑")
                || normalized.contains("积水") || normalized.contains("waterstain")
                || normalized.contains("wetpatch") || normalized.contains("puddle")) {
            return "water stain";
        }
        if (normalized.contains("重物下的人")
                || normalized.contains("吊物下的人")
                || normalized.contains("悬挂物下的人")
                || normalized.contains("suspendedload")) {
            return "person under a suspended load";
        }
        if (normalized.contains("没戴安全帽") || normalized.contains("未戴安全帽") || normalized.contains("未佩戴安全帽")
                || normalized.contains("withouthelmet") || normalized.contains("nohelmet")) {
            return "person without a safety helmet";
        }
        if (normalized.contains("戴安全帽") || normalized.contains("佩戴安全帽") || normalized.contains("wearinghelmet")) {
            return "person wearing a safety helmet";
        }
        if (normalized.contains("没穿反光衣") || normalized.contains("未穿反光衣") || normalized.contains("未穿反光背心")
                || normalized.contains("withoutvest") || normalized.contains("novest")) {
            return "person without a reflective safety vest";
        }
        if (normalized.contains("反光衣") || normalized.contains("反光背心") || normalized.contains("安全背心")
                || normalized.contains("reflectivevest") || normalized.contains("safetyvest") || normalized.contains("hivis")) {
            return "reflective safety vest";
        }
        if (normalized.contains("安全帽") || normalized.contains("头盔") || normalized.contains("helmet") || normalized.contains("hardhat")) {
            return "safety helmet";
        }
        if (normalized.contains("电动车") || normalized.contains("电瓶车") || normalized.contains("电动自行车")
                || normalized.contains("electricbike") || normalized.contains("ebike") || normalized.contains("electricscooter")) {
            return "electric bicycle";
        }
        if (normalized.contains("自行车") || normalized.contains("单车") || normalized.contains("脚踏车")
                || normalized.contains("bicycle") || normalized.contains("bike")) {
            return "bicycle";
        }
        if (normalized.contains("摩托车") || normalized.contains("摩托") || normalized.contains("机车")
                || normalized.contains("motorcycle") || normalized.contains("motorbike") || normalized.contains("scooter")) {
            return "motorcycle";
        }
        if (normalized.equals("车") || normalized.contains("车辆") || normalized.contains("汽车")
                || normalized.contains("机动车") || normalized.contains("car") || normalized.contains("vehicle")) {
            return "car";
        }
        if (normalized.equals("人") || normalized.contains("行人") || normalized.contains("人员")
                || normalized.contains("person") || normalized.contains("people") || normalized.contains("pedestrian") || normalized.contains("human")) {
            return "person";
        }
        return looksEnglish(label) ? label.trim().toLowerCase(Locale.ROOT) : label.trim();
    }

    private boolean looksEnglish(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.chars().noneMatch(ch -> ch >= 0x4E00 && ch <= 0x9FFF);
    }

    private List<ModelRegistry> matchRegistryModels(List<String> labels, List<ModelRegistry> xingmuModels) {
        List<ModelRegistry> matched = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String label : labels) {
            String normalizedLabel = normalize(label);
            ModelRegistry best = null;
            int bestScore = 0;
            for (ModelRegistry model : xingmuModels) {
                if (seen.contains(model.getModelId())) {
                    continue;
                }
                if (!routingEnabled(model)) {
                    continue;
                }
                int score = modelMatchScore(normalizedLabel, model);
                if (score > bestScore) {
                    best = model;
                    bestScore = score;
                }
            }
            if (best != null && bestScore > 0) {
                matched.add(best);
                seen.add(best.getModelId());
            }
        }
        return matched;
    }

    private List<ModelRegistry> availableModels(List<ModelRegistry> models) {
        return models.stream()
                .filter(model -> model.getStatus() == ModelRegistry.Status.AVAILABLE)
                .toList();
    }

    private List<String> unavailableModelIds(List<ModelRegistry> models) {
        return models.stream()
                .filter(model -> model.getStatus() != ModelRegistry.Status.AVAILABLE)
                .map(ModelRegistry::getModelId)
                .toList();
    }

    private boolean matchesModel(String normalizedLabel, ModelRegistry model) {
        return modelMatchScore(normalizedLabel, model) > 0;
    }

    private int modelMatchScore(String normalizedLabel, ModelRegistry model) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return 0;
        }
        int bestScore = 0;
        for (String alias : aliasesForMatch(model)) {
            String normalizedAlias = normalize(alias);
            if (normalizedAlias.isBlank()) {
                continue;
            }
            int score = 0;
            if (normalizedLabel.equals(normalizedAlias)) {
                score = 1000 + normalizedAlias.length();
            } else if (normalizedLabel.contains(normalizedAlias)) {
                score = 500 + normalizedAlias.length();
            } else if (normalizedAlias.contains(normalizedLabel)) {
                score = 400 + normalizedLabel.length();
            }
            bestScore = Math.max(bestScore, score);
        }
        return bestScore;
    }

    private List<String> aliasesForMatch(ModelRegistry model) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (model.getName() != null) {
            values.add(model.getName());
        }
        if (model.getClassesJson() != null) {
            values.addAll(model.getClassesJson());
        }
        if (model.getAliasesJson() != null) {
            values.addAll(model.getAliasesJson());
        }
        return new ArrayList<>(values);
    }

    private boolean routingEnabled(ModelRegistry model) {
        Map<String, Object> resource = model.getResourceJson();
        if (resource == null || !resource.containsKey("routing_enabled")) {
            return true;
        }
        Object value = resource.get("routing_enabled");
        if (value instanceof Boolean bool) {
            return bool;
        }
        return !"false".equalsIgnoreCase(String.valueOf(value));
    }

    private List<String> unavailableLocalQualityModels() {
        List<String> unavailable = new ArrayList<>();
        for (String modelId : List.of(LOCAL_LOCATE_ANYTHING_MODEL_ID, LOCAL_VLM_MODEL_ID)) {
            Optional<ModelRegistry> model = modelRegistryRepository.findByModelId(modelId);
            if (model.isEmpty() || model.get().getStatus() != ModelRegistry.Status.AVAILABLE) {
                unavailable.add(modelId);
            }
        }
        return unavailable;
    }

    private List<String> availableLocalQualityModels() {
        List<String> available = new ArrayList<>();
        for (String modelId : List.of(LOCAL_LOCATE_ANYTHING_MODEL_ID, LOCAL_VLM_MODEL_ID)) {
            Optional<ModelRegistry> model = modelRegistryRepository.findByModelId(modelId);
            if (model.isPresent() && model.get().getStatus() == ModelRegistry.Status.AVAILABLE) {
                available.add(modelId);
            }
        }
        return available;
    }

    @SuppressWarnings("unchecked")
    private int refreshLocalModelRuntimeStatus() {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(
                    algorithmServiceUrl + "/internal/models/adapters/status", Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return 0;
            }
            Map<String, Object> body = objectMap(response.getBody());
            Map<String, Object> items = objectMap(body.get("items"));
            int updated = 0;
            updated += updateLocalModelRuntimeStatus(
                    LOCAL_LOCATE_ANYTHING_MODEL_ID, objectMap(items.get("locate_anything")));
            updated += updateLocalModelRuntimeStatus(
                    LOCAL_VLM_MODEL_ID, objectMap(items.get("local_vlm")));
            return updated;
        } catch (RestClientException e) {
            log.warn("Unable to refresh local model runtime status from algorithm-service: {}", e.getMessage());
            return 0;
        }
    }

    private int updateLocalModelRuntimeStatus(String modelId, Map<String, Object> status) {
        if (status == null || status.isEmpty()) {
            return 0;
        }
        Optional<ModelRegistry> modelOptional = modelRegistryRepository.findByModelId(modelId);
        if (modelOptional.isEmpty()) {
            return 0;
        }
        ModelRegistry model = modelOptional.get();
        boolean available = Boolean.TRUE.equals(status.get("available"));
        model.setStatus(available ? ModelRegistry.Status.AVAILABLE : ModelRegistry.Status.UNAVAILABLE);
        Object version = status.get("version");
        if (version != null && !String.valueOf(version).isBlank()) {
            model.setVersion(String.valueOf(version));
        }
        Object endpoint = status.get("endpoint");
        if (endpoint != null && !String.valueOf(endpoint).isBlank()) {
            model.setEndpoint(String.valueOf(endpoint));
        }
        model.setUnavailableReason(available ? null : runtimeReason(status));
        Map<String, Object> resource = model.getResourceJson() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(model.getResourceJson());
        resource.put("runtime_status", status);
        resource.put("external_api", false);
        model.setResourceJson(resource);
        modelRegistryRepository.save(model);
        return 1;
    }

    private String runtimeReason(Map<String, Object> status) {
        Object reason = firstObject(status, "reason", "load_error", "import_error");
        if (reason == null) {
            return "本地大模型服务当前不可用，禁止回退到外部 API。";
        }
        String text = String.valueOf(reason);
        return text.isBlank() ? "本地大模型服务当前不可用，禁止回退到外部 API。" : truncate(text, 500);
    }

    private Map<String, Object> callAlgorithmOrchestrator(String path, Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("algorithmServiceUrl", algorithmServiceUrl);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(algorithmServiceUrl + path, payload, Map.class);
            result.put("available", response.getStatusCode().is2xxSuccessful());
            result.put("statusCode", response.getStatusCode().value());
            result.put("body", response.getBody());
            return result;
        } catch (RestClientException e) {
            result.put("available", false);
            result.put("reason", "algorithm-service 编排接口当前不可用或尚未实现: " + e.getMessage());
            result.put("nextAction", "进入 Stage 3 后实现 algorithm-service 内部编排接口，再重试该动作。");
            return result;
        }
    }

    private Map<String, Object> fetchAlgorithmJobProgress(AutoLabelJob job) {
        if (job == null || job.getId() == null || !isActiveJobStatus(job.getStatus())) {
            return Collections.emptyMap();
        }
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(
                    algorithmServiceUrl + "/internal/auto-label/progress/" + job.getId(),
                    Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                return Collections.emptyMap();
            }
            Map<String, Object> body = objectMap(response.getBody());
            if (!Boolean.TRUE.equals(body.get("available"))) {
                return Collections.emptyMap();
            }
            return body;
        } catch (RestClientException e) {
            log.debug("Auto-label progress endpoint unavailable: jobId={}, reason={}", job.getId(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    private boolean isActiveJobStatus(AutoLabelJob.Status status) {
        return List.of(AutoLabelJob.Status.RUNNING, AutoLabelJob.Status.PROBING, AutoLabelJob.Status.SYNCING).contains(status);
    }

    private boolean isDryRunAlgorithmResult(Map<String, Object> algorithmResult) {
        String status = algorithmStatus(algorithmResult);
        return status != null && status.startsWith("DRY_RUN");
    }

    @SuppressWarnings("unchecked")
    private String algorithmStatus(Map<String, Object> algorithmResult) {
        Object body = algorithmResult.get("body");
        if (body instanceof Map<?, ?> map) {
            Object status = map.get("status");
            return status == null ? null : String.valueOf(status);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> algorithmBody(Map<String, Object> algorithmResult) {
        Object body = algorithmResult.get("body");
        return body instanceof Map<?, ?> map ? (Map<String, Object>) map : Collections.emptyMap();
    }

    private Map<String, Object> algorithmSummary(Map<String, Object> algorithmResult) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (algorithmResult == null || algorithmResult.isEmpty()) {
            return summary;
        }
        for (String key : List.of("path", "algorithmServiceUrl", "available", "statusCode", "reason", "nextAction")) {
            if (algorithmResult.containsKey(key)) {
                summary.put(key, algorithmResult.get(key));
            }
        }
        Map<String, Object> body = algorithmBody(algorithmResult);
        for (String key : List.of(
                "status",
                "available",
                "job_id",
                "image_count",
                "candidate_count",
                "prediction_count",
                "result_count",
                "frame_count",
                "fused_count",
                "external_api_used",
                "reason",
                "message")) {
            if (body.containsKey(key)) {
                summary.put(key, body.get(key));
            }
        }
        Object adapterReports = body.get("adapter_reports");
        if (adapterReports instanceof List<?> list) {
            summary.put("adapter_report_count", list.size());
        }
        return summary;
    }

    private Map<String, Object> jobPayload(AutoLabelJob job) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("job_id", job.getId());
        payload.put("project_id", job.getProject().getId());
        payload.put("dataset_id", job.getDatasetId());
        payload.put("pipeline", PIPELINE);
        payload.put("strategy", STRATEGY);
        payload.put("priority_mode", PRIORITY_MODE);
        if (job.getRoutePlan() != null) {
            Map<String, Object> routePlan = modelRoutePlanToMap(job.getRoutePlan());
            if (!jobSemanticVerificationEnabled(job)) {
                routePlan = withoutSemanticVerifier(routePlan);
            }
            payload.put("route_plan", routePlan);
        }
        payload.put("semantic_verification_enabled", jobSemanticVerificationEnabled(job));
        payload.put("image_paths", jobImagePaths(job));
        return payload;
    }

    private List<String> jobImagePaths(AutoLabelJob job) {
        return jobImages(job).stream()
                .map(this::uploadPath)
                .filter(path -> path != null && !path.isBlank())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<ProjectImage> jobImages(AutoLabelJob job) {
        List<ProjectImage> projectImages = projectImageRepository.findProjectImagesNativeByType(job.getProject().getId());
        Object selected = job.getRuntimeJson() == null ? null : job.getRuntimeJson().get("selected_image_ids");
        if (!(selected instanceof List<?> selectedIds) || selectedIds.isEmpty()) {
            return projectImages;
        }
        Set<Long> selectedIdSet = new LinkedHashSet<>();
        for (Object value : selectedIds) {
            Long id = asLong(value);
            if (id != null) {
                selectedIdSet.add(id);
            }
        }
        return projectImages.stream()
                .filter(image -> selectedIdSet.contains(image.getId()))
                .toList();
    }

    private List<Long> selectedImageIds(Long projectId, Map<String, Object> request) {
        Object raw = firstObject(request, "image_ids", "imageIds", "selected_image_ids", "selectedImageIds");
        if (!(raw instanceof List<?> values)) {
            return Collections.emptyList();
        }
        Set<Long> projectImageIds = projectImageRepository.findProjectImagesNativeByType(projectId).stream()
                .map(ProjectImage::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Long> selected = new ArrayList<>();
        for (Object value : values) {
            Long id = asLong(value);
            if (id != null && projectImageIds.contains(id)) {
                selected.add(id);
            }
        }
        return selected;
    }

    @SuppressWarnings("unchecked")
    private int savePredictionCandidates(AutoLabelJob job, Map<String, Object> runBody) {
        predictionCandidateRepository.deleteByJobId(job.getId());
        Object rawCandidates = runBody.get("candidates");
        if (!(rawCandidates instanceof List<?> candidates)) {
            return 0;
        }
        Map<String, ProjectImage> imageLookup = projectImageLookup(job.getProject().getId());
        int saved = 0;
        for (Object item : candidates) {
            if (!(item instanceof Map<?, ?> candidate)) {
                continue;
            }
            ProjectImage image = resolveCandidateImage(candidate, imageLookup);
            List<Double> bbox = doubleList(firstObject(candidate, "bbox_xyxy", "bbox"));
            String label = stringValue(candidate.get("label"));
            String sourceModel = stringValue(candidate.get("source_model"));
            if (image == null || label == null || label.isBlank() || sourceModel == null || sourceModel.isBlank() || bbox.size() < 4) {
                continue;
            }
            PredictionCandidate entity = PredictionCandidate.builder()
                    .job(job)
                    .imageId(image.getId())
                    .label(label)
                    .bboxJson(bbox.subList(0, 4))
                    .score(asDouble(candidate.get("score"), 0.0))
                    .sourceModel(sourceModel)
                    .modelVersion(stringValue(candidate.get("model_version")))
                    .prompt(stringValue(candidate.get("prompt")))
                    .rawOutputJson((Map<String, Object>) candidate)
                    .build();
            predictionCandidateRepository.save(entity);
            saved++;
        }
        return saved;
    }

    @SuppressWarnings("unchecked")
    private int saveFusedPredictions(AutoLabelJob job, Map<String, Object> fusionBody) {
        fusedPredictionRepository.deleteByJobId(job.getId());
        Object rawPredictions = fusionBody.get("fused_predictions");
        if (!(rawPredictions instanceof List<?> predictions)) {
            return 0;
        }
        Map<String, ProjectImage> imageLookup = projectImageLookup(job.getProject().getId());
        int saved = 0;
        for (Object item : predictions) {
            if (!(item instanceof Map<?, ?> prediction)) {
                continue;
            }
            ProjectImage image = resolveCandidateImage(prediction, imageLookup);
            List<Double> bbox = doubleList(firstObject(prediction, "bbox_xyxy", "bbox"));
            String label = stringValue(prediction.get("label"));
            if (image == null || label == null || label.isBlank() || bbox.size() < 4) {
                continue;
            }
            FusedPrediction entity = FusedPrediction.builder()
                    .job(job)
                    .imageId(image.getId())
                    .label(label)
                    .bboxJson(bbox.subList(0, 4))
                    .finalScore(asDouble(firstObject(prediction, "final_score", "score"), 0.0))
                    .sourceModelsJson(stringList(prediction.get("source_models")))
                    .fusionReason(stringValue(prediction.get("fusion_reason")))
                    .reviewPriority(parseReviewPriority(prediction.get("review_priority")))
                    .syncedToLabelStudio(false)
                    .build();
            fusedPredictionRepository.save(entity);
            saved++;
        }
        return saved;
    }

    private List<Map<String, Object>> fusedPredictionsForFormatter(AutoLabelJob job) {
        Map<Long, ProjectImage> imagesById = new LinkedHashMap<>();
        for (ProjectImage image : projectImageRepository.findProjectImagesNativeByType(job.getProject().getId())) {
            imagesById.put(image.getId(), image);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (FusedPrediction prediction : fusedPredictionRepository.findByJobIdOrderByCreatedAtAsc(job.getId())) {
            ProjectImage image = imagesById.get(prediction.getImageId());
            if (image == null) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("image_path", uploadPath(image));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("image_id", image.getFileName());
            item.put("label", prediction.getLabel());
            item.put("bbox_xyxy", prediction.getBboxJson());
            item.put("final_score", prediction.getFinalScore());
            item.put("source_models", prediction.getSourceModelsJson());
            item.put("fusion_reason", prediction.getFusionReason());
            item.put("review_priority", prediction.getReviewPriority().name());
            item.put("metadata", metadata);
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> annotatedVideoPayloads(AutoLabelJob job) {
        Path uploadRoot = Paths.get(uploadBasePath).toAbsolutePath().normalize();
        String variant = jobSemanticVerificationEnabled(job) ? "checked" : "raw";

        Map<Long, List<FusedPrediction>> predictionsByImage = new LinkedHashMap<>();
        for (FusedPrediction prediction : fusedPredictionRepository.findByJobIdOrderByCreatedAtAsc(job.getId())) {
            predictionsByImage
                    .computeIfAbsent(prediction.getImageId(), ignored -> new ArrayList<>())
                    .add(prediction);
        }

        List<ProjectImage> orderedImages = new ArrayList<>(jobImages(job));
        orderedImages.sort((left, right) -> {
            Map<String, Object> leftMeta = imageMetadata(left);
            Map<String, Object> rightMeta = imageMetadata(right);
            int bySource = String.valueOf(leftMeta.getOrDefault("source_video_id", ""))
                    .compareTo(String.valueOf(rightMeta.getOrDefault("source_video_id", "")));
            if (bySource != 0) {
                return bySource;
            }
            return Double.compare(doubleOrZero(leftMeta.get("timestamp_sec")), doubleOrZero(rightMeta.get("timestamp_sec")));
        });

        Map<String, List<Map<String, Object>>> framesBySource = new LinkedHashMap<>();
        for (ProjectImage image : orderedImages) {
            Map<String, Object> metadata = imageMetadata(image);
            String sourceVideoId = stringValue(metadata.get("source_video_id"));
            if (sourceVideoId == null || sourceVideoId.isBlank()) {
                continue;
            }
            String imagePath = uploadPath(image);
            if (imagePath == null || imagePath.isBlank()) {
                continue;
            }
            List<FusedPrediction> predictions = predictionsByImage.getOrDefault(image.getId(), Collections.emptyList());
            List<Map<String, Object>> predictionItems = new ArrayList<>();
            for (FusedPrediction prediction : predictions) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("label", prediction.getLabel());
                item.put("bbox", prediction.getBboxJson());
                item.put("score", prediction.getFinalScore());
                item.put("source_models", prediction.getSourceModelsJson());
                item.put("review_priority", prediction.getReviewPriority() == null ? null : prediction.getReviewPriority().name());
                predictionItems.add(item);
            }

            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("image_id", image.getFileName());
            frame.put("image_path", imagePath);
            frame.put("file_path", image.getFilePath());
            frame.put("source_video_id", sourceVideoId);
            frame.put("source_video_name", metadata.get("source_video_name"));
            frame.put("timestamp_sec", metadata.get("timestamp_sec"));
            frame.put("frame_index", metadata.get("frame_index"));
            frame.put("predictions", predictionItems);
            framesBySource.computeIfAbsent(sourceVideoId, ignored -> new ArrayList<>()).add(frame);
        }

        List<Map<String, Object>> payloads = new ArrayList<>();
        for (ProjectVideo video : projectVideoRepository.findByProjectIdOrderByCreatedAtAsc(job.getProject().getId())) {
            List<Map<String, Object>> frames = framesBySource.getOrDefault(video.getSourceVideoId(), Collections.emptyList());
            if (frames.isEmpty()) {
                continue;
            }
            String relativePath = job.getProject().getId()
                    + "/annotated-videos/" + video.getSourceVideoId()
                    + "/job_" + job.getId() + "_" + variant + "_sampled_1fps.mp4";
            Path outputPath = uploadRoot.resolve(relativePath).normalize();
            if (!outputPath.startsWith(uploadRoot)) {
                throw new IllegalArgumentException("标注视频输出路径越界");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("job_id", job.getId());
            payload.put("project_id", job.getProject().getId());
            payload.put("semantic_verification_enabled", jobSemanticVerificationEnabled(job));
            payload.put("variant", jobSemanticVerificationEnabled(job) ? "quality_checked" : "raw_detector_only");
            payload.put("output_path", outputPath.toString());
            payload.put("source_video_id", video.getSourceVideoId());
            payload.put("source_video_name", video.getOriginalFileName());
            payload.put("mode", "sampled_keyframe_video");
            payload.put("fps", 1.0);
            payload.put("frames", frames);
            payloads.add(payload);
        }
        return payloads;
    }

    private Map<String, Object> annotatedVideoFromRuntime(AutoLabelJob job) {
        if (job == null || job.getRuntimeJson() == null) {
            return null;
        }
        Object value = job.getRuntimeJson().get("annotated_video");
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            typed.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return publicMap(typed);
    }

    private List<Map<String, Object>> annotatedVideosFromRuntime(AutoLabelJob job) {
        if (job == null || job.getRuntimeJson() == null) {
            return Collections.emptyList();
        }
        Object value = job.getRuntimeJson().get("annotated_videos");
        if (!(value instanceof List<?> list)) {
            Map<String, Object> single = annotatedVideoFromRuntime(job);
            return single == null || single.isEmpty() ? Collections.emptyList() : List.of(single);
        }
        List<Map<String, Object>> videos = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = objectMap(item);
            if (!map.isEmpty()) {
                videos.add(publicMap(map));
            }
        }
        return videos;
    }

    private void updateProjectVideoRenderedOutputs(Long projectId, List<Map<String, Object>> renderedVideos) {
        Map<String, ProjectVideo> videosBySource = new LinkedHashMap<>();
        for (ProjectVideo video : projectVideoRepository.findByProjectIdOrderByCreatedAtAsc(projectId)) {
            videosBySource.put(video.getSourceVideoId(), video);
        }
        for (Map<String, Object> renderedVideo : renderedVideos) {
            String sourceVideoId = stringValue(renderedVideo.get("sourceVideoId"));
            ProjectVideo video = videosBySource.get(sourceVideoId);
            if (video == null) {
                continue;
            }
            List<Map<String, Object>> existing = video.getAnnotatedVideosJson() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(video.getAnnotatedVideosJson());
            existing.removeIf(item -> Objects.equals(item.get("relativePath"), renderedVideo.get("relativePath"))
                    || Objects.equals(item.get("mode"), renderedVideo.get("mode")));
            existing.add(new LinkedHashMap<>(renderedVideo));
            video.setAnnotatedVideosJson(existing);
            projectVideoRepository.save(video);
        }
    }

    private boolean annotatedVideoFileExists(Map<String, Object> annotatedVideo) {
        if (annotatedVideo == null || annotatedVideo.isEmpty()) {
            return false;
        }
        Object relativePathValue = firstObject(annotatedVideo, "relativePath", "output_relative_path", "outputRelativePath");
        String relativePath = relativePathValue == null ? null : String.valueOf(relativePathValue).trim();
        if ((relativePath == null || relativePath.isBlank()) && annotatedVideo.get("url") != null) {
            String url = String.valueOf(annotatedVideo.get("url"));
            String prefix = "/api/v1/files/";
            int index = url.indexOf(prefix);
            if (index >= 0) {
                relativePath = url.substring(index + prefix.length());
            }
        }
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        Path uploadRoot = Paths.get(uploadBasePath).toAbsolutePath().normalize();
        Path videoPath = uploadRoot.resolve(relativePath).normalize();
        return videoPath.startsWith(uploadRoot) && Files.isRegularFile(videoPath);
    }

    private boolean currentSampledAnnotatedVideoExists(Map<String, Object> annotatedVideo) {
        Object mode = firstObject(annotatedVideo, "mode");
        return "sampled_keyframe_video".equals(String.valueOf(mode)) && annotatedVideoFileExists(annotatedVideo);
    }

    private Map<String, Object> imageMetadata(ProjectImage image) {
        return image.getMetadataJson() == null ? Collections.emptyMap() : image.getMetadataJson();
    }

    private double doubleOrZero(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private Long ensureLabelStudioProject(Project project, Long userId) {
        if (project.getLsProjectId() != null) {
            return project.getLsProjectId();
        }
        return labelStudioProxyService.syncProjectToLS(project, userId);
    }

    private void prepareLabelStudioTasks(Project project, Long lsProjectId, Long userId) {
        if (lsProjectId == null) {
            return;
        }
        labelStudioProxyService.prepareProjectReviewWorkspace(project, userId);
        for (int i = 0; i < 10; i++) {
            int count = labelStudioProxyService.getProjectTaskCount(lsProjectId, userId);
            if (count > 0) {
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Map<String, Object> importPredictionsWithRetry(Long lsProjectId, List<Map<String, Object>> predictions, Long userId) {
        Map<String, Object> first = labelStudioProxyService.importPredictions(lsProjectId, predictions, userId);
        if (asInt(first.get("success"), 0) > 0 || predictions.isEmpty()) {
            return first;
        }
        Map<String, Object> second = labelStudioProxyService.importPredictions(lsProjectId, predictions, userId);
        second.put("retryAttempted", true);
        second.put("firstAttempt", first);
        return second;
    }

    private Map<String, Object> quickReviewStats(int taskCount, int tasksWithPredictions, int totalPredictionResults) {
        Map<String, Object> stats = new LinkedHashMap<>();
        int safeTaskCount = Math.max(0, taskCount);
        int safePredictedTasks = Math.max(0, Math.min(tasksWithPredictions, safeTaskCount));
        stats.put("totalTasks", safeTaskCount);
        stats.put("reviewedTasks", 0);
        stats.put("pendingTasks", safeTaskCount);
        stats.put("tasksWithPredictions", safePredictedTasks);
        stats.put("totalPredictions", safePredictedTasks);
        stats.put("totalPredictionResults", Math.max(0, totalPredictionResults));
        stats.put("totalAnnotationResults", 0);
        stats.put("source", "platform_sync_summary");
        return stats;
    }

    private void markFusedPredictionsSynced(Long jobId) {
        List<FusedPrediction> predictions = fusedPredictionRepository.findByJobIdOrderByCreatedAtAsc(jobId);
        for (FusedPrediction prediction : predictions) {
            prediction.setSyncedToLabelStudio(true);
        }
        fusedPredictionRepository.saveAll(predictions);
    }

    @SuppressWarnings("unchecked")
    private ReviewedDatasetResult buildReviewedYoloDataset(Project project, List<Map<String, Object>> reviewed, AutoLabelJob job) {
        try {
            String versionSeed = String.valueOf(System.currentTimeMillis());
            Path outputDir = Paths.get("/root/autodl-fs/Annotation-Platform/training_runs",
                    "project_" + project.getId(),
                    "reviewed_auto_label_" + versionSeed);
            Path trainImages = outputDir.resolve("images/train");
            Path valImages = outputDir.resolve("images/val");
            Path trainLabels = outputDir.resolve("labels/train");
            Path valLabels = outputDir.resolve("labels/val");
            Files.createDirectories(trainImages);
            Files.createDirectories(valImages);
            Files.createDirectories(trainLabels);
            Files.createDirectories(valLabels);

            Map<String, Integer> labelMap = labelMap(project);
            int validIndex = 0;
            int trainCount = 0;
            int valCount = 0;
            int annotations = 0;
            List<String> missingImages = new ArrayList<>();

            for (Map<String, Object> item : reviewed) {
                if (!"annotation".equals(item.get("source"))) {
                    continue;
                }
                String imageName = baseName(stringValue(item.get("image_name")));
                if (imageName.isBlank()) {
                    continue;
                }
                Object rawBoxes = item.get("annotations");
                if (!(rawBoxes instanceof List<?> boxes) || boxes.isEmpty()) {
                    continue;
                }
                Path sourceImage = Paths.get("/root/autodl-fs/uploads", String.valueOf(project.getId()), imageName);
                if (!Files.exists(sourceImage)) {
                    missingImages.add(imageName);
                    continue;
                }
                String split = validIndex > 0 && (validIndex + 1) % 5 == 0 ? "val" : "train";
                Path imageTarget = ("val".equals(split) ? valImages : trainImages).resolve(imageName);
                Files.copy(sourceImage, imageTarget, StandardCopyOption.REPLACE_EXISTING);

                List<String> yoloLines = new ArrayList<>();
                for (Object boxObj : boxes) {
                    if (!(boxObj instanceof Map<?, ?> box)) {
                        continue;
                    }
                    String label = stringValue(box.get("label"));
                    Integer classId = labelMap.get(label);
                    if (classId == null) {
                        continue;
                    }
                    double x = asDouble(box.get("x"), 0.0) / 100.0;
                    double y = asDouble(box.get("y"), 0.0) / 100.0;
                    double width = asDouble(box.get("width"), 0.0) / 100.0;
                    double height = asDouble(box.get("height"), 0.0) / 100.0;
                    if (width <= 0 || height <= 0) {
                        continue;
                    }
                    double centerX = clamp01(x + width / 2.0);
                    double centerY = clamp01(y + height / 2.0);
                    yoloLines.add(String.format(Locale.US, "%d %.6f %.6f %.6f %.6f",
                            classId, centerX, centerY, clamp01(width), clamp01(height)));
                }
                if (yoloLines.isEmpty()) {
                    continue;
                }
                Path labelTarget = ("val".equals(split) ? valLabels : trainLabels)
                        .resolve(imageName.replaceFirst("\\.[^.]+$", ".txt"));
                Files.write(labelTarget, yoloLines, StandardCharsets.UTF_8);
                annotations += yoloLines.size();
                if ("val".equals(split)) {
                    valCount++;
                } else {
                    trainCount++;
                }
                validIndex++;
            }

            writeDataYaml(outputDir, labelMap);
            Map<String, Object> modelCard = modelCard(project, job, outputDir, trainCount, valCount, annotations, labelMap,
                    versionSeed, missingImages);
            modelCard.put("datasetSource", "reviewed-labels");
            Files.writeString(outputDir.resolve("model_card.md"), modelCardMarkdown(modelCard), StandardCharsets.UTF_8);
            return new ReviewedDatasetResult(outputDir, trainCount, valCount, annotations, labelMap, modelCard);
        } catch (Exception e) {
            throw new RuntimeException("构建已复核训练数据集失败: " + e.getMessage(), e);
        }
    }

    private ReviewedDatasetResult buildFusedPredictionYoloDataset(Project project, AutoLabelJob job) {
        try {
            String versionSeed = String.valueOf(System.currentTimeMillis());
            Path outputDir = Paths.get("/root/autodl-fs/Annotation-Platform/training_runs",
                    "project_" + project.getId(),
                    "unreviewed_fused_auto_label_" + versionSeed);
            Path trainImages = outputDir.resolve("images/train");
            Path valImages = outputDir.resolve("images/val");
            Path trainLabels = outputDir.resolve("labels/train");
            Path valLabels = outputDir.resolve("labels/val");
            Files.createDirectories(trainImages);
            Files.createDirectories(valImages);
            Files.createDirectories(trainLabels);
            Files.createDirectories(valLabels);

            Map<String, Integer> labelMap = labelMap(project);
            Map<Long, ProjectImage> imagesById = new LinkedHashMap<>();
            for (ProjectImage image : projectImageRepository.findProjectImagesNativeByType(project.getId())) {
                imagesById.put(image.getId(), image);
            }

            Map<Long, List<String>> labelsByImage = new LinkedHashMap<>();
            Map<Long, String> splitByImage = new LinkedHashMap<>();
            Map<Long, ProjectImage> selectedImages = new LinkedHashMap<>();
            LinkedHashSet<String> missingImages = new LinkedHashSet<>();
            LinkedHashSet<String> invalidBoxes = new LinkedHashSet<>();
            int validImageIndex = 0;
            int annotations = 0;

            for (FusedPrediction prediction : fusedPredictionRepository.findByJobIdOrderByCreatedAtAsc(job.getId())) {
                String label = prediction.getLabel() == null ? "" : prediction.getLabel().trim();
                if (label.isBlank()) {
                    continue;
                }
                Integer classId = labelMap.get(label);
                if (classId == null) {
                    classId = labelMap.size();
                    labelMap.put(label, classId);
                }

                ProjectImage image = imagesById.get(prediction.getImageId());
                if (image == null) {
                    missingImages.add("image_id=" + prediction.getImageId());
                    continue;
                }
                String sourcePath = uploadPath(image);
                if (sourcePath == null || sourcePath.isBlank()) {
                    missingImages.add(image.getFileName());
                    continue;
                }
                Path sourceImage = Paths.get(sourcePath);
                if (!Files.exists(sourceImage)) {
                    missingImages.add(image.getFileName());
                    continue;
                }
                BufferedImage bufferedImage = ImageIO.read(sourceImage.toFile());
                if (bufferedImage == null || bufferedImage.getWidth() <= 0 || bufferedImage.getHeight() <= 0) {
                    missingImages.add(image.getFileName());
                    continue;
                }

                List<Double> bbox = prediction.getBboxJson();
                if (bbox == null || bbox.size() < 4) {
                    invalidBoxes.add(image.getFileName());
                    continue;
                }
                double imageWidth = bufferedImage.getWidth();
                double imageHeight = bufferedImage.getHeight();
                double x1 = clamp(asDouble(bbox.get(0), 0.0), 0.0, imageWidth);
                double y1 = clamp(asDouble(bbox.get(1), 0.0), 0.0, imageHeight);
                double x2 = clamp(asDouble(bbox.get(2), 0.0), 0.0, imageWidth);
                double y2 = clamp(asDouble(bbox.get(3), 0.0), 0.0, imageHeight);
                if (x2 < x1) {
                    double tmp = x1;
                    x1 = x2;
                    x2 = tmp;
                }
                if (y2 < y1) {
                    double tmp = y1;
                    y1 = y2;
                    y2 = tmp;
                }
                double boxWidth = x2 - x1;
                double boxHeight = y2 - y1;
                if (boxWidth <= 1.0 || boxHeight <= 1.0) {
                    invalidBoxes.add(image.getFileName());
                    continue;
                }

                if (!labelsByImage.containsKey(image.getId())) {
                    String split = validImageIndex > 0 && (validImageIndex + 1) % 5 == 0 ? "val" : "train";
                    labelsByImage.put(image.getId(), new ArrayList<>());
                    splitByImage.put(image.getId(), split);
                    selectedImages.put(image.getId(), image);
                    validImageIndex++;
                }
                double centerX = clamp01((x1 + boxWidth / 2.0) / imageWidth);
                double centerY = clamp01((y1 + boxHeight / 2.0) / imageHeight);
                labelsByImage.get(image.getId()).add(String.format(Locale.US, "%d %.6f %.6f %.6f %.6f",
                        classId, centerX, centerY, clamp01(boxWidth / imageWidth), clamp01(boxHeight / imageHeight)));
                annotations++;
            }

            int trainCount = 0;
            int valCount = 0;
            for (Map.Entry<Long, List<String>> entry : labelsByImage.entrySet()) {
                ProjectImage image = selectedImages.get(entry.getKey());
                if (image == null || entry.getValue().isEmpty()) {
                    continue;
                }
                String split = splitByImage.getOrDefault(entry.getKey(), "train");
                Path sourceImage = Paths.get(uploadPath(image));
                Path imageTarget = ("val".equals(split) ? valImages : trainImages).resolve(image.getFileName());
                Files.copy(sourceImage, imageTarget, StandardCopyOption.REPLACE_EXISTING);
                Path labelTarget = ("val".equals(split) ? valLabels : trainLabels)
                        .resolve(image.getFileName().replaceFirst("\\.[^.]+$", ".txt"));
                Files.write(labelTarget, entry.getValue(), StandardCharsets.UTF_8);
                if ("val".equals(split)) {
                    valCount++;
                } else {
                    trainCount++;
                }
            }

            writeDataYaml(outputDir, labelMap);
            List<String> issues = new ArrayList<>(missingImages);
            invalidBoxes.stream().map(value -> "invalid_bbox:" + value).forEach(issues::add);
            Map<String, Object> modelCard = modelCard(project, job, outputDir, trainCount, valCount, annotations, labelMap,
                    versionSeed, issues);
            modelCard.put("modelName", "auto-label-fused-project-" + project.getId());
            modelCard.put("datasetSource", "unreviewed-fused-predictions");
            modelCard.put("reviewedAnnotationVersion", "unreviewed-fused-" + job.getId() + "-" + versionSeed);
            modelCard.put("applicableScenario", "项目内智能标注融合框快速训练闭环；建议上线前抽样复核。");
            modelCard.put("nonApplicableScenario", "未经过人工复核的高风险场景、跨域数据或未覆盖类别");
            modelCard.put("claimBoundary", "使用未复核智能标注框训练，仅作为快速闭环/增量验证，不等同人工验收标注。");
            Files.writeString(outputDir.resolve("model_card.md"), modelCardMarkdown(modelCard), StandardCharsets.UTF_8);
            return new ReviewedDatasetResult(outputDir, trainCount, valCount, annotations, labelMap, modelCard);
        } catch (Exception e) {
            throw new RuntimeException("构建智能标注融合框训练数据集失败: " + e.getMessage(), e);
        }
    }

    private ModelTrainingRecord createReviewedDryRunTrainingRecord(
            Long projectId,
            Long userId,
            ReviewedDatasetResult dataset,
            int epochs,
            int batchSize,
            int imageSize,
            String modelType,
            String device,
            String reason) {
        ModelTrainingRecord record = ModelTrainingRecord.builder()
                .projectId(projectId)
                .userId(userId)
                .taskId("auto-label-reviewed-dryrun-" + System.currentTimeMillis())
                .runName("reviewed_auto_label_" + System.currentTimeMillis())
                .status(ModelTrainingRecord.TrainingStatus.PENDING)
                .epochs(epochs)
                .batchSize(batchSize)
                .imageSize(imageSize)
                .modelType(modelType)
                .datasetPath(dataset.outputDir().resolve("data.yaml").toString())
                .outputDir(dataset.outputDir().toString())
                .totalImages(dataset.totalImages())
                .totalAnnotations(dataset.totalAnnotations())
                .errorMessage(reason)
                .testResults(com.alibaba.fastjson2.JSON.toJSONString(Map.of(
                        "mode", String.valueOf(dataset.modelCard().getOrDefault("datasetSource", "reviewed-labels")) + "-dry-run",
                        "reason", reason,
                        "dataset", dataset.toMap(),
                        "modelCard", dataset.modelCard()
                )))
                .startedAt(LocalDateTime.now())
                .build();
        return modelTrainingRecordRepository.save(record);
    }

    private Map<String, Integer> labelMap(Project project) {
        Map<String, Integer> labelMap = new LinkedHashMap<>();
        List<String> labels = project.getLabels() == null ? Collections.emptyList() : project.getLabels();
        int index = 0;
        for (String label : labels) {
            if (label != null && !label.isBlank() && !labelMap.containsKey(label)) {
                labelMap.put(label, index++);
            }
        }
        return labelMap;
    }

    private void writeDataYaml(Path outputDir, Map<String, Integer> labelMap) throws Exception {
        StringBuilder yaml = new StringBuilder();
        yaml.append("path: ").append(outputDir).append("\n");
        yaml.append("train: images/train\n");
        yaml.append("val: images/val\n\n");
        yaml.append("names:\n");
        for (Map.Entry<String, Integer> entry : labelMap.entrySet()) {
            yaml.append("  ").append(entry.getValue()).append(": ").append(entry.getKey()).append("\n");
        }
        Files.writeString(outputDir.resolve("data.yaml"), yaml.toString(), StandardCharsets.UTF_8);
    }

    private Map<String, Object> modelCard(Project project,
                                          AutoLabelJob job,
                                          Path outputDir,
                                          int trainCount,
                                          int valCount,
                                          int annotations,
                                          Map<String, Integer> labelMap,
                                          String versionSeed,
                                          List<String> missingImages) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("modelName", "reviewed-auto-label-project-" + project.getId());
        card.put("projectId", project.getId());
        card.put("datasetVersion", "dataset-" + project.getId() + "-" + versionSeed);
        card.put("labelSchemaVersion", "labels-" + project.getId() + "-" + labelMap.size());
        card.put("reviewedAnnotationVersion", "reviewed-" + project.getLsProjectId() + "-" + versionSeed);
        card.put("trainValSplitVersion", "split-80-20-" + versionSeed);
        card.put("jobId", job == null ? null : job.getId());
        card.put("classes", new ArrayList<>(labelMap.keySet()));
        card.put("trainImages", trainCount);
        card.put("valImages", valCount);
        card.put("totalAnnotations", annotations);
        card.put("trainingConfig", Map.of("defaultEpochs", 1, "defaultBatchSize", 2, "defaultImageSize", 640));
        card.put("metrics", Map.of("status", "not_trained_yet", "reason", "dry-run only; no metric is reported"));
        card.put("recommendedThreshold", 0.25);
        card.put("failureSamples", missingImages.stream().limit(20).toList());
        card.put("applicableScenario", "项目内已复核标注对应场景");
        card.put("nonApplicableScenario", "未复核、未覆盖类别或跨域数据");
        card.put("weightsPath", null);
        card.put("inferenceEndpoint", null);
        card.put("datasetPath", outputDir.resolve("data.yaml").toString());
        return card;
    }

    private String modelCardMarkdown(Map<String, Object> card) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# 模型卡\n\n");
        for (Map.Entry<String, Object> entry : card.entrySet()) {
            markdown.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return markdown.toString();
    }

    private Map<String, Object> trainingRecordToMap(ModelTrainingRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "id", record.getId());
        put(map, "projectId", record.getProjectId());
        put(map, "taskId", record.getTaskId());
        put(map, "runName", record.getRunName());
        put(map, "status", record.getStatus());
        put(map, "datasetPath", record.getDatasetPath());
        put(map, "outputDir", record.getOutputDir());
        put(map, "modelType", record.getModelType());
        put(map, "totalImages", record.getTotalImages());
        put(map, "totalAnnotations", record.getTotalAnnotations());
        put(map, "errorMessage", record.getErrorMessage());
        return map;
    }

    private Map<String, ProjectImage> projectImageLookup(Long projectId) {
        Map<String, ProjectImage> lookup = new LinkedHashMap<>();
        for (ProjectImage image : projectImageRepository.findProjectImagesNativeByType(projectId)) {
            lookup.put(String.valueOf(image.getId()), image);
            lookup.put(image.getFileName(), image);
            lookup.put(baseName(image.getFileName()), image);
            lookup.put(image.getFilePath(), image);
            lookup.put(baseName(image.getFilePath()), image);
            lookup.put(uploadPath(image), image);
        }
        return lookup;
    }

    private ProjectImage resolveCandidateImage(Map<?, ?> candidate, Map<String, ProjectImage> lookup) {
        for (String key : List.of("image_id", "imageId", "image_name", "imageName", "image_path", "imagePath")) {
            ProjectImage image = lookup.get(stringValue(candidate.get(key)));
            if (image != null) {
                return image;
            }
        }
        Object metadata = candidate.get("metadata");
        if (metadata instanceof Map<?, ?> map) {
            return lookup.get(baseName(stringValue(map.get("image_path"))));
        }
        return null;
    }

    private String uploadPath(ProjectImage image) {
        if (image == null || image.getFilePath() == null || image.getFilePath().isBlank()) {
            return null;
        }
        String filePath = image.getFilePath();
        if (filePath.startsWith("/")) {
            return filePath;
        }
        return "/root/autodl-fs/uploads/" + filePath;
    }

    private Map<String, Object> mergeRuntime(Map<String, Object> source, String key, Object value) {
        Map<String, Object> runtime = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        runtime.put(key, value);
        runtime.put("last_updated_at", LocalDateTime.now().toString());
        runtime.put("priority_mode", PRIORITY_MODE);
        runtime.put("strategy", STRATEGY);
        return runtime;
    }

    private Map<String, Object> withoutSemanticVerifier(Map<String, Object> routePlan) {
        Map<String, Object> result = new LinkedHashMap<>(routePlan);
        Object rawAuxiliary = result.get("auxiliaryModelIds");
        if (rawAuxiliary instanceof List<?> list) {
            result.put("auxiliaryModelIds", list.stream()
                    .filter(item -> item != null && !LOCAL_VLM_MODEL_ID.equals(String.valueOf(item)))
                    .toList());
        }
        if (LOCAL_VLM_MODEL_ID.equals(String.valueOf(result.get("primaryModelId")))) {
            result.put("primaryModelId", LOCAL_LOCATE_ANYTHING_MODEL_ID);
        }
        Map<String, Object> score = objectMap(result.get("score"));
        if (!score.isEmpty()) {
            score = new LinkedHashMap<>(score);
            score.put("semantic_verification_enabled", false);
            result.put("score", score);
        }
        result.put("semanticVerificationEnabled", false);
        return result;
    }

    private boolean semanticVerificationEnabled(Map<String, Object> request) {
        Object value = firstObject(request, "semantic_verification_enabled", "semanticVerificationEnabled", "use_qwen_vl", "useQwenVl");
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        return !("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text));
    }

    private boolean jobSemanticVerificationEnabled(AutoLabelJob job) {
        Map<String, Object> runtime = job.getRuntimeJson();
        if (runtime == null) {
            return true;
        }
        Object value = firstObject(runtime, "semantic_verification_enabled", "semanticVerificationEnabled");
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        return !("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text));
    }

    private void inspectAndIgnorePriorityMode(Long projectId, Map<String, Object> request) {
        if (request == null) {
            return;
        }
        Object mode = firstObject(request, "priority_mode", "priorityMode", "strategy_mode", "strategyMode");
        if (mode != null && !PRIORITY_MODE.equalsIgnoreCase(String.valueOf(mode))) {
            log.warn("Ignoring unsupported AUTO_LABEL_TO_MODEL priority mode: projectId={}, supplied={}",
                    projectId, truncate(String.valueOf(mode), 80));
        }
    }

    private Map<String, Object> baseResponse() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", UUID.randomUUID().toString());
        data.put("pipeline", PIPELINE);
        data.put("strategy", STRATEGY);
        data.put("priorityMode", PRIORITY_MODE);
        return data;
    }

    private PageSpec predictionPageSpec(Integer page, Integer size, Integer limit, Integer offset) {
        int effectiveSize = asInt(limit != null ? limit : size, DEFAULT_PREDICTION_PREVIEW_LIMIT);
        effectiveSize = Math.max(1, Math.min(effectiveSize, MAX_PREDICTION_PREVIEW_LIMIT));
        int effectivePage = Math.max(0, asInt(page, 0));
        if (page == null && offset != null && offset > 0) {
            effectivePage = Math.max(0, offset / effectiveSize);
        }
        return new PageSpec(effectivePage, effectiveSize, PageRequest.of(effectivePage, effectiveSize));
    }

    private Map<String, Object> modelToMap(ModelRegistry model) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "id", model.getId());
        put(map, "modelId", model.getModelId());
        put(map, "name", model.getName());
        put(map, "version", model.getVersion());
        put(map, "modelType", model.getModelType());
        put(map, "taskType", model.getTaskType());
        put(map, "domainTags", model.getDomainTagsJson());
        put(map, "classes", model.getClassesJson());
        put(map, "aliases", model.getAliasesJson());
        put(map, "endpoint", model.getEndpoint());
        put(map, "status", model.getStatus());
        put(map, "metrics", model.getMetricsJson());
        put(map, "resource", publicMap(model.getResourceJson()));
        put(map, "licenseInfo", model.getLicenseInfo());
        put(map, "unavailableReason", model.getUnavailableReason());
        put(map, "createdAt", model.getCreatedAt());
        put(map, "updatedAt", model.getUpdatedAt());
        return map;
    }

    private Map<String, Object> requirementToMap(ProjectRequirement requirement) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "id", requirement.getId());
        put(map, "projectId", requirement.getProject().getId());
        put(map, "rawUserText", requirement.getRawUserText());
        put(map, "rawUserLabels", requirement.getRawUserLabelsJson());
        put(map, "parsedSchema", requirement.getParsedSchemaJson());
        put(map, "labelSchema", requirement.getLabelSchemaJson());
        put(map, "promptPack", requirement.getPromptPackJson());
        put(map, "createdBy", requirement.getCreatedBy());
        put(map, "createdAt", requirement.getCreatedAt());
        return map;
    }

    private Map<String, Object> datasetProfileToMap(DatasetProfile profile) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "id", profile.getId());
        put(map, "projectId", profile.getProject().getId());
        put(map, "datasetId", profile.getDatasetId());
        put(map, "numImages", profile.getNumImages());
        put(map, "sampleCount", profile.getSampleCount());
        put(map, "profile", profile.getProfileJson());
        put(map, "qualityWarnings", profile.getQualityWarningsJson());
        put(map, "createdAt", profile.getCreatedAt());
        return map;
    }

    private Map<String, Object> modelRoutePlanToMap(ModelRoutePlan routePlan) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "id", routePlan.getId());
        put(map, "projectId", routePlan.getProject().getId());
        put(map, "datasetId", routePlan.getDatasetId());
        put(map, "requirementId", routePlan.getRequirement() != null ? routePlan.getRequirement().getId() : null);
        put(map, "primaryModelId", routePlan.getPrimaryModelId());
        put(map, "auxiliaryModelIds", routePlan.getAuxiliaryModelIdsJson());
        put(map, "strategy", routePlan.getStrategy());
        put(map, "priorityMode", routePlan.getPriorityMode());
        put(map, "reason", routePlan.getReason());
        put(map, "score", routePlan.getScoreJson());
        put(map, "createdAt", routePlan.getCreatedAt());
        return map;
    }

    private Map<String, Object> autoLabelJobToMap(AutoLabelJob job) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "id", job.getId());
        put(map, "projectId", job.getProject().getId());
        put(map, "datasetId", job.getDatasetId());
        put(map, "routePlanId", job.getRoutePlan() != null ? job.getRoutePlan().getId() : null);
        put(map, "status", job.getStatus());
        put(map, "pipeline", job.getPipeline());
        put(map, "priorityMode", job.getPriorityMode());
        put(map, "totalImages", job.getTotalImages());
        put(map, "processedImages", job.getProcessedImages());
        put(map, "failedImages", job.getFailedImages());
        put(map, "createdBy", job.getCreatedBy());
        put(map, "runtime", publicMap(job.getRuntimeJson()));
        put(map, "errorMessage", job.getErrorMessage());
        put(map, "startedAt", job.getStartedAt());
        put(map, "completedAt", job.getCompletedAt());
        put(map, "createdAt", job.getCreatedAt());
        put(map, "updatedAt", job.getUpdatedAt());
        return map;
    }

    private Map<String, Object> predictionCandidateToMap(PredictionCandidate candidate) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "id", candidate.getId());
        put(map, "jobId", candidate.getJob().getId());
        put(map, "imageId", candidate.getImageId());
        put(map, "label", candidate.getLabel());
        put(map, "bbox", candidate.getBboxJson());
        put(map, "score", candidate.getScore());
        put(map, "sourceModel", candidate.getSourceModel());
        put(map, "modelVersion", candidate.getModelVersion());
        put(map, "prompt", candidate.getPrompt());
        put(map, "rawOutput", publicMap(candidate.getRawOutputJson()));
        put(map, "createdAt", candidate.getCreatedAt());
        return map;
    }

    private Map<String, Object> fusedPredictionToMap(FusedPrediction prediction) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "id", prediction.getId());
        put(map, "jobId", prediction.getJob().getId());
        put(map, "imageId", prediction.getImageId());
        put(map, "label", prediction.getLabel());
        put(map, "bbox", prediction.getBboxJson());
        put(map, "finalScore", prediction.getFinalScore());
        put(map, "sourceModels", prediction.getSourceModelsJson());
        put(map, "fusionReason", prediction.getFusionReason());
        put(map, "reviewPriority", prediction.getReviewPriority());
        put(map, "syncedToLabelStudio", prediction.getSyncedToLabelStudio());
        put(map, "createdAt", prediction.getCreatedAt());
        return map;
    }

    private void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private Map<String, Object> publicMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        Object sanitized = redactInternalValues(source);
        if (sanitized instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private Map<String, Object> objectMap(Object source) {
        if (!(source instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private Object redactInternalValues(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isInternalPathKey(key)) {
                    continue;
                }
                Object redacted = redactInternalValues(entry.getValue());
                if (redacted != null) {
                    result.put(key, redacted);
                }
            }
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::redactInternalValues)
                    .filter(item -> item != null)
                    .toList();
        }
        if (value instanceof String text && containsInternalPath(text)) {
            return "[internal-path-redacted]";
        }
        return value;
    }

    private boolean isInternalPathKey(String key) {
        String normalized = normalize(key);
        return normalized.contains("root")
                || normalized.contains("path")
                || normalized.contains("dir")
                || normalized.contains("localstorage")
                || normalized.contains("algorithmservice");
    }

    private boolean containsInternalPath(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.contains("/root/autodl")
                || normalized.contains("127.0.0.1:8001")
                || normalized.contains("localhost:8001");
    }

    private Map<String, Object> warning(String code, String message) {
        Map<String, Object> warning = new LinkedHashMap<>();
        warning.put("code", code);
        warning.put("message", message);
        return warning;
    }

    private String datasetId(Long projectId, Map<String, Object> request) {
        String datasetId = firstString(request, "dataset_id", "datasetId");
        return datasetId == null || datasetId.isBlank() ? "project-" + projectId : datasetId.trim();
    }

    private String firstString(Map<String, Object> request, String... keys) {
        Object value = firstObject(request, keys);
        return value == null ? null : String.valueOf(value).trim();
    }

    private Object firstObject(Map<?, ?> request, String... keys) {
        if (request == null) {
            return null;
        }
        for (String key : keys) {
            if (request.containsKey(key)) {
                return request.get(key);
            }
        }
        return null;
    }

    private String firstNonBlank(Map<?, ?> request, String... keys) {
        Object value = firstObject(request, keys);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private Double asDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<Double> doubleList(Object value) {
        List<Double> values = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                values.add(asDouble(item, 0.0));
            }
        }
        return values;
    }

    private List<String> stringList(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = stringValue(item);
                if (text != null && !text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String baseName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private FusedPrediction.ReviewPriority parseReviewPriority(Object value) {
        if (value == null) {
            return FusedPrediction.ReviewPriority.MEDIUM;
        }
        try {
            return FusedPrediction.ReviewPriority.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FusedPrediction.ReviewPriority.MEDIUM;
        }
    }

    private String generateLabelConfig(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "<View><Image name=\"image\" value=\"$image\"/><RectangleLabels name=\"label\" toName=\"image\"/></View>";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<View>\n");
        sb.append("  <Image name=\"image\" value=\"$image\" zoom=\"true\"/>\n");
        sb.append("  <RectangleLabels name=\"label\" toName=\"image\">\n");
        for (String label : labels) {
            sb.append("    <Label value=\"").append(escapeXmlAttribute(label)).append("\"/>\n");
        }
        sb.append("  </RectangleLabels>\n");
        sb.append("</View>");
        return sb.toString();
    }

    private String escapeXmlAttribute(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private List<String> asStringList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object displayName = map.get("display_name");
                    if (displayName == null) {
                        displayName = map.get("displayName");
                    }
                    if (displayName == null) {
                        displayName = map.get("name");
                    }
                    if (displayName == null) {
                        displayName = map.get("canonical_name");
                    }
                    if (displayName != null && !String.valueOf(displayName).isBlank()) {
                        labels.add(String.valueOf(displayName).trim());
                    }
                } else if (item != null && !String.valueOf(item).isBlank()) {
                    labels.add(String.valueOf(item).trim());
                }
            }
        } else if (value instanceof String text) {
            for (String part : text.split("[,，;；、\\n]+")) {
                if (!part.isBlank()) {
                    labels.add(part.trim());
                }
            }
        }
        return new ArrayList<>(labels);
    }

    private String canonicalName(String label) {
        return normalize(label).replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]+", "_");
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "")
                .replace("/", "")
                .trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private void applySeed(ModelRegistry model, RegistrySeed seed) {
        model.setModelId(seed.modelId());
        model.setName(seed.name());
        model.setVersion(seed.version());
        model.setModelType(seed.modelType());
        model.setTaskType(seed.taskType());
        model.setDomainTagsJson(seed.domainTags());
        model.setClassesJson(seed.classes());
        model.setAliasesJson(seed.aliases());
        model.setEndpoint(seed.endpoint());
        model.setStatus(seed.status());
        model.setMetricsJson(seed.metrics());
        model.setResourceJson(seed.resource());
        model.setLicenseInfo(seed.licenseInfo());
        model.setUnavailableReason(seed.unavailableReason());
    }

    @SuppressWarnings("unchecked")
    private List<RegistrySeed> dynamicRegistrySeedsFromAlgorithmService() {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(
                    algorithmServiceUrl + "/internal/models/registry", Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Collections.emptyList();
            }
            Map<String, Object> body = objectMap(response.getBody());
            Object rawItems = body.get("items");
            if (!(rawItems instanceof List<?> items) || items.isEmpty()) {
                return Collections.emptyList();
            }
            List<RegistrySeed> seeds = new ArrayList<>();
            for (Object rawItem : items) {
                Map<String, Object> item = objectMap(rawItem);
                RegistrySeed seed = registrySeedFromAlgorithmItem(item);
                if (seed != null) {
                    seeds.add(seed);
                }
            }
            return seeds;
        } catch (RestClientException e) {
            log.warn("Unable to sync model registry from algorithm-service: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private RegistrySeed registrySeedFromAlgorithmItem(Map<String, Object> item) {
        String modelId = firstNonBlank(item, "model_id", "modelId");
        String name = firstNonBlank(item, "name");
        String modelType = firstNonBlank(item, "model_type", "modelType");
        String taskType = firstNonBlank(item, "task_type", "taskType");
        if (modelId == null || name == null || modelType == null || taskType == null) {
            return null;
        }
        ModelRegistry.Status status = registryStatus(firstObject(item, "status"), Boolean.TRUE.equals(item.get("available")));
        Map<String, Object> resource = objectMap(item.get("resource"));
        resource = resource.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(resource);
        resource.put("source", firstNonBlank(item, "source"));
        resource.put("excel_no", firstObject(item, "excel_no", "excelNo"));
        resource.put("model_group", firstObject(item, "model_group", "modelGroup"));
        resource.put("event_type", firstObject(item, "event_type", "eventType"));
        resource.put("sample_id", firstObject(item, "sample_id", "sampleId"));
        resource.put("frontend_visible", item.getOrDefault("frontend_visible", item.get("frontendVisible")));
        resource.values().removeIf(value -> value == null || String.valueOf(value).isBlank());

        String unavailableReason = status == ModelRegistry.Status.AVAILABLE
                ? null
                : truncate(String.valueOf(firstObject(item, "unavailable_reason", "unavailableReason", "reason")), 500);
        if ("null".equals(unavailableReason)) {
            unavailableReason = null;
        }
        return new RegistrySeed(
                modelId,
                name,
                stringOrDefault(firstObject(item, "version"), "dynamic"),
                modelType,
                taskType,
                stringList(item.get("domain_tags")),
                stringList(item.get("classes")),
                stringList(item.get("aliases")),
                firstNonBlank(item, "endpoint"),
                status,
                objectMap(item.get("metrics")),
                resource,
                firstNonBlank(item, "license_info", "licenseInfo"),
                unavailableReason
        );
    }

    private ModelRegistry.Status registryStatus(Object value, boolean available) {
        if (value != null) {
            try {
                return ModelRegistry.Status.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to availability-derived status.
            }
        }
        return available ? ModelRegistry.Status.AVAILABLE : ModelRegistry.Status.UNAVAILABLE;
    }

    private String stringOrDefault(Object value, String defaultValue) {
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    private int countXingmuSeeds(List<RegistrySeed> seeds) {
        return countSeedsByType(seeds, MODEL_TYPE_XINGMU);
    }

    private int countSeedsByType(List<RegistrySeed> seeds, String modelType) {
        int count = 0;
        for (RegistrySeed seed : seeds) {
            if (modelType.equals(seed.modelType())) {
                count++;
            }
        }
        return count;
    }

    private List<String> hiddenXingmuScenarios() {
        return List.of("小吃车", "违规摆摊", "非法垂钓", "违停区域占用", "高速应急车道占用",
                "行人闯入高速公路", "林场违建识别", "非法偷盗识别");
    }

    private List<RegistrySeed> registrySeeds() {
        List<RegistrySeed> seeds = new ArrayList<>();
        addXingmu(seeds, 1, "人形", "通用场景", "m1_uav_rgb_general", "/api/model-demo/predict", "image_detection", List.of("人", "人员", "行人", "person", "human"));
        addXingmu(seeds, 2, "红外人形", "通用场景", "m2_thermal", "/api/model-demo/predict", "image_detection", List.of("红外人", "热成像人", "thermal person"));
        addXingmu(seeds, 3, "车辆", "通用场景", "m1_uav_rgb_general", "/api/model-demo/predict", "image_detection", List.of("车", "汽车", "机动车", "vehicle", "car"));
        addXingmu(seeds, 4, "红外车辆", "通用场景", "m2_thermal", "/api/model-demo/predict", "image_detection", List.of("红外车", "热成像车辆", "thermal vehicle"));
        addXingmu(seeds, 5, "人群聚集", "通用场景", null, "/api/model-demo/event-video-replay", "event_sample", List.of("聚集", "人群", "crowd"));
        addXingmu(seeds, 6, "船只", "通用场景", "m1_uav_rgb_general", "/api/model-demo/predict", "image_detection", List.of("船", "船舶", "boat", "ship"));
        addXingmu(seeds, 7, "红外船只", "通用场景", "m2_thermal", "/api/model-demo/predict", "image_detection", List.of("红外船", "热成像船", "thermal boat"));
        addXingmu(seeds, 8, "通用文字OCR识别", "通用场景", null, "/api/model-demo/ocr", "single_image_endpoint", List.of("文字", "OCR", "文本", "text"));
        addXingmu(seeds, 9, "烟火", "消防场景", "m3_smoke_fire", "/api/model-demo/predict", "image_detection", List.of("烟", "火", "烟雾", "火情", "smoke", "fire"));
        addXingmu(seeds, 10, "红外烟火", "消防场景", "m2_thermal", "/api/model-demo/predict", "image_detection", List.of("红外烟火", "热成像火情", "thermal fire"));
        addXingmu(seeds, 11, "安全帽", "工地场景", "m4_ppe", "/api/model-demo/predict", "image_detection", List.of("头盔", "安全帽", "helmet", "hardhat"));
        addXingmu(seeds, 12, "安全帽未佩戴", "工地场景", null, "/api/model-demo/no-helmet-public", "single_image_endpoint", List.of("未戴安全帽", "未佩戴安全帽", "no helmet", "without helmet"));
        addXingmu(seeds, 13, "工程车辆", "工地场景", "m1_uav_rgb_general", "/api/model-demo/predict", "image_detection", List.of("工程车", "construction vehicle"));
        addXingmu(seeds, 14, "挖掘机", "工地场景", "m1_uav_rgb_general", "/api/model-demo/predict", "image_detection", List.of("挖机", "excavator"));
        addXingmu(seeds, 15, "渣土车", "城管场景", "m1_uav_rgb_general", "/api/model-demo/predict", "image_detection", List.of("泥头车", "dump truck"));
        addXingmu(seeds, 16, "垃圾包", "城管场景", "m6_water_env", "/api/model-demo/predict", "image_detection", List.of("垃圾袋", "垃圾堆", "trash bag"));
        addXingmu(seeds, 19, "建筑垃圾乱堆", "城管场景", null, "/api/model-demo/construction-waste-pile-proxy", "single_image_endpoint", List.of("建筑垃圾", "construction waste"));
        addXingmu(seeds, 20, "井盖", "市政场景", null, "/api/model-demo/manhole-abnormal-v2", "single_image_endpoint", List.of("井盖异常", "窨井盖", "manhole"));
        addXingmu(seeds, 21, "太阳能板", "能源场景", "m7_solar", "/api/model-demo/predict", "image_detection", List.of("光伏板", "solar panel"));
        addXingmu(seeds, 23, "水面漂浮物", "水利场景", "m6_water_env", "/api/model-demo/predict", "image_detection", List.of("漂浮物", "floating object"));
        addXingmu(seeds, 24, "水面油污", "水利场景", "m6_water_env", "/api/model-demo/predict", "image_detection", List.of("油污", "oil spill"));
        addXingmu(seeds, 25, "水面垃圾", "水利场景", "m6_water_env", "/api/model-demo/predict", "image_detection", List.of("水上垃圾", "water garbage"));
        addXingmu(seeds, 26, "水面植物", "水利场景", "m6_water_env", "/api/model-demo/predict", "image_detection", List.of("水草", "water plant"));
        addXingmu(seeds, 27, "涉水识别", "水利场景", null, "/api/model-demo/water-intrusion-proxy-v2", "single_image_endpoint", List.of("涉水", "积水行人", "water intrusion"));
        addXingmu(seeds, 28, "东西焚烧", "环保场景", null, "/api/model-demo/open-burning-dual-light-proxy-v2", "single_image_endpoint", List.of("露天焚烧", "焚烧", "open burning"));
        addXingmu(seeds, 29, "交通拥堵", "交通场景", null, "/api/model-demo/event-video-replay", "event_sample", List.of("拥堵", "堵车", "traffic jam"));
        addXingmu(seeds, 30, "路面标线", "交通场景", null, "/api/model-demo/road-marking-wear", "single_image_endpoint", List.of("道路标线", "标线磨损", "road marking"));
        addXingmu(seeds, 31, "路面破损", "交通场景", "m5_road_municipal", "/api/model-demo/predict", "image_detection", List.of("道路破损", "坑槽", "road damage"));
        addXingmu(seeds, 32, "路面杂物", "交通场景", "m5_road_municipal", "/api/model-demo/predict", "image_detection", List.of("道路杂物", "road debris"));
        addXingmu(seeds, 33, "路面积水", "交通场景", "m5_road_municipal", "/api/model-demo/predict", "image_detection", List.of("道路积水", "ponding"));
        addXingmu(seeds, 34, "非机动车道占用", "交通场景", null, "/api/model-demo/traffic-lane-parking-proxy-v2", "single_image_endpoint", List.of("非机动车道停车", "bike lane occupied"));
        addXingmu(seeds, 36, "近景车牌号码识别", "交通场景", null, "/api/model-demo/plate-recognize", "single_image_endpoint", List.of("车牌", "license plate"));

        seeds.add(new RegistrySeed(GROUNDING_DINO_MODEL_ID, "Grounding DINO", "existing", "GROUNDING_DINO",
                "open_vocabulary_detection", List.of("open_vocab", "proposal"), List.of("open-vocabulary"),
                List.of("DINO", "GroundingDINO", "开放词汇检测"), "algorithm-service:/api/v1/algo/dino",
                ModelRegistry.Status.AVAILABLE, Map.of("role", "proposal_teacher"),
                Map.of("service", "dino", "local", true), "Existing project integration", null));
        seeds.add(new RegistrySeed(LOCAL_LOCATE_ANYTHING_MODEL_ID, "LocateAnything-3B Local", "pending-local-gpu", "LOCAL_GROUNDING_LMM",
                "language_grounding", List.of("local_large_model", "quality_cleanup"), List.of("natural-language grounding"),
                List.of("LocateAnything", "复杂自然语言定位"), "algorithm-service:/internal/models/locate-anything",
                ModelRegistry.Status.UNAVAILABLE, Map.of("role", "quality_cleanup"),
                Map.of("requires_gpu", true, "external_api", false), "NVIDIA LocateAnything, local deployment required",
                "本地 GPU 服务尚未在 Stage 2 配置，禁止回退到外部 API。"));
        seeds.add(new RegistrySeed(LOCAL_VLM_MODEL_ID, "Qwen3-VL-4B Local Semantic Verifier", "pending-local-gpu", "LOCAL_VLM",
                "semantic_verification", List.of("local_large_model", "semantic_verify"), List.of("semantic verification"),
                List.of("本地VLM", "语义验证", "Qwen-VL"), "algorithm-service:/internal/models/local-vlm",
                ModelRegistry.Status.UNAVAILABLE, Map.of("role", "semantic_verifier"),
                Map.of("requires_gpu", true, "external_api", false), "Local OpenAI-compatible VLM endpoint required",
                "本地 VLM 服务尚未在 Stage 2 配置，禁止回退到外部 API。"));
        seeds.add(new RegistrySeed(YOLO_WORLD_MODEL_ID, "YOLO-World Placeholder", "reserved", "YOLO_WORLD_PLACEHOLDER",
                "open_vocabulary_detection", List.of("reserved", "realtime"), List.of("open-vocabulary"),
                List.of("YOLO-World"), "algorithm-service:/internal/models/yolo-world",
                ModelRegistry.Status.UNAVAILABLE, Map.of("role", "future_realtime_teacher"),
                Map.of("optional", true), "Reserved adapter only", "本轮不是主线硬依赖，仅保留 registry 和 adapter 预留。"));
        return seeds;
    }

    private void addXingmu(List<RegistrySeed> seeds, int excelNo, String name, String domain, String modelGroup,
                           String endpoint, String taskType, List<String> aliases) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("xingmu_root", "/root/autodl-fs/xingmu_model");
        resource.put("frontend_visible", true);
        resource.put("excel_no", excelNo);
        if (modelGroup != null) {
            resource.put("model_group", modelGroup);
        }

        List<String> allAliases = new ArrayList<>();
        allAliases.add(name);
        allAliases.addAll(aliases);
        seeds.add(new RegistrySeed(String.format("xingmu-%02d-%s", excelNo, canonicalName(name)),
                name, "v0.5.6_public_ocr_repair", MODEL_TYPE_XINGMU, taskType,
                List.of(domain), List.of(name), allAliases, "xingmu:" + endpoint,
                ModelRegistry.Status.AVAILABLE, Map.of("source", "xingmu_frontend_displayed_32"),
                resource, "xingmu_model internal product capability", null));
    }

    private record RegistrySeed(
            String modelId,
            String name,
            String version,
            String modelType,
            String taskType,
            List<String> domainTags,
            List<String> classes,
            List<String> aliases,
            String endpoint,
            ModelRegistry.Status status,
            Map<String, Object> metrics,
            Map<String, Object> resource,
            String licenseInfo,
            String unavailableReason
    ) {
    }

    private record ReviewedDatasetResult(
            Path outputDir,
            int trainImages,
            int valImages,
            int totalAnnotations,
            Map<String, Integer> labelMap,
            Map<String, Object> modelCard
    ) {
        int totalImages() {
            return trainImages + valImages;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("outputDir", outputDir.toString());
            map.put("dataYaml", outputDir.resolve("data.yaml").toString());
            map.put("modelCardPath", outputDir.resolve("model_card.md").toString());
            map.put("trainImages", trainImages);
            map.put("valImages", valImages);
            map.put("totalImages", totalImages());
            map.put("totalAnnotations", totalAnnotations);
            map.put("labelMap", labelMap);
            return map;
        }
    }

    private record PageSpec(int page, int size, Pageable pageable) {
    }
}
