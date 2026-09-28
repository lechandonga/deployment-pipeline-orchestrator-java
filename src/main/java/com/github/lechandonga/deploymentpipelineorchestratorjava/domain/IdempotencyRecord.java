package com.github.lechandonga.deploymentpipelineorchestratorjava.domain;

import java.time.Instant;

/**
 * 幂等记录：同一幂等键首次请求的指纹与处理结果，重放时据此返回原结果或拒绝。
 */
public record IdempotencyRecord(
        String idempotencyKey,
        Stage stage,
        String requestFingerprint,
        String releaseId,
        String result,
        Instant firstSeenAt
) {
}
