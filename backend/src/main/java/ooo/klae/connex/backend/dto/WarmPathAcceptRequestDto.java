package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body accepting a warm path: the target being reached, the bridge who will make the
 * introduction, and optionally localized follow-up task text (the server composes a mention-token
 * default when absent). The cap leaves room for two maximum-length contact names inside the
 * composed mention tokens.
 */
@Data
@NoArgsConstructor
public class WarmPathAcceptRequestDto {
    @NotNull
    private Integer targetPersonId;
    @NotNull
    private Integer bridgePersonId;
    @Size(max = 1000)
    private String taskDescription;
}
