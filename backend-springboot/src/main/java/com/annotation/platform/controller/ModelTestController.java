package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.service.ModelTestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/test")
public class ModelTestController {

    @Autowired
    private ModelTestService modelTestService;

    @Value("${file.upload.base-path:/root/autodl-fs/uploads}")
    private String uploadBasePath;

    @PostMapping("/start")
    public Result<Map<String, String>> startTest(
            @RequestBody Map<String, Object> request
    ) {
        try {
            String modelPath = (String) request.get("model_path");
            @SuppressWarnings("unchecked")
            List<String> imagePaths = (List<String>) request.get("image_paths");
            Double confThreshold = request.get("conf_threshold") != null ? 
                    Double.parseDouble(request.get("conf_threshold").toString()) : 0.25;
            Double iouThreshold = request.get("iou_threshold") != null ? 
                    Double.parseDouble(request.get("iou_threshold").toString()) : 0.45;
            String device = request.get("device") != null ? 
                    (String) request.get("device") : "0";

            String taskId = modelTestService.startTest(
                    modelPath,
                    imagePaths,
                    confThreshold,
                    iouThreshold,
                    device
            );

            Map<String, String> result = Map.of("task_id", taskId);
            return Result.success(result);
        } catch (Exception e) {
            log.error("Failed to start test", e);
            return Result.error("500", "Failed to start test: " + e.getMessage());
        }
    }

    @PostMapping("/start/upload")
    public Result<Map<String, String>> startTestWithUpload(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("model_path") String modelPath,
            @RequestParam(value = "conf_threshold", defaultValue = "0.25") Double confThreshold,
            @RequestParam(value = "iou_threshold", defaultValue = "0.45") Double iouThreshold,
            @RequestParam(value = "device", defaultValue = "0") String device
    ) {
        try {
            List<File> imageFiles = new ArrayList<>();

            for (MultipartFile file : files) {
                String originalFilename = file.getOriginalFilename();
                String fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
                String uniqueFilename = UUID.randomUUID().toString() + fileExtension;

                Path uploadDir = Paths.get(uploadBasePath, "test_uploads");
                Files.createDirectories(uploadDir);

                Path filePath = uploadDir.resolve(uniqueFilename);
                file.transferTo(filePath.toFile());

                imageFiles.add(filePath.toFile());
            }

            String taskId = modelTestService.startTestWithUpload(
                    modelPath,
                    imageFiles,
                    confThreshold,
                    iouThreshold,
                    device
            );

            Map<String, String> result = Map.of("task_id", taskId);
            return Result.success(result);
        } catch (IOException e) {
            log.error("Failed to upload files", e);
            return Result.error("500", "Failed to upload files: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to start test with upload", e);
            return Result.error("500", "Failed to start test: " + e.getMessage());
        }
    }

    @GetMapping("/status/{taskId}")
    public Result<Map<String, Object>> getTestStatus(@PathVariable String taskId) {
        try {
            Map<String, Object> status = modelTestService.getTestStatus(taskId);
            return Result.success(status);
        } catch (Exception e) {
            log.error("Failed to get test status", e);
            return Result.error("500", "Failed to get test status: " + e.getMessage());
        }
    }

    @GetMapping("/results/{taskId}")
    public Result<Map<String, Object>> getTestResults(@PathVariable String taskId) {
        try {
            Map<String, Object> results = modelTestService.getTestResults(taskId);
            return Result.success(results);
        } catch (Exception e) {
            log.error("Failed to get test results", e);
            return Result.error("500", "Failed to get test results: " + e.getMessage());
        }
    }

    @GetMapping("/results/{taskId}/image/{imageIndex}")
    public Result<Map<String, Object>> getTestResultForImage(
            @PathVariable String taskId,
            @PathVariable int imageIndex
    ) {
        try {
            Map<String, Object> result = modelTestService.getTestResultForImage(taskId, imageIndex);
            return Result.success(result);
        } catch (Exception e) {
            log.error("Failed to get test result for image", e);
            return Result.error("500", "Failed to get test result for image: " + e.getMessage());
        }
    }

    @PostMapping("/cancel/{taskId}")
    public Result<Void> cancelTest(@PathVariable String taskId) {
        try {
            modelTestService.cancelTest(taskId);
            return Result.success(null);
        } catch (Exception e) {
            log.error("Failed to cancel test", e);
            return Result.error("500", "Failed to cancel test: " + e.getMessage());
        }
    }
}
