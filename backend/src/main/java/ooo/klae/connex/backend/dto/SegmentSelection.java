package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One selected smart-segment predicate. {@code key} identifies the predicate from the
 * catalog; {@code days} is the optional window used by time-based predicates (e.g. no-activity).
 */
@Data
@NoArgsConstructor
public class SegmentSelection {

    @NotBlank
    @Size(max = 64)
    private String key;

    private Integer days;
}
