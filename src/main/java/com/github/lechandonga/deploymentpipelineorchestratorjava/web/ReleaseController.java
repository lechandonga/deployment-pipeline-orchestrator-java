package com.github.lechandonga.deploymentpipelineorchestratorjava.web;

import com.github.lechandonga.deploymentpipelineorchestratorjava.service.AbortCommand;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.AdvanceCommand;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.CreateReleaseCommand;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.PipelineOrchestratorService;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.ReleaseQueryService;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.ReleaseView;
import com.github.lechandonga.deploymentpipelineorchestratorjava.web.dto.AdvanceRequest;
import com.github.lechandonga.deploymentpipelineorchestratorjava.web.dto.CreateReleaseRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部署流水线 REST 接口。
 * 幂等键通过 {@code Idempotency-Key} 请求头传递（也可在 body 中携带）。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private static final Logger log = LoggerFactory.getLogger(ReleaseController.class);

    private final PipelineOrchestratorService orchestrator;
    private final ReleaseQueryService queryService;

    public ReleaseController(PipelineOrchestratorService orchestrator,
                             ReleaseQueryService queryService) {
        this.orchestrator = orchestrator;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<ReleaseView> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
            @RequestBody CreateReleaseRequest request) {
        String key = headerKey;
        log.info("api.create releaseId={} version={} idempotencyKey={}",
                request.releaseId(), request.version(), key);
        CreateReleaseCommand command = new CreateReleaseCommand(
                request.releaseId(),
                request.application(),
                request.version(),
                request.params(),
                request.canaryTargets(),
                request.fullTargets(),
                request.healthTimeout(),
                key);
        ReleaseView view = orchestrator.create(command);
        return ResponseEntity.status(view.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    @PostMapping("/{releaseId}/advance")
    public ResponseEntity<ReleaseView> advance(
            @PathVariable String releaseId,
            @RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
            @RequestBody(required = false) AdvanceRequest request) {
        String key = headerKey != null ? headerKey : (request != null ? request.idempotencyKey() : null);
        log.info("api.advance releaseId={} idempotencyKey={}", releaseId, key);
        ReleaseView view = orchestrator.advance(releaseId, new AdvanceCommand(key));
        return ResponseEntity.ok(view);
    }

    @PostMapping("/{releaseId}/abort")
    public ResponseEntity<ReleaseView> abort(
            @PathVariable String releaseId,
            @RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
            @RequestBody(required = false) AdvanceRequest request) {
        String key = headerKey != null ? headerKey : (request != null ? request.idempotencyKey() : null);
        log.info("api.abort releaseId={} idempotencyKey={}", releaseId, key);
        ReleaseView view = orchestrator.abort(releaseId, new AbortCommand(key));
        return ResponseEntity.ok(view);
    }

    @PostMapping("/{releaseId}/rollback")
    public ResponseEntity<ReleaseView> rollback(@PathVariable String releaseId) {
        log.info("api.rollback releaseId={}", releaseId);
        return ResponseEntity.ok(orchestrator.rollback(releaseId));
    }

    @GetMapping("/{releaseId}")
    public ReleaseView get(@PathVariable String releaseId) {
        return queryService.get(releaseId);
    }

    @GetMapping
    public List<ReleaseView> list() {
        return queryService.list();
    }
}
