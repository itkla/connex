package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** User-confirmed canonical task fields and an optional warm-path bridge. */
public record RadarTaskRequestDto(
        @NotBlank @Size(max = 1000) String description,
        @Size(max = 10)
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Due date must use YYYY-MM-DD")
        String dueDate,
        Integer assignedToId,
        Integer personId,
        Integer dealId,
        Integer bridgePersonId) {
}
