package com.github.lechandonga.deploymentpipelineorchestratorjava.repository;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.IdempotencyRecord;

import java.util.Optional;

/**
 * 幂等记录存储：按幂等键取放首次请求的指纹与结果。
 */
public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(String idempotencyKey);

    void putIfAbsent(IdempotencyRecord record);
}
