package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

/** 失败终态的可区分原因。 */
public enum FailureReason {
    /** 健康门禁未满足 */
    HEALTH_GATE_UNMET,
    /** 某个部署目标执行失败 */
    TARGET_FAILED,
    /** 用户主动放弃 */
    ABORTED_BY_USER,
    /** 等待超时 */
    TIMEOUT
}
