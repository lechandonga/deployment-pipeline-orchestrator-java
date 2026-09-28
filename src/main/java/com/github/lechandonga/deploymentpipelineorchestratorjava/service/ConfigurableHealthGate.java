package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 默认健康门禁：目标不在 unhealthyTargets 集合中即视为健康。 */
@Component
public class ConfigurableHealthGate implements HealthGate {

    private final Set<String> unhealthyTargets = ConcurrentHashMap.newKeySet();

    public void markUnhealthy(String target) {
        unhealthyTargets.add(target);
    }

    public void markHealthy(String target) {
        unhealthyTargets.remove(target);
    }

    @Override
    public boolean isHealthy(String target) {
        return !unhealthyTargets.contains(target);
    }
}
