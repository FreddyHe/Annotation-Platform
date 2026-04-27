package com.annotation.platform.service;

import com.annotation.platform.entity.*;
import com.annotation.platform.exception.BusinessException;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.EdgeDeploymentRepository;
import com.annotation.platform.repository.InferenceDataPointRepository;
import com.annotation.platform.repository.ModelTrainingRecordRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class EdgeSimulatorService {

    private final ProjectRepository projectRepository;
    private final ModelTrainingRecordRepository trainingRecordRepository;
    private final EdgeDeploymentRepository edgeDeploymentRepository;
    private final InferenceDataPointRepository inferenceDataPointRepository;
    private final ProjectConfigService projectConfigService;
    private final RoundService roundService;
    private final VlmJudgeService vlmJudgeService;
    private final IncrementalProjectService incrementalProjectService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService inferenceExecutor = Executors.newFixedThreadPool(2);
    private final Map<String, EdgeInferenceJob> inferenceJobs = new ConcurrentHashMap<>();

    @Value("${app.algorithm.url:http://localhost:8001}")
    private String algorithmServiceUrl;

    @Value("${app.file.upload.base-path:/root/autodl-fs/uploads}")
    private String uploadBasePath;

    @PreDestroy
    public void shutdownInferenceExecutor() {
        inferenceExecutor.shutdownNow();
    }

    @Transactional
    public EdgeDeployment deploy(Long projectId, Long modelRecordId, String edgeNodeName) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        ModelTrainingRecord model = trainingRecordRepository.findById(modelRecordId)
                .orElseThrow(() -> new ResourceNotFoundException("ModelTrainingRecord", "id", modelRecordId));
        if (model.getBestModelPath() == null || model.getBestModelPath().isBlank()) {
            throw new BusinessException("模型尚未生成 best.pt，无法部署");
        }
        IterationRound round = roundService.openDeploymentRound(
                project,
                modelRecordId,
                "部署模型记录 #" + modelRecordId + " 后开启的新采集轮次"
        );

        Optional<EdgeDeployment> active = edgeDeploymentRepository.findLatestActiveByProjectId(projectId);
        active.ifPresent(deployment -> {
            deployment.setStatus(EdgeDeployment.DeploymentStatus.ROLLED_BACK);
            deployment.setRolledBackAt(LocalDateTime.now());
            edgeDeploymentRepository.save(deployment);
        });

        EdgeDeployment deployment = EdgeDeployment.builder()
                .projectId(projectId)
                .roundId(round.getId())
                .modelRecordId(modelRecordId)
                .edgeNodeName(edgeNodeName == null || edgeNodeName.isBlank() ? "虚拟边端-A" : edgeNodeName)
                .previousDeploymentId(active.map(EdgeDeployment::getId).orElse(null))
                .status(EdgeDeployment.DeploymentStatus.ACTIVE)
                .deployedAt(LocalDateTime.now())
                .build();
        deployment = edgeDeploymentRepository.save(deployment);
        roundService.markRoundDeployed(round.getId());
        return deployment;
    }

    @Transactional
    public EdgeDeployment rollback(Long projectId, Long targetDeploymentId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        EdgeDeployment target = edgeDeploymentRepository.findById(targetDeploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("EdgeDeployment", "id", targetDeploymentId));
        if (!Objects.equals(target.getProjectId(), projectId)) {
            throw new BusinessException("目标部署不属于当前项目");
        }
        ModelTrainingRecord model = trainingRecordRepository.findById(target.getModelRecordId())
                .orElseThrow(() -> new ResourceNotFoundException("ModelTrainingRecord", "id", target.getModelRecordId()));
        if (model.getBestModelPath() == null || model.getBestModelPath().isBlank()) {
            throw new BusinessException("目标模型尚未生成 best.pt，无法回滚部署");
        }
        Optional<EdgeDeployment> previousActive = edgeDeploymentRepository.findLatestActiveByProjectId(projectId);
        for (EdgeDeployment deployment : edgeDeploymentRepository.findByProjectIdOrderByDeployedAtDesc(projectId)) {
            if (deployment.getStatus() == EdgeDeployment.DeploymentStatus.ACTIVE) {
                deployment.setStatus(EdgeDeployment.DeploymentStatus.ROLLED_BACK);
                deployment.setRolledBackAt(LocalDateTime.now());
                edgeDeploymentRepository.save(deployment);
            }
        }
        IterationRound round = roundService.openDeploymentRound(
                project,
                target.getModelRecordId(),
                "回滚到部署 #" + targetDeploymentId + " 的模型记录 #" + target.getModelRecordId()
        );
        EdgeDeployment rollbackDeployment = EdgeDeployment.builder()
                .projectId(projectId)
                .roundId(round.getId())
                .modelRecordId(target.getModelRecordId())
                .edgeNodeName(target.getEdgeNodeName() + "（回滚）")
                .previousDeploymentId(previousActive.map(EdgeDeployment::getId).orElse(targetDeploymentId))
                .status(EdgeDeployment.DeploymentStatus.ACTIVE)
                .deployedAt(LocalDateTime.now())
                .build();
        rollbackDeployment = edgeDeploymentRepository.save(rollbackDeployment);
        roundService.markRoundDeployed(round.getId());
        return rollbackDeployment;
    }

    @Transactional
    public Map<String, Object> runInference(Long deploymentId, MultipartFile[] files) throws Exception {
        return runInference(deploymentId, files, null);
    }

    @Transactional
    public Map<String, Object> runInference(Long deploymentId, MultipartFile[] files, MultipartFile archive) throws Exception {
        return runInference(deploymentId, files, archive, "COLLECT_AND_UPLOAD");
    }

    @Transactional
    public Map<String, Object> runInference(Long deploymentId, MultipartFile[] files, MultipartFile archive, String mode) throws Exception {
        EdgeDeployment deployment = edgeDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("EdgeDeployment", "id", deploymentId));
        ModelTrainingRecord model = trainingRecordRepository.findById(deployment.getModelRecordId())
                .orElseThrow(() -> new ResourceNotFoundException("ModelTrainingRecord", "id", deployment.getModelRecordId()));
        ProjectConfig config = projectConfigService.getOrCreate(deployment.getProjectId());

        boolean pureInference = isPureInferenceMode(mode);
        Path pureTempDir = null;
        List<String> imagePaths;
        if (pureInference) {
            pureTempDir = Paths.get(System.getProperty("java.io.tmpdir"), "edge_pure_inference",
                    String.valueOf(deploymentId), UUID.randomUUID().toString());
            imagePaths = saveFilesToDirectory(pureTempDir, files, archive);
        } else {
            imagePaths = saveFiles(deploymentId, files, archive);
        }
        if (imagePaths.isEmpty()) {
            if (pureTempDir != null) {
                deleteRecursively(pureTempDir);
            }
            throw new BusinessException("请上传图片或包含图片的 zip 压缩包");
        }

        List<Map<String, Object>> inferenceResults;
        try {
            inferenceResults = callAlgorithmInference(model.getBestModelPath(), imagePaths);
        } finally {
            if (pureTempDir != null) {
                deleteRecursively(pureTempDir);
            }
        }

        if (pureInference) {
            Map<String, Object> response = new HashMap<>();
            response.put("deploymentId", deploymentId);
            response.put("mode", "PURE_INFERENCE");
            response.put("count", inferenceResults.size());
            response.put("frameCount", inferenceResults.size());
            response.put("uploadedFrameCount", imagePaths.size());
            response.put("results", inferenceResults);
            response.put("reviewSyncStatus", "纯推理模式：未写入数据池，未同步 Label Studio。");
            return response;
        }

        List<Map<String, Object>> saved = new ArrayList<>();
        List<Long> savedIds = new ArrayList<>();
        for (Map<String, Object> result : inferenceResults) {
            double avgConfidence = asDouble(result.get("avg_confidence"), 0.0);
            InferenceDataPoint.PoolType poolType = decidePool(avgConfidence, config);
            InferenceDataPoint point = InferenceDataPoint.builder()
                    .projectId(deployment.getProjectId())
                    .roundId(deployment.getRoundId())
                    .deploymentId(deploymentId)
                    .imagePath(String.valueOf(result.get("image_path")))
                    .fileName(String.valueOf(result.get("file_name")))
                    .inferenceBboxJson(objectMapper.writeValueAsString(result.getOrDefault("detections", List.of())))
                    .avgConfidence(avgConfidence)
                    .poolType(poolType)
                    .humanReviewed(false)
                    .build();
            point = inferenceDataPointRepository.save(point);
            savedIds.add(point.getId());
            saved.add(toPointResponse(point));
        }

        roundService.markRoundCollecting(deployment.getRoundId());
        if (Boolean.TRUE.equals(config.getEnableAutoVlmJudge())) {
            vlmJudgeService.judgeAndSplit(deployment.getProjectId(), deployment.getRoundId());
            saved = inferenceDataPointRepository.findAllById(savedIds).stream()
                    .map(this::toPointResponse)
                    .toList();
        }

        try {
            List<InferenceDataPoint> latestPoints = inferenceDataPointRepository.findAllById(savedIds);
            Long projectOwnerId = projectRepository.findById(deployment.getProjectId())
                    .map(project -> project.getCreatedBy() != null ? project.getCreatedBy().getId() : null)
                    .orElse(null);
            incrementalProjectService.syncTrustedPointsToMainProject(
                    deployment.getProjectId(), latestPoints, projectOwnerId);
            incrementalProjectService.syncLowBToIncrementalProject(
                    deployment.getProjectId(), projectOwnerId);
            saved = inferenceDataPointRepository.findAllById(savedIds).stream()
                    .map(this::toPointResponse)
                    .toList();
        } catch (Exception syncErr) {
            log.warn("Label Studio sync failed, inference flow continues: {}", syncErr.getMessage());
        }

        return Map.of(
                "deploymentId", deploymentId,
                "mode", "COLLECT_AND_UPLOAD",
                "count", saved.size(),
                "frameCount", saved.size(),
                "uploadedFrameCount", imagePaths.size(),
                "reviewSyncStatus", "LOW_B points are synced to incremental Label Studio projects when available.",
                "results", saved,
                "poolStats", poolStats(deployment.getProjectId(), deployment.getRoundId())
        );
    }

    public Map<String, Object> startInferenceJob(Long deploymentId,
                                                  MultipartFile[] files,
                                                  MultipartFile archive,
                                                  String mode) throws Exception {
        EdgeDeployment deployment = edgeDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("EdgeDeployment", "id", deploymentId));
        ModelTrainingRecord model = trainingRecordRepository.findById(deployment.getModelRecordId())
                .orElseThrow(() -> new ResourceNotFoundException("ModelTrainingRecord", "id", deployment.getModelRecordId()));
        if (model.getBestModelPath() == null || model.getBestModelPath().isBlank()) {
            throw new BusinessException("模型尚未生成 best.pt，无法执行边端推理");
        }

        boolean pureInference = isPureInferenceMode(mode);
        Path pureTempDir = null;
        List<String> imagePaths;
        if (pureInference) {
            pureTempDir = Paths.get(System.getProperty("java.io.tmpdir"), "edge_pure_inference",
                    String.valueOf(deploymentId), UUID.randomUUID().toString());
            imagePaths = saveFilesToDirectory(pureTempDir, files, archive);
        } else {
            imagePaths = saveFiles(deploymentId, files, archive);
        }
        if (imagePaths.isEmpty()) {
            if (pureTempDir != null) {
                deleteRecursively(pureTempDir);
            }
            throw new BusinessException("请上传图片或包含图片的 zip 压缩包");
        }

        String jobId = UUID.randomUUID().toString();
        EdgeInferenceJob job = new EdgeInferenceJob(
                jobId,
                deploymentId,
                deployment.getProjectId(),
                deployment.getRoundId(),
                pureInference ? "PURE_INFERENCE" : "COLLECT_AND_UPLOAD",
                imagePaths,
                pureTempDir,
                model.getBestModelPath()
        );
        inferenceJobs.put(jobId, job);
        inferenceExecutor.submit(() -> executeInferenceJob(jobId));
        return toJobResponse(job);
    }

    public Map<String, Object> getInferenceJob(String jobId) {
        EdgeInferenceJob job = inferenceJobs.get(jobId);
        if (job == null) {
            throw new ResourceNotFoundException("EdgeInferenceJob", "id", jobId);
        }
        return toJobResponse(job);
    }

    private void executeInferenceJob(String jobId) {
        EdgeInferenceJob job = inferenceJobs.get(jobId);
        if (job == null) {
            return;
        }
        try {
            job.status = "RUNNING";
            job.stage = "SERVER_PROCESSING";
            job.message = "等待算法服务资源并执行边端推理";
            job.percent = 20;

            List<Map<String, Object>> inferenceResults = callAlgorithmInference(job.modelPath, job.imagePaths);
            job.percent = 72;
            job.processedCount = inferenceResults.size();

            if ("PURE_INFERENCE".equals(job.mode)) {
                Map<String, Object> response = new HashMap<>();
                response.put("deploymentId", job.deploymentId);
                response.put("mode", "PURE_INFERENCE");
                response.put("count", inferenceResults.size());
                response.put("frameCount", inferenceResults.size());
                response.put("uploadedFrameCount", job.imagePaths.size());
                response.put("results", inferenceResults);
                response.put("reviewSyncStatus", "纯推理模式：未写入数据池，未同步 Label Studio。");
                job.result = response;
                job.status = "COMPLETED";
                job.stage = "COMPLETED";
                job.message = "纯推理完成";
                job.percent = 100;
                return;
            }

            job.stage = "PERSISTING";
            job.message = "正在写入回流数据池";
            ProjectConfig config = projectConfigService.getOrCreate(job.projectId);
            List<Map<String, Object>> saved = new ArrayList<>();
            List<Long> savedIds = new ArrayList<>();
            for (Map<String, Object> result : inferenceResults) {
                double avgConfidence = asDouble(result.get("avg_confidence"), 0.0);
                InferenceDataPoint.PoolType poolType = decidePool(avgConfidence, config);
                InferenceDataPoint point = InferenceDataPoint.builder()
                        .projectId(job.projectId)
                        .roundId(job.roundId)
                        .deploymentId(job.deploymentId)
                        .imagePath(String.valueOf(result.get("image_path")))
                        .fileName(String.valueOf(result.get("file_name")))
                        .inferenceBboxJson(objectMapper.writeValueAsString(result.getOrDefault("detections", List.of())))
                        .avgConfidence(avgConfidence)
                        .poolType(poolType)
                        .humanReviewed(false)
                        .build();
                point = inferenceDataPointRepository.save(point);
                savedIds.add(point.getId());
                saved.add(toPointResponse(point));
                job.processedCount = saved.size();
                job.percent = 72 + Math.min(12, (int) Math.round(saved.size() * 12.0 / Math.max(1, inferenceResults.size())));
            }

            roundService.markRoundCollecting(job.roundId);
            if (Boolean.TRUE.equals(config.getEnableAutoVlmJudge())) {
                job.stage = "VLM_JUDGING";
                job.message = "正在执行 VLM 判定";
                job.percent = 86;
                vlmJudgeService.judgeAndSplit(job.projectId, job.roundId);
                saved = inferenceDataPointRepository.findAllById(savedIds).stream()
                        .map(this::toPointResponse)
                        .toList();
            }

            job.stage = "SYNCING_LABEL_STUDIO";
            job.message = "正在同步 Label Studio";
            job.percent = 94;
            try {
                List<InferenceDataPoint> latestPoints = inferenceDataPointRepository.findAllById(savedIds);
                Long projectOwnerId = projectRepository.findById(job.projectId)
                        .map(project -> project.getCreatedBy() != null ? project.getCreatedBy().getId() : null)
                        .orElse(null);
                incrementalProjectService.syncTrustedPointsToMainProject(job.projectId, latestPoints, projectOwnerId);
                incrementalProjectService.syncLowBToIncrementalProject(job.projectId, projectOwnerId);
                saved = inferenceDataPointRepository.findAllById(savedIds).stream()
                        .map(this::toPointResponse)
                        .toList();
            } catch (Exception syncErr) {
                log.warn("Label Studio sync failed, inference flow continues: {}", syncErr.getMessage());
            }

            Map<String, Object> response = new HashMap<>();
            response.put("deploymentId", job.deploymentId);
            response.put("mode", "COLLECT_AND_UPLOAD");
            response.put("count", saved.size());
            response.put("frameCount", saved.size());
            response.put("uploadedFrameCount", job.imagePaths.size());
            response.put("reviewSyncStatus", "LOW_B points are synced to incremental Label Studio projects when available.");
            response.put("results", saved);
            response.put("poolStats", poolStats(job.projectId, job.roundId));
            job.result = response;
            job.status = "COMPLETED";
            job.stage = "COMPLETED";
            job.message = "模拟视频流推理完成";
            job.percent = 100;
        } catch (Exception e) {
            log.error("Edge inference job failed: jobId={}, deploymentId={}", jobId, job.deploymentId, e);
            job.status = "FAILED";
            job.stage = "FAILED";
            job.message = "模拟视频流推理失败";
            job.error = e.getMessage();
            job.percent = 100;
        } finally {
            job.completedAt = LocalDateTime.now();
            if (job.pureTempDir != null) {
                deleteRecursively(job.pureTempDir);
            }
        }
    }

    public List<EdgeDeployment> listDeployments(Long projectId) {
        return edgeDeploymentRepository.findByProjectIdOrderByDeployedAtDesc(projectId);
    }

    public List<InferenceDataPoint> inferenceHistory(Long deploymentId) {
        return inferenceDataPointRepository.findByDeploymentIdOrderByCreatedAtDesc(deploymentId);
    }

    public Map<String, Object> poolStats(Long projectId, Long roundId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("projectId", projectId);
        stats.put("roundId", roundId);
        for (InferenceDataPoint.PoolType type : InferenceDataPoint.PoolType.values()) {
            stats.put(poolKey(type), inferenceDataPointRepository.countByRoundIdAndPoolType(roundId, type));
        }
        return stats;
    }

    public Map<String, Object> toDeploymentResponse(EdgeDeployment deployment) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", deployment.getId());
        response.put("projectId", deployment.getProjectId());
        response.put("roundId", deployment.getRoundId());
        response.put("modelRecordId", deployment.getModelRecordId());
        response.put("edgeNodeName", deployment.getEdgeNodeName());
        response.put("status", deployment.getStatus() != null ? deployment.getStatus().name() : null);
        response.put("deployedAt", deployment.getDeployedAt());
        response.put("rolledBackAt", deployment.getRolledBackAt());
        response.put("previousDeploymentId", deployment.getPreviousDeploymentId());
        return response;
    }

    public Map<String, Object> toPointResponse(InferenceDataPoint point) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", point.getId());
        response.put("projectId", point.getProjectId());
        response.put("roundId", point.getRoundId());
        response.put("deploymentId", point.getDeploymentId());
        response.put("imagePath", point.getImagePath());
        response.put("fileName", point.getFileName());
        response.put("detections", parseJson(point.getInferenceBboxJson()));
        response.put("avgConfidence", point.getAvgConfidence());
        response.put("poolType", point.getPoolType() != null ? point.getPoolType().name() : null);
        response.put("vlmDecision", point.getVlmDecision() != null ? point.getVlmDecision().name() : null);
        response.put("vlmReasoning", point.getVlmReasoning());
        response.put("humanReviewed", point.getHumanReviewed());
        response.put("lsTaskId", point.getLsTaskId());
        response.put("lsSubProjectId", point.getLsSubProjectId());
        response.put("usedInRoundId", point.getUsedInRoundId());
        response.put("createdAt", point.getCreatedAt());
        return response;
    }

    private List<String> saveFiles(Long deploymentId, MultipartFile[] files, MultipartFile archive) throws Exception {
        Path dir = Paths.get(uploadBasePath, "edge_inference", String.valueOf(deploymentId));
        return saveFilesToDirectory(dir, files, archive);
    }

    private List<String> saveFilesToDirectory(Path dir, MultipartFile[] files, MultipartFile archive) throws Exception {
        Files.createDirectories(dir);
        List<String> paths = new ArrayList<>();
        if (archive != null && !archive.isEmpty()) {
            paths.addAll(extractZipImages(dir, archive));
        }
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String original = file.getOriginalFilename() == null ? "image.jpg" : file.getOriginalFilename();
                if (isZip(original)) {
                    paths.addAll(extractZipImages(dir, file));
                    continue;
                }
                if (!isImage(original)) {
                    continue;
                }
                String suffix = original.contains(".") ? original.substring(original.lastIndexOf(".")) : ".jpg";
                Path target = dir.resolve(UUID.randomUUID() + suffix);
                file.transferTo(target.toFile());
                paths.add(target.toString());
            }
        }
        return paths;
    }

    private boolean isPureInferenceMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return false;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        return "PURE_INFERENCE".equals(normalized) || "PURE".equals(normalized);
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        } catch (Exception e) {
            log.debug("Failed to clean pure inference temp files: {}", e.getMessage());
        }
    }

    private List<String> extractZipImages(Path dir, MultipartFile archive) throws Exception {
        List<String> paths = new ArrayList<>();
        Path extractDir = dir.resolve("zip_" + UUID.randomUUID());
        Files.createDirectories(extractDir);
        try (ZipInputStream zip = new ZipInputStream(archive.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !isImage(entry.getName())) {
                    continue;
                }
                String fileName = Paths.get(entry.getName()).getFileName().toString();
                String suffix = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".")) : ".jpg";
                Path target = extractDir.resolve(UUID.randomUUID() + suffix).normalize();
                if (!target.startsWith(extractDir)) {
                    throw new BusinessException("zip 文件包含非法路径");
                }
                Files.copy(zip, target);
                paths.add(target.toString());
            }
        }
        return paths;
    }

    private boolean isZip(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private boolean isImage(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callAlgorithmInference(String modelPath, List<String> imagePaths) throws Exception {
        String url = algorithmServiceUrl + "/api/v1/algo/edge-inference/batch";
        Map<String, Object> request = new HashMap<>();
        request.put("model_path", modelPath);
        request.put("image_paths", imagePaths);
        request.put("conf_threshold", 0.3);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(request, headers), String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        return objectMapper.convertValue(root.get("results"), List.class);
    }

    private InferenceDataPoint.PoolType decidePool(double avgConfidence, ProjectConfig config) {
        if (avgConfidence >= config.getHighPoolThreshold()) {
            return InferenceDataPoint.PoolType.HIGH;
        }
        if (avgConfidence < config.getLowPoolThreshold()) {
            return InferenceDataPoint.PoolType.DISCARDED;
        }
        return InferenceDataPoint.PoolType.LOW_A_CANDIDATE;
    }

    private String poolKey(InferenceDataPoint.PoolType type) {
        return switch (type) {
            case HIGH -> "highCount";
            case LOW_A_CANDIDATE -> "lowACandidateCount";
            case LOW_A -> "lowACount";
            case LOW_B -> "lowBCount";
            case DISCARDED -> "discardedCount";
        };
    }

    private double asDouble(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return fallback;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> toJobResponse(EdgeInferenceJob job) {
        Map<String, Object> response = new HashMap<>();
        response.put("jobId", job.jobId);
        response.put("deploymentId", job.deploymentId);
        response.put("projectId", job.projectId);
        response.put("roundId", job.roundId);
        response.put("mode", job.mode);
        response.put("status", job.status);
        response.put("stage", job.stage);
        response.put("message", job.message);
        response.put("percent", job.percent);
        response.put("frameCount", job.imagePaths.size());
        response.put("processedCount", job.processedCount);
        response.put("error", job.error);
        response.put("createdAt", job.createdAt);
        response.put("completedAt", job.completedAt);
        if (job.result != null) {
            response.put("result", job.result);
        }
        return response;
    }

    private static class EdgeInferenceJob {
        private final String jobId;
        private final Long deploymentId;
        private final Long projectId;
        private final Long roundId;
        private final String mode;
        private final List<String> imagePaths;
        private final Path pureTempDir;
        private final String modelPath;
        private final LocalDateTime createdAt = LocalDateTime.now();
        private volatile LocalDateTime completedAt;
        private volatile String status = "QUEUED";
        private volatile String stage = "QUEUED";
        private volatile String message = "已提交推理任务，等待执行";
        private volatile int percent = 5;
        private volatile int processedCount = 0;
        private volatile String error;
        private volatile Map<String, Object> result;

        private EdgeInferenceJob(String jobId,
                                 Long deploymentId,
                                 Long projectId,
                                 Long roundId,
                                 String mode,
                                 List<String> imagePaths,
                                 Path pureTempDir,
                                 String modelPath) {
            this.jobId = jobId;
            this.deploymentId = deploymentId;
            this.projectId = projectId;
            this.roundId = roundId;
            this.mode = mode;
            this.imagePaths = List.copyOf(imagePaths);
            this.pureTempDir = pureTempDir;
            this.modelPath = modelPath;
        }
    }
}
