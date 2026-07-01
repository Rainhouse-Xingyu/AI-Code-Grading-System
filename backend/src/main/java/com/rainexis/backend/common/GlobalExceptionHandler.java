package com.rainexis.backend.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器
 * 通过 @RestControllerAdvice 拦截所有Controller抛出的异常，统一转换为 ApiResponse 格式返回
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 处理业务异常，根据异常中的code返回对应的HTTP状态码 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(HttpStatus.valueOf(ex.getCode()))
                .body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    /** 处理参数校验失败异常（@Valid校验不通过） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, message));
    }

    /** 处理文件上传超限异常 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, "文件大小不能超过 50MB"));
    }

    /** 兜底处理所有未捕获的异常，返回500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, ex.getMessage()));
    }
}
