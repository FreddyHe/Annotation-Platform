package com.annotation.platform.service;

import com.annotation.platform.entity.ProjectLabel;
import com.annotation.platform.repository.ProjectLabelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FormatConverterService {

    @Autowired
    private ProjectLabelRepository projectLabelRepository;

    @Autowired
    private com.annotation.platform.repository.ProjectRepository projectRepository;

    @Value("${label.studio.url:http://localhost:5001}")
    private String labelStudioUrl;

    @Value("${file.upload.base-path:/root/autodl-fs/uploads}")
    private String uploadBasePath;

    @Value("${training.output-base-path:/root/autodl-fs/Annotation-Platform/training_runs}")
    private String trainingOutputBasePath;

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

            ProcessBuilder pb = new ProcessBuilder("curl", "-s", "-H", "Authorization: Token " + token, paginatedUrl);
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
            
            // Label Studio API 可能返回直接数组 [...] 或分页对象 {"tasks": [...]}
            JsonNode tasksNode;
            if (responseNode.isArray()) {
                tasksNode = responseNode;
            } else {
                tasksNode = responseNode.get("tasks");
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

    public static class DatasetConversionResult {
        private String outputPath;
        private int trainImages;
        private int valImages;
        private int totalAnnotations;
        private Map<String, Integer> labelMap;

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
    }
}
