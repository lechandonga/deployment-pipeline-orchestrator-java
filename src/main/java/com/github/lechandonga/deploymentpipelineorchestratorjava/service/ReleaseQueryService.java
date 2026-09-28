package com.github.lechandonga.deploymentpipelineorchestratorjava.service;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseEvent;
import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseOrder;
import com.github.lechandonga.deploymentpipelineorchestratorjava.error.ApiErrorCode;
import com.github.lechandonga.deploymentpipelineorchestratorjava.error.ApiException;
import com.github.lechandonga.deploymentpipelineorchestratorjava.repository.ReleaseRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 发布单查询服务：返回当前状态视图（含完整事件流），支持历史复现与最终判定追溯。
 */
@Service
public class ReleaseQueryService {

    private final ReleaseRepository repository;

    public ReleaseQueryService(ReleaseRepository repository) {
        this.repository = repository;
    }

    public ReleaseView get(String releaseId) {
        ReleaseOrder order = repository.findById(releaseId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.RELEASE_NOT_FOUND,
                        "发布单不存在: " + releaseId, "按 releaseId 查询无记录"));
        order.lock().lock();
        try {
            return toView(order);
        } finally {
            order.lock().unlock();
        }
    }

    public List<ReleaseView> list() {
        return repository.findAll().stream().map(this::toViewLocked).toList();
    }

    private ReleaseView toViewLocked(ReleaseOrder order) {
        order.lock().lock();
        try {
            return toView(order);
        } finally {
            order.lock().unlock();
        }
    }

    private ReleaseView toView(ReleaseOrder order) {
        List<ReleaseEvent> events = order.snapshotEvents();
        String decision = order.getStatus().isTerminal()
                ? "最终判定 status=" + order.getStatus() + " reason=" + order.getFailureReason()
                : "进行中 status=" + order.getStatus();
        return new ReleaseView(
                order.getReleaseId(),
                order.getApplication(),
                order.getVersion(),
                order.getStatus(),
                order.getFailureReason(),
                order.getFailureDetail(),
                false,
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
