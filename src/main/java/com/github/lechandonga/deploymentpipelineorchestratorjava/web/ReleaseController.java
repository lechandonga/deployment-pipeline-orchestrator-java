package com.github.lechandonga.deploymentpipelineorchestratorjava.web;

import com.github.lechandonga.deploymentpipelineorchestratorjava.dto.CreateReleaseRequest;
import com.github.lechandonga.deploymentpipelineorchestratorjava.dto.ErrorResponse;
import com.github.lechandonga.deploymentpipelineorchestratorjava.dto.ReleaseView;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.ReleaseException;
import com.github.lechandonga.deploymentpipelineorchestratorjava.service.ReleaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 发布单 REST 入口。所有变更操作要求 Idempotency-Key 头。 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private static final long DEFAULT_STAGE_TIMEOUT_MS = 5 * 60_000L;

    private final ReleaseService service;

    public ReleaseController(ReleaseService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ReleaseView> create(
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateReleaseRequest req) {
        long timeout = req.stageTimeoutMillis() != null
                ? req.stageTimeoutMillis() : DEFAULT_STAGE_TIMEOUT_MS;
        ReleaseView view = service.create(key, req.version(),
                req.canaryTargets() != null ? req.canaryTargets() : List.of(),
                req.fullTargets() != null ? req.fullTargets() : List.of(),
                timeout);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    @PostMapping("/{id}/advance")
    public ReleaseView advance(@PathVariable String id,
                               @RequestHeader("Idempotency-Key") String key) {
        return service.advance(id, key);
    }

    @PostMapping("/{id}/rollback")
    public ReleaseView rollback(@PathVariable String id,
                                @RequestHeader("Idempotency-Key") String key) {
        return service.rollback(id, key);
    }

    @PostMapping("/{id}/abort")
    public ReleaseView abort(@PathVariable String id,
                             @RequestHeader("Idempotency-Key") String key) {
        return service.abort(id, key);
    }

    @GetMapping("/{id}")
    public ReleaseView get(@PathVariable String id) {
        return service.get(id);
    }

    @ExceptionHandler(ReleaseException.class)
    public ResponseEntity<ErrorResponse> onReleaseException(ReleaseException e) {
        HttpStatus status = switch (e.getCode()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "IDEMPOTENCY_CONFLICT" -> HttpStatus.CONFLICT;
            case "TERMINAL_STATE", "TARGET_FAILED", "HEALTH_GATE_UNMET" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(e.getCode(), e.getMessage()));
    }
}
