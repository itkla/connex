package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Validated filters and pagination for duplicate-family review items. */
@Data
@NoArgsConstructor
public class DuplicateReviewQuery {

    @Pattern(regexp = "person|company")
    private String recordType;

    @Pattern(regexp = "email|phone|domain|external_id")
    private String kind;

    @Pattern(regexp = "open|dismissed")
    private String state = "open";

    @Min(1)
    @Max(1_000_000)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int size = 50;
}
