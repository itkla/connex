package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to evaluate a set of smart-segment predicates for a record type. The predicates
 * are combined with AND; the response is the ids of records matching all of them, scoped
 * to the active workspace and the current user.
 */
@Data
@NoArgsConstructor
public class SegmentEvaluateRequest {

    @NotBlank
    @Size(max = 16)
    private String recordType;

    @NotNull
    @Valid
    @Size(max = 16)
    private List<SegmentSelection> segments;
}
