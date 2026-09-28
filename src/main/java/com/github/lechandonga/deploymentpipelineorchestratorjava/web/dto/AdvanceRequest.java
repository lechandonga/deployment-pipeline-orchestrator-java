package com.github.lechandonga.deploymentpipelineorchestratorjava.web.dto;

/**
 * 阶段推进 HTTP 请求体。
 */
public record AdvanceRequest(String idempotencyKey) {
}
