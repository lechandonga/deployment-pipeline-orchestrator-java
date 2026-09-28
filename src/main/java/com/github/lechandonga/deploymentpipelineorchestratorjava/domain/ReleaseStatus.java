package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

/**
 * 发布单状态。流转方向单调：
 * CREATED -> CANARY_IN_PROGRESS -> FULL_ROLLOUT_IN_PROGRESS -> SUCCEEDED
 * 任意进行态可进入失败终态（FAILED / ROLLED_BACK / ABORTED / TIMED_OUT），终态不可再推进。
 */
public enum ReleaseStatus {
    CREATED,
    CANARY_IN_PROGRESS,
    FULL_ROLLOUT_IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    ROLLED_BACK,
    ABORTED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == ROLLED_BACK
                || this == ABORTED || this == TIMED_OUT;
    }
}
