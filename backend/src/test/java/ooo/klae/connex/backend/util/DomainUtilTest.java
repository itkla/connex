package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Domain canonicalization: ASCII domains are lowercased and unchanged, an
 * internationalized domain and an email on it canonicalize to the same punycode
 * value so an allowlist match holds regardless of the Unicode form, and garbage
 * is rejected at normalize time.
 */
class DomainUtilTest {

    @Test
    void asciiDomainsAreLowercasedAndStable() {
        assertEquals("acme.com", DomainUtil.normalize("Acme.com"));
        assertEquals("acme.com", DomainUtil.normalize("@ACME.com"));
        assertEquals("acme.com", DomainUtil.of("Alice@ACME.com"));
    }

    @Test
    void internationalizedDomainNormalizesToPunycode_andEmailMatches() {
        String punycode = DomainUtil.normalize("münchen.de");
        assertEquals("xn--mnchen-3ya.de", punycode);
        assertEquals(punycode, DomainUtil.of("bewerber@münchen.de"),
            "a Unicode email address must canonicalize to the stored punycode domain");
    }

    @Test
    void normalizeRejectsBlankAndNonDomain() {
        assertThrows(BadRequestException.class, () -> DomainUtil.normalize("  "));
        assertThrows(BadRequestException.class, () -> DomainUtil.normalize("notadomain"));
    }

    @Test
    void ofReturnsEmptyForNoAtSign() {
        assertEquals("", DomainUtil.of("no-at-sign"));
    }
}
