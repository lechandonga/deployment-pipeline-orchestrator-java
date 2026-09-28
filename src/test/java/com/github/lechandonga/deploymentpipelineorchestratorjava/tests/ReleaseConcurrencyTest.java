package com.github.lechandonga.deploymentpipelineorchestratorjava.tests;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseStatus;
import com.github.lechandonga.deploymentpipelineorchestratorjava.error.ApiException;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.ReleaseView;
import com.github.lechandonga.deploymentpipelineorchestratorjava.tests.support.PipelineHarness;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖并发场景：
 * <ul>
 *   <li>同幂等键并发创建：只产生一张发布单，其余全部拿到原结果；</li>
 *   <li>并发重复推进：只有一个线程迁移成功，其余被串行化，状态不重复、不丢失；</li>
 *   <li>推进与超时扫描竞争：结果只能是“推进成功”或“超时回滚”之一，绝不出现状态跳变；</li>
 *   <li>并发回滚/放弃：回补只执行一次。</li>
 * </ul>
 */
class ReleaseConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseConcurrencyTest.class);

    private final PipelineHarness harness = new PipelineHarness();

    @Test
    void concurrentCreate_sameIdempotencyKey_singleReleaseCreated() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Set<String> resultingStatuses = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(threads);

        log.info("[输入] {} 个线程用同幂等键 key-cc-1 并发创建同一发布单", threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    ReleaseView view = harness.orchestrator.create(harness.createCommand(
                            "rel-cc", "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                            Duration.ofMinutes(2), "key-cc-1"));
                    resultingStatuses.add(view.status().name() + "#replay=" + view.replay());
                    log.info("[线程结果] create status={} replay={}", view.status(), view.replay());
                } catch (ApiException ex) {
                    resultingStatuses.add("ERROR:" + ex.getCode());
                } catch (Exception ex) {
                    resultingStatuses.add("ERROR:" + ex.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            }));
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, harness.queryService.list().size(), "并发创建只能产生一张发布单");
        log.info("[判定] 发布单数量=1，线程结果集合={}", resultingStatuses);
        assertTrue(resultingStatuses.stream().anyMatch(s -> s.contains("replay=true")),
                "必须存在重放返回原结果的线程");
        assertTrue(resultingStatuses.stream().noneMatch(s -> s.startsWith("ERROR")),
                "同键并发创建不应报冲突");
    }

    @Test
    void concurrentAdvance_serializedWithoutDoubleTransition() throws Exception {
        String releaseId = "rel-adv";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), null));

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Set<ReleaseStatus> finalObserved = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(threads);
        log.info("[输入] {} 个线程并发对 {} 执行无键推进（模拟重复提交/重试）", threads, releaseId);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    barrier.await();
                    try {
                        ReleaseView view = harness.orchestrator.advance(releaseId,
                                harness.advanceCommand(null));
                        finalObserved.add(view.status());
                        log.info("[线程结果] advance status={} deployed={}",
                                view.status(), view.deployedTargets().size());
                    } catch (ApiException ex) {
                        log.info("[线程结果] advance 被拒绝 code={}（串行化后的正常表现）", ex.getCode());
                    }
                } catch (Exception ex) {
                    log.error("[线程结果] advance 并发异常", ex);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        ReleaseView finalView = harness.queryService.get(releaseId);
        log.warn("[判定] 最终状态={} 事件数={} 配额占用={} 回补={}",
                finalView.status(), finalView.events().size(),
                harness.quotaService.occupiedCount(), harness.quotaService.refundedCount());
        assertEquals(ReleaseStatus.SUCCEEDED, finalView.status(), "并发推进最终必须收敛到 SUCCEEDED");
        assertEquals(6, harness.quotaService.occupiedCount(),
                "灰度2 + 全量4 槽位只扣减一次，无重复扣减");
        assertEquals(0, harness.quotaService.refundedCount(), "成功路径不应有回补");
        // 每个阶段只可能发生一次迁移事件；统计进入关键状态的次数
        long canaryEntries = finalView.events().stream()
                .filter(e -> e.toStatus() == ReleaseStatus.CANARY_STABLE).count();
        long successEntries = finalView.events().stream()
                .filter(e -> e.toStatus() == ReleaseStatus.SUCCEEDED).count();
        assertEquals(1, canaryEntries, "灰度稳定迁移只能发生一次");
        assertEquals(1, successEntries, "成功迁移只能发生一次");
    }

    @Test
    void advanceRacesTimeout_exactlyOneOutcome() throws Exception {
        String releaseId = "rel-race";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofSeconds(10), null));
        // 先让灰度部署完成并进入 PENDING 等待
        harness.healthGate.setPendingTargets(Set.of("node-1"));
        harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
        log.info("[输入] {} 已在灰度等待，门禁将在竞争期间恢复健康", releaseId);

        // 时钟推进到超时边界；门禁同时恢复健康
        harness.clock.advance(Duration.ofSeconds(10));
        harness.healthGate.reset();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> outcomes = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();
        pool.submit(() -> {
            try {
                barrier.await();
                ReleaseView v = harness.orchestrator.advance(releaseId, harness.advanceCommand(null));
                synchronized (outcomes) {
                    outcomes.add("advance:" + v.status());
                }
            } catch (ApiException e) {
                // 合法结局：超时先赢，推进方被终态守卫拒绝（串行化生效，未发生重复迁移）
                synchronized (outcomes) {
                    outcomes.add("advance-rejected:" + e.getCode());
                }
            } catch (Exception e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        });
        pool.submit(() -> {
            try {
                barrier.await();
                boolean expired = harness.orchestrator.expireIfTimedOut(
                        harness.repository.findById(releaseId).orElseThrow());
                synchronized (outcomes) {
                    outcomes.add("timeout:" + expired);
                }
            } catch (Exception e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        });
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "竞争线程必须全部结束");

        ReleaseView finalView = harness.queryService.get(releaseId);
        log.warn("[判定] 竞争结果 outcomes={} errors={} 最终状态={} 原因={}",
                outcomes, errors.stream().map(Exception::getMessage).toList(),
                finalView.status(), finalView.failureReason());
        assertTrue(errors.isEmpty(), "竞争不应产生未处理异常");

        // 两种合法结局互斥：要么推进赢（CANARY_STABLE），要么超时赢（TIMED_OUT）
        boolean advanceWon = finalView.status() == ReleaseStatus.CANARY_STABLE;
        boolean timeoutWon = finalView.status() == ReleaseStatus.TIMED_OUT;
        assertTrue(advanceWon ^ timeoutWon, "推进与超时只能有一个赢家，不允许状态跳变");
        if (advanceWon) {
            assertEquals(0, harness.quotaService.refundedCount());
        } else {
            assertEquals(2, harness.quotaService.refundedCount(), "超时赢家负责回滚并回补");
        }
    }

    @Test
    void concurrentAbortAndRollback_singleRefund() throws Exception {
        String releaseId = "rel-abort-race";
        harness.orchestrator.create(harness.createCommand(
                releaseId, "v1.0.0", harness.canaryTargets(), harness.fullTargets(),
                Duration.ofMinutes(2), null));
        harness.orchestrator.advance(releaseId, harness.advanceCommand(null));

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        log.info("[输入] {} 个线程并发放弃/回滚 {}", threads, releaseId);

        for (int i = 0; i < threads; i++) {
            final boolean abort = i % 2 == 0;
            pool.submit(() -> {
                try {
                    barrier.await();
                    try {
                        ReleaseView v = abort
                                ? harness.orchestrator.abort(releaseId, harness.abortCommand(null))
                                : harness.orchestrator.rollback(releaseId);
                        log.info("[线程结果] {} status={} reason={}",
                                abort ? "abort" : "rollback", v.status(), v.failureReason());
                    } catch (ApiException ex) {
                        log.info("[线程结果] {} 被拒绝 code={}", abort ? "abort" : "rollback", ex.getCode());
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        ReleaseView view = harness.queryService.get(releaseId);
        log.warn("[判定] 最终状态={} 回补数={} 已部署残留={}",
                view.status(), harness.quotaService.refundedCount(), view.deployedTargets());
        assertEquals(2, harness.quotaService.refundedCount(), "并发回滚/放弃只允许回补一次");
        assertEquals(0, view.deployedTargets().size());
        assertTrue(view.status() == ReleaseStatus.ROLLED_BACK
                || view.status() == ReleaseStatus.ABORTED, "结局只能是 ROLLED_BACK 或 ABORTED");
    }
}
