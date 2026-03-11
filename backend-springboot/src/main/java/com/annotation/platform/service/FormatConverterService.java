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

        JsonNode annotations = fetchLabelStudioAnnotations(labelStudioProjectId, lsToken);

        int trainCount = 0;
        int valCount = 0;
        int totalAnnotations = 0;

        for (JsonNode task : annotations) {
            JsonNode annotation = task.get("annotations");
            if (annotation == null || annotation.size() == 0) {
                continue;
            }

            JsonNode latestAnnotation = annotation.get(0);
            JsonNode results = latestAnnotation.get("result");
            if (results == null || results.size() == 0) {
                continue;
            }

            String imagePath = task.get("data").get("image").asText();
            String fileName = Paths.get(imagePath).getFileName().toString();

            Path sourceImage = Paths.get(imagePath);
            if (!Files.exists(sourceImage)) {
                log.warn("Image not found: {}", imagePath);
                continue;
            }

            boolean isTrain = Math.random() < 0.8;
            String split = isTrain ? "train" : "val";

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

            if (isTrain) {
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
            JsonNode tasksNode = responseNode.get("tasks");

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
