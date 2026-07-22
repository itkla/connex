package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Selects an accessible saved view as the caller's default for a record type. */
@Data
@NoArgsConstructor
public class SavedViewDefaultRequest {
    @NotNull
    @Positive
    private Integer savedViewId;
}
