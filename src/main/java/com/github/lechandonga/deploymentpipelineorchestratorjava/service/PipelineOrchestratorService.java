package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

import com.github.lechandonga.deploymentpipelineorchestratorjava.deploy.TargetDeployer;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.FailureReason;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.IdempotencyRecord;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseEvent;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseOrder;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseStatus;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.Stage;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.TargetResult;
import com.github.lechandonga.deploymentpipelineorchestratorjava.error.ApiErrorCode;
import com.github.lechandonga.deploymentpipelineorchestratorjava.error.ApiException;
import com.github.lechandonga.deploymentpipelineorchestratorjava.gate.GateResult;
import com.github.lechandonga.deploymentpipelineorchestratorjava.gate.GateVerdict;
import com.github.lechandonga.deploymentpipelineorchestratorjava.gate.HealthGate;
import com.github.lechandonga.deploymentpipelineorchestratorjava.quota.QuotaService;
import com.github.lechandonga.deploymentpipelineorchestratorjava.repository.IdempotencyStore;
import com.github.lechandonga.deploymentpipelineorchestratorjava.repository.ReleaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 部署流水线编排服务：创建、灰度→全量推进、回滚与放弃。
 *
 * <p>一致性保证：
 * <ul>
 *   <li>单发布单的全部读改写都在聚合锁内串行完成，状态迁移单调且只允许合法路径；</li>
 *   <li>创建/推进/放弃携带幂等键时，相同指纹重放返回原结果（replay=true），
 *       版本/参数指纹不一致时抛出 {@link ApiErrorCode#IDEMPOTENCY_CONFLICT} 并给出原因；</li>
 *   <li>配额占用/回补按操作令牌幂等，回滚后再推进不会重复扣减；</li>
 *   <li>部署失败、健康门禁失败、等待超时、主动放弃落入各自可区分的终态/原因。</li>
 * </ul>
 */
@Service
public class PipelineOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestratorService.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final ReleaseRepository repository;
    private final IdempotencyStore idempotencyStore;
    private final HealthGate healthGate;
    private final TargetDeployer deployer;
    private final QuotaService quotaService;
    private final Clock clock;

    /** 幂等键维度的分段锁：把“查重放→执行→落账”串行化，消除并发 check-then-act 窗口。 */
    private final ConcurrentHashMap<String, Object> idempotencyLocks = new ConcurrentHashMap<>();

    public PipelineOrchestratorService(ReleaseRepository repository,
                                       IdempotencyStore idempotencyStore,
                                       HealthGate healthGate,
                                       TargetDeployer deployer,
                                       QuotaService quotaService,
                                       Clock clock) {
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
        this.healthGate = healthGate;
        this.deployer = deployer;
        this.quotaService = quotaService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    public ReleaseView create(CreateReleaseCommand command) {
        validateCreate(command);
        String fingerprint = createFingerprint(command);
        boolean hasKey = command.idempotencyKey() != null && !command.idempotencyKey().isBlank();
        // 同幂等键的并发请求在同一把分段锁内完成“查重放→建单→落账”
        Object keyLock = hasKey ? idempotencyLocks.computeIfAbsent(command.idempotencyKey(), k -> new Object()) : null;
        if (keyLock != null) {
            synchronized (keyLock) {
                return doCreate(command, fingerprint, true);
            }
        }
        return doCreate(command, fingerprint, false);
    }

    private ReleaseView doCreate(CreateReleaseCommand command, String fingerprint, boolean hasKey) {
        if (hasKey) {
            Optional<IdempotencyRecord> existing = idempotencyStore.find(command.idempotencyKey());
            if (existing.isPresent()) {
                return replayOrReject(existing.get(), fingerprint, Stage.CREATE);
            }
        }

        if (repository.findById(command.releaseId()).isPresent()) {
            throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT,
                    "releaseId 已存在: " + command.releaseId(),
                    "同一 releaseId 重复创建不属于幂等重放，拒绝以防产生重复发布单");
        }

        ReleaseOrder order = new ReleaseOrder();
        order.setReleaseId(command.releaseId());
        order.setApplication(command.application());
        order.setVersion(command.version());
        order.setParamsHash(fingerprint);
        order.setTargets(new com.github.lechandonga.deploymentpipelineorchestratorjava.domain.DeploymentTargets(
                List.copyOf(command.canaryTargets()), List.copyOf(command.fullTargets())));
        order.setStatus(ReleaseStatus.CREATED);
        order.setFailureReason(FailureReason.NONE);
        order.setCreatedAt(clock.instant());
        order.setHealthTimeout(command.healthTimeout() != null ? command.healthTimeout() : DEFAULT_TIMEOUT);

        order.lock().lock();
        try {
            order.appendEvent(clock, Stage.CREATE, null, ReleaseStatus.CREATED,
                    "创建发布单 version=" + command.version()
                            + " canary=" + command.canaryTargets()
                            + " full=" + command.fullTargets()
                            + " timeout=" + order.getHealthTimeout(),
                    "fingerprint=" + fingerprint);
            repository.save(order);
            if (hasKey) {
                idempotencyStore.putIfAbsent(new IdempotencyRecord(
                        command.idempotencyKey(), Stage.CREATE, fingerprint,
                        command.releaseId(), ReleaseStatus.CREATED.name(), clock.instant()));
            }
        } finally {
            order.lock().unlock();
        }
        log.info("release.created releaseId={} version={} fingerprint={}",
                command.releaseId(), command.version(), fingerprint);
        return toView(order, false, "created");
    }

    // ------------------------------------------------------------------
    // 阶段推进：CREATED -> 灰度 -> CANARY_STABLE -> 全量 -> SUCCEEDED
    // ------------------------------------------------------------------

    public ReleaseView advance(String releaseId, AdvanceCommand command) {
        String key = keyOf(command);
        if (key != null) {
            Object keyLock = idempotencyLocks.computeIfAbsent(key, k -> new Object());
            synchronized (keyLock) {
                return doAdvance(releaseId, command, key);
            }
        }
        return doAdvance(releaseId, command, null);
    }

    private static String keyOf(AdvanceCommand command) {
        if (command == null || command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            return null;
        }
        return command.idempotencyKey();
    }

    private ReleaseView doAdvance(String releaseId, AdvanceCommand command, String key) {
        ReleaseOrder order = requireOrder(releaseId);
        order.lock().lock();
        try {
            if (key != null) {
                Optional<IdempotencyRecord> existing = idempotencyStore.find(key);
                if (existing.isPresent()) {
                    return replayOrReject(existing.get(), order.getParamsHash(), Stage.PROMOTE);
                }
            }

            ReleaseStatus current = order.getStatus();
            if (current.isTerminal()) {
                throw new ApiException(ApiErrorCode.TERMINAL_STATE,
                        "发布单已处于终态 " + current + "，不得再推进",
                        "terminal=" + current + " reason=" + order.getFailureReason());
            }
            switch (current) {
                case CREATED -> startCanary(order);
                case CANARY_STABLE -> promoteFull(order);
                case CANARY_ROLLING_OUT -> evaluateCanaryGate(order);
                case FULL_ROLLING_OUT -> evaluateFullGate(order);
                case ROLLED_BACK ->
                        throw new ApiException(ApiErrorCode.INVALID_TRANSITION,
                                "已回滚的发布单只能放弃或查询，不能再次推进（避免重复扣减）",
                                "status=ROLLED_BACK；如需重试请新建发布单");
                default -> throw new ApiException(ApiErrorCode.INVALID_TRANSITION,
                        "当前状态 " + current + " 不允许推进", "非法迁移被拒绝");
            }

            if (key != null) {
                idempotencyStore.putIfAbsent(new IdempotencyRecord(
                        key, Stage.PROMOTE, order.getParamsHash(),
                        releaseId, order.getStatus().name(), clock.instant()));
            }
            return toView(order, false, "advanced");
        } finally {
            order.lock().unlock();
        }
    }

    /** 第一步推进：部署灰度目标 -> CANARY_ROLLING_OUT，并立即做一次门禁判定。 */
    private void startCanary(ReleaseOrder order) {
        order.setCanaryStartedAt(clock.instant());
        order.appendEvent(clock, Stage.CANARY, ReleaseStatus.CREATED,
                ReleaseStatus.CANARY_ROLLING_OUT,
                "开始灰度部署 targets=" + order.getTargets().canary(), null);
        boolean allDeployed = deployTargets(order, Stage.CANARY, order.getTargets().canary());
        if (!allDeployed) {
            rollbackLocked(order, FailureReason.TARGET_FAILED,
                    "灰度目标部署失败，不把发布当成功，执行回滚");
            return;
        }
        evaluateCanaryGate(order);
    }

    /** 灰度门禁：健康->CANARY_STABLE；等待->停留；不健康->回滚。 */
    private void evaluateCanaryGate(ReleaseOrder order) {
        GateResult gate = healthGate.evaluate(order.getReleaseId(), Stage.CANARY.name(),
                order.getTargets().canary());
        switch (gate.verdict()) {
            case HEALTHY -> {
                order.setStableSince(clock.instant());
                order.appendEvent(clock, Stage.CANARY, ReleaseStatus.CANARY_ROLLING_OUT,
                        ReleaseStatus.CANARY_STABLE, "灰度门禁通过", gate.detail());
            }
            case PENDING -> {
                if (isTimedOut(order, order.getCanaryStartedAt())) {
                    rollbackLocked(order, FailureReason.WAIT_TIMEOUT,
                            "灰度健康等待超时 timeout=" + order.getHealthTimeout()
                                    + " basis=" + gate.detail());
                } else {
                    order.appendEvent(clock, Stage.CANARY, ReleaseStatus.CANARY_ROLLING_OUT,
                            ReleaseStatus.CANARY_ROLLING_OUT,
                            "灰度健康条件未满足，继续等待", gate.detail());
                }
            }
            case UNHEALTHY -> rollbackLocked(order, FailureReason.HEALTH_GATE_UNHEALTHY,
                    "灰度门禁判定不健康 basis=" + gate.detail());
        }
    }

    /** 第二步推进：从 CANARY_STABLE 部署全量目标 -> FULL_ROLLING_OUT，并立即判定。 */
    private void promoteFull(ReleaseOrder order) {
        order.setFullStartedAt(clock.instant());
        order.appendEvent(clock, Stage.PROMOTE, ReleaseStatus.CANARY_STABLE,
                ReleaseStatus.FULL_ROLLING_OUT,
                "开始全量部署 targets=" + order.getTargets().full(), null);
        boolean allDeployed = deployTargets(order, Stage.PROMOTE, order.getTargets().full());
        if (!allDeployed) {
            rollbackLocked(order, FailureReason.TARGET_FAILED,
                    "全量目标部署失败，不把发布当成功，执行回滚");
            return;
        }
        evaluateFullGate(order);
    }

    /** 全量门禁：健康->SUCCEEDED；等待->停留；不健康->回滚。 */
    private void evaluateFullGate(ReleaseOrder order) {
        GateResult gate = healthGate.evaluate(order.getReleaseId(), Stage.PROMOTE.name(),
                order.getTargets().full());
        switch (gate.verdict()) {
            case HEALTHY -> {
                order.setFinishedAt(clock.instant());
                order.setFailureReason(FailureReason.NONE);
                order.appendEvent(clock, Stage.PROMOTE, ReleaseStatus.FULL_ROLLING_OUT,
                        ReleaseStatus.SUCCEEDED, "全量门禁通过，发布成功", gate.detail());
            }
            case PENDING -> {
                if (isTimedOut(order, order.getFullStartedAt())) {
                    rollbackLocked(order, FailureReason.WAIT_TIMEOUT,
                            "全量健康等待超时 timeout=" + order.getHealthTimeout()
                                    + " basis=" + gate.detail());
                } else {
                    order.appendEvent(clock, Stage.PROMOTE, ReleaseStatus.FULL_ROLLING_OUT,
                            ReleaseStatus.FULL_ROLLING_OUT,
                            "全量健康条件未满足，继续等待", gate.detail());
                }
            }
            case UNHEALTHY -> rollbackLocked(order, FailureReason.HEALTH_GATE_UNHEALTHY,
                    "全量门禁判定不健康 basis=" + gate.detail());
        }
    }

    /**
     * 逐个部署目标：先占用配额（令牌幂等），再执行部署；成功才记账。
     * 部署失败立即停止并返回 false，绝不静默吞错。
     */
    private boolean deployTargets(ReleaseOrder order, Stage stage, List<String> targets) {
        for (String target : targets) {
            boolean deducted = quotaService.occupy(order.getReleaseId(), stage.name(), target);
            TargetResult result = deployer.deploy(order.getReleaseId(), stage.name(), target, order.getVersion());
            if (!result.success()) {
                order.appendEvent(clock, stage, order.getStatus(), order.getStatus(),
                        "目标部署失败 target=" + target + " detail=" + result.detail()
                                + " quotaFirstDeduct=" + deducted,
                        "failure=target_deploy_failed");
                return false;
            }
            boolean newlyMarked = order.markDeployed(target);
            order.appendEvent(clock, stage, order.getStatus(), order.getStatus(),
                    "目标部署成功 target=" + target + " quotaFirstDeduct=" + deducted
                            + " newlyMarked=" + newlyMarked,
                    null);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 回滚 / 放弃
    // ------------------------------------------------------------------

    /** 显式回滚请求：非终态时回滚到 ROLLED_BACK；已回滚为幂等重放。 */
    public ReleaseView rollback(String releaseId) {
        ReleaseOrder order = requireOrder(releaseId);
        order.lock().lock();
        try {
            ReleaseStatus current = order.getStatus();
            if (current == ReleaseStatus.ROLLED_BACK) {
                return toView(order, true, "rollback 幂等重放：发布单已是 ROLLED_BACK");
            }
            if (current.isTerminal()) {
                throw new ApiException(ApiErrorCode.TERMINAL_STATE,
                        "发布单已处于终态 " + current + "，不能回滚",
                        "terminal=" + current + " reason=" + order.getFailureReason());
            }
            rollbackLocked(order, FailureReason.ABANDONED, "收到显式回滚请求");
            return toView(order, false, "rolled back");
        } finally {
            order.lock().unlock();
        }
    }

    /** 主动放弃：回滚后进入 ABORTED 终态。 */
    public ReleaseView abort(String releaseId, AbortCommand command) {
        String key = command != null && command.idempotencyKey() != null && !command.idempotencyKey().isBlank()
                ? command.idempotencyKey() : null;
        if (key != null) {
            Object keyLock = idempotencyLocks.computeIfAbsent(key, k -> new Object());
            synchronized (keyLock) {
                return doAbort(releaseId, key);
            }
        }
        return doAbort(releaseId, null);
    }

    private ReleaseView doAbort(String releaseId, String key) {
        ReleaseOrder order = requireOrder(releaseId);
        order.lock().lock();
        try {
            if (key != null) {
                Optional<IdempotencyRecord> existing = idempotencyStore.find(key);
                if (existing.isPresent()) {
                    return replayOrReject(existing.get(), order.getParamsHash(), Stage.ABORT);
                }
            }
            ReleaseStatus current = order.getStatus();
            if (current.isTerminal()) {
                throw new ApiException(ApiErrorCode.TERMINAL_STATE,
                        "发布单已处于终态 " + current + "，不能放弃",
                        "terminal=" + current + " reason=" + order.getFailureReason());
            }
            rollbackLocked(order, FailureReason.ABANDONED, "主动放弃发布");
            order.setFinishedAt(clock.instant());
            if (order.getStatus() == ReleaseStatus.ROLLBACK_FAILED) {
                // 回滚本身失败时保留 ROLLBACK_FAILED 终态，不得被 ABORTED 覆盖
                order.appendEvent(clock, Stage.ABORT, ReleaseStatus.ROLLBACK_FAILED,
                        ReleaseStatus.ROLLBACK_FAILED,
                        "主动放弃触发回滚，但部分目标回滚失败，维持 ROLLBACK_FAILED 终态",
                        "reason=ROLLBACK_FAILED original=ABANDONED");
            } else {
                order.appendEvent(clock, Stage.ABORT, ReleaseStatus.ROLLED_BACK,
                        ReleaseStatus.ABORTED,
                        "回滚完成，发布单进入 ABORTED 终态", "reason=ABANDONED");
            }

            if (key != null) {
                idempotencyStore.putIfAbsent(new IdempotencyRecord(
                        key, Stage.ABORT, order.getParamsHash(),
                        releaseId, order.getStatus().name(), clock.instant()));
            }
            return toView(order, false, "aborted");
        } finally {
            order.lock().unlock();
        }
    }

    /**
     * 持锁回滚：对所有已成功部署的目标逐个回滚并回补配额（均按令牌幂等）。
     * 回滚后状态为 ROLLED_BACK；若存在回滚失败目标，则升级为 ROLLBACK_FAILED 终态。
     * 调用方负责决定最终原因（目标失败/不健康/超时/放弃）。
     */
    private void rollbackLocked(ReleaseOrder order, FailureReason reason, String trigger) {
        ReleaseStatus from = order.getStatus();
        order.appendEvent(clock, Stage.ROLLBACK, from, ReleaseStatus.ROLLED_BACK,
                "开始回滚，触发原因: " + trigger, "reason=" + reason.name());

        List<String> deployed = new ArrayList<>(order.deployedTargetsSnapshot());
        List<String> failedTargets = new ArrayList<>();
        // 逆序回滚更接近真实运维语义
        java.util.Collections.reverse(deployed);
        for (String target : deployed) {
            TargetResult result = deployer.rollback(order.getReleaseId(), Stage.ROLLBACK.name(),
                    target, order.getVersion());
            if (result.success()) {
                boolean unmarked = order.unmarkDeployed(target);
                boolean refunded = quotaService.refund(order.getReleaseId(),
                        stageOfTarget(order, target), target);
                order.appendEvent(clock, Stage.ROLLBACK, ReleaseStatus.ROLLED_BACK,
                        ReleaseStatus.ROLLED_BACK,
                        "目标回滚成功 target=" + target + " unmarked=" + unmarked
                                + " quotaRefunded=" + refunded, null);
            } else {
                failedTargets.add(target);
                order.appendEvent(clock, Stage.ROLLBACK, ReleaseStatus.ROLLED_BACK,
                        ReleaseStatus.ROLLED_BACK,
                        "目标回滚失败 target=" + target + " detail=" + result.detail(),
                        "failure=rollback_target_failed");
            }
        }

        order.setFailureReason(reason);
        order.setFailureDetail(trigger);
        order.setFinishedAt(clock.instant());
        if (!failedTargets.isEmpty()) {
            order.setFailureReason(FailureReason.ROLLBACK_FAILED);
            order.setFailureDetail("回滚部分失败 targets=" + failedTargets + "；触发原因: " + reason);
            order.appendEvent(clock, Stage.ROLLBACK, ReleaseStatus.ROLLED_BACK,
                    ReleaseStatus.ROLLBACK_FAILED,
                    "部分目标回滚失败，进入 ROLLBACK_FAILED 终态 failed=" + failedTargets,
                    "reason=ROLLBACK_FAILED original=" + reason);
        } else {
            order.appendEvent(clock, Stage.ROLLBACK, ReleaseStatus.ROLLED_BACK,
                    ReleaseStatus.ROLLED_BACK,
                    "回滚完成，已回补全部配额", "finalReason=" + reason.name());
        }
    }

    /** 根据目标属于灰度还是全量集合，推断占用配额时使用的阶段令牌。 */
    private String stageOfTarget(ReleaseOrder order, String target) {
        if (order.getTargets().canary().contains(target)) {
            return Stage.CANARY.name();
        }
        return Stage.PROMOTE.name();
    }

    // ------------------------------------------------------------------
    // 超时
    // ------------------------------------------------------------------

    /** 供扫描器调用：等待门禁超时则回滚并落 TIMED_OUT 终态。 */
    public boolean expireIfTimedOut(ReleaseOrder order) {
        order.lock().lock();
        try {
            ReleaseStatus current = order.getStatus();
            Instant waitStart = switch (current) {
                case CANARY_ROLLING_OUT -> order.getCanaryStartedAt();
                case FULL_ROLLING_OUT -> order.getFullStartedAt();
                default -> null;
            };
            if (waitStart == null || !isTimedOut(order, waitStart)) {
                return false;
            }
            log.info("release.timeout releaseId={} status={} waited={} timeout={}",
                    order.getReleaseId(), current,
                    Duration.between(waitStart, clock.instant()), order.getHealthTimeout());
            rollbackLocked(order, FailureReason.WAIT_TIMEOUT,
                    "等待健康门禁超时 timeout=" + order.getHealthTimeout()
                            + " waited=" + Duration.between(waitStart, clock.instant()));
            // 回滚成功才覆盖为专用超时终态；回滚失败时保留 ROLLBACK_FAILED，二者可区分
            if (order.getStatus() != ReleaseStatus.ROLLBACK_FAILED) {
                order.appendEvent(clock, Stage.ROLLBACK, ReleaseStatus.ROLLED_BACK,
                        ReleaseStatus.TIMED_OUT,
                        "回滚完成，发布单进入 TIMED_OUT 终态", "reason=WAIT_TIMEOUT");
            }
            return true;
        } finally {
            order.lock().unlock();
        }
    }

    private boolean isTimedOut(ReleaseOrder order, Instant waitStart) {
        if (waitStart == null) {
            return false;
        }
        return !clock.instant().isBefore(waitStart.plus(order.getHealthTimeout()));
    }

    // ------------------------------------------------------------------
    // 幂等重放 / 拒绝
    // ------------------------------------------------------------------

    /**
     * 同键重放：指纹一致返回原结果；版本/参数指纹不一致抛出可区分冲突。
     */
    private ReleaseView replayOrReject(IdempotencyRecord record, String currentFingerprint, Stage stage) {
        if (!record.requestFingerprint().equals(currentFingerprint)) {
            log.warn("idempotency.conflict key={} stage={} storedFp={} requestFp={}",
                    record.idempotencyKey(), stage, record.requestFingerprint(), currentFingerprint);
            throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT,
                    "幂等键 " + record.idempotencyKey() + " 的重放请求与首次请求不一致（版本或参数对不上）",
                    "stage=" + stage + " stored.fingerprint=" + record.requestFingerprint()
                            + " request.fingerprint=" + currentFingerprint
                            + " originalReleaseId=" + record.releaseId());
        }
        ReleaseOrder original = requireOrder(record.releaseId());
        log.info("idempotency.replay key={} stage={} releaseId={} originalResult={}",
                record.idempotencyKey(), stage, record.releaseId(), record.result());
        return toView(original, true, "幂等重放，返回首次请求结果 " + record.result());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private ReleaseOrder requireOrder(String releaseId) {
        return repository.findById(releaseId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.RELEASE_NOT_FOUND,
                        "发布单不存在: " + releaseId, "按 releaseId 查询无记录"));
    }

    private void validateCreate(CreateReleaseCommand command) {
        if (command == null || isBlank(command.releaseId()) || isBlank(command.application())
                || isBlank(command.version())) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,
                    "releaseId/application/version 不能为空", "创建命令基础字段校验失败");
        }
        if (command.canaryTargets() == null || command.canaryTargets().isEmpty()
                || command.fullTargets() == null || command.fullTargets().isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,
                    "灰度目标与全量目标均不能为空", "目标集合校验失败");
        }
        if (!command.fullTargets().containsAll(command.canaryTargets())) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,
                    "灰度目标必须是全量目标的子集", "目标集合关系校验失败");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 对版本 + 规范化参数生成稳定指纹，用于判断重放是否同一次请求。 */
    private String createFingerprint(CreateReleaseCommand command) {
        Map<String, String> sorted = new TreeMap<>(
                command.params() != null ? command.params() : Map.of());
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("application", command.application());
        canonical.put("version", command.version());
        canonical.put("params", sorted);
        canonical.put("canary", command.canaryTargets().stream().sorted().toList());
        canonical.put("full", command.fullTargets().stream().sorted().toList());
        return sha256(canonical.toString());
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 组装对外视图（需在持锁或聚合稳定时调用）。 */
    private ReleaseView toView(ReleaseOrder order, boolean replay, String decision) {
        List<ReleaseEvent> events = order.snapshotEvents();
        return new ReleaseView(
                order.getReleaseId(),
                order.getApplication(),
                order.getVersion(),
                order.getStatus(),
                order.getFailureReason(),
                order.getFailureDetail(),
                replay,
                decision,
                order.getCreatedAt(),
                order.getCanaryStartedAt(),
                order.getStableSince(),
                order.getFullStartedAt(),
                order.getFinishedAt(),
                order.getHealthTimeout(),
                order.deployedTargetsSnapshot(),
                events
        );
    }
}
