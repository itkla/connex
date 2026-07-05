package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A segment definition: a set of {@link SegmentCondition}s and/or nested {@code groups}, combined
 * with {@code match} ({@code "all"} = AND / intersection, {@code "any"} = OR / union). Nested groups
 * let authors mix AND and OR (e.g. {@code A AND (B OR C)}); each group is evaluated recursively and
 * folded in like a condition. {@code negate} complements a group within the workspace's records.
 * Evaluated to the ids of matching records, scoped to the active workspace and the current user.
 */
@Data
@NoArgsConstructor
public class SegmentDefinition {

    @NotBlank
    @Size(max = 8)
    private String match;

    @Valid
    @Size(max = 16)
    private List<SegmentCondition> conditions;

    @Valid
    @Size(max = 8)
    private List<SegmentDefinition> groups;

    private boolean negate;
}
