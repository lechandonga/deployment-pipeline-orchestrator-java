package com.github.lechandonga.deploymentpipelineorchestratorjava.repository;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseOrder;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存仓储：以 releaseId 为键，始终返回同一聚合对象引用。
 * 聚合内部自带锁，因此仓储不再加锁；findAll 按创建时间排序便于排查。
 */
@Repository
public class InMemoryReleaseRepository implements ReleaseRepository {

    private final ConcurrentHashMap<String, ReleaseOrder> store = new ConcurrentHashMap<>();

    @Override
    public ReleaseOrder save(ReleaseOrder order) {
        ReleaseOrder existing = store.putIfAbsent(order.getReleaseId(), order);
        return existing != null ? existing : order;
    }

    @Override
    public Optional<ReleaseOrder> findById(String releaseId) {
        return Optional.ofNullable(store.get(releaseId));
    }

    @Override
    public List<ReleaseOrder> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(ReleaseOrder::getCreatedAt))
                .toList();
    }
}
