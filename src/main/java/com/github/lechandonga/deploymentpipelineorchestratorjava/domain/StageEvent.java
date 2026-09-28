package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

import java.time.Instant;

/** 状态迁移审计事件，用于历史查询与判定复现。 */
public record StageEvent(
        Instant at,
        ReleaseStatus from,
        ReleaseStatus to,
        Stage stage,
        String detail) {
}
