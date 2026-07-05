package ooo.klae.connex.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dry-run payload for a rule's WHEN condition: evaluate {@code condition} over the active workspace's
 * {@code recordType} records without creating or firing a rule, so an author can see the reach before
 * enabling automation. Only the segment-backed record types ({@code company}, {@code person},
 * {@code deal}) are previewable.
 */
@Data
@NoArgsConstructor
public class RulePreviewRequest {

    @NotBlank
    @Size(max = 16)
    private String recordType;

    @NotNull
    @Valid
    private SegmentDefinition condition;
}
