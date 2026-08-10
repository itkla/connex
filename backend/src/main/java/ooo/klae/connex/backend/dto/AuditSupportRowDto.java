package ooo.klae.connex.backend.dto;

import java.time.Instant;

/**
 * One audit row projected to the closed set of fields a support bundle may disclose.
 *
 * <p>This exists so the excluded fields are never fetched at all. Selecting the full audit row and
 * dropping columns while formatting would be post-hoc scrubbing: the actor display name, target
 * label, summary, change and context bodies, IP address, user agent and session identifier would
 * be read into memory, and any later formatting change could leak them. The mapper selects only
 * these columns and performs no join to {@code app_user}.
 *
 * <p>Actors are identified by id alone. A bundle leaves the tenant, so employee display names must
 * not travel with it; the organization administrator resolves the id in their own admin UI.
 *
 * @param auditId    the audit row id
 * @param workspaceId the workspace the event belongs to, or null for organization-plane events
 * @param orgId      the organization the event belongs to
 * @param action     the audited action
 * @param entityType the record type the event concerns
 * @param entityId   the record id the event concerns
 * @param actorId    the acting account id
 * @param outcome    the recorded outcome
 * @param serverMintedRequestId the non-spoofable server-minted request identifier
 * @param untrustedClientAssertedCorrelationId the untrusted client-asserted correlation identifier
 * @param createdAt  when the event was recorded
 */
public record AuditSupportRowDto(
    Long auditId,
    Integer workspaceId,
    Integer orgId,
    String action,
    String entityType,
    Integer entityId,
    Integer actorId,
    String outcome,
    String serverMintedRequestId,
    String untrustedClientAssertedCorrelationId,
    Instant createdAt) {
}
