package com.github.lechandonga.deploymentpipelineorchestratorjava.repository;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.ReleaseOrder;

import java.util.List;
import java.util.Optional;

/**
 * 发布单仓储。
 */
public interface ReleaseRepository {

    ReleaseOrder save(ReleaseOrder order);

    Optional<ReleaseOrder> findById(String releaseId);

    List<ReleaseOrder> findAll();
}
