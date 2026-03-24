package com.annotation.platform.entity;

public enum AssessmentStatus {
    CREATED,                // 刚创建
    PARSING,                // 需求解析中
    PARSED,                 // 需求已解析
    OVD_TESTING,            // OVD测试中
    OVD_TESTED,             // OVD测试完成
    EVALUATING,             // VLM评估中
    EVALUATED,              // VLM评估完成（桶A直接到COMPLETED，非桶A继续）
    DATASET_SEARCHED,       // 数据集检索完成（仅非桶A）
    AWAITING_USER_JUDGMENT, // 等待用户判断数据集匹配度（仅非桶A）
    ESTIMATING,             // 资源估算中
    COMPLETED,              // 全部完成
    FAILED                  // 失败
}