package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.naming.NamingException;

import org.junit.jupiter.api.Test;

class JndiDnsTxtResolverTest {

    @Test
    void normalizesQuotedChunksWithoutLiveDns() throws Exception {
        assertEquals(
                "v=spf1 include:_spf.example.com ~all",
                JndiDnsTxtResolver.normalizeTxtValue(
                        "\"v=spf1 include:_spf.example.com \" \"~all\""));
        assertEquals(
                "v=DMARC1; p=none",
                JndiDnsTxtResolver.normalizeTxtValue("v=DMARC1; p=none"));
    }

    @Test
    void refusesMalformedQuotedResponsesWithoutLiveDns() {
        assertThrows(
                NamingException.class,
                () -> JndiDnsTxtResolver.normalizeTxtValue("\"v=spf1\" trailing"));
        assertThrows(
                NamingException.class,
                () -> JndiDnsTxtResolver.normalizeTxtValue("\"unterminated"));
        assertThrows(
                NamingException.class,
                () -> JndiDnsTxtResolver.normalizeTxtValue(new byte[] { 1, 2 }));
    }

    @Test
    void refusesJndiAndUrlSyntaxBeforeOpeningAResolver() {
        JndiDnsTxtResolver resolver = new JndiDnsTxtResolver();

        assertThrows(NamingException.class, () -> resolver.resolveTxt("dns:example.com"));
        assertThrows(NamingException.class, () -> resolver.resolveTxt("user@example.com"));
        assertThrows(NamingException.class, () -> resolver.resolveTxt("example.com/path"));
    }
}
