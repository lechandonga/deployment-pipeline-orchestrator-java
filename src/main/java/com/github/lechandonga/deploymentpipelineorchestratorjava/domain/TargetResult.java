package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

/**
 * 单个部署目标（机器/实例）的执行结果。
 */
public record TargetResult(String target, boolean success, String detail) {
}
