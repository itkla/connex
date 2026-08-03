package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.WorkflowInterventionDto;
import ooo.klae.connex.backend.dto.WorkflowInterventionOwnerRequest;
import ooo.klae.connex.backend.dto.WorkflowInterventionResolveRequest;
import ooo.klae.connex.backend.dto.WorkflowOperationsDetailDto;
import ooo.klae.connex.backend.dto.WorkflowOperationsRunPageDto;
import ooo.klae.connex.backend.dto.WorkflowOperationsSummaryDto;
import ooo.klae.connex.backend.services.WorkflowInterventionService;
import ooo.klae.connex.backend.services.WorkflowOperationsService;

/** Workflow operations center endpoints. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorkflowOperationsController {

    private final WorkflowOperationsService operationsService;
    private final WorkflowInterventionService interventionService;

    @GetMapping("/workflow-operations/summary")
    public WorkflowOperationsSummaryDto summary() {
        return operationsService.summary();
    }

    @GetMapping("/workflow-operations/runs")
    public WorkflowOperationsRunPageDto runs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String failureCategory,
            @RequestParam(required = false) Integer ownerId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return operationsService.runs(
            status, failureCategory, ownerId, limit, cursor);
    }

    @GetMapping("/workflows/{workflowId}/operations")
    public WorkflowOperationsDetailDto workflow(@PathVariable int workflowId) {
        return operationsService.workflow(workflowId);
    }

    @PutMapping("/workflow-interventions/{interventionId}/owner")
    public WorkflowInterventionDto updateOwner(
            @PathVariable long interventionId,
            @Valid @RequestBody WorkflowInterventionOwnerRequest request) {
        return interventionService.updateOwner(interventionId, request);
    }

    @PostMapping("/workflow-interventions/{interventionId}/resolve")
    public WorkflowInterventionDto resolve(
            @PathVariable long interventionId,
            @Valid @RequestBody WorkflowInterventionResolveRequest request) {
        return interventionService.resolve(interventionId, request);
    }
}
