package com.annotation.platform.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    AUTH_001("AUTH_001", "未认证，请先登录"),
    AUTH_002("AUTH_002", "Token 已过期，请重新登录"),
    AUTH_003("AUTH_003", "用户名或密码错误"),
    AUTH_004("AUTH_004", "用户已存在"),

    USER_001("USER_001", "用户不存在"),
    USER_002("USER_002", "权限不足"),
    USER_003("USER_003", "用户名或邮箱已存在"),

    ORG_001("ORG_001", "组织不存在"),
    ORG_002("ORG_002", "组织名称已存在"),

    PROJ_001("PROJ_001", "项目不存在"),
    PROJ_002("PROJ_002", "项目名称已存在"),
    PROJ_003("PROJ_003", "项目状态不允许此操作"),

    FILE_001("FILE_001", "文件不存在"),
    FILE_002("FILE_002", "文件上传失败"),
    FILE_003("FILE_003", "文件大小超限"),
    FILE_004("FILE_004", "文件格式不支持"),
    FILE_005("FILE_005", "分块上传失败"),
    FILE_006("FILE_006", "文件合并失败"),

    TASK_001("TASK_001", "任务不存在"),
    TASK_002("TASK_002", "任务执行失败"),
    TASK_003("TASK_003", "任务已在运行中"),

    LS_001("LS_001", "Label Studio 连接失败"),
    LS_002("LS_002", "Label Studio 认证失败"),
    LS_003("LS_003", "Label Studio 项目不存在"),

    ALGO_001("ALGO_001", "算法服务连接失败"),
    ALGO_002("ALGO_002", "算法服务执行失败"),

    SYSTEM_ERROR("SYSTEM_ERROR", "系统错误，请稍后重试"),
    PARAM_ERROR("PARAM_ERROR", "参数错误"),
    ;

    private final String code;
    private final String message;
}
