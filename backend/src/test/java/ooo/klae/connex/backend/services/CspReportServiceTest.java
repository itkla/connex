package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.CspViolationRecord;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;
import tools.jackson.databind.ObjectMapper;

class CspReportServiceTest {
    private static final ResolvedClientIp CLIENT = new ResolvedClientIp("198.51.100.7", false);

    private final CspReportRateLimiter rateLimiter = mock(CspReportRateLimiter.class);
    private final CspReportService service = new CspReportService(new ObjectMapper(), rateLimiter);

    private void allowThrottle() {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
    }

    @Test
    void sanitizesALegacyReportToHostsAndPathsOnly() {
        allowThrottle();

        List<CspViolationRecord> records = service.ingest("""
                {"csp-report":{"document-uri":"https://connex.example.com/dashboard?token=secret#x",
                "effective-directive":"img-src","blocked-uri":"https://cdn.example.invalid/logo.png?sig=abc",
                "source-file":"https://connex.example.com/_next/static/chunks/app.js",
                "disposition":"enforce","status-code":200,"line-number":42}}
                """, CLIENT);

        assertEquals(1, records.size());
        CspViolationRecord record = records.getFirst();
        assertEquals("enforce", record.disposition());
        assertEquals("img-src", record.directive());
        assertEquals("https://cdn.example.invalid", record.blockedHost());
        assertEquals("/dashboard", record.documentPath());
        assertEquals("https://connex.example.com", record.sourceHost());
        assertEquals(42, record.lineNumber());
        assertEquals(200, record.statusCode());
        assertEquals(CspReportService.LEGACY_FORMAT, record.format());
    }

    @Test
    void fallsBackToTheViolatedDirectiveAndReportsMissingNumbersAsMinusOne() {
        allowThrottle();

        List<CspViolationRecord> records = service.ingest(
                "{\"csp-report\":{\"violated-directive\":\"script-src 'self' 'nonce-abc'\","
                        + "\"blocked-uri\":\"inline\",\"document-uri\":\"https://connex.example.com/\"}}",
                CLIENT);

        CspViolationRecord record = records.getFirst();
        assertEquals("script-src", record.directive());
        assertEquals("inline", record.blockedHost());
        assertEquals("/", record.documentPath());
        assertEquals("unknown", record.disposition());
        assertEquals("unknown", record.sourceHost());
        assertEquals(-1, record.lineNumber());
        assertEquals(-1, record.statusCode());
    }

    @Test
    void readsTheReportingApiEnvelopeAndSkipsOtherReportTypes() {
        allowThrottle();

        List<CspViolationRecord> records = service.ingest("""
                [{"type":"csp-violation","url":"https://connex.example.com/library/files",
                  "body":{"effectiveDirective":"script-src-elem","blockedURL":"inline",
                          "disposition":"enforce","statusCode":200,"lineNumber":7}},
                 {"type":"deprecation","body":{"id":"legacy"}},
                 {"type":"csp-violation","body":{"effectiveDirective":"font-src","blockedURL":"data",
                          "documentURL":"https://connex.example.com/records/contacts?q=x",
                          "disposition":"report"}}]
                """, CLIENT);

        assertEquals(2, records.size());
        assertEquals("script-src-elem", records.get(0).directive());
        assertEquals("inline", records.get(0).blockedHost());
        assertEquals("/library/files", records.get(0).documentPath());
        assertEquals(CspReportService.REPORTING_API_FORMAT, records.get(0).format());
        assertEquals("data", records.get(1).blockedHost());
        assertEquals("/records/contacts", records.get(1).documentPath());
        assertEquals("report", records.get(1).disposition());
    }

    @Test
    void capsTheRecordsProcessedPerRequest() {
        allowThrottle();
        String entries = IntStream.range(0, 25)
                .mapToObj(index -> "{\"type\":\"csp-violation\",\"body\":{\"effectiveDirective\":\"img-src\","
                        + "\"blockedURL\":\"https://cdn.example.invalid/" + index + ".png\","
                        + "\"documentURL\":\"https://connex.example.com/dashboard\"}}")
                .collect(Collectors.joining(","));

        List<CspViolationRecord> records = service.ingest("[" + entries + "]", CLIENT);

        assertEquals(CspReportService.MAX_REPORTS_PER_REQUEST, records.size());
    }

    @Test
    void neverReadsFreeTextOrPolicyEchoFields() {
        allowThrottle();

        List<CspViolationRecord> records = service.ingest("""
                {"csp-report":{"document-uri":"https://connex.example.com/records/contacts/9",
                "effective-directive":"script-src-elem","blocked-uri":"inline",
                "script-sample":"const apiKey = 'sk-live-secret'",
                "referrer":"https://connex.example.com/records/contacts?q=secret",
                "original-policy":"default-src 'self'; script-src 'nonce-secret'"}}
                """, CLIENT);

        String rendered = records.getFirst().toString();
        assertTrue(rendered.contains("/records/contacts/9"), rendered);
        for (String secret : List.of("sk-live-secret", "q=secret", "nonce-secret", "original-policy")) {
            assertTrue(!rendered.contains(secret), "record leaked " + secret + ": " + rendered);
        }
    }

    @Test
    void stripsControlCharactersAndTruncatesOverlongValues() {
        allowThrottle();
        String directive = "img-src\\n injected=log-line";
        String path = "/" + "a".repeat(600);

        List<CspViolationRecord> records = service.ingest(
                "{\"csp-report\":{\"effective-directive\":\"" + directive + "\","
                        + "\"blocked-uri\":\"inline\",\"document-uri\":\"https://connex.example.com"
                        + path + "\"}}",
                CLIENT);

        CspViolationRecord record = records.getFirst();
        assertEquals("img-src", record.directive());
        assertTrue(record.documentPath().startsWith("/aaa"), record.documentPath());
        assertTrue(record.documentPath().length() <= 256, record.documentPath());
        assertTrue(record.documentPath().length() < path.length(), record.documentPath());
        assertTrue(record.documentPath().indexOf('\n') < 0);
    }

    @Test
    void classifiesUnknownBlockedValuesWithoutEchoingThem() {
        allowThrottle();

        List<CspViolationRecord> records = service.ingest(
                "{\"csp-report\":{\"effective-directive\":\"connect-src\","
                        + "\"blocked-uri\":\"chrome-extension://abcdefghijklmnop/inject.js\","
                        + "\"document-uri\":\"https://connex.example.com/dashboard\"}}",
                CLIENT);

        assertEquals("other", records.getFirst().blockedHost());
    }

    @Test
    void returnsNothingForUnparsableOrEmptyBodies() {
        allowThrottle();

        for (String body : List.of("not json", "[]", "{}", "\"text\"", "   ", "{\"csp-report\":[]}")) {
            assertEquals(List.of(), service.ingest(body, CLIENT), body);
        }
        assertEquals(List.of(), service.ingest(null, CLIENT));
    }

    @Test
    void dropsEveryReportOnceTheClientWindowIsExhausted() {
        when(rateLimiter.tryAcquire("198.51.100.7")).thenReturn(false);

        assertEquals(List.of(), service.ingest(
                "{\"csp-report\":{\"effective-directive\":\"img-src\",\"blocked-uri\":\"inline\"}}",
                CLIENT));
    }
}
