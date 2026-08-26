package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Single-use handoff credential and provider callback material supplied by the helper. */
public record NativeCompleteRequest(
    @NotBlank @Size(max = 128) String handoffTicket,
    @NotBlank @Size(max = 4096) String code,
    @NotBlank @Size(max = 256) String state
) {
}
