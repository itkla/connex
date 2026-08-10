package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Validated client error-boundary report.
 *
 * @param digest optional client-asserted framework error digest used only in deployment-local logs
 * @param message client error summary
 * @param stack optional client stack trace
 * @param path optional caller-controlled browser pathname mapped to a server-owned route template
 */
public record ClientErrorRequest(
        @Size(max = 128) String digest,
        @NotBlank @Size(max = 1_000) String message,
        @Size(max = 8_000) String stack,
        @Size(max = 300) String path) {
}
