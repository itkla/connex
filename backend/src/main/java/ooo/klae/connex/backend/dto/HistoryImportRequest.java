package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bounded CSV interaction-history request shared by preview and commit.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoryImportRequest {

    @NotNull
    @Size(max = 5000)
    private List<@NotNull Map<String, String>> rows;

    @NotEmpty
    @Valid
    @Size(max = 64)
    private List<HistoryImportColumnMapping> mapping;

    @Size(max = 5000)
    private Map<@PositiveOrZero Integer, @Positive Integer> links;

    @Pattern(regexp = "^[0-9a-f]{64}$")
    private String duplicateReviewProof;
}
