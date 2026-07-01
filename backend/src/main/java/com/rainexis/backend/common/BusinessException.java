package com.rainexis.backend.common;

/**
 * 业务异常类
 * 用于在业务逻辑中抛出携带HTTP状态码的异常，由 GlobalExceptionHandler 统一捕获处理
 */
public class BusinessException extends RuntimeException {
    /** 业务异常对应的HTTP状态码 */
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /** 快速创建 400 Bad Request 异常 */
    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    /** 快速创建 401 Unauthorized 异常 */
    public static BusinessException unauthorized(String message) {
        return new BusinessException(401, message);
    }

    /** 快速创建 403 Forbidden 异常 */
    public static BusinessException forbidden(String message) {
        return new BusinessException(403, message);
    }

    /** 快速创建 404 Not Found 异常 */
    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    /** 快速创建 409 Conflict 异常 */
    public static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }
}
