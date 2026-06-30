package ooo.klae.connex.backend.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to move many deals to a single stage. Capped at 1000 ids per call. */
@Data
@NoArgsConstructor
public class BulkStageRequest {
    @NotEmpty
    @Size(max = 1000)
    private List<@Positive Integer> ids = new ArrayList<>();

    @NotNull
    @Positive
    private Integer stageId;
}
