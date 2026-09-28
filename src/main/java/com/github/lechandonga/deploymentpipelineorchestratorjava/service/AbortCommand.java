package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

/**
 * 主动放弃命令（触发回滚后进入 ABORTED 终态）。
 */
public record AbortCommand(String idempotencyKey) {
}
