package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.FailureReason;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseOrder;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseStatus;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.Stage;
import com.github.lechandonga.deploymentpipelineorchestratorjava.dto.ReleaseView;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发布编排核心服务。
 *
 * <p>幂等：每个变更操作携带 idempotencyKey；首次执行后记录 (fingerprint -> 结果快照)。
 * 同 key 同参数重放返回原结果；同 key 不同参数拒绝（IDEMPOTENCY_CONFLICT）。</p>
 *
 * <p>并发：每个发布单一锁（locks map），阶段推进在锁内串行执行，状态单调前移；
 * 进入终态后一切推进请求被拒绝（TERMINAL_STATE）。</p>
 */
@Service
public class ReleaseService {

    /** 幂等记录：参数指纹 + 操作结果快照。 */
    private record IdempotentRecord(String fingerprint, ReleaseView result) {
    }

    private final Map<String, ReleaseOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, IdempotentRecord> idempotencyStore = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    private final HealthGate healthGate;
    private final TargetDeployer deployer;
    private Clock clock = Clock.systemUTC();

    public ReleaseService(HealthGate healthGate, TargetDeployer deployer) {
        this.healthGate = healthGate;
        this.deployer = deployer;
    }

    /** 测试用：注入可控时钟以复现超时。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    private Object lockOf(String releaseId) {
        return locks.computeIfAbsent(releaseId, k -> new Object());
    }

    // ---------- 创建 ----------

    public ReleaseView create(String idempotencyKey, String version,
                              List<String> canaryTargets, List<String> fullTargets,
                              long stageTimeoutMillis) {
        String fingerprint = "create|" + version + "|" + canaryTargets + "|" + fullTargets
                + "|" + stageTimeoutMillis;
        IdempotentRecord existing = idempotencyStore.get(idempotencyKey);
        if (existing != null) {
            return replayOrReject(idempotencyKey, existing, fingerprint);
        }
        ReleaseOrder order = new ReleaseOrder(UUID.randomUUID().toString(), version,
                canaryTargets, fullTargets, now(), stageTimeoutMillis);
        orders.put(order.getId(), order);
        ReleaseView view = ReleaseView.of(order, false);
        IdempotentRecord race = idempotencyStore.putIfAbsent(idempotencyKey,
                new IdempotentRecord(fingerprint, view));
        if (race != null) {
            // 并发下同 key 已被其他请求登记：丢弃本次新建，按已有记录判定
            orders.remove(order.getId());
            return replayOrReject(idempotencyKey, race, fingerprint);
        }
        return view;
    }

    // ---------- 阶段推进 ----------

    public ReleaseView advance(String releaseId, String idempotencyKey) {
        String fingerprint = "advance|" + releaseId;
        IdempotentRecord existing = idempotencyStore.get(idempotencyKey);
        if (existing != null) {
            return replayOrReject(idempotencyKey, existing, fingerprint);
        }
        synchronized (lockOf(releaseId)) {
            existing = idempotencyStore.get(idempotencyKey);
            if (existing != null) {
                return replayOrReject(idempotencyKey, existing, fingerprint);
            }
            ReleaseOrder order = mustGet(releaseId);
            applyTimeoutIfNeeded(order);
            if (order.getStatus().isTerminal()) {
                throw new ReleaseException("TERMINAL_STATE",
                        "release " + releaseId + " already in terminal state " + order.getStatus());
            }
            ReleaseView view = doAdvance(order);
            idempotencyStore.put(idempotencyKey, new IdempotentRecord(fingerprint, view));
            return view;
        }
    }

    private ReleaseView doAdvance(ReleaseOrder order) {
        Instant now = now();
        switch (order.getStatus()) {
            case CREATED -> {
                order.setCurrentStage(Stage.CANARY);
                order.advanceTo(ReleaseStatus.CANARY_IN_PROGRESS, Stage.CANARY, "start canary", now);
                deployTargets(order, order.getCanaryTargets(), Stage.CANARY);
                checkHealth(order, order.getCanaryTargets(), Stage.CANARY);
            }
            case CANARY_IN_PROGRESS -> {
                checkHealth(order, order.getCanaryTargets(), Stage.CANARY);
                order.setCurrentStage(Stage.FULL_ROLLOUT);
                order.advanceTo(ReleaseStatus.FULL_ROLLOUT_IN_PROGRESS, Stage.FULL_ROLLOUT,
                        "canary healthy, start full rollout", now);
                deployTargets(order, order.getFullTargets(), Stage.FULL_ROLLOUT);
                checkHealth(order, order.getFullTargets(), Stage.FULL_ROLLOUT);
            }
            case FULL_ROLLOUT_IN_PROGRESS -> {
                checkHealth(order, order.getFullTargets(), Stage.FULL_ROLLOUT);
                order.advanceTo(ReleaseStatus.SUCCEEDED, Stage.FULL_ROLLOUT,
                        "all targets healthy, release succeeded", now);
            }
            default -> throw new ReleaseException("TERMINAL_STATE",
                    "release " + order.getId() + " already in terminal state " + order.getStatus());
        }
        return ReleaseView.of(order, false);
    }

    private void deployTargets(ReleaseOrder order, List<String> targets, Stage stage) {
        for (String target : targets) {
            if (order.getCompletedTargets().contains(target)) {
                continue; // 幂等：已完成目标不重复执行
            }
            if (!deployer.deploy(target)) {
                order.fail(ReleaseStatus.FAILED, FailureReason.TARGET_FAILED, stage,
                        "deploy failed on target " + target, now());
                throw new ReleaseException("TARGET_FAILED",
                        "deploy failed on target " + target);
            }
            order.markTargetCompleted(target);
        }
    }

    private void checkHealth(ReleaseOrder order, List<String> targets, Stage stage) {
        for (String target : targets) {
            if (!healthGate.isHealthy(target)) {
                order.fail(ReleaseStatus.FAILED, FailureReason.HEALTH_GATE_UNMET, stage,
                        "health gate unmet on target " + target, now());
                throw new ReleaseException("HEALTH_GATE_UNMET",
                        "health gate unmet on target " + target);
            }
        }
    }

    // ---------- 回滚 ----------

    public ReleaseView rollback(String releaseId, String idempotencyKey) {
        String fingerprint = "rollback|" + releaseId;
        IdempotentRecord existing = idempotencyStore.get(idempotencyKey);
        if (existing != null) {
            return replayOrReject(idempotencyKey, existing, fingerprint);
        }
        synchronized (lockOf(releaseId)) {
            existing = idempotencyStore.get(idempotencyKey);
            if (existing != null) {
                return replayOrReject(idempotencyKey, existing, fingerprint);
            }
            ReleaseOrder order = mustGet(releaseId);
            applyTimeoutIfNeeded(order);
            ReleaseStatus s = order.getStatus();
            if (s != ReleaseStatus.FAILED && s.isTerminal()) {
                throw new ReleaseException("TERMINAL_STATE",
                        "cannot rollback release in terminal state " + s);
            }
            // 只回滚已完成且未回滚过的目标，防止重复扣减
            for (String target : order.getCompletedTargets()) {
                if (order.markTargetRolledBack(target)) {
                    deployer.rollback(target);
                }
            }
            order.rollBack(order.getCurrentStage(),
                    "rolled back " + order.getRolledBackTargets().size() + " targets", now());
            ReleaseView view = ReleaseView.of(order, false);
            idempotencyStore.put(idempotencyKey, new IdempotentRecord(fingerprint, view));
            return view;
        }
    }

    // ---------- 主动放弃 ----------

    public ReleaseView abort(String releaseId, String idempotencyKey) {
        String fingerprint = "abort|" + releaseId;
        IdempotentRecord existing = idempotencyStore.get(idempotencyKey);
        if (existing != null) {
            return replayOrReject(idempotencyKey, existing, fingerprint);
        }
        synchronized (lockOf(releaseId)) {
            existing = idempotencyStore.get(idempotencyKey);
            if (existing != null) {
                return replayOrReject(idempotencyKey, existing, fingerprint);
            }
            ReleaseOrder order = mustGet(releaseId);
            applyTimeoutIfNeeded(order);
            if (order.getStatus().isTerminal()) {
                throw new ReleaseException("TERMINAL_STATE",
                        "cannot abort release in terminal state " + order.getStatus());
            }
            order.fail(ReleaseStatus.ABORTED, FailureReason.ABORTED_BY_USER, order.getCurrentStage(),
                    "aborted by user", now());
            ReleaseView view = ReleaseView.of(order, false);
            idempotencyStore.put(idempotencyKey, new IdempotentRecord(fingerprint, view));
            return view;
        }
    }

    // ---------- 查询与超时 ----------

    public ReleaseView get(String releaseId) {
        synchronized (lockOf(releaseId)) {
            ReleaseOrder order = mustGet(releaseId);
            applyTimeoutIfNeeded(order);
            return ReleaseView.of(order, false);
        }
    }

    /** 扫描所有发布单，把等待超时的推进到 TIMED_OUT 终态（可被定时任务或查询触发）。 */
    public void sweepTimeouts() {
        for (ReleaseOrder order : orders.values()) {
            synchronized (lockOf(order.getId())) {
                applyTimeoutIfNeeded(order);
            }
        }
    }

    private void applyTimeoutIfNeeded(ReleaseOrder order) {
        if (order.isTimedOut(now())) {
            order.fail(ReleaseStatus.TIMED_OUT, FailureReason.TIMEOUT, order.getCurrentStage(),
                    "stage timed out after " + order.getStageTimeoutMillis() + "ms", now());
        }
    }

    // ---------- 内部工具 ----------

    private ReleaseOrder mustGet(String releaseId) {
        ReleaseOrder order = orders.get(releaseId);
        if (order == null) {
            throw new ReleaseException("NOT_FOUND", "release " + releaseId + " not found");
        }
        return order;
    }

    private ReleaseView replayOrReject(String key, IdempotentRecord record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new ReleaseException("IDEMPOTENCY_CONFLICT",
                    "idempotency key " + key + " replayed with different parameters");
        }
        return new ReleaseView(record.result().id(), record.result().version(),
                record.result().status(), record.result().currentStage(),
                record.result().failureReason(), record.result().completedTargets(),
                record.result().rolledBackTargets(), record.result().createdAt(),
                record.result().finishedAt(), record.result().history(), true);
    }
}
