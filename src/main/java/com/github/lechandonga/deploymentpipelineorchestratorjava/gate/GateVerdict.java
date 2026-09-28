package com.github.lechandonga.deploymentpipelineorchestratorjava.gate;

/**
 * 健康门禁三态判定：放行、继续等待、明确不健康。
 */
public enum GateVerdict {
    HEALTHY,
    PENDING,
    UNHEALTHY
}
