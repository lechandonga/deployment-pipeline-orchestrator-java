package com.github.lechandonga.deploymentpipelineorchestratorjava.deploy;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.TargetResult;

/**
 * 目标部署执行器：在单个目标上执行部署或回滚。
 */
public interface TargetDeployer {

    TargetResult deploy(String releaseId, String stage, String target, String version);

    TargetResult rollback(String releaseId, String stage, String target, String version);
}
