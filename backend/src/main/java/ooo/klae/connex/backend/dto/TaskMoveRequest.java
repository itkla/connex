package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to move a single task to a target status column and ordinal position on the Kanban board. */
@Data
@NoArgsConstructor
public class TaskMoveRequest {
    @NotNull
    @Pattern(regexp = "todo|in_progress|done", message = "Status must be todo, in_progress or done")
    private String status;

    @NotNull
    @PositiveOrZero
    private Integer position;
}
