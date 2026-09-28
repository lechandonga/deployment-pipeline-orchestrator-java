package com.github.lechandonga.deploymentpipelineorchestratorjava.error;

/**
 * 业务异常：携带可区分错误码、可读消息与判定依据。
 */
public class ApiException extends RuntimeException {

    private final ApiErrorCode code;
    private final String decisionBasis;

    public ApiException(ApiErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ApiErrorCode code, String message, String decisionBasis) {
        super(message);
        this.code = code;
        this.decisionBasis = decisionBasis;
    }

    public ApiErrorCode getCode() {
        return code;
    }

    public String getDecisionBasis() {
        return decisionBasis;
    }
}
