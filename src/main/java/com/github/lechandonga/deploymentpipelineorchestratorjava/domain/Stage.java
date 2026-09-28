package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

/**
 * 发布阶段标识。
 */
public enum Stage {
    CREATE,
    CANARY,
    PROMOTE,
    ROLLBACK,
    ABORT
}
