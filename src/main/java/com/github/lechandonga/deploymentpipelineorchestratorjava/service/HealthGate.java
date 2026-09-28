package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

/** 健康门禁：判定某个部署目标当前是否健康。 */
public interface HealthGate {
    boolean isHealthy(String target);
}
