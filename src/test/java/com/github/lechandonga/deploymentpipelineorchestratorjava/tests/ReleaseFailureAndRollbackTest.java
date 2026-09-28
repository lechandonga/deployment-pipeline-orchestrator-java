package com.github.lechandonga.deploymentpipelineorchestratorjava.tests;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.FailureReason;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseStatus;
import com.github.lechandonga.deploymentpipelineorchestratorjava.error.ApiErrorCode;
import com.github.lechandonga.deploymentpipelineorchestratorjava.error.ApiException;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.ReleaseView;
import com.github.lechandonga.deploymentpipelineorchestratorjava.tests.support.PipelineHarness;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖：健康门禁失败、门禁等待超时、目标部署失败、主动放弃、回滚后不得再推进，
 * 且回滚/再推进不出现重复扣减或重复回补。
 */
class ReleaseFailureAndRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseFailureAndRollbackTest.class);

    private final PipelineHarness harness = new PipelineHarness();

    @Test
    void canaryUnhealthy_rollsBackWithDistinguishableReason() {
        String releaseId = "rel-unhealthy";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), null));
        harness.healthGate.setUnhealthyTargets(Set.of("node-1"));
        log.info("[输入] releaseId={} 注入不健康目标 node-1", releaseId);

        ReleaseView view = harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        log.warn("[关键状态] 门禁失败后 status={} reason={} detail={}",
                view.status(), view.failureReason(), view.failureDetail());
        assertEquals(ReleaseStatus.ROLLED_BACK, view.status());
        assertEquals(FailureReason.HEALTH_GATE_UNHEALTHY, view.failureReason(),
                "健康失败必须与超时/放弃可区分");
        assertEquals(0, view.deployedTargets().size(), "回滚后不应残留已部署目标");
        assertEquals(2, harness.quotaService.refundedCount(), "灰度占用应全部回补");
    }

    @Test
    void pendingGate_thenTimeout_entersTimedOutTerminal() {
        String releaseId = "rel-timeout";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofSeconds(30), null));
        harness.healthGate.setPendingTargets(Set.of("node-2"));
        log.info("[输入] releaseId={} 门禁一直 PENDING，timeout=30s", releaseId);

        ReleaseView waiting = harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        assertEquals(ReleaseStatus.CANARY_ROLLING_OUT, waiting.status(), "未超时应继续等待，不卡死不失败");
        log.info("[关键状态] 等待中 status={}（未超时不停留于错误终态）", waiting.status());

        harness.clock.advance(Duration.ofSeconds(31));
        boolean expired = harness.orchestrator.expireIfTimedOut(harness.repository.findById(releaseId).orElseThrow());
        ReleaseView view = harness.queryService.get(releaseId);
        log.warn("[判定] 超时扫描 expired={} status={} reason={}", expired, view.status(), view.failureReason());
        assertTrue(expired);
        assertEquals(ReleaseStatus.TIMED_OUT, view.status(), "超时必须落可区分终态");
        assertEquals(FailureReason.WAIT_TIMEOUT, view.failureReason());
        assertTrue(view.status().isTerminal());
        assertEquals(2, harness.quotaService.refundedCount(), "超时回滚也必须回补配额");
    }

    @Test
    void pendingGate_withinTimeout_allowsLaterSuccess() {
        String releaseId = "rel-pending-then-ok";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(10), null));
        harness.healthGate.setPendingTargets(Set.of("node-1"));

        ReleaseView waiting = harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        assertEquals(ReleaseStatus.CANARY_ROLLING_OUT, waiting.status());
        log.info("[关键状态] 首次门禁 PENDING status={}", waiting.status());

        harness.clock.advance(Duration.ofMinutes(1));
        harness.healthGate.reset();
        ReleaseView stable = harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        log.info("[判定] 门禁恢复后再次推进 status={}", stable.status());
        assertEquals(ReleaseStatus.CANARY_STABLE, stable.status());

        ReleaseView succeeded = harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        assertEquals(ReleaseStatus.SUCCEEDED, succeeded.status());
    }

    @Test
    void targetDeployFailure_rollsBackAndNotSilentlySucceeded() {
        String releaseId = "rel-deploy-fail";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), null));
        // node-1 先部署成功并占用；node-2 部署失败 -> 只能回滚已成功的 node-1
        harness.deployer.setFailDeployTargets(Set.of("node-2"));
        log.info("[输入] releaseId={} 注入部署失败目标 node-2", releaseId);

        ReleaseView view = harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        log.warn("[关键状态] 部署失败后 status={} reason={} deployed={} refunded={}",
                view.status(), view.failureReason(), view.deployedTargets(),
                harness.quotaService.refundedCount());
        assertEquals(ReleaseStatus.ROLLED_BACK, view.status());
        assertEquals(FailureReason.TARGET_FAILED, view.failureReason());
        assertEquals(1, harness.quotaService.refundedCount(), "只回补真正成功部署过的 node-1");
        assertEquals(0, view.deployedTargets().size());
    }

    @Test
    void abort_rollsBackToAbortedTerminalAndBlocksAdvance() {
        String releaseId = "rel-abort";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), null));
        harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        log.info("[输入] releaseId={} 灰度稳定后主动放弃", releaseId);

        ReleaseView aborted = harness.orchestrator.abort(releaseId, harness.abortCommand("key-abort-1"));
        log.warn("[关键状态] 放弃后 status={} reason={}", aborted.status(), aborted.failureReason());
        assertEquals(ReleaseStatus.ABORTED, aborted.status());
        assertEquals(FailureReason.ABANDONED, aborted.failureReason());
        assertEquals(2, harness.quotaService.refundedCount());

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> harness.orchestrator.advance(releaseId, harness.advanceCommand(null)));
        log.warn("[判定依据] 终态后推进被拒绝 code={}", ex.getCode());
        assertEquals(ApiErrorCode.TERMINAL_STATE, ex.getCode());
    }

    @Test
    void rollbackIsIdempotent_andDoesNotDoubleRefund() {
        String releaseId = "rel-rb-idem";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), null));
        harness.orchestrator.advance(releaseId, harness.advanceCommand(null));

        ReleaseView rb1 = harness.orchestrator.rollback(releaseId);
        int refundAfterFirst = harness.quotaService.refundedCount();
        log.info("[关键状态] 首次回滚 status={} refunded={}", rb1.status(), refundAfterFirst);
        assertEquals(ReleaseStatus.ROLLED_BACK, rb1.status());

        ReleaseView rb2 = harness.orchestrator.rollback(releaseId);
        log.info("[关键状态] 再次回滚 status={} replay={} refunded={}",
                rb2.status(), rb2.replay(), harness.quotaService.refundedCount());
        assertTrue(rb2.replay(), "已回滚状态下重复回滚应按幂等重放处理");
        assertEquals(refundAfterFirst, harness.quotaService.refundedCount(), "不得重复回补");

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> harness.orchestrator.advance(releaseId, harness.advanceCommand(null)));
        assertEquals(ApiErrorCode.INVALID_TRANSITION, ex.getCode());
        log.warn("[判定依据] 回滚后推进被拒绝，避免重复扣减 code={} basis={}",
                ex.getCode(), ex.getDecisionBasis());
    }

    @Test
    void rollbackFailure_markedRollbackFailedTerminal() {
        String releaseId = "rel-rb-fail";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), null));
        harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        harness.deployer.setFailRollbackTargets(Set.of("node-1"));
        log.info("[输入] releaseId={} 注入回滚失败目标 node-1", releaseId);

        ReleaseView view = harness.orchestrator.abort(releaseId, harness.abortCommand(null));
        log.warn("[关键状态] 部分回滚失败 status={} reason={} detail={}",
                view.status(), view.failureReason(), view.failureDetail());
        assertEquals(ReleaseStatus.ROLLBACK_FAILED, view.status());
        assertEquals(FailureReason.ROLLBACK_FAILED, view.failureReason());
        assertFalse(view.events().isEmpty(), "事件流必须保留失败细节用于复现");
    }
}
