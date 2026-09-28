package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseOrder;
import com.github.lechandonga.deploymentpipelineorchestratorjava.repository.ReleaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时扫描等待健康门禁的发布单；超时迁移由编排器在聚合锁内完成，
 * 因此扫描与并发推进之间不会出现重复迁移或状态丢失。
 */
@Component
public class TimeoutScanner {

    private static final Logger log = LoggerFactory.getLogger(TimeoutScanner.class);

    private final ReleaseRepository repository;
    private final PipelineOrchestratorService orchestrator;

    public TimeoutScanner(ReleaseRepository repository, PipelineOrchestratorService orchestrator) {
        this.repository = repository;
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${pipeline.timeout-scan-ms:500}")
    public void scan() {
        for (ReleaseOrder order : repository.findAll()) {
            try {
                boolean expired = orchestrator.expireIfTimedOut(order);
                if (expired) {
                    log.warn("timeout.scan releaseId={} 已超时回滚并进入 TIMED_OUT",
                            order.getReleaseId());
                }
            } catch (RuntimeException ex) {
                // 单个发布单异常不影响本轮其它发布单的扫描
                log.error("timeout.scan error releaseId={}", order.getReleaseId(), ex);
            }
        }
    }
}
