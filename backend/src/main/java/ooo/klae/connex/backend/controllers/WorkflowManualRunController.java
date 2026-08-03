package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.WorkflowInvocationResultDto;
import ooo.klae.connex.backend.dto.WorkflowManualConfirmRequest;
import ooo.klae.connex.backend.dto.WorkflowManualPreparationDto;
import ooo.klae.connex.backend.dto.WorkflowManualPrepareRequest;
import ooo.klae.connex.backend.services.WorkflowManualRunService;

/** Exact-scope canonical manual workflow invocation endpoints. */
@RestController
@RequestMapping("/api/workflows/{workflowId}/manual-runs")
@RequiredArgsConstructor
public class WorkflowManualRunController {

    private final WorkflowManualRunService manualRunService;

    @PostMapping("/prepare")
    public WorkflowManualPreparationDto prepare(
            @PathVariable int workflowId,
            @Valid @RequestBody WorkflowManualPrepareRequest request) {
        return manualRunService.prepare(workflowId, request);
    }

    @PostMapping
    public WorkflowInvocationResultDto confirm(
            @PathVariable int workflowId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WorkflowManualConfirmRequest request) {
        return manualRunService.confirm(workflowId, idempotencyKey, request);
    }

    @GetMapping("/{invocationId}")
    public WorkflowInvocationResultDto get(
            @PathVariable int workflowId,
            @PathVariable long invocationId) {
        return manualRunService.get(workflowId, invocationId);
    }

    @PostMapping("/{invocationId}/cancel")
    public WorkflowInvocationResultDto cancel(
            @PathVariable int workflowId,
            @PathVariable long invocationId) {
        return manualRunService.cancel(workflowId, invocationId);
    }
}
