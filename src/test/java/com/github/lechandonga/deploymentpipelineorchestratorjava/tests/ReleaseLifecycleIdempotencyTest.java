package com.github.lechandonga.deploymentpipelineorchestratorjava.tests;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseStatus;
import com.github.lechandonga.deploymentpipelineorchestratorjava.error.ApiErrorCode;
import com.github.lechandonga.deploymentpipelineorchestratorjava.error.ApiException;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.CreateReleaseCommand;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.ReleaseView;
import com.github.lechandonga.deploymentpipelineorchestratorjava.tests.support.PipelineHarness;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖：正常 灰度→全量 生命周期；创建/推进的幂等重放；版本/参数不一致的重放冲突。
 */
class ReleaseLifecycleIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseLifecycleIdempotencyTest.class);

    private final PipelineHarness harness = new PipelineHarness();

    @Test
    void happyFlow_canaryThenFull_reachesSucceeded() {
        String releaseId = "rel-happy";
        log.info("[输入] releaseId={} 期望路径 CREATED->CANARY_ROLLING_OUT->CANARY_STABLE->FULL_ROLLING_OUT->SUCCEEDED",
                releaseId);

        ReleaseView created = harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), null));
        log.info("[关键状态] 创建后 status={} reason={}", created.status(), created.failureReason());
        assertEquals(ReleaseStatus.CREATED, created.status());

        ReleaseView canary = harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        log.info("[判定] 第一次推进 status={} gateHealth=true => 灰度稳定", canary.status());
        assertEquals(ReleaseStatus.CANARY_STABLE, canary.status());

        ReleaseView full = harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        log.info("[判定] 第二次推进 status={} gateHealth=true => 全量成功 deployed={}",
                full.status(), full.deployedTargets());
        assertEquals(ReleaseStatus.SUCCEEDED, full.status());
        assertEquals(4, full.deployedTargets().size());
        assertNotNull(full.finishedAt(), "终态必须有完成时间");
        assertFalse(full.replay(), "首次执行不应标记为重放");
    }

    @Test
    void create_sameIdempotencyKey_samePayload_replaysOriginalResult() {
        CreateReleaseCommand first = harness.createCommand(
                "rel-replay", "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), "key-create-1");
        ReleaseView v1 = harness.orchestrator.create(first);
        log.info("[输入] 首次创建 releaseId=rel-replay key=key-create-1 status={}", v1.status());

        ReleaseView v2 = harness.orchestrator.create(first);
        log.info("[关键状态] 同键同体重放 status={} replay={} decision={}",
                v2.status(), v2.replay(), v2.decision());
        assertTrue(v2.replay(), "相同幂等键+相同指纹必须返回原结果并标记 replay");
        assertEquals(v1.status(), v2.status());
        assertEquals(1, harness.queryService.list().size(), "重放不得产生第二条发布单");
    }

    @Test
    void create_sameIdempotencyKey_differentVersion_isRejectedWithConflict() {
        harness.orchestrator.create(harness.createCommand(
                "rel-conflict-v", "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), "key-create-version"));
        log.info("[输入] 首次创建 version=v1.0.0 key=key-create-version");

        CreateReleaseCommand replayed = harness.createCommand(
                "rel-conflict-v", "v9.9.9", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), "key-create-version");
        ApiException ex = assertThrows(ApiException.class, () -> harness.orchestrator.create(replayed));
        log.warn("[判定依据] 版本对不上被拒绝 code={} message={} basis={}",
                ex.getCode(), ex.getMessage(), ex.getDecisionBasis());
        assertEquals(ApiErrorCode.IDEMPOTENCY_CONFLICT, ex.getCode());
        assertTrue(ex.getDecisionBasis().contains("fingerprint"), "冲突原因必须包含指纹差异");
    }

    @Test
    void create_sameIdempotencyKey_differentParams_isRejectedWithConflict() {
        harness.orchestrator.create(harness.createCommand(
                "rel-conflict-p", "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Map.of("region", "cn-east-1"), Duration.ofMinutes(2), "key-create-params"));
        log.info("[输入] 首次创建 params.region=cn-east-1 key=key-create-params");

        CreateReleaseCommand replayed = harness.createCommand(
                "rel-conflict-p", "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Map.of("region", "us-west-2"), Duration.ofMinutes(2), "key-create-params");
        ApiException ex = assertThrows(ApiException.class, () -> harness.orchestrator.create(replayed));
        log.warn("[判定依据] 参数对不上被拒绝 code={} basis={}", ex.getCode(), ex.getDecisionBasis());
        assertEquals(ApiErrorCode.IDEMPOTENCY_CONFLICT, ex.getCode());
    }

    @Test
    void advance_sameIdempotencyKey_replaysWithoutDoubleExecution() {
        String releaseId = "rel-advance-replay";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), "key-c"));

        ReleaseView first = harness.orchestrator.advance(releaseId, harness.advanceCommand("key-a1"));
        log.info("[关键状态] 推进首次执行 status={}", first.status());
        ReleaseView replay = harness.orchestrator.advance(releaseId, harness.advanceCommand("key-a1"));
        log.info("[关键状态] 推进同键重放 status={} replay={} events={}",
                replay.status(), replay.replay(), replay.events().size());

        assertTrue(replay.replay());
        assertEquals(first.status(), replay.status());
        assertEquals(first.events().size(), replay.events().size(), "重放不得追加事件或重复执行");
        assertEquals(2, harness.quotaService.occupiedCount(), "灰度两目标只扣减一次");

        // 第二次推进用新键，正常继续；灰度与全量是两个阶段，槽位令牌分别扣减（2+4=6）
        ReleaseView full = harness.orchestrator.advance(releaseId, harness.advanceCommand("key-a2"));
        assertEquals(ReleaseStatus.SUCCEEDED, full.status());
        log.info("[判定] 新键推进继续完成 status={} occupied={}", full.status(),
                harness.quotaService.occupiedCount());
        assertEquals(6, harness.quotaService.occupiedCount());
    }

    @Test
    void advance_intoTerminalState_isRejected() {
        String releaseId = "rel-terminal";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), null));
        harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        log.info("[输入] releaseId={} 已到终态 SUCCEEDED，再次推进", releaseId);

        ApiException ex = assertThrows(ApiException.class,
                () -> harness.orchestrator.advance(releaseId, harness.advanceCommand(null)));
        log.warn("[判定依据] 终态推进被拒绝 code={} basis={}", ex.getCode(), ex.getDecisionBasis());
        assertEquals(ApiErrorCode.TERMINAL_STATE, ex.getCode());
    }
}
