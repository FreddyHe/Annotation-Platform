package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.entity.SingleClassDetectionRecord;
import com.annotation.platform.service.SingleClassDetectionService;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/detection")
public class SingleClassDetectionController {

    @Autowired
    private SingleClassDetectionService singleClassDetectionService;

    @Value("${file.upload.base-path:/root/autodl-fs/uploads}")
    private String uploadBasePath;

    @Value("${app.yolo.model.path:/root/autodl-fs/xingmu_jiancepingtai/runs/detect/train7/weights/best.pt}")
    private String defaultModelPath;

    @PostMapping("/single-class")
    public Result<Map<String, Object>> detectSingleClass(
            @RequestParam("image") MultipartFile image,
            @RequestParam("class_id") String classIdStr,
            @RequestParam(value = "model_path", required = false) String modelPath,
            @RequestParam(value = "model_id", required = false) String modelId,
            @RequestParam(value = "model_name", required = false) String modelName,
            @RequestParam(value = "class_name", required = false) String className,
            @RequestParam(value = "confidence_threshold", defaultValue = "0.5") String confidenceThresholdStr,
            @RequestParam(value = "iou_threshold", defaultValue = "0.45") String iouThresholdStr,
            HttpServletRequest request
    ) {
        try {
            Integer classId = Integer.parseInt(classIdStr);
            Double confidenceThreshold = Double.parseDouble(confidenceThresholdStr);
            Double iouThreshold = Double.parseDouble(iouThresholdStr);
            if (confidenceThreshold < 0 || confidenceThreshold > 1 || iouThreshold < 0 || iouThreshold > 1) {
                return Result.error("400", "阈值必须在0到1之间");
            }
            if (image.isEmpty()) {
                return Result.error("400", "请上传图片");
            }
            String contentType = image.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return Result.error("400", "仅支持图片文件");
            }
            
            log.info("Received single class detection request: class_id={}, conf={}, iou={}", 
                    classId, confidenceThreshold, iouThreshold);

            if (modelPath == null || modelPath.isEmpty()) {
                modelPath = defaultModelPath;
            }

            String originalFilename = image.getOriginalFilename();
            String fileExtension = ".jpg";
            if (originalFilename != null && originalFilename.lastIndexOf(".") >= 0) {
                fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
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
            SingleClassDetectionRecord record = singleClassDetectionService.saveDetectionRecord(
                    currentUserId(request),
                    modelId,
                    modelName,
                    modelPath,
                    classId,
                    className,
                    filePath.toString(),
                    confidenceThreshold,
                    iouThreshold,
                    result
            );
            if (record != null) {
                result.put("record_id", record.getId());
            }

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
            modelInfo.put("model_path", defaultModelPath);
            return Result.success(modelInfo);
        } catch (Exception e) {
            log.error("Failed to get model info", e);
            return Result.error("500", "Failed to get model info: " + e.getMessage());
        }
    }

    @GetMapping("/single-class/history")
    public Result<List<SingleClassDetectionRecord>> listDetectionHistory(HttpServletRequest request) {
        return Result.success(singleClassDetectionService.listDetectionHistory(currentUserId(request)));
    }

    @DeleteMapping("/single-class/history")
    public Result<Void> clearDetectionHistory(HttpServletRequest request) {
        singleClassDetectionService.clearDetectionHistory(currentUserId(request));
        return Result.success();
    }

    private Long currentUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId instanceof Long ? (Long) userId : 1L;
    }
}
