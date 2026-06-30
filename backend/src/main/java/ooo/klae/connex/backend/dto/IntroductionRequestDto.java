package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body identifying the pair of contacts an action (record an intro, or dismiss a
 * suggestion) targets. The {@code note} is optional context kept on a recorded introduction and
 * ignored when dismissing.
 */
@Data
@NoArgsConstructor
public class IntroductionRequestDto {
    @NotNull
    private Integer personAId;
    @NotNull
    private Integer personBId;
    @Size(max = 500)
    private String note;
}
