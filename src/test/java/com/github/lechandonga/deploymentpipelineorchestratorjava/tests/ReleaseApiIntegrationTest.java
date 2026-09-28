package com.github.lechandonga.deploymentpipelineorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层集成测试：幂等重放 200、指纹冲突 409、终态可查、事件流可复现。
 */
@SpringBootTest(properties = "pipeline.timeout-scan-ms=600000")
@AutoConfigureMockMvc
class ReleaseApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createReplayAndConflictOverHttp() throws Exception {
        // JSON body 手工构造，避免 LinkedHashMap.toString 问题
        String firstBody = """
                {"releaseId":"rel-http","application":"app-demo","version":"v1.0.0",
                 "params":{"region":"cn-east-1"},
                 "canaryTargets":["node-1"],"fullTargets":["node-1","node-2"],
                 "healthTimeout":"PT2M"}
                """;

        MvcResult created = mockMvc.perform(post("/api/releases")
                        .header("Idempotency-Key", "key-http-1")
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn();
        log.info("[HTTP] 首次创建 201 body={}", created.getResponse().getContentAsString());

        MvcResult replay = mockMvc.perform(post("/api/releases")
                        .header("Idempotency-Key", "key-http-1")
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true))
                .andReturn();
        log.info("[HTTP] 同键重放 200 replay=true body={}", replay.getResponse().getContentAsString());

        String changed = firstBody.replace("v1.0.0", "v2.0.0");
        MvcResult conflict = mockMvc.perform(post("/api/releases")
                        .header("Idempotency-Key", "key-http-1")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andReturn();
        JsonNode errorNode = mapper.readTree(conflict.getResponse().getContentAsString());
        log.warn("[HTTP] 版本冲突 409 code={} basis={}",
                errorNode.get("code").asText(), errorNode.get("decisionBasis").asText());
        assertTrue(errorNode.get("decisionBasis").asText().contains("fingerprint"));

        // 推进到终态后查询，事件流可复现
        mockMvc.perform(post("/api/releases/rel-http/advance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANARY_STABLE"));
        mockMvc.perform(post("/api/releases/rel-http/advance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        MvcResult queried = mockMvc.perform(get("/api/releases/rel-http"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn();
        JsonNode view = mapper.readTree(queried.getResponse().getContentAsString());
        log.info("[HTTP] 终态查询事件数={} 最终判定={}",
                view.get("events").size(), view.get("decision").asText());
        assertTrue(view.get("events").size() >= 5, "事件流必须完整可复现");
    }

    @Test
    void unknownRelease_returns404() throws Exception {
        mockMvc.perform(get("/api/releases/no-such-release"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_FOUND"));
    }
}
