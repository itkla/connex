package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body identifying the warm path an action targets. Dismissing with a {@code null}
 * bridge hides every path to the target; accepting requires a bridge and creates the follow-up
 * task, whose text may be supplied localized via {@code taskDescription} (the server composes a
 * default when absent).
 */
@Data
@NoArgsConstructor
public class WarmPathRequestDto {
    @NotNull
    private Integer targetPersonId;
    private Integer bridgePersonId;
    @Size(max = 500)
    private String taskDescription;
}
