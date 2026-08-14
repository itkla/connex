package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Binds invite acceptance to the exact purpose-bound flow rendered in its preview. */
@Data
@NoArgsConstructor
public class InviteFlowAcceptRequest {

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    private String flowId;
}
