package com.github.lechandonga.deploymentpipelineorchestratorjava.dto;

import java.util.List;

public record CreateReleaseRequest(
        String version,
        List<String> canaryTargets,
        List<String> fullTargets,
        Long stageTimeoutMillis) {
}
