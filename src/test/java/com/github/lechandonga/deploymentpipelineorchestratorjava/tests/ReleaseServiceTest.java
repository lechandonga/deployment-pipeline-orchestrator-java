package com.github.lechandonga.deploymentpipelineorchestratorjava.tests;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.FailureReason;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseStatus;
import com.github.lechandonga.deploymentpipelineorchestratorjava.dto.ReleaseView;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.ConfigurableHealthGate;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.ReleaseException;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.ReleaseService;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.TargetDeployer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseServiceTest.class);

    /** 可推进的时钟，用于复现超时。 */
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-28T00:00:00Z");

        void advanceMillis(long ms) {
            now = now.plusMillis(ms);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** 记录调用次数的执行器，可配置失败目标。 */
    static class RecordingDeployer implements TargetDeployer {
        final Map<String, AtomicInteger> deployCalls = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> rollbackCalls = new ConcurrentHashMap<>();
        volatile List<String> failOn = List.of();

        @Override
        public boolean deploy(String target) {
            deployCalls.computeIfAbsent(target, k -> new AtomicInteger()).incrementAndGet();
            return !failOn.contains(target);
        }

        @Override
        public boolean rollback(String target) {
            rollbackCalls.computeIfAbsent(target, k -> new AtomicInteger()).incrementAndGet();
            return true;
        }

        int deployCount(String target) {
            AtomicInteger c = deployCalls.get(target);
            return c == null ? 0 : c.get();
        }

        int rollbackCount(String target) {
            AtomicInteger c = rollbackCalls.get(target);
            return c == null ? 0 : c.get();
        }
    }

    private ConfigurableHealthGate healthGate;
    private RecordingDeployer deployer;
    private MutableClock clock;
    private ReleaseService service;

    @BeforeEach
    void setUp() {
        healthGate = new ConfigurableHealthGate();
        deployer = new RecordingDeployer();
        clock = new MutableClock();
        service = new ReleaseService(healthGate, deployer);
        service.setClock(clock);
    }

    private ReleaseView createDefault(String key) {
        return service.create(key, "v1.0.0", List.of("canary-1"), List.of("full-1", "full-2"), 60_000);
    }

    @Test
    void createIsIdempotentAndRejectsMismatchedReplay() {
        log.info("[输入] 同一幂等键 key=A 连续创建两次，参数相同");
        ReleaseView first = createDefault("A");
        ReleaseView replay = createDefault("A");
        log.info("[关键状态] first.id={} replay.id={} replayed={}", first.id(), replay.id(), replay.replayed());
        assertEquals(first.id(), replay.id(), "同 key 同参数重放必须返回原发布单");
        assertTrue(replay.replayed(), "重放结果必须带 replayed 标记");

        log.info("[输入] 同一幂等键 key=A 但版本改为 v2.0.0");
        ReleaseException e = assertThrows(ReleaseException.class,
                () -> service.create("A", "v2.0.0", List.of("canary-1"), List.of("full-1"), 60_000));
        log.info("[判定依据] code={} message={}", e.getCode(), e.getMessage());
        assertEquals("IDEMPOTENCY_CONFLICT", e.getCode(), "参数对不上的重放必须被拒绝且原因可区分");
    }

    @Test
    void concurrentAdvanceWithSameKeyExecutesOnlyOnce() throws InterruptedException {
        ReleaseView created = createDefault("C");
        String id = created.id();
        int threads = 16;
        List<ReleaseView> results = runConcurrentAdvance(id, threads, i -> "same-key");
        ReleaseView after = service.get(id);
        long fresh = results.stream().filter(r -> !r.replayed()).count();
        long replayed = results.stream().filter(ReleaseView::replayed).count();
        log.info("[关键状态] 首次执行数={} 重放数={} 当前状态={} canary-1部署次数={}",
                fresh, replayed, after.status(), deployer.deployCount("canary-1"));
        assertEquals(threads, results.size(), "重试风暴下每个请求都要拿到结果");
        assertEquals(1, fresh, "同一幂等键只应真正执行一次");
        assertEquals(threads - 1, replayed, "其余请求必须拿到原结果的重放");
        assertEquals(ReleaseStatus.CANARY_IN_PROGRESS, after.status());
        assertEquals(1, deployer.deployCount("canary-1"), "灰度目标不得被重复部署");
    }

    @Test
    void concurrentAdvanceWithDistinctKeysStaysMonotonic() throws InterruptedException {
        ReleaseView created = createDefault("C2");
        String id = created.id();
        int threads = 16;
        List<ReleaseView> results = runConcurrentAdvance(id, threads, i -> "adv-" + i);
        ReleaseView after = service.get(id);
        log.info("[关键状态] 成功推进数={} 最终状态={} 各目标部署次数 canary-1={} full-1={} full-2={}",
                results.size(), after.status(),
                deployer.deployCount("canary-1"), deployer.deployCount("full-1"),
                deployer.deployCount("full-2"));
        assertTrue(results.size() <= 3, "一次发布只有 3 个可推进阶段，成功推进不得超限");
        assertEquals(ReleaseStatus.SUCCEEDED, after.status(), "状态不得丢失，应单调推进到成功");
        assertEquals(1, deployer.deployCount("canary-1"));
        assertEquals(1, deployer.deployCount("full-1"));
        assertEquals(1, deployer.deployCount("full-2"));
    }

    /** 并发推进辅助：keyFn 决定每个线程的幂等键，返回成功拿到结果的视图列表。 */
    private List<ReleaseView> runConcurrentAdvance(String id, int threads,
                                                   java.util.function.IntFunction<String> keyFn)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<ReleaseView> successes = new ArrayList<>();
        List<ReleaseException> failures = new ArrayList<>();
        log.info("[输入] {} 个线程并发推进同一发布单 {}", threads, id);
        for (int i = 0; i < threads; i++) {
            String key = keyFn.apply(i);
            pool.submit(() -> {
                try {
                    start.await();
                    ReleaseView v = service.advance(id, key);
                    synchronized (successes) {
                        successes.add(v);
                    }
                } catch (ReleaseException e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        for (ReleaseException e : failures) {
            log.info("[判定依据] 被拒线程 code={}", e.getCode());
            assertEquals("TERMINAL_STATE", e.getCode(), "被拒请求必须给出可区分原因");
        }
        return successes;
    }

    @Test
    void advanceReplayReturnsOriginalResult() {
        ReleaseView created = createDefault("R");
        log.info("[输入] 发布单 {} 用 key=K1 推进两次", created.id());
        ReleaseView first = service.advance(created.id(), "K1");
        ReleaseView replay = service.advance(created.id(), "K1");
        log.info("[关键状态] first.status={} replay.status={} replayed={}",
                first.status(), replay.status(), replay.replayed());
        assertEquals(first.status(), replay.status());
        assertTrue(replay.replayed());
        assertEquals(1, deployer.deployCount("canary-1"), "重放不得重复执行部署");
    }

    @Test
    void fullRolloutFailureRollsBackOnceAndStaysTerminal() {
        ReleaseView created = createDefault("F");
        String id = created.id();
        service.advance(id, "a1");
        deployer.failOn = List.of("full-2");
        log.info("[输入] full-2 部署失败后推进全量阶段");

        ReleaseException failed = assertThrows(ReleaseException.class,
                () -> service.advance(id, "a2"));
        ReleaseView afterFail = service.get(id);
        log.info("[关键状态] status={} failureReason={} code={}",
                afterFail.status(), afterFail.failureReason(), failed.getCode());
        assertEquals(ReleaseStatus.FAILED, afterFail.status());
        assertEquals(FailureReason.TARGET_FAILED, afterFail.failureReason());
        assertEquals("TARGET_FAILED", failed.getCode());

        log.info("[输入] 对失败发布单执行回滚，随后再次回滚、再次推进");
        ReleaseView rolledBack = service.rollback(id, "rb1");
        log.info("[关键状态] status={} rolledBackTargets={} full-1回滚次数={}",
                rolledBack.status(), rolledBack.rolledBackTargets(), deployer.rollbackCount("full-1"));
        assertEquals(ReleaseStatus.ROLLED_BACK, rolledBack.status());
        assertEquals(1, deployer.rollbackCount("full-1"), "每个目标只回滚一次");
        assertEquals(0, deployer.rollbackCount("full-2"), "未完成部署的目标不得回滚");

        ReleaseException again = assertThrows(ReleaseException.class, () -> service.rollback(id, "rb2"));
        log.info("[判定依据] 重复回滚 code={}", again.getCode());
        assertEquals("TERMINAL_STATE", again.getCode(), "回滚后不得重复回滚（防重复扣减）");
        assertEquals(1, deployer.rollbackCount("full-1"));

        ReleaseException adv = assertThrows(ReleaseException.class, () -> service.advance(id, "a3"));
        log.info("[判定依据] 终态推进 code={}", adv.getCode());
        assertEquals("TERMINAL_STATE", adv.getCode(), "终态后不得再被推进（防状态跳变）");
        assertEquals(ReleaseStatus.ROLLED_BACK, service.get(id).status());
    }

    @Test
    void unhealthyCanaryFailsWithHealthGateReason() {
        healthGate.markUnhealthy("canary-1");
        ReleaseView created = createDefault("H");
        log.info("[输入] canary-1 不健康时推进灰度阶段");
        ReleaseException e = assertThrows(ReleaseException.class,
                () -> service.advance(created.id(), "h1"));
        ReleaseView after = service.get(created.id());
        log.info("[关键状态] status={} failureReason={} code={}",
                after.status(), after.failureReason(), e.getCode());
        assertEquals(ReleaseStatus.FAILED, after.status());
        assertEquals(FailureReason.HEALTH_GATE_UNMET, after.failureReason());
        assertEquals("HEALTH_GATE_UNMET", e.getCode());
    }

    @Test
    void waitingTooLongEndsInTimeout() {
        ReleaseView created = createDefault("T");
        String id = created.id();
        service.advance(id, "t1");
        log.info("[输入] 灰度进行中，时钟推进超过 60s 超时阈值后查询");
        clock.advanceMillis(61_000);
        ReleaseView after = service.get(id);
        log.info("[关键状态] status={} failureReason={} history={}",
                after.status(), after.failureReason(), after.history());
        assertEquals(ReleaseStatus.TIMED_OUT, after.status(), "等待超时必须落到 TIMED_OUT 终态而不是卡住");
        assertEquals(FailureReason.TIMEOUT, after.failureReason());

        ReleaseException e = assertThrows(ReleaseException.class, () -> service.advance(id, "t2"));
        log.info("[判定依据] 超时后推进 code={}", e.getCode());
        assertEquals("TERMINAL_STATE", e.getCode());
    }

    @Test
    void abortEndsInDistinctTerminalAndHistoryIsReproducible() {
        ReleaseView created = createDefault("X");
        String id = created.id();
        service.advance(id, "x1");
        log.info("[输入] 灰度进行中主动放弃");
        ReleaseView aborted = service.abort(id, "x2");
        log.info("[关键状态] status={} failureReason={}", aborted.status(), aborted.failureReason());
        assertEquals(ReleaseStatus.ABORTED, aborted.status());
        assertEquals(FailureReason.ABORTED_BY_USER, aborted.failureReason());

        ReleaseView queried = service.get(id);
        log.info("[判定依据] 历史事件数={} 事件={}", queried.history().size(), queried.history());
        assertEquals(aborted.status(), queried.status(), "历史发布单可查询且判定可复现");
        assertEquals(2, queried.history().size(), "每次状态迁移都要留下审计事件");
    }

    @Test
    void happyPathReachesSucceededAndRejectsFurtherAdvance() {
        ReleaseView created = createDefault("S");
        String id = created.id();
        log.info("[输入] 依次推进 灰度 -> 全量 -> 成功");
        service.advance(id, "s1");
        service.advance(id, "s2");
        ReleaseView done = service.advance(id, "s3");
        log.info("[关键状态] status={} completedTargets={}", done.status(), done.completedTargets());
        assertEquals(ReleaseStatus.SUCCEEDED, done.status());
        assertEquals(1, deployer.deployCount("canary-1"));
        assertEquals(1, deployer.deployCount("full-1"));
        assertEquals(1, deployer.deployCount("full-2"));

        ReleaseException e = assertThrows(ReleaseException.class, () -> service.advance(id, "s4"));
        log.info("[判定依据] 成功后推进 code={}", e.getCode());
        assertEquals("TERMINAL_STATE", e.getCode());
        assertNotEquals(ReleaseStatus.CREATED, service.get(id).status());
    }
}
