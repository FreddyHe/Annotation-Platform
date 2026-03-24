package com.annotation.platform.entity;

public enum DatasetMatchLevel {
    ALMOST_MATCH,    // 几乎一致 → 桶B(CUSTOM_LOW)
    PARTIAL_MATCH,   // 部分相关 → 桶B+(CUSTOM_MEDIUM)  
    NOT_USABLE       // 几乎不可用 → 桶C(CUSTOM_HIGH)
}
