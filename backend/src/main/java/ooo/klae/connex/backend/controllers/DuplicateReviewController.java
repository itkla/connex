package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.DuplicateReviewDecisionRequest;
import ooo.klae.connex.backend.dto.DuplicateReviewItemDto;
import ooo.klae.connex.backend.dto.DuplicateReviewQuery;
import ooo.klae.connex.backend.dto.DuplicateReviewSummaryDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.services.DuplicateReviewService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** HTTP surface for the workspace duplicate-family review queue. */
@RestController
@RequestMapping("/api/duplicate-reviews")
@RequiredArgsConstructor
public class DuplicateReviewController {

    private final DuplicateReviewService duplicateReviewService;

    /**
     * Returns a filtered page of current review items.
     *
     * @param query filters and pagination
     * @return visible review items
     */
    @GetMapping
    @RequirePermission(Permission.REPORT_READ)
    public PageResponse<DuplicateReviewItemDto> list(
            @Valid @ModelAttribute DuplicateReviewQuery query) {
        return duplicateReviewService.list(query);
    }

    /**
     * Returns current open counts by record type.
     *
     * @return open item counts
     */
    @GetMapping("/summary")
    @RequirePermission(Permission.REPORT_READ)
    public DuplicateReviewSummaryDto summary() {
        return duplicateReviewService.summary();
    }

    /**
     * Dismisses one exact evidence-specific pair.
     *
     * @param request pair, evidence kind, fingerprint, and optional note
     * @return dismissed item
     */
    @PostMapping("/dismiss")
    @RequirePermission(Permission.REPORT_READ)
    public DuplicateReviewItemDto dismiss(
            @Valid @RequestBody DuplicateReviewDecisionRequest request) {
        return duplicateReviewService.dismiss(request);
    }

    /**
     * Reopens one exact evidence-specific pair.
     *
     * @param request pair, evidence kind, and fingerprint
     * @return open item
     */
    @PostMapping("/reopen")
    @RequirePermission(Permission.REPORT_READ)
    public DuplicateReviewItemDto reopen(
            @Valid @RequestBody DuplicateReviewDecisionRequest request) {
        return duplicateReviewService.reopen(request);
    }
}
