package com.github.lechandonga.deploymentpipelineorchestratorjava.gate;

import com.github.lechandonga.deploymentpipelineorchestratorjava.domain.TargetResult;

import java.util.List;

/**
 * 健康门禁判定结果及依据。
 */
public record GateResult(GateVerdict verdict, List<TargetResult> targets, String detail) {
}
