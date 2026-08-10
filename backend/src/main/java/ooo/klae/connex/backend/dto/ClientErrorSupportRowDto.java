package ooo.klae.connex.backend.dto;

import java.time.Instant;

/**
 * One client-error metadata row projected to the closed set of fields a support bundle may
 * disclose.
 *
 * @param id the metadata row id
 * @param workspaceId the workspace in which the client reported the error
 * @param untrustedClientAssertedCorrelationHmac the disclosure-domain HMAC of the untrusted
 *        client assertion; useful only as a lookup aid
 * @param pagePath the closed-vocabulary route template
 * @param reportedAt when the client reported the error
 */
public record ClientErrorSupportRowDto(
        Long id,
        Integer workspaceId,
        String untrustedClientAssertedCorrelationHmac,
        String pagePath,
        Instant reportedAt) {
}
