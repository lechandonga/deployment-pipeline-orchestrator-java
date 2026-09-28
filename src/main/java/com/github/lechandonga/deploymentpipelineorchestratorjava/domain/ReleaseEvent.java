package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

import java.time.Instant;

/**
 * 不可变的发布事件记录，用于事后复现判定过程。
 */
public record ReleaseEvent(
        long sequence,
        Instant occurredAt,
        Stage stage,
        ReleaseStatus fromStatus,
        ReleaseStatus toStatus,
        String message,
        String reason
) {
}
