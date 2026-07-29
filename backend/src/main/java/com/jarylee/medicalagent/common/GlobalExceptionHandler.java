package com.jarylee.medicalagent.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Optional;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .body(ApiResponse.failure(exception.code(), exception.getMessage(), traceId(request)));
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> unauthorized(BadCredentialsException exception, HttpServletRequest request) {
        return ApiResponse.failure("AUTHENTICATION_FAILED", exception.getMessage(), traceId(request));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> badRequest(Exception exception, HttpServletRequest request) {
        String message = exception instanceof MethodArgumentNotValidException validation
                ? Optional.ofNullable(validation.getBindingResult().getFieldError())
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .orElse("请求校验失败")
                : exception.getMessage();
        return ApiResponse.failure("INVALID_REQUEST", message, traceId(request));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> internalError(Exception exception, HttpServletRequest request) {
        return ApiResponse.failure("INTERNAL_ERROR", "处理失败，请使用追踪标识联系管理员", traceId(request));
    }

    private String traceId(HttpServletRequest request) {
        return Optional.ofNullable(request.getAttribute(TraceIdFilter.ATTRIBUTE))
                .map(Object::toString).orElse("not-provided");
    }
}
