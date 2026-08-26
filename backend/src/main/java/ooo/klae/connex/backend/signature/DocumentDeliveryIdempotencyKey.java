package ooo.klae.connex.backend.signature;

import java.util.UUID;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Canonical syntax boundary for document-delivery caller-retained request keys. */
public final class DocumentDeliveryIdempotencyKey {
    private DocumentDeliveryIdempotencyKey() {
    }

    /** Validates and canonicalizes one UUID supplied through {@code Idempotency-Key}. */
    public static String canonicalize(String value) {
        if (value == null || value.length() != 36) {
            throw invalid();
        }
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equalsIgnoreCase(value)) {
                throw invalid();
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static BadRequestException invalid() {
        return new BadRequestException("Idempotency-Key must be a UUID");
    }
}
