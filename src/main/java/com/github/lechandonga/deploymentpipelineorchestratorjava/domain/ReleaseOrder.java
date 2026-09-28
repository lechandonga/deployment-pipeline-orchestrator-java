package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 发布单聚合。非线程安全，所有变更必须由 ReleaseService 在 per-release 锁内执行，
 * 以保证阶段推进串行且单调。
 */
public class ReleaseOrder {

    private final String id;
    private final String version;
    private final List<String> canaryTargets;
    private final List<String> fullTargets;
    private final Instant createdAt;
    private final long stageTimeoutMillis;

    private ReleaseStatus status = ReleaseStatus.CREATED;
    private Stage currentStage;
    private FailureReason failureReason;
    private Instant statusChangedAt;
    private Instant finishedAt;

    /** 当前阶段已完成的目标（单调追加，回滚不重复扣减）。 */
    private final Set<String> completedTargets = new LinkedHashSet<>();
    /** 已回滚的目标，防止重复回滚扣减。 */
    private final Set<String> rolledBackTargets = new LinkedHashSet<>();
    private final List<StageEvent> history = new ArrayList<>();

    public ReleaseOrder(String id, String version, List<String> canaryTargets,
                        List<String> fullTargets, Instant createdAt, long stageTimeoutMillis) {
        this.id = id;
        this.version = version;
        this.canaryTargets = List.copyOf(canaryTargets);
        this.fullTargets = List.copyOf(fullTargets);
        this.createdAt = createdAt;
        this.stageTimeoutMillis = stageTimeoutMillis;
        this.statusChangedAt = createdAt;
    }

    /** 进入下一阶段的推进：状态单调前移，记录审计事件。 */
    public void advanceTo(ReleaseStatus next, Stage stage, String detail, Instant now) {
        if (status.isTerminal()) {
            throw new IllegalStateException("release " + id + " already terminal: " + status);
        }
        if (next.ordinal() <= status.ordinal() && !next.isTerminal()) {
            throw new IllegalStateException("non-monotonic transition " + status + " -> " + next);
        }
        transition(next, stage, detail, now);
    }

    /** 落入失败终态。 */
    public void fail(ReleaseStatus terminal, FailureReason reason, Stage stage, String detail, Instant now) {
        if (!terminal.isTerminal() || terminal == ReleaseStatus.SUCCEEDED) {
            throw new IllegalArgumentException("not a failure terminal: " + terminal);
        }
        if (status.isTerminal()) {
            throw new IllegalStateException("release " + id + " already terminal: " + status);
        }
        this.failureReason = reason;
        transition(terminal, stage, detail, now);
    }

    /** 失败回滚：允许从进行中状态或 FAILED 进入 ROLLED_BACK，其余终态拒绝。 */
    public void rollBack(Stage stage, String detail, Instant now) {
        if (status != ReleaseStatus.FAILED && status.isTerminal()) {
            throw new IllegalStateException("cannot rollback release " + id + " in state " + status);
        }
        this.failureReason = FailureReason.TARGET_FAILED;
        transition(ReleaseStatus.ROLLED_BACK, stage, detail, now);
    }

    private void transition(ReleaseStatus next, Stage stage, String detail, Instant now) {
        ReleaseStatus from = this.status;
        this.status = next;
        this.statusChangedAt = now;
        if (next.isTerminal()) {
            this.finishedAt = now;
        }
        this.history.add(new StageEvent(now, from, next, stage, detail));
    }

    /** 标记目标完成，返回是否为新增（幂等：重复上报不重复计数）。 */
    public boolean markTargetCompleted(String target) {
        return completedTargets.add(target);
    }

    /** 标记目标已回滚，返回是否为新增（防止重复扣减）。 */
    public boolean markTargetRolledBack(String target) {
        return rolledBackTargets.add(target);
    }

    public boolean isTimedOut(Instant now) {
        return !status.isTerminal()
                && status != ReleaseStatus.CREATED
                && now.toEpochMilli() - statusChangedAt.toEpochMilli() > stageTimeoutMillis;
    }

    public String getId() { return id; }
    public String getVersion() { return version; }
    public List<String> getCanaryTargets() { return canaryTargets; }
    public List<String> getFullTargets() { return fullTargets; }
    public Instant getCreatedAt() { return createdAt; }
    public long getStageTimeoutMillis() { return stageTimeoutMillis; }
    public ReleaseStatus getStatus() { return status; }
    public Stage getCurrentStage() { return currentStage; }
    public void setCurrentStage(Stage currentStage) { this.currentStage = currentStage; }
    public FailureReason getFailureReason() { return failureReason; }
    public Instant getStatusChangedAt() { return statusChangedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Set<String> getCompletedTargets() { return Collections.unmodifiableSet(completedTargets); }
    public Set<String> getRolledBackTargets() { return Collections.unmodifiableSet(rolledBackTargets); }
    public List<StageEvent> getHistory() { return Collections.unmodifiableList(history); }
}
