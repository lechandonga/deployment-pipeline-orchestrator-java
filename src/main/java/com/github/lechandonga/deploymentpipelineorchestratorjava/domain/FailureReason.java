package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

/**
 * 失败/终止终态的可区分原因码。
 */
public enum FailureReason {
    /** 无失败（成功路径或进行中）。 */
    NONE,
    /** 健康门禁明确判定不健康。 */
    HEALTH_GATE_UNHEALTHY,
    /** 等待健康门禁满足条件超时。 */
    WAIT_TIMEOUT,
    /** 主动放弃。 */
    ABANDONED,
    /** 部署目标执行失败（不得静默成功）。 */
    TARGET_FAILED,
    /** 触发回滚后，部分目标回滚失败。 */
    ROLLBACK_FAILED
}
