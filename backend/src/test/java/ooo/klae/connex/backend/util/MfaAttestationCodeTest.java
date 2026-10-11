package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MfaAttestationCodeTest {

    @Test
    void aGeneratedCodeIsSixteenCrockfordCharactersInFourGroups() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = MfaAttestationCode.generate();
            assertTrue(code.matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}"), code);
            assertEquals(code.replace("-", ""), MfaAttestationCode.normalize(code));
            seen.add(code);
        }
        assertEquals(200, seen.size());
    }

    @Test
    void typedInputIsReadCaseInsensitivelyWithCrockfordAliases() {
        assertEquals("0123456789ABCDEF", MfaAttestationCode.normalize("o123 4567-89ab-cdef"));
        assertEquals("1111222233334444", MfaAttestationCode.normalize("ILil-2222-3333-4444"));
    }

    @Test
    void malformedInputIsNotACode() {
        assertNull(MfaAttestationCode.normalize(null));
        assertNull(MfaAttestationCode.normalize(""));
        assertNull(MfaAttestationCode.normalize("0123-4567-89AB-CDE"));
        assertNull(MfaAttestationCode.normalize("0123-4567-89AB-CDEF-0"));
        assertNull(MfaAttestationCode.normalize("0123-4567-89AB-CDEU"));
        assertNull(MfaAttestationCode.normalize("0123-4567-89AB-CDE!"));
    }

    @Test
    void theDigestIsBoundToItsGrantee() {
        String code = "0123456789ABCDEF";

        String first = MfaAttestationCode.digest(7, code);

        assertTrue(first.matches("[0-9a-f]{64}"));
        assertEquals(first, MfaAttestationCode.digest(7, MfaAttestationCode.normalize("0123-4567-89ab-cdef")));
        assertNotEquals(first, MfaAttestationCode.digest(8, code));
    }
}
