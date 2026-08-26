package ooo.klae.connex.backend.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;

class DocumentDeliveryIdempotencyKeyTest {
    @Test
    void canonicalizesAValidUuid() {
        assertEquals(
            "550e8400-e29b-41d4-a716-446655440000",
            DocumentDeliveryIdempotencyKey.canonicalize(
                "550E8400-E29B-41D4-A716-446655440000"));
    }

    @Test
    void rejectsMissingAndNonUuidKeys() {
        assertThrows(BadRequestException.class,
            () -> DocumentDeliveryIdempotencyKey.canonicalize(null));
        assertThrows(BadRequestException.class,
            () -> DocumentDeliveryIdempotencyKey.canonicalize("not-an-idempotency-key"));
    }
}
