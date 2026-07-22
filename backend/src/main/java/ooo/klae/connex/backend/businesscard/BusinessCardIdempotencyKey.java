package ooo.klae.connex.backend.businesscard;

import java.util.UUID;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Canonical syntax boundary shared by pre-multipart admission and tenant import processing.
 */
public final class BusinessCardIdempotencyKey {
    private BusinessCardIdempotencyKey() {
    }

    /**
     * Validates and canonicalizes one caller-retained UUID.
     *
     * @param value raw Idempotency-Key header
     * @return lowercase canonical UUID
     */
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
