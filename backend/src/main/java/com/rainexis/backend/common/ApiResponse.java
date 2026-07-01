package com.rainexis.backend.common;

/**
 * 统一API响应封装类
 * 所有Controller返回值都通过此类包装，确保前端接收到的响应格式一致
 *
 * @param <T> 响应数据的类型
 */
public class ApiResponse<T> {
    /** HTTP状态码 */
    private Integer code;
    /** 响应消息 */
    private String message;
    /** 响应数据体 */
    private T data;
    /** 响应时间戳（毫秒） */
    private Long timestamp;

    public ApiResponse() {
    }

    public ApiResponse(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /** 构建成功响应（code=200） */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    /** 构建失败响应 */
    public static <T> ApiResponse<T> fail(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
