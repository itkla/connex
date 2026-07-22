package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Optional pin-position replacement for an accessible saved view. */
@Data
@NoArgsConstructor
public class SavedViewPinRequest {
    @PositiveOrZero
    private Integer position;
}
