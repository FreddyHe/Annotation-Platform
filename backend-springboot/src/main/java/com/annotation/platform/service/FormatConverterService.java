package com.annotation.platform.service;

import com.annotation.platform.entity.InferenceDataPoint;
import com.annotation.platform.entity.IterationRound;
import com.annotation.platform.entity.LsSubProject;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.ProjectLabel;
import com.annotation.platform.repository.InferenceDataPointRepository;
import com.annotation.platform.repository.IterationRoundRepository;
import com.annotation.platform.repository.LsSubProjectRepository;
import com.annotation.platform.repository.ProjectLabelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FormatConverterService {

    @Autowired
    private ProjectLabelRepository projectLabelRepository;

    @Autowired
    private com.annotation.platform.repository.ProjectRepository projectRepository;

    @Autowired
    private InferenceDataPointRepository inferenceDataPointRepository;

    @Autowired
    private IterationRoundRepository iterationRoundRepository;

    @Autowired
    private LsSubProjectRepository lsSubProjectRepository;

    @Value("${app.label-studio.url:http://localhost:5001}")
    private String labelStudioUrl;

    @Value("${app.file.upload.base-path:/root/autodl-fs/uploads}")
    private String uploadBasePath;

    @Value("${app.training.output-base-path:/root/autodl-fs/Annotation-Platform/training_runs}")
    private String trainingOutputBasePath;

    @Value("${app.label-studio.admin-token:}")
    private String adminToken;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public DatasetConversionResult convertLabelStudioToYOLO(
            Long projectId,
            String labelStudioProjectId,
            String lsToken,
            String outputDir
    ) throws Exception {
        log.info("Converting Label Studio project {} to YOLO format", labelStudioProjectId);

        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);

        Path imagesDir = outputPath.resolve("images");
        Path labelsDir = outputPath.resolve("labels");

        Files.createDirectories(imagesDir.resolve("train"));
        Files.createDirectories(imagesDir.resolve("val"));
        Files.createDirectories(labelsDir.resolve("train"));
        Files.createDirectories(labelsDir.resolve("val"));

        List<ProjectLabel> labels = getProjectLabels(projectId);
        Map<String, Integer> labelMap = createLabelMap(labels);
        
        // 如果 project_labels 表为空，从 Project 实体的 labels JSON 字段构建
        if (labelMap.isEmpty()) {
            com.annotation.platform.entity.Project project = projectRepository.findById(projectId).orElse(null);
            if (project != null && project.getLabels() != null) {
                int classId = 0;
                for (String labelName : project.getLabels()) {
                    labelMap.put(labelName, classId++);
                }
                log.info("Built label map from project.labels: {}", labelMap);
            }
        }

        JsonNode annotations = fetchLabelStudioAnnotations(labelStudioProjectId, lsToken);

        int trainCount = 0;
        int valCount = 0;
        int totalAnnotations = 0;
        List<Object[]> validTasks = new ArrayList<>();

        for (JsonNode task : annotations) {
            // 优先使用 annotations，如果为空则使用 predictions（自动标注结果）
            JsonNode annotationArray = task.get("annotations");
            JsonNode predictionArray = task.get("predictions");
            
            JsonNode resultSource = null;
            if (annotationArray != null && annotationArray.size() > 0) {
                resultSource = annotationArray.get(0);
            } else if (predictionArray != null && predictionArray.size() > 0) {
                resultSource = predictionArray.get(0);
            }
            
            if (resultSource == null) {
                continue;
            }
            
            JsonNode results = resultSource.get("result");
            if (results == null || results.size() == 0) {
                continue;
            }

            String imagePath = task.get("data").get("image").asText();
            
            // 解析 Label Studio 图片路径
            // 格式: /data/local-files/?d=uploads/449/filename.jpg
            Path sourceImage = resolveImagePath(imagePath);
            if (sourceImage == null || !Files.exists(sourceImage)) {
                log.warn("Image not found: {} (resolved: {})", imagePath, sourceImage);
                continue;
            }
            String fileName = sourceImage.getFileName().toString();

            validTasks.add(new Object[]{sourceImage, fileName, results});
        }

        // 确保至少有 1 张验证图片：取最后 max(1, 20%) 张作为 val
        int totalValid = validTasks.size();
        int valCount0 = Math.max(1, (int) Math.round(totalValid * 0.2));
        if (valCount0 >= totalValid) valCount0 = Math.max(0, totalValid - 1);
        int trainSplitEnd = totalValid - valCount0;

        // 打乱顺序以保证随机性
        java.util.Collections.shuffle(validTasks);

        for (int idx = 0; idx < validTasks.size(); idx++) {
            Object[] item = validTasks.get(idx);
            Path sourceImage = (Path) item[0];
            String fileName = (String) item[1];
            JsonNode results = (JsonNode) item[2];
            String split = idx < trainSplitEnd ? "train" : "val";

            Path targetImage = imagesDir.resolve(split).resolve(fileName);
            Files.copy(sourceImage, targetImage, StandardCopyOption.REPLACE_EXISTING);

            Path labelFile = labelsDir.resolve(split).resolve(fileName.replaceFirst("\\.[^.]+$", ".txt"));
            List<String> yoloAnnotations = new ArrayList<>();

            for (JsonNode result : results) {
                if (!"rectanglelabels".equals(result.get("type").asText())) {
                    continue;
                }

                JsonNode value = result.get("value");
                JsonNode x = value.get("x");
                JsonNode y = value.get("y");
                JsonNode width = value.get("width");
                JsonNode height = value.get("height");
                JsonNode originalWidth = value.get("originalWidth");
                JsonNode originalHeight = value.get("originalHeight");

                if (x == null || y == null || width == null || height == null) {
                    continue;
                }

                double xNorm = x.asDouble() / 100.0;
                double yNorm = y.asDouble() / 100.0;
                double wNorm = width.asDouble() / 100.0;
                double hNorm = height.asDouble() / 100.0;

                double centerX = xNorm + wNorm / 2.0;
                double centerY = yNorm + hNorm / 2.0;

                JsonNode labelsNode = value.get("rectanglelabels");
                if (labelsNode == null || labelsNode.size() == 0) {
                    continue;
                }

                String labelName = labelsNode.get(0).asText();
                Integer classId = labelMap.get(labelName);
                if (classId == null) {
                    log.warn("Label not found in label map: {}", labelName);
                    continue;
                }

                yoloAnnotations.add(String.format(Locale.US, "%d %.6f %.6f %.6f %.6f", classId, centerX, centerY, wNorm, hNorm));
                totalAnnotations++;
            }

            Files.write(labelFile, yoloAnnotations);

            if ("train".equals(split)) {
                trainCount++;
            } else {
                valCount++;
            }
        }

        createDataYaml(outputPath, labelMap, imagesDir, labelsDir);

        DatasetConversionResult result = new DatasetConversionResult();
        result.setOutputPath(outputPath.toString());
        result.setTrainImages(trainCount);
        result.setValImages(valCount);
        result.setTotalAnnotations(totalAnnotations);
        result.setLabelMap(labelMap);

        log.info("Conversion completed: {} train images, {} val images, {} total annotations", trainCount, valCount, totalAnnotations);

        return result;
    }

    public DatasetConversionResult buildFeedbackDataset(Long projectId, Long sourceRoundId, String lsToken) throws Exception {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));
        IterationRound sourceRound = iterationRoundRepository.findById(sourceRoundId)
                .orElseThrow(() -> new RuntimeException("Source round not found"));

        String outputDir = trainingOutputBasePath + "/project_" + projectId + "/feedback_round_" + sourceRound.getRoundNumber();
        Path outputPath = Paths.get(outputDir);
        Path imagesDir = outputPath.resolve("images");
        Path labelsDir = outputPath.resolve("labels");
        deleteRecursively(imagesDir);
        deleteRecursively(labelsDir);
        Files.deleteIfExists(outputPath.resolve("data.yaml"));
        Files.createDirectories(imagesDir.resolve("train"));
        Files.createDirectories(imagesDir.resolve("val"));
        Files.createDirectories(labelsDir.resolve("train"));
        Files.createDirectories(labelsDir.resolve("val"));

        List<ProjectLabel> labels = getProjectLabels(projectId);
        Map<String, Integer> labelMap = createLabelMap(labels);
        if (labelMap.isEmpty() && project.getLabels() != null) {
            int classId = 0;
            for (String labelName : project.getLabels()) {
                labelMap.put(labelName, classId++);
            }
        }

        DatasetConversionResult result = new DatasetConversionResult();
        result.setOutputPath(outputPath.toString());
        result.setLabelMap(labelMap);

        if (sourceRound.getRoundNumber() != null && sourceRound.getRoundNumber() == 1
                && project.getLsProjectId() != null && lsToken != null && !lsToken.isBlank()) {
            DatasetConversionResult lsResult = convertLabelStudioToYOLO(
                    projectId,
                    String.valueOf(project.getLsProjectId()),
                    lsToken,
                    outputDir
            );
            result.setTrainImages(lsResult.getTrainImages());
            result.setValImages(lsResult.getValImages());
            result.setTotalAnnotations(lsResult.getTotalAnnotations());
        }

        List<InferenceDataPoint> feedbackPoints = new ArrayList<>();
        feedbackPoints.addAll(inferenceDataPointRepository.findByRoundIdAndPoolType(sourceRoundId, InferenceDataPoint.PoolType.HIGH));
        feedbackPoints.addAll(inferenceDataPointRepository.findByRoundIdAndPoolType(sourceRoundId, InferenceDataPoint.PoolType.LOW_A));
        feedbackPoints.addAll(inferenceDataPointRepository.findByRoundIdAndPoolTypeAndHumanReviewed(sourceRoundId, InferenceDataPoint.PoolType.LOW_B, true));

        int index = 0;
        int trainImages = result.getTrainImages();
        int valImages = result.getValImages();
        int annotations = result.getTotalAnnotations();
        for (InferenceDataPoint point : feedbackPoints) {
            Path sourceImage = Paths.get(point.getImagePath());
            if (!Files.exists(sourceImage)) {
                log.warn("Feedback image not found: {}", sourceImage);
                continue;
            }
            String split = index % 5 == 0 ? "val" : "train";
            String targetName = point.getId() + "_" + sourceImage.getFileName();
            Path targetImage = imagesDir.resolve(split).resolve(targetName);
            Files.copy(sourceImage, targetImage, StandardCopyOption.REPLACE_EXISTING);

            List<String> yoloLines = detectionsToYoloLines(point, sourceImage, labelMap);
            Path labelFile = labelsDir.resolve(split).resolve(targetName.replaceFirst("\\.[^.]+$", ".txt"));
            Files.write(labelFile, yoloLines);
            annotations += yoloLines.size();
            if ("train".equals(split)) {
                trainImages++;
            } else {
                valImages++;
            }
            index++;
        }

        createDataYaml(outputPath, labelMap, imagesDir, labelsDir);
        result.setTrainImages(trainImages);
        result.setValImages(valImages);
        result.setTotalAnnotations(annotations);
        log.info("Feedback dataset built: project={}, sourceRound={}, train={}, val={}, annotations={}",
                projectId, sourceRoundId, trainImages, valImages, annotations);
        return result;
    }

    public DatasetConversionResult buildFlywheelDataset(Long projectId, String lsToken, String outputDir) throws Exception {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        Path outputPath = Paths.get(outputDir);
        Path imagesDir = outputPath.resolve("images");
        Path labelsDir = outputPath.resolve("labels");
        Files.createDirectories(imagesDir.resolve("train"));
        Files.createDirectories(imagesDir.resolve("val"));
        Files.createDirectories(labelsDir.resolve("train"));
        Files.createDirectories(labelsDir.resolve("val"));

        List<ProjectLabel> labels = getProjectLabels(projectId);
        Map<String, Integer> labelMap = createLabelMap(labels);
        if (labelMap.isEmpty() && project.getLabels() != null) {
            int classId = 0;
            for (String labelName : project.getLabels()) {
                labelMap.put(labelName, classId++);
            }
        }

        List<LsTaskItem> validTasks = new ArrayList<>();
        if (project.getLsProjectId() != null) {
            validTasks.addAll(readLsTasks(String.valueOf(project.getLsProjectId()), lsToken, "main", labelMap, true));
        }
        List<LsSubProject> allIncrementalSubs = lsSubProjectRepository.findByProjectIdAndSubType(
                projectId, LsSubProject.SubType.INCREMENTAL);
        List<LsSubProject> reviewedSubs = allIncrementalSubs.stream()
                .filter(sub -> sub.getStatus() == LsSubProject.Status.REVIEWED)
                .toList();
        List<LsSubProject> skippedSubs = allIncrementalSubs.stream()
                .filter(sub -> sub.getStatus() != LsSubProject.Status.REVIEWED)
                .toList();
        for (LsSubProject sub : reviewedSubs) {
            validTasks.addAll(readLsTasks(String.valueOf(sub.getLsProjectId()), lsToken,
                    "sub_" + sub.getBatchNumber(), labelMap, false));
        }

        Collections.shuffle(validTasks);
        int totalValid = validTasks.size();
        int valTarget = Math.max(1, (int) Math.round(totalValid * 0.2));
        if (valTarget >= totalValid) {
            valTarget = Math.max(0, totalValid - 1);
        }
        int trainSplitEnd = totalValid - valTarget;

        int trainCount = 0;
        int valCount = 0;
        int totalAnnotations = 0;
        for (int idx = 0; idx < validTasks.size(); idx++) {
            LsTaskItem item = validTasks.get(idx);
            String split = idx < trainSplitEnd ? "train" : "val";
            Path targetImage = imagesDir.resolve(split).resolve(item.targetName());
            Files.copy(item.sourceImage(), targetImage, StandardCopyOption.REPLACE_EXISTING);
            Path labelFile = labelsDir.resolve(split).resolve(item.targetName().replaceFirst("\\.[^.]+$", ".txt"));
            Files.write(labelFile, item.yoloLines());
            totalAnnotations += item.yoloLines().size();
            if ("train".equals(split)) {
                trainCount++;
            } else {
                valCount++;
            }
        }

        createDataYaml(outputPath, labelMap, imagesDir, labelsDir);
        DatasetConversionResult result = new DatasetConversionResult();
        result.setOutputPath(outputPath.toString());
        result.setTrainImages(trainCount);
        result.setValImages(valCount);
        result.setTotalAnnotations(totalAnnotations);
        result.setLabelMap(labelMap);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mainLsProjectId", project.getLsProjectId());
        metadata.put("reviewedIncrementalProjects", reviewedSubs.stream().map(LsSubProject::getLsProjectId).toList());
        metadata.put("skippedIncrementalProjects", skippedSubs.stream()
                .map(sub -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("lsProjectId", sub.getLsProjectId());
                    item.put("status", sub.getStatus() != null ? sub.getStatus().name() : null);
                    item.put("expectedTasks", sub.getExpectedTasks());
                    item.put("reviewedTasks", sub.getReviewedTasks());
                    return item;
                })
                .toList());
        metadata.put("sourceSummary", summarizeSources(validTasks));
        metadata.put("sourceItems", sourceItems(validTasks));
        metadata.put("datasetFingerprint", datasetFingerprint(validTasks, labelMap));
        metadata.put("skippedIncrementalPolicy", "Only REVIEWED incremental Label Studio projects are included; reviewed incrementals use annotations only.");
        metadata.put("splitPolicy", "Random 80/20 train/val split, with at least one validation image when possible.");
        result.setMetadata(metadata);
        log.info("Flywheel dataset built: project={}, train={}, val={}, annotations={}, reviewedSubs={}",
                projectId, trainCount, valCount, totalAnnotations, reviewedSubs.size());
        return result;
    }

    private List<LsTaskItem> readLsTasks(String lsProjectId,
                                         String lsToken,
                                         String sourcePrefix,
                                         Map<String, Integer> labelMap,
                                         boolean includePredictions) throws Exception {
        JsonNode annotations = fetchLabelStudioAnnotations(lsProjectId, lsToken);
        List<LsTaskItem> validTasks = new ArrayList<>();
        int index = 0;
        for (JsonNode task : annotations) {
            JsonNode resultSource = null;
            JsonNode annotationArray = task.get("annotations");
            JsonNode predictionArray = task.get("predictions");
            if (annotationArray != null && annotationArray.size() > 0) {
                resultSource = annotationArray.get(0);
            } else if (includePredictions && predictionArray != null && predictionArray.size() > 0) {
                resultSource = predictionArray.get(0);
            }
            if (resultSource == null || resultSource.get("result") == null || resultSource.get("result").size() == 0) {
                continue;
            }
            JsonNode imageNode = task.path("data").get("image");
            if (imageNode == null) {
                continue;
            }
            Path sourceImage = resolveImagePath(imageNode.asText());
            if (sourceImage == null || !Files.exists(sourceImage)) {
                log.warn("Image not found for flywheel dataset: {} (resolved: {})", imageNode.asText(), sourceImage);
                continue;
            }
            List<String> yoloLines = labelStudioResultsToYolo(resultSource.get("result"), labelMap);
            if (yoloLines.isEmpty()) {
                continue;
            }
            String resultKind = annotationArray != null && annotationArray.size() > 0 ? "annotation" : "prediction";
            Long taskId = task.has("id") && task.get("id").canConvertToLong() ? task.get("id").asLong() : null;
            String safeName = sourcePrefix + "_" + lsProjectId + "_" + index + "_" + sourceImage.getFileName();
            validTasks.add(new LsTaskItem(
                    sourceImage,
                    safeName,
                    yoloLines,
                    sourcePrefix,
                    Long.parseLong(lsProjectId),
                    taskId,
                    resultKind,
                    imageNode.asText()
            ));
            index++;
        }
        return validTasks;
    }

    private List<String> labelStudioResultsToYolo(JsonNode results, Map<String, Integer> labelMap) {
        List<String> yoloAnnotations = new ArrayList<>();
        for (JsonNode result : results) {
            if (!"rectanglelabels".equals(result.path("type").asText())) {
                continue;
            }
            JsonNode value = result.get("value");
            if (value == null || value.get("x") == null || value.get("y") == null
                    || value.get("width") == null || value.get("height") == null) {
                continue;
            }
            JsonNode labelsNode = value.get("rectanglelabels");
            if (labelsNode == null || labelsNode.size() == 0) {
                continue;
            }
            Integer classId = labelMap.get(labelsNode.get(0).asText());
            if (classId == null) {
                continue;
            }
            double xNorm = value.get("x").asDouble() / 100.0;
            double yNorm = value.get("y").asDouble() / 100.0;
            double wNorm = value.get("width").asDouble() / 100.0;
            double hNorm = value.get("height").asDouble() / 100.0;
            yoloAnnotations.add(String.format(Locale.US, "%d %.6f %.6f %.6f %.6f",
                    classId, xNorm + wNorm / 2.0, yNorm + hNorm / 2.0, wNorm, hNorm));
        }
        return yoloAnnotations;
    }

    private Map<String, Long> summarizeSources(List<LsTaskItem> items) {
        return items.stream().collect(Collectors.groupingBy(this::sourceBucket, LinkedHashMap::new, Collectors.counting()));
    }

    private List<Map<String, Object>> sourceItems(List<LsTaskItem> items) {
        return items.stream()
                .sorted(Comparator.comparing(this::sourceIdentity))
                .map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("source", sourceBucket(item));
                    row.put("sourcePrefix", item.sourcePrefix());
                    row.put("lsProjectId", item.lsProjectId());
                    row.put("lsTaskId", item.lsTaskId());
                    row.put("resultKind", item.resultKind());
                    row.put("imagePath", item.imagePath());
                    row.put("targetName", item.targetName());
                    row.put("boxCount", item.yoloLines().size());
                    return row;
                })
                .toList();
    }

    private String datasetFingerprint(List<LsTaskItem> items, Map<String, Integer> labelMap) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(labelMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("|"))
                    .getBytes(StandardCharsets.UTF_8));
            List<String> lines = items.stream()
                    .sorted(Comparator.comparing(this::sourceIdentity))
                    .map(item -> sourceIdentity(item) + "|" + String.join(";", item.yoloLines()))
                    .toList();
            for (String line : lines) {
                digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String sourceBucket(LsTaskItem item) {
        if ("main".equals(item.sourcePrefix())) {
            return "annotation".equals(item.resultKind()) ? "MAIN_ANNOTATION" : "MAIN_PREDICTION";
        }
        return "INCREMENTAL_ANNOTATION";
    }

    private String sourceIdentity(LsTaskItem item) {
        return item.lsProjectId() + ":" + item.lsTaskId() + ":" + item.resultKind() + ":" + item.imagePath();
    }

    private record LsTaskItem(Path sourceImage,
                              String targetName,
                              List<String> yoloLines,
                              String sourcePrefix,
                              Long lsProjectId,
                              Long lsTaskId,
                              String resultKind,
                              String imagePath) {
    }

    private Map<String, Integer> createLabelMap(List<ProjectLabel> labels) {
        Map<String, Integer> labelMap = new HashMap<>();
        int classId = 0;
        for (ProjectLabel label : labels) {
            labelMap.put(label.getName(), classId++);
        }
        return labelMap;
    }

    private List<ProjectLabel> getProjectLabels(Long projectId) {
        return projectLabelRepository.findByProjectIdAndIsActive(projectId, true);
    }

    /**
     * 解析 Label Studio 图片路径为本地文件路径
     * 支持格式: /data/local-files/?d=uploads/449/filename.jpg
     */
    private Path resolveImagePath(String lsImagePath) {
        if (lsImagePath == null) return null;
        
        // 处理 /data/local-files/?d=xxx 格式
        if (lsImagePath.contains("/data/local-files/")) {
            int dIdx = lsImagePath.indexOf("?d=");
            if (dIdx >= 0) {
                String relativePath = lsImagePath.substring(dIdx + 3);
                // relativePath = uploads/449/filename.jpg
                // uploadBasePath = /root/autodl-fs/uploads
                // 需要去掉 relativePath 中的 "uploads/" 前缀
                if (relativePath.startsWith("uploads/")) {
                    relativePath = relativePath.substring("uploads/".length());
                }
                Path directPath = Paths.get(relativePath);
                if (directPath.isAbsolute()) {
                    return directPath;
                }
                return Paths.get(uploadBasePath, relativePath);
            }
        }
        
        // 处理绝对路径
        Path absolutePath = Paths.get(lsImagePath);
        if (absolutePath.isAbsolute()) {
            return absolutePath;
        }
        
        // 处理相对路径
        return Paths.get(uploadBasePath, lsImagePath);
    }

    private JsonNode fetchLabelStudioAnnotations(String projectId, String token) throws Exception {
        String url = String.format("%s/api/projects/%s/tasks", labelStudioUrl, projectId);

        List<JsonNode> allTasks = new ArrayList<>();
        int page = 1;
        int pageSize = 100;

        while (true) {
            String paginatedUrl = String.format("%s?page=%d&page_size=%d&fields=all", url, page, pageSize);

            String effectiveToken = token != null && !token.isBlank() ? token : adminToken;
            ProcessBuilder pb = new ProcessBuilder("curl", "-s", "-H", "Authorization: Token " + effectiveToken, paginatedUrl);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Failed to fetch Label Studio annotations");
            }

            JsonNode responseNode = objectMapper.readTree(response.toString());
            if (responseNode.isObject() && responseNode.has("status_code")) {
                int statusCode = responseNode.path("status_code").asInt();
                if (statusCode >= 400) {
                    throw new RuntimeException(String.format(
                            "Failed to fetch Label Studio tasks: projectId=%s, status=%d, detail=%s",
                            projectId,
                            statusCode,
                            responseNode.path("detail").asText("")
                    ));
                }
            }
            
            // Label Studio API 可能返回直接数组 [...] 或分页对象 {"tasks": [...]}
            JsonNode tasksNode;
            if (responseNode.isArray()) {
                tasksNode = responseNode;
            } else {
                tasksNode = responseNode.get("tasks");
            }

            if (tasksNode == null && responseNode.isObject() && responseNode.has("results")) {
                tasksNode = responseNode.get("results");
            }

            if (tasksNode == null || tasksNode.size() == 0) {
                break;
            }

            for (JsonNode task : tasksNode) {
                allTasks.add(task);
            }

            if (tasksNode.size() < pageSize) {
                break;
            }

            page++;
        }

        log.info("Fetched {} tasks from Label Studio project {}", allTasks.size(), projectId);
        return objectMapper.valueToTree(allTasks);
    }

    private List<String> detectionsToYoloLines(InferenceDataPoint point, Path imagePath, Map<String, Integer> labelMap) {
        List<String> lines = new ArrayList<>();
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null) {
                return lines;
            }
            JsonNode detections = objectMapper.readTree(point.getInferenceBboxJson() == null ? "[]" : point.getInferenceBboxJson());
            if (!detections.isArray()) {
                return lines;
            }
            for (JsonNode detection : detections) {
                JsonNode bbox = detection.get("bbox");
                if (bbox == null) {
                    continue;
                }
                int classId = resolveClassId(detection, labelMap);
                double x1 = bbox.path("x1").asDouble();
                double y1 = bbox.path("y1").asDouble();
                double x2 = bbox.path("x2").asDouble();
                double y2 = bbox.path("y2").asDouble();
                double centerX = clamp(((x1 + x2) / 2.0) / image.getWidth());
                double centerY = clamp(((y1 + y2) / 2.0) / image.getHeight());
                double width = clamp(Math.abs(x2 - x1) / image.getWidth());
                double height = clamp(Math.abs(y2 - y1) / image.getHeight());
                if (width <= 0 || height <= 0) {
                    continue;
                }
                lines.add(String.format(Locale.US, "%d %.6f %.6f %.6f %.6f", classId, centerX, centerY, width, height));
            }
        } catch (Exception e) {
            log.warn("Failed to convert feedback point {} to YOLO: {}", point.getId(), e.getMessage());
        }
        return lines;
    }

    private int resolveClassId(JsonNode detection, Map<String, Integer> labelMap) {
        String label = detection.path("label").asText(null);
        if (label != null && labelMap.containsKey(label)) {
            return labelMap.get(label);
        }
        if (detection.has("class_id")) {
            return detection.get("class_id").asInt();
        }
        return 0;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private void createDataYaml(Path outputPath, Map<String, Integer> labelMap, Path imagesDir, Path labelsDir) throws IOException {
        StringBuilder yamlContent = new StringBuilder();
        yamlContent.append("path: ").append(outputPath.toString()).append("\n");
        yamlContent.append("train: images/train\n");
        yamlContent.append("val: images/val\n\n");
        yamlContent.append("names:\n");

        List<Map.Entry<String, Integer>> sortedLabels = labelMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .collect(Collectors.toList());

        for (Map.Entry<String, Integer> entry : sortedLabels) {
            yamlContent.append("  ").append(entry.getValue()).append(": ").append(entry.getKey()).append("\n");
        }

        Path dataYamlPath = outputPath.resolve("data.yaml");
        Files.write(dataYamlPath, yamlContent.toString().getBytes());

        log.info("Created data.yaml at {}", dataYamlPath);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        }
    }

    public static class DatasetConversionResult {
        private String outputPath;
        private int trainImages;
        private int valImages;
        private int totalAnnotations;
        private Map<String, Integer> labelMap;
        private Map<String, Object> metadata = new LinkedHashMap<>();

        public String getOutputPath() {
            return outputPath;
        }

        public void setOutputPath(String outputPath) {
            this.outputPath = outputPath;
        }

        public int getTrainImages() {
            return trainImages;
        }

        public void setTrainImages(int trainImages) {
            this.trainImages = trainImages;
        }

        public int getValImages() {
            return valImages;
        }

        public void setValImages(int valImages) {
            this.valImages = valImages;
        }

        public int getTotalAnnotations() {
            return totalAnnotations;
        }

        public void setTotalAnnotations(int totalAnnotations) {
            this.totalAnnotations = totalAnnotations;
        }

        public Map<String, Integer> getLabelMap() {
            return labelMap;
        }

        public void setLabelMap(Map<String, Integer> labelMap) {
            this.labelMap = labelMap;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }
}
