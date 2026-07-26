package ooo.klae.connex.backend.services;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Canonical identifier kinds supported by {@link MatchingService}.
 */
@Getter
@RequiredArgsConstructor
public enum IdentityKind {
    EMAIL("email"),
    PHONE("phone"),
    DOMAIN("domain"),
    EXTERNAL_ID("external_id");

    private final String databaseValue;
}
