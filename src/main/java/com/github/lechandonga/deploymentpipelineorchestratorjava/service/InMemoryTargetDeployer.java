package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 默认内存执行器：所有目标成功，可通过 failTargets 模拟失败（便于演示与测试）。 */
@Component
public class InMemoryTargetDeployer implements TargetDeployer {

    private final Set<String> failTargets = ConcurrentHashMap.newKeySet();

    public void failOn(String target) {
        failTargets.add(target);
    }

    public void clearFailures() {
        failTargets.clear();
    }

    @Override
    public boolean deploy(String target) {
        return !failTargets.contains(target);
    }

    @Override
    public boolean rollback(String target) {
        return true;
    }
}
