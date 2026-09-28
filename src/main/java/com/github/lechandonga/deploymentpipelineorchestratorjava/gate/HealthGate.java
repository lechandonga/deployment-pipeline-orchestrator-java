package com.github.lechandonga.deploymentpipelineorchestratorjava.gate;

import java.util.List;

/**
 * 健康门禁：判定当前已部署目标集合是否达到放行条件。
 */
public interface HealthGate {

    /**
     * @return 三态判定（放行/等待/不健康）与每个目标的依据
     */
    GateResult evaluate(String releaseId, String stage, List<String> targets);
}
