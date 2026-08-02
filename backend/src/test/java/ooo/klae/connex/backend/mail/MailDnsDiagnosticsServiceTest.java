package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import javax.naming.NamingException;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.MailDiagnosticTestDto.Dns;

class MailDnsDiagnosticsServiceTest {

    @Test
    void mapsSpfAndDmarcIndependentlyAndNeverReturnsRawTxt() throws Exception {
        DnsTxtResolver resolver = mock(DnsTxtResolver.class);
        when(resolver.resolveTxt("example.com"))
                .thenReturn(List.of("google-site-verification=credential", "v=spf1 include:mail.example ~all"));
        when(resolver.resolveTxt("_dmarc.example.com"))
                .thenReturn(List.of("v=DMARC1; p=reject; rua=mailto:secret@example.com"));

        Dns dns = new MailDnsDiagnosticsService(resolver).diagnose("Sender@Example.com");

        assertEquals("example.com", dns.domain());
        assertEquals("present", dns.spf().status());
        assertEquals("present", dns.dmarc().status());
        assertEquals("not_configured", dns.dkim().status());
        assertFalse(dns.toString().contains("credential"));
        assertFalse(dns.toString().contains("mailto"));
    }

    @Test
    void eachLookupFailureMapsOnlyThatRecordToUnknown() throws Exception {
        DnsTxtResolver resolver = mock(DnsTxtResolver.class);
        when(resolver.resolveTxt("example.com")).thenThrow(new NamingException("timeout"));
        when(resolver.resolveTxt("_dmarc.example.com")).thenReturn(List.of());

        Dns dns = new MailDnsDiagnosticsService(resolver).diagnose("sender@example.com");

        assertEquals("unknown", dns.spf().status());
        assertEquals("unknown", dns.dmarc().status());
        assertEquals("not_configured", dns.dkim().status());
    }

    @Test
    void dmarcFailureDoesNotAlterSuccessfulSpfStatus() throws Exception {
        DnsTxtResolver resolver = mock(DnsTxtResolver.class);
        when(resolver.resolveTxt("example.com")).thenReturn(List.of("v=spf1 -all"));
        when(resolver.resolveTxt("_dmarc.example.com"))
                .thenThrow(new NamingException("timeout"));

        Dns dns = new MailDnsDiagnosticsService(resolver).diagnose("sender@example.com");

        assertEquals("present", dns.spf().status());
        assertEquals("unknown", dns.dmarc().status());
        assertEquals("not_configured", dns.dkim().status());
    }

    @Test
    void malformedSenderProducesUnknownAdviceWithoutLookup() {
        Dns dns = new MailDnsDiagnosticsService(mock(DnsTxtResolver.class))
                .diagnose("https://user:password@example.com/path");

        assertEquals("unknown", dns.spf().status());
        assertEquals("unknown", dns.dmarc().status());
        assertEquals("not_configured", dns.dkim().status());
    }

    @Test
    void versionPrefixesRequireACompleteSpfOrDmarcToken() throws Exception {
        DnsTxtResolver resolver = mock(DnsTxtResolver.class);
        when(resolver.resolveTxt("example.com"))
                .thenReturn(List.of("v=spf10 -all", "v=spf1evil"));
        when(resolver.resolveTxt("_dmarc.example.com"))
                .thenReturn(List.of("v=DMARC10; p=reject", "v=DMARC1evil"));

        Dns dns = new MailDnsDiagnosticsService(resolver).diagnose("sender@example.com");

        assertEquals("unknown", dns.spf().status());
        assertEquals("unknown", dns.dmarc().status());
    }
}
