package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A segment definition: a set of {@link SegmentCondition}s combined with {@code match}
 * ({@code "all"} = AND / intersection, {@code "any"} = OR / union). Evaluated to the ids of
 * matching records, scoped to the active workspace and the current user.
 */
@Data
@NoArgsConstructor
public class SegmentDefinition {

    @NotBlank
    @Size(max = 8)
    private String match;

    @NotNull
    @Valid
    @Size(max = 16)
    private List<SegmentCondition> conditions;
}
