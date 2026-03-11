package com.annotation.platform.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private Boolean success;

    private String message;

    private T data;

    private String errorCode;

    private Result(Boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    private Result(Boolean success, String message, String errorCode) {
        this.success = success;
        this.message = message;
        this.errorCode = errorCode;
    }

    public static <T> Result<T> success() {
        return new Result<>(true, "操作成功", null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(true, "操作成功", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(true, message, data);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(false, message, null);
    }

    public static <T> Result<T> error(String errorCode, String message) {
        return new Result<>(false, message, errorCode);
    }

    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(false, errorCode.getMessage(), errorCode.getCode());
    }
}
