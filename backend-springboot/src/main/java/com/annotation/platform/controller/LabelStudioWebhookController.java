package com.annotation.platform.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.annotation.platform.common.Result;
import com.annotation.platform.entity.InferenceDataPoint;
import com.annotation.platform.entity.LsSubProject;
import com.annotation.platform.repository.InferenceDataPointRepository;
import com.annotation.platform.repository.LsSubProjectRepository;
import com.annotation.platform.service.IncrementalProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/ls-webhook")
@RequiredArgsConstructor
public class LabelStudioWebhookController {

    private final InferenceDataPointRepository inferenceDataPointRepository;
    private final LsSubProjectRepository lsSubProjectRepository;
    private final IncrementalProjectService incrementalProjectService;

    @PostMapping("/annotation")
    public Result<Void> onAnnotation(@RequestBody Map<String, Object> payload) {
        try {
            JSONObject body = JSON.parseObject(JSON.toJSONString(payload));
            String action = body.getString("action");
            JSONObject task = body.getJSONObject("task");
            JSONObject annotation = body.getJSONObject("annotation");
            if (task == null || annotation == null) {
                log.debug("Webhook payload missing task or annotation, action={}", action);
                return Result.success();
            }

            Long lsTaskId = task.getLong("id");
            Long lsProjectId = task.getLong("project");
            if (lsTaskId == null) {
                return Result.success();
            }

            Optional<InferenceDataPoint> opt = inferenceDataPointRepository.findByLsTaskId(lsTaskId);
            if (opt.isEmpty()) {
                log.debug("Webhook data point not found: lsTaskId={}", lsTaskId);
                return Result.success();
            }

            InferenceDataPoint point = opt.get();
            com.alibaba.fastjson2.JSONArray result = annotation.getJSONArray("result");
            if (result != null) {
                point.setInferenceBboxJson(convertLsResultToDetections(result));
            }
            point.setHumanReviewed(true);

            if (lsProjectId != null) {
                Optional<LsSubProject> subOpt = lsSubProjectRepository.findByLsProjectId(lsProjectId);
                subOpt.ifPresent(sub -> {
                    point.setLsSubProjectId(sub.getId());
                    incrementalProjectService.refreshReviewProgress(sub, null);
                });
            }
            inferenceDataPointRepository.save(point);
            log.info("Webhook processed: action={}, lsTaskId={}, pointId={}", action, lsTaskId, point.getId());
        } catch (Exception e) {
            log.error("Webhook processing failed: {}", e.getMessage(), e);
        }
        return Result.success();
    }

    private String convertLsResultToDetections(com.alibaba.fastjson2.JSONArray lsResults) {
        com.alibaba.fastjson2.JSONArray detections = new com.alibaba.fastjson2.JSONArray();
        for (int i = 0; i < lsResults.size(); i++) {
            JSONObject r = lsResults.getJSONObject(i);
            JSONObject value = r.getJSONObject("value");
            if (value == null) {
                continue;
            }

            Integer originalWidth = r.getInteger("original_width");
            Integer originalHeight = r.getInteger("original_height");
            if (originalWidth == null || originalHeight == null) {
                continue;
            }

            double xPct = value.getDoubleValue("x");
            double yPct = value.getDoubleValue("y");
            double wPct = value.getDoubleValue("width");
            double hPct = value.getDoubleValue("height");
            double x1 = xPct / 100.0 * originalWidth;
            double y1 = yPct / 100.0 * originalHeight;
            double x2 = x1 + wPct / 100.0 * originalWidth;
            double y2 = y1 + hPct / 100.0 * originalHeight;

            com.alibaba.fastjson2.JSONArray labels = value.getJSONArray("rectanglelabels");
            String label = labels != null && !labels.isEmpty() ? labels.getString(0) : "object";

            JSONObject bbox = new JSONObject();
            bbox.put("x1", x1);
            bbox.put("y1", y1);
            bbox.put("x2", x2);
            bbox.put("y2", y2);

            JSONObject detection = new JSONObject();
            detection.put("label", label);
            detection.put("confidence", 1.0);
            detection.put("bbox", bbox);
            detections.add(detection);
        }
        return detections.toJSONString();
    }
}
