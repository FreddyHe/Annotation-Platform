package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.service.SingleClassDetectionService;
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
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/detection")
public class SingleClassDetectionController {

    @Autowired
    private SingleClassDetectionService singleClassDetectionService;

    @Value("${file.upload.base-path:/root/autodl-fs/uploads}")
    private String uploadBasePath;

    @Value("${yolo.model.path:/root/autodl-tmp/xingmu_jiancepingtai/runs/detect/train7/weights/best.pt}")
    private String defaultModelPath;

    @PostMapping("/single-class")
    public Result<Map<String, Object>> detectSingleClass(
            @RequestParam("image") MultipartFile image,
            @RequestParam("class_id") String classIdStr,
            @RequestParam(value = "model_path", required = false) String modelPath,
            @RequestParam(value = "confidence_threshold", defaultValue = "0.5") String confidenceThresholdStr,
            @RequestParam(value = "iou_threshold", defaultValue = "0.45") String iouThresholdStr
    ) {
        try {
            Integer classId = Integer.parseInt(classIdStr);
            Double confidenceThreshold = Double.parseDouble(confidenceThresholdStr);
            Double iouThreshold = Double.parseDouble(iouThresholdStr);
            
            log.info("Received single class detection request: class_id={}, conf={}, iou={}", 
                    classId, confidenceThreshold, iouThreshold);

            if (modelPath == null || modelPath.isEmpty()) {
                modelPath = defaultModelPath;
            }

            String originalFilename = image.getOriginalFilename();
            String fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
            String uniqueFilename = UUID.randomUUID().toString() + fileExtension;

            Path uploadDir = Paths.get(uploadBasePath, "single_class_detection");
            Files.createDirectories(uploadDir);

            Path filePath = uploadDir.resolve(uniqueFilename);
            image.transferTo(filePath.toFile());

            Map<String, Object> result = singleClassDetectionService.detectSingleClass(
                    filePath.toFile(),
                    classId,
                    modelPath,
                    confidenceThreshold,
                    iouThreshold
            );

            return Result.success(result);
        } catch (IOException e) {
            log.error("Failed to upload image", e);
            return Result.error("500", "Failed to upload image: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to perform single class detection", e);
            return Result.error("500", "Failed to perform detection: " + e.getMessage());
        }
    }

    @GetMapping("/model-info")
    public Result<Map<String, Object>> getModelInfo() {
        try {
            Map<String, Object> modelInfo = singleClassDetectionService.getModelInfo();
            return Result.success(modelInfo);
        } catch (Exception e) {
            log.error("Failed to get model info", e);
            return Result.error("500", "Failed to get model info: " + e.getMessage());
        }
    }
}
