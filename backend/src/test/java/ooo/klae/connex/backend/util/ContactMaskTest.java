package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContactMaskTest {

    @Test
    void masksLocalPartAndKeepsDomain() {
        assertEquals("m***@example.com", ContactMask.maskEmail("member@example.com"));
        assertEquals("j***@dest.test", ContactMask.maskEmail("jane.doe@dest.test"));
        assertEquals("a***@example.com", ContactMask.maskEmail("  alice@example.com  "));
    }

    @Test
    void handlesBlankAndMalformedAddresses() {
        assertEquals("", ContactMask.maskEmail(null));
        assertEquals("", ContactMask.maskEmail("   "));
        assertEquals("***", ContactMask.maskEmail("not-an-email"));
        assertEquals("***", ContactMask.maskEmail("@missing-local.com"));
        assertEquals("***", ContactMask.maskEmail("missing-domain@"));
        assertEquals("***", ContactMask.maskEmail("alice@example.com,bob@example.net"));
        assertEquals("***", ContactMask.maskEmail("alice@example.com;bob@example.net"));
        assertEquals("***", ContactMask.maskEmail("Alice <alice@example.com>"));
        assertEquals("***", ContactMask.maskEmail("alice@example.com\nbob@example.net"));
        assertEquals("***", ContactMask.maskEmail("a@b@c.example"));
    }
}
