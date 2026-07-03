package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * A user-facing view of an enrolled passkey for the security settings list. Carries no key
 * material or attestation data — only the base64url credential id (for rename/delete) and
 * display metadata.
 */
public record PasskeyDto(
    String credentialId,
    String label,
    List<String> transports,
    Instant createdAt,
    Instant lastUsedAt
) {}
