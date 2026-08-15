package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bounded CSV product-catalog request shared by preview and commit. {@code onConflict} decides what
 * happens to a row whose SKU already exists and defaults to {@code skip}; {@code rowDecisions}
 * overrides that policy for one source row and is bound into the review proof.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductImportRequest {

    @NotNull
    @Size(max = 5000)
    private List<@NotNull Map<String, String>> rows;

    @NotEmpty
    @Valid
    @Size(max = 64)
    private List<ProductImportColumnMapping> mapping;

    @Pattern(regexp = "^(overwrite|skip)$")
    private String onConflict;

    @Size(max = 5000)
    private Map<@PositiveOrZero Integer, @Pattern(regexp = "^(create|update|skip)$") String> rowDecisions;

    @Pattern(regexp = "^[0-9a-f]{64}$")
    private String duplicateReviewProof;
}
