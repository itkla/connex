package ooo.klae.connex.backend.dto;

import java.time.Instant;

/**
 * One client-error metadata row projected to the closed set of fields a support bundle may
 * disclose.
 *
 * @param id the metadata row id
 * @param workspaceId the workspace in which the client reported the error
 * @param correlationId the correlation identifier shown to the client
 * @param digest the optional framework digest
 * @param pagePath the query-free, credential-redacted page path
 * @param reportedAt when the client reported the error
 */
public record ClientErrorSupportRowDto(
        Long id,
        Integer workspaceId,
        String correlationId,
        String digest,
        String pagePath,
        Instant reportedAt) {
}
