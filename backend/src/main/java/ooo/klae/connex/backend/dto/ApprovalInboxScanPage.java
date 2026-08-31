package ooo.klae.connex.backend.dto;

import java.util.List;

/** One raw-bounded, eligibility-filtered approval inbox scan page. */
public record ApprovalInboxScanPage(
    List<ApprovalInboxItemDto> items,
    ApprovalInboxCursor nextCursor,
    int rawRowCount,
    boolean exhausted
) {
}
