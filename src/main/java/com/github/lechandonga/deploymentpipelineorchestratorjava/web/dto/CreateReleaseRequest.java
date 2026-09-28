package com.github.lechandonga.deploymentpipelineorchestratorjava.web.dto;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 创建发布单 HTTP 请求体。
 */
public record CreateReleaseRequest(
        String releaseId,
        String application,
        String version,
        Map<String, String> params,
        List<String> canaryTargets,
        List<String> fullTargets,
        Duration healthTimeout
) {
}
