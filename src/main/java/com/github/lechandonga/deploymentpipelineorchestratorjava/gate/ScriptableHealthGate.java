package com.github.lechandonga.deploymentpipelineorchestratorjava.gate;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.TargetResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 可脚本化健康门禁：测试可注入“继续等待（PENDING）”或“明确不健康（UNHEALTHY）”目标。
 * 默认全部健康。真实系统中这里可以替换为探针/指标查询实现。
 *
 * <p>三态语义：
 * <ul>
 *   <li>{@link GateVerdict#HEALTHY}：所有目标健康，可以进入下一阶段；</li>
 *   <li>{@link GateVerdict#PENDING}：尚无失败但条件未满足，应继续等待直至超时；</li>
 *   <li>{@link GateVerdict#UNHEALTHY}：已有目标明确不健康，立即判失败并回滚。</li>
 * </ul>
 */
@Component
public class ScriptableHealthGate implements HealthGate {

    private static final Logger log = LoggerFactory.getLogger(ScriptableHealthGate.class);

    private volatile Predicate<String> pendingTarget = t -> false;
    private volatile Predicate<String> unhealthyTarget = t -> false;
    private volatile int evaluations = 0;

    public void setPendingTargets(Set<String> targets) {
        Set<String> snapshot = Set.copyOf(targets);
        this.pendingTarget = snapshot::contains;
    }

    public void setUnhealthyTargets(Set<String> targets) {
        Set<String> snapshot = Set.copyOf(targets);
        this.unhealthyTarget = snapshot::contains;
    }

    /** 便捷方法：把一组目标统一标记为指定判定。 */
    public void setVerdict(GateVerdict forced, Set<String> targets) {
        if (forced == GateVerdict.PENDING) {
            setPendingTargets(targets);
            this.unhealthyTarget = t -> false;
        } else if (forced == GateVerdict.UNHEALTHY) {
            setUnhealthyTargets(targets);
            this.pendingTarget = t -> false;
        } else {
            reset();
        }
    }

    public int evaluationCount() {
        return evaluations;
    }

    public void reset() {
        this.pendingTarget = t -> false;
        this.unhealthyTarget = t -> false;
        this.evaluations = 0;
    }

    @Override
    public GateResult evaluate(String releaseId, String stage, List<String> targets) {
        List<TargetResult> results = new ArrayList<>();
        boolean anyPending = false;
        boolean anyUnhealthy = false;
        for (String target : targets) {
            if (unhealthyTarget.test(target)) {
                anyUnhealthy = true;
                results.add(new TargetResult(target, false, "health-check failed"));
            } else if (pendingTarget.test(target)) {
                anyPending = true;
                results.add(new TargetResult(target, false, "health-check pending"));
            } else {
                results.add(new TargetResult(target, true, "healthy"));
            }
        }
        evaluations++;

        GateVerdict verdict = anyUnhealthy ? GateVerdict.UNHEALTHY
                : anyPending ? GateVerdict.PENDING : GateVerdict.HEALTHY;
        String detail = "evaluated=" + targets.size()
                + " unhealthy=" + results.stream().filter(r -> r.detail().contains("failed")).count()
                + " pending=" + results.stream().filter(r -> r.detail().contains("pending")).count();
        log.info("health.evaluate releaseId={} stage={} targets={} verdict={} basis={}",
                releaseId, stage, targets, verdict, detail);
        return new GateResult(verdict, List.copyOf(results), detail);
    }
}
