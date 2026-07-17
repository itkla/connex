package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body dismissing a warm path: with a {@code null} bridge it hides every path to the
 * target, with a bridge only that avenue.
 */
@Data
@NoArgsConstructor
public class WarmPathRequestDto {
    @NotNull
    private Integer targetPersonId;
    private Integer bridgePersonId;
}
