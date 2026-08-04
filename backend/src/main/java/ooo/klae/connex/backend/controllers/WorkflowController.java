package ooo.klae.connex.backend.controllers;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
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

import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDto;
import ooo.klae.connex.backend.dto.WorkflowDraftRequest;
import ooo.klae.connex.backend.dto.WorkflowLegacyRuleResolutionDto;
import ooo.klae.connex.backend.dto.WorkflowListItemDto;
import ooo.klae.connex.backend.dto.WorkflowPublishRequest;
import ooo.klae.connex.backend.dto.WorkflowRuntimeOwnerRequest;
import ooo.klae.connex.backend.dto.WorkflowSimulateRequest;
import ooo.klae.connex.backend.dto.WorkflowSimulationDto;
import ooo.klae.connex.backend.dto.WorkflowValidationDto;
import ooo.klae.connex.backend.dto.WorkflowVersionDto;
import ooo.klae.connex.backend.services.WorkflowRuntimeOwnershipService;
import ooo.klae.connex.backend.services.WorkflowService;
import ooo.klae.connex.backend.services.WorkflowSimulationService;

/** HTTP lifecycle contract for workspace-scoped versioned workflows. */
@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowRuntimeOwnershipService runtimeOwnershipService;
    private final WorkflowSimulationService simulationService;

    @GetMapping
    public List<WorkflowListItemDto> list(
            @RequestParam(defaultValue = "false") boolean archived) {
        return workflowService.list(archived);
    }

    @PostMapping
    public ResponseEntity<WorkflowDto> create(
            @Valid @RequestBody WorkflowCreateRequest request) {
        WorkflowDto workflow = workflowService.create(request);
        return ResponseEntity.created(URI.create("/api/workflows/" + workflow.id())).body(workflow);
    }

    @GetMapping("/{id}")
    public WorkflowDto get(@PathVariable int id) {
        return workflowService.getById(id);
    }

    @PutMapping("/{id}/draft")
    public WorkflowDto saveDraft(
            @PathVariable int id,
            @Valid @RequestBody WorkflowDraftRequest request) {
        return workflowService.saveDraft(id, request);
    }

    @PostMapping("/{id}/validate")
    public WorkflowValidationDto validate(@PathVariable int id) {
        return workflowService.validate(id);
    }

    @PostMapping("/{id}/simulate")
    public WorkflowSimulationDto simulate(
            @PathVariable int id,
            @Valid @RequestBody WorkflowSimulateRequest request) {
        return simulationService.simulate(id, request);
    }

    @GetMapping("/legacy-rules/{legacyRuleId}")
    public WorkflowLegacyRuleResolutionDto resolveLegacyRule(
            @PathVariable int legacyRuleId) {
        return workflowService.resolveLegacyRule(legacyRuleId);
    }

    @PostMapping("/{id}/publish")
    public WorkflowDto publish(
            @PathVariable int id,
            @Valid @RequestBody WorkflowPublishRequest request) {
        return workflowService.publish(id, request);
    }

    @PostMapping("/{id}/enable")
    public WorkflowDto enable(@PathVariable int id) {
        return workflowService.enable(id);
    }

    @PostMapping("/{id}/disable")
    public WorkflowDto disable(@PathVariable int id) {
        return workflowService.disable(id);
    }

    @PostMapping("/{id}/archive")
    public WorkflowDto archive(@PathVariable int id) {
        return workflowService.archive(id);
    }

    @PostMapping("/{id}/restore")
    public WorkflowDto restore(@PathVariable int id) {
        return workflowService.restore(id);
    }

    @PostMapping("/{id}/runtime/canonical")
    public WorkflowDto cutOverToCanonical(
            @PathVariable int id,
            @Valid @RequestBody WorkflowRuntimeOwnerRequest request) {
        return runtimeOwnershipService.cutOverToCanonical(
            id, request.expectedActiveVersionId());
    }

    @PostMapping("/{id}/runtime/legacy")
    public WorkflowDto rollBackToLegacy(
            @PathVariable int id,
            @Valid @RequestBody WorkflowRuntimeOwnerRequest request) {
        return runtimeOwnershipService.rollBackToLegacy(
            id, request.expectedActiveVersionId());
    }

    @GetMapping("/{id}/versions")
    public List<WorkflowVersionDto> versions(@PathVariable int id) {
        return workflowService.versions(id);
    }
}
