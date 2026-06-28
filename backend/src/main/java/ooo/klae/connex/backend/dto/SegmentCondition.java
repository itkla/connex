package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One condition in a {@link SegmentDefinition}. {@code type} is {@code "predicate"} (a graph-aware
 * predicate identified by {@code key}, optionally parameterized by {@code days}) or {@code "field"}
 * (a record field compared with {@code op} against {@code value}). {@code negate} inverts the match.
 * This shared shape is the substrate a future rule engine can reuse as its {@code WHEN}.
 */
@Data
@NoArgsConstructor
public class SegmentCondition {

    @NotBlank
    @Size(max = 16)
    private String type;

    @Size(max = 64)
    private String key;

    private Integer days;

    @Size(max = 64)
    private String field;

    @Size(max = 16)
    private String op;

    @Size(max = 255)
    private String value;

    private boolean negate;
}
