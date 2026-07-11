package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class IntroRationaleDtoTest {
    private static final int PERSON_A_ID = 41;
    private static final int PERSON_B_ID = 73;
    private static final String GENERATED_AT = "2026-07-09T18:30:00Z";

    @Test
    void of_createsAvailableRationale() {
        IntroRationaleDto result = IntroRationaleDto.of(
                PERSON_A_ID,
                PERSON_B_ID,
                "Alice and Bob share three trusted connections.",
                GENERATED_AT,
                2);

        assertEquals(PERSON_A_ID, result.getPersonAId());
        assertEquals(PERSON_B_ID, result.getPersonBId());
        assertTrue(result.isAvailable());
        assertEquals("Alice and Bob share three trusted connections.", result.getRationale());
        assertEquals(GENERATED_AT, result.getGeneratedAt());
        assertEquals(2, result.getWarnings());
        assertNull(result.getReason());
    }

    @Test
    void unavailable_acceptsSupportedReasons() {
        for (String reason : List.of("not_configured", "provider_error", "not_a_suggestion")) {
            IntroRationaleDto result = IntroRationaleDto.unavailable(PERSON_A_ID, PERSON_B_ID, reason);

            assertEquals(PERSON_A_ID, result.getPersonAId());
            assertEquals(PERSON_B_ID, result.getPersonBId());
            assertFalse(result.isAvailable());
            assertNull(result.getRationale());
            assertNull(result.getGeneratedAt());
            assertEquals(0, result.getWarnings());
            assertEquals(reason, result.getReason());
        }
    }

    @Test
    void unavailable_rejectsUnsupportedReason() {
        assertThrows(IllegalArgumentException.class,
                () -> IntroRationaleDto.unavailable(PERSON_A_ID, PERSON_B_ID, "unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> IntroRationaleDto.unavailable(PERSON_A_ID, PERSON_B_ID, null));
    }
}
