package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.dto.WorkItemActionResponse;
import ooo.klae.connex.backend.dto.WorkItemDecisionRequest;
import ooo.klae.connex.backend.dto.WorkItemPageDto;
import ooo.klae.connex.backend.dto.WorkItemSummaryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.work.WorkItemService;

/** REST boundary for the active workspace's deterministic My Work projection. */
@RestController
@RequestMapping("/api/my-work")
@RequiredArgsConstructor
public class MyWorkController {
    private final WorkItemService workItemService;

    /** Returns one ranked page across the selected source providers. */
    @GetMapping
    public WorkItemPageDto get(
            @RequestParam(required = false) List<String> source,
            @RequestParam(required = false) List<String> urgency,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return workItemService.getPage(source, urgency, page, size);
    }

    /** Returns count-only My Work metadata with the same failure isolation. */
    @GetMapping("/summary")
    public WorkItemSummaryDto summary() {
        return workItemService.summary();
    }

    /** Completes one assigned task against its strong source version. */
    @PostMapping("/tasks/{id}/complete")
    public WorkItemActionResponse completeTask(
            @PathVariable int id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return workItemService.completeTask(id, version(ifMatch));
    }

    /** Snoozes one current-recipient deal-close notification. */
    @PostMapping("/notifications/{id}/snooze")
    public WorkItemActionResponse snoozeNotification(
            @PathVariable int id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody SnoozeRequest request) {
        return workItemService.snoozeNotification(id, request, version(ifMatch));
    }

    /** Dismisses one current-recipient deal-close notification. */
    @PostMapping("/notifications/{id}/dismiss")
    public WorkItemActionResponse dismissNotification(
            @PathVariable int id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return workItemService.dismissNotification(id, version(ifMatch));
    }

    /** Decides one exact actionable approval step against its chain-aware version. */
    @PostMapping("/document-approvals/{id}/decision")
    public WorkItemActionResponse decideApproval(
            @PathVariable int id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody WorkItemDecisionRequest request) {
        return workItemService.decideApproval(
            id, request.stepId(), request.decision(), request.comment(), version(ifMatch));
    }

    private static String version(String ifMatch) {
        String value = ifMatch == null ? "" : ifMatch.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.matches("[0-9a-f]{64}")) {
            throw new BadRequestException("If-Match must contain a current My Work version");
        }
        return value;
    }
}
