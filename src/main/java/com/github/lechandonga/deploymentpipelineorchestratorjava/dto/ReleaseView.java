package com.github.lechandonga.deploymentpipelineorchestratorjava.dto;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.FailureReason;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseOrder;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseStatus;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.Stage;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.StageEvent;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 发布单对外视图（不可变快照，可复现最终判定）。 */
public record ReleaseView(
        String id,
        String version,
        ReleaseStatus status,
        Stage currentStage,
        FailureReason failureReason,
        Set<String> completedTargets,
        Set<String> rolledBackTargets,
        Instant createdAt,
        Instant finishedAt,
        List<StageEvent> history,
        boolean replayed) {

    public static ReleaseView of(ReleaseOrder o, boolean replayed) {
        return new ReleaseView(o.getId(), o.getVersion(), o.getStatus(), o.getCurrentStage(),
                o.getFailureReason(), o.getCompletedTargets(), o.getRolledBackTargets(),
                o.getCreatedAt(), o.getFinishedAt(), o.getHistory(), replayed);
    }
}
