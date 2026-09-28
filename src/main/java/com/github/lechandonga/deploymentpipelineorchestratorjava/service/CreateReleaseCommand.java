package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 创建发布单命令。
 */
public record CreateReleaseCommand(
        String releaseId,
        String application,
        String version,
        Map<String, String> params,
        List<String> canaryTargets,
        List<String> fullTargets,
        Duration healthTimeout,
        String idempotencyKey
) {
}
