package com.annotation.platform.entity;

public enum AssessmentStatus {
    CREATED,        // 刚创建
    PARSING,        // 需求解析中
    PARSED,         // 需求已解析
    OVD_TESTING,    // OVD测试中
    OVD_TESTED,     // OVD测试完成
    EVALUATING,     // VLM评估中
    EVALUATED,      // VLM评估完成
    ESTIMATING,     // 资源估算中
    COMPLETED,      // 全部完成
    FAILED          // 失败
}