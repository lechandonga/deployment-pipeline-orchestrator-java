package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

/** 目标执行器：对单个部署目标执行部署/回滚。返回 false 表示执行失败。 */
public interface TargetDeployer {
    boolean deploy(String target);

    boolean rollback(String target);
}
