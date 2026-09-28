package com.github.lechandonga.deploymentpipelineorchestratorjava.tests.support;

import com.github.lechandonga.deploymentpipelineorchestratorjava.deploy.SimulatedTargetDeployer;
import com.github.lechandonga.deploymentpipelineorchestratorjava.gate.ScriptableHealthGate;
import com.github.lechandonga.deploymentpipelineorchestratorjava.quota.QuotaService;
import com.github.lechandonga.deploymentpipelineorchestratorjava.repository.InMemoryIdempotencyStore;
import com.github.lechandonga.deploymentpipelineorchestratorjava.repository.InMemoryReleaseRepository;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.AbortCommand;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.AdvanceCommand;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.CreateReleaseCommand;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.PipelineOrchestratorService;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.ReleaseQueryService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 测试夹具：手工装配全部组件（不走 Spring 上下文，用例更快且可控）。
 */
public class PipelineHarness {

    public final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T00:00:00Z"));
    public final InMemoryReleaseRepository repository = new InMemoryReleaseRepository();
    public final InMemoryIdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
    public final ScriptableHealthGate healthGate = new ScriptableHealthGate();
    public final SimulatedTargetDeployer deployer = new SimulatedTargetDeployer();
    public final QuotaService quotaService = new QuotaService();
    public final PipelineOrchestratorService orchestrator;
    public final ReleaseQueryService queryService;

    public PipelineHarness() {
        this.orchestrator = new PipelineOrchestratorService(
                repository, idempotencyStore, healthGate, deployer, quotaService, clock);
        this.queryService = new ReleaseQueryService(repository);
    }

    public CreateReleaseCommand createCommand(String releaseId, String version,
                                              List<String> canary, List<String> full,
                                              Duration timeout, String idempotencyKey) {
        return new CreateReleaseCommand(releaseId, "app-demo", version,
                Map.of("region", "cn-east-1"), canary, full, timeout, idempotencyKey);
    }

    public CreateReleaseCommand createCommand(String releaseId, String version,
                                              List<String> canary, List<String> full,
                                              Map<String, String> params,
                                              Duration timeout, String idempotencyKey) {
        return new CreateReleaseCommand(releaseId, "app-demo", version,
                params, canary, full, timeout, idempotencyKey);
    }

    public AdvanceCommand advanceCommand(String key) {
        return new AdvanceCommand(key);
    }

    public AbortCommand abortCommand(String key) {
        return new AbortCommand(key);
    }

    public List<String> canaryTargets() {
        return List.of("node-1", "node-2");
    }

    public List<String> fullTargets() {
        return List.of("node-1", "node-2", "node-3", "node-4");
    }
}
