package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.WorkflowRunDetailDto;
import ooo.klae.connex.backend.dto.WorkflowRunPageDto;
import ooo.klae.connex.backend.services.WorkflowRunReadService;

/** HTTP read contract for merged canonical and retained legacy workflow run history. */
@RestController
@RequestMapping("/api/workflows/{workflowId}/runs")
@RequiredArgsConstructor
public class WorkflowRunController {

    private final WorkflowRunReadService runReadService;

    @GetMapping
    public WorkflowRunPageDto list(
            @PathVariable int workflowId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return runReadService.listRuns(workflowId, limit, cursor);
    }

    @GetMapping("/{runKey}")
    public WorkflowRunDetailDto get(
            @PathVariable int workflowId,
            @PathVariable String runKey) {
        return runReadService.getRun(workflowId, runKey);
    }
}
