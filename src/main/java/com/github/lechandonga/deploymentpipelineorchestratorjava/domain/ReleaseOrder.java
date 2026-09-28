package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 发布单聚合根：持有当前状态、阶段时间戳、失败原因、已部署目标以及完整事件日志。
 * 所有状态读改写均在 {@link #lock()} 内串行完成；事件只追加，保证判定过程可复现。
 */
public class ReleaseOrder {

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong eventSeq = new AtomicLong(0);
    private final List<ReleaseEvent> events = new ArrayList<>();

    /** 成功部署到新版本的目标（用于回滚与防重复扣减），保持插入顺序。 */
    private final Set<String> deployedTargets = new LinkedHashSet<>();

    private String releaseId;
    private String application;
    private String version;
    private String paramsHash;
    private DeploymentTargets targets;
    private ReleaseStatus status;
    private FailureReason failureReason = FailureReason.NONE;
    private String failureDetail;
    private Instant createdAt;
    private Instant canaryStartedAt;
    private Instant stableSince;
    private Instant fullStartedAt;
    private Instant finishedAt;
    private Duration healthTimeout;
    private int versioned;

    public ReleaseOrder() {
    }

    /** 串行化单发布单全部读改写的锁（可重入，允许编排层持锁调用本聚合方法）。 */
    public ReentrantLock lock() {
        return lock;
    }

    /** 追加一条不可变事件；from/to/message/reason 即为可复现的判定依据。 */
    public void appendEvent(Clock clock, Stage stage, ReleaseStatus from, ReleaseStatus to,
                            String message, String reason) {
        long seq = eventSeq.incrementAndGet();
        ReleaseEvent event = new ReleaseEvent(seq, clock.instant(), stage, from, to, message, reason);
        events.add(event);
        if (to != null) {
            this.status = to;
        }
    }

    /** 记录一个已成功部署的目标；重复记录返回 false，供上层判断是否重复扣减。 */
    public boolean markDeployed(String target) {
        return deployedTargets.add(target);
    }

    /** 移除一个已部署目标（回滚成功后调用）；不存在返回 false，保证回补只发生一次。 */
    public boolean unmarkDeployed(String target) {
        return deployedTargets.remove(target);
    }

    public List<String> deployedTargetsSnapshot() {
        return List.copyOf(deployedTargets);
    }

    public List<ReleaseEvent> snapshotEvents() {
        return List.copyOf(events);
    }

    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }
    public String getApplication() { return application; }
    public void setApplication(String application) { this.application = application; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getParamsHash() { return paramsHash; }
    public void setParamsHash(String paramsHash) { this.paramsHash = paramsHash; }
    public DeploymentTargets getTargets() { return targets; }
    public void setTargets(DeploymentTargets targets) { this.targets = targets; }
    public ReleaseStatus getStatus() { return status; }
    public void setStatus(ReleaseStatus status) { this.status = status; }
    public FailureReason getFailureReason() { return failureReason; }
    public void setFailureReason(FailureReason failureReason) { this.failureReason = failureReason; }
    public String getFailureDetail() { return failureDetail; }
    public void setFailureDetail(String failureDetail) { this.failureDetail = failureDetail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCanaryStartedAt() { return canaryStartedAt; }
    public void setCanaryStartedAt(Instant canaryStartedAt) { this.canaryStartedAt = canaryStartedAt; }
    public Instant getStableSince() { return stableSince; }
    public void setStableSince(Instant stableSince) { this.stableSince = stableSince; }
    public Instant getFullStartedAt() { return fullStartedAt; }
    public void setFullStartedAt(Instant fullStartedAt) { this.fullStartedAt = fullStartedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Duration getHealthTimeout() { return healthTimeout; }
    public void setHealthTimeout(Duration healthTimeout) { this.healthTimeout = healthTimeout; }
    public int getVersioned() { return versioned; }
    public void setVersioned(int versioned) { this.versioned = versioned; }
}
