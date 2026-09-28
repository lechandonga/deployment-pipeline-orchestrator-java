package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

/**
 * 发布单生命周期状态（阶段推进必须串行且单调，终态不可再推进）。
 */
public enum ReleaseStatus {
    CREATED(false),
    CANARY_ROLLING_OUT(false),
    CANARY_STABLE(false),
    FULL_ROLLING_OUT(false),
    ROLLED_BACK(false),
    SUCCEEDED(true),
    HEALTH_CHECK_FAILED(true),
    TIMED_OUT(true),
    ABORTED(true),
    ROLLBACK_FAILED(true);

    private final boolean terminal;

    ReleaseStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
