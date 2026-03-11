package com.annotation.platform.service.algorithm;

import com.annotation.platform.dto.request.algorithm.DinoDetectRequest;
import com.annotation.platform.dto.request.algorithm.VlmCleanRequest;
import com.annotation.platform.dto.response.algorithm.DinoDetectResponse;
import com.annotation.platform.dto.response.algorithm.VlmCleanResponse;

public interface AlgorithmService {
    
    /**
     * 启动 DINO 检测任务
     */
    DinoDetectResponse startDinoDetection(DinoDetectRequest request);
    
    /**
     * 启动 VLM 清洗任务
     */
    VlmCleanResponse startVlmCleaning(VlmCleanRequest request);
    
    /**
     * 查询任务状态
     */
    Object getTaskStatus(String taskId);
    
    /**
     * 获取任务结果
     */
    Object getTaskResults(String taskId);
}
