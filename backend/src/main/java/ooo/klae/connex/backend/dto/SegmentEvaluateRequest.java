package ooo.klae.connex.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to evaluate a {@link SegmentDefinition} for a record type. The response is the ids of
 * records matching the definition, scoped to the active workspace and the current user.
 */
@Data
@NoArgsConstructor
public class SegmentEvaluateRequest {

    @NotBlank
    @Size(max = 16)
    private String recordType;

    @NotNull
    @Valid
    private SegmentDefinition definition;
}
