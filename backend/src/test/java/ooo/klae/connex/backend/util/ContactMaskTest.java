package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContactMaskTest {

    @Test
    void masksLocalPartAndKeepsDomain() {
        assertEquals("m***@example.com", ContactMask.maskEmail("member@example.com"));
        assertEquals("j***@dest.test", ContactMask.maskEmail("jane.doe@dest.test"));
    }

    @Test
    void handlesBlankAndMalformedAddresses() {
        assertEquals("", ContactMask.maskEmail(null));
        assertEquals("", ContactMask.maskEmail("   "));
        assertEquals("***", ContactMask.maskEmail("not-an-email"));
        assertEquals("***", ContactMask.maskEmail("@missing-local.com"));
    }
}
