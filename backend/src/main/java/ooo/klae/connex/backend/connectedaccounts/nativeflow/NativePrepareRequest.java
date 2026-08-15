package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Pairing credential and loopback callback supplied by the local helper. */
public record NativePrepareRequest(
    @NotBlank @Size(max = 128) String pairingCode,
    @NotBlank @Size(max = 255) String redirectUri
) {
}
