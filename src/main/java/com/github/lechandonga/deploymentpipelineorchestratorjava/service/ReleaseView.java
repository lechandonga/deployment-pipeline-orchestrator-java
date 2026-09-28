package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.FailureReason;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseEvent;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 发布单对外视图：当前状态、失败原因、关键时间点与可复现事件流。
 */
public record ReleaseView(
        String releaseId,
        String application,
        String version,
        ReleaseStatus status,
        FailureReason failureReason,
        String failureDetail,
        boolean replay,
        String decision,
        Instant createdAt,
        Instant canaryStartedAt,
        Instant stableSince,
        Instant fullStartedAt,
        Instant finishedAt,
        Duration healthTimeout,
        List<String> deployedTargets,
        List<ReleaseEvent> events
) {
}
