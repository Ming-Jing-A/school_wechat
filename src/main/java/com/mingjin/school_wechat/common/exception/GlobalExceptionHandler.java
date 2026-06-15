package com.mingjin.school_wechat.common.exception;

import com.mingjin.school_wechat.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException ex, HttpServletResponse response) {
        response.setContentType("application/json;charset=UTF-8");
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception ex, HttpServletResponse response) {
        response.setContentType("application/json;charset=UTF-8");
        return ApiResponse.fail(ex.getMessage());
    }
}
