package com.github.lechandonga.deploymentpipelineorchestratorjava.repository;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.IdempotencyRecord;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存幂等存储：putIfAbsent 保证并发下只有首次请求落账，其余请求重放。
 */
@Repository
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, IdempotencyRecord> store = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> find(String idempotencyKey) {
        return Optional.ofNullable(store.get(idempotencyKey));
    }

    @Override
    public void putIfAbsent(IdempotencyRecord record) {
        store.putIfAbsent(record.idempotencyKey(), record);
    }
}
