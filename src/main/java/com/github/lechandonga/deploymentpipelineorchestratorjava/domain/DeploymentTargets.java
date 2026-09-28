package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

import java.util.List;

/**
 * 一次发布要操作的目标集合（灰度子集 + 全量集合）。
 */
public record DeploymentTargets(List<String> canary, List<String> full) {
}
