package com.github.lechandonga.deploymentpipelineorchestratorjava.web.dto;

/**
 * 统一错误响应：错误码、消息、判定依据。
 */
public record ErrorResponse(String code, String message, String decisionBasis) {
}
