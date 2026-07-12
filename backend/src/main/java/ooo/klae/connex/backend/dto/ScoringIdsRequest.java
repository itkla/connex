package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Data;

/** Bounded record-id body for score requests too large for a safe query string. */
@Data
public class ScoringIdsRequest {
    @NotEmpty
    @Size(max = 2000)
    private List<@NotNull @Positive Integer> ids;
}
