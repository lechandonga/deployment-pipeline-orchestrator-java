package com.github.lechandonga.deploymentpipelineorchestratorjava.error;

/**
 * 对外可区分的错误码（HTTP 状态 + 业务语义）。
 */
public enum ApiErrorCode {
    IDEMPOTENCY_REPLAY(200),
    IDEMPOTENCY_CONFLICT(409),
    RELEASE_NOT_FOUND(404),
    INVALID_TRANSITION(409),
    TERMINAL_STATE(409),
    VALIDATION_ERROR(400);

    private final int httpStatus;

    ApiErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
