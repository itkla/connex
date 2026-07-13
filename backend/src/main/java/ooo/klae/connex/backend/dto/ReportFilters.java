package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Workspace-scoped filters shared by every widget in a report definition.
 * @param pipelineIds pipeline ids
 * @param ownerIds workspace member ids
 * @param statuses entity status keys
 * @param tagIds workspace tag ids
 * @param warmthBands relationship warmth bands
 */
public record ReportFilters(
        @Size(max = 50) List<@Positive Integer> pipelineIds,
        @Size(max = 50) List<@Positive Integer> ownerIds,
        @Size(max = 16) List<@Size(max = 32) String> statuses,
        @Size(max = 50) List<@Positive Integer> tagIds,
        @Size(max = 4) List<@Size(max = 16) String> warmthBands) {
}
