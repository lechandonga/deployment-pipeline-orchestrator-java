package com.github.lechandonga.deploymentpipelineorchestratorjava.deploy;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.TargetResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 模拟部署执行器：测试可注入“部署失败/回滚失败”的目标集合，默认全部成功。
 * 执行器本身不做状态管理，幂等与防重由编排层 + 配额层保证。
 */
@Component
public class SimulatedTargetDeployer implements TargetDeployer {

    private static final Logger log = LoggerFactory.getLogger(SimulatedTargetDeployer.class);

    private volatile Set<String> failDeployTargets = Set.of();
    private volatile Set<String> failRollbackTargets = Set.of();

    public void setFailDeployTargets(Set<String> targets) {
        this.failDeployTargets = Set.copyOf(targets);
    }

    public void setFailRollbackTargets(Set<String> targets) {
        this.failRollbackTargets = Set.copyOf(targets);
    }

    public void reset() {
        this.failDeployTargets = Set.of();
        this.failRollbackTargets = Set.of();
    }

    @Override
    public TargetResult deploy(String releaseId, String stage, String target, String version) {
        boolean ok = !failDeployTargets.contains(target);
        String detail = ok ? "deployed " + version
                : "simulated deploy failure on " + target;
        log.info("deploy releaseId={} stage={} target={} version={} success={}",
                releaseId, stage, target, version, ok);
        return new TargetResult(target, ok, detail);
    }

    @Override
    public TargetResult rollback(String releaseId, String stage, String target, String version) {
        boolean ok = !failRollbackTargets.contains(target);
        String detail = ok ? "rolled back from " + version
                : "simulated rollback failure on " + target;
        log.warn("rollback releaseId={} stage={} target={} version={} success={}",
                releaseId, stage, target, version, ok);
        return new TargetResult(target, ok, detail);
    }
}
