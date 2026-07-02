package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to change only a task's due date, as a {@code YYYY-MM-DD} calendar day. */
@Data
@NoArgsConstructor
public class TaskRescheduleRequest {
    @NotNull
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Due date must be a YYYY-MM-DD calendar date")
    private String dueDate;
}
