package com.github.lechandonga.deploymentpipelineorchestratorjava.web;

import com.github.lechandonga.deploymentpipelineorchestratorjava.error.ApiException;
import com.github.lechandonga.deploymentpipelineorchestratorjava.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：把业务错误码映射成可区分的 HTTP 响应体（code/message/decisionBasis）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        log.warn("api.error code={} message={} basis={}",
                ex.getCode(), ex.getMessage(), ex.getDecisionBasis());
        ErrorResponse body = new ErrorResponse(
                ex.getCode().name(), ex.getMessage(), ex.getDecisionBasis());
        return ResponseEntity.status(ex.getCode().httpStatus()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", "请求体无法解析", ex.getMostSpecificCause().getMessage()));
    }
}
