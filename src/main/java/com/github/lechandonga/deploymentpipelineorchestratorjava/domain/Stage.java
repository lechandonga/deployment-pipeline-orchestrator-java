package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

/** 发布阶段：灰度 -> 全量。 */
public enum Stage {
    CANARY,
    FULL_ROLLOUT
}
