package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

/** 编排层业务异常，code 用于客户端区分失败原因。 */
public class ReleaseException extends RuntimeException {
    private final String code;

    public ReleaseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
