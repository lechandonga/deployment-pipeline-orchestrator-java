package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

/**
 * 阶段推进命令（携带幂等键时重放返回原结果）。
 */
public record AdvanceCommand(String idempotencyKey) {
}
