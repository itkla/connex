package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to move a single deal to a target stage and ordinal position on the Kanban board. */
@Data
@NoArgsConstructor
public class DealMoveRequest {
    @NotNull
    @Positive
    private Integer stageId;

    @NotNull
    @PositiveOrZero
    private Integer position;
}
