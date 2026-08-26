package ooo.klae.connex.backend.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.observability.ReportedError.Source;

class RequestPathRedactorTest {

    @ParameterizedTest
    @CsvSource({
        "/dashboard, /dashboard",
        "/records/contacts/42, /records/contacts/{id}",
        "/records/contacts/private@example.com, /records/contacts/{id}",
        "/overview/reports/9/snapshots/12, /overview/reports/{id}/snapshots/{snapshotId}",
        "/insights/reports/9/snapshots/12, /insights/reports/{id}/snapshots/{snapshotId}",
        "/intelligence/radar, /intelligence/radar",
        "/settings/workspace/people, /settings/workspace/people",
        "/docs/using-connex/notifications-and-mentions, /docs/{...slug}",
        "/invite/private-token, /invite/{token}",
        "/ja/invite/private-token, /{locale}/invite/{token}"
    })
    void mapsKnownRoutesToServerOwnedTemplates(String path, String expected) {
        assertEquals(expected, RequestPathRedactor.redact(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "/records/private@example.com",
        "/records/contacts/42/private@example.com",
        "/api/nonexistent/private@example.com",
        "/future/privateCRMRecordEncoded123",
        "/dashboard/"
    })
    void mapsEveryUnrecognizedShapeToOneUnknownValue(String path) {
        assertEquals(RequestPathRedactor.UNKNOWN_ROUTE, RequestPathRedactor.redact(path));
    }

    @Test
    void removesQueryAndFragmentBeforeMatchingWithoutPreservingTheirContent() {
        assertEquals(
            "/records/contacts/{id}",
            RequestPathRedactor.redact(
                "/records/contacts/42?email=private@example.com#privateCRMRecord"));
    }

    @Test
    void safelyReacceptsAnAlreadyStoredTemplateAtReadTime() {
        assertEquals(
            "/records/contacts/{id}",
            RequestPathRedactor.redact("/records/contacts/{id}"));
        assertEquals(
            "/{locale}/records/contacts/{id}",
            RequestPathRedactor.redact("/{locale}/records/contacts/{id}"));
    }

    @Test
    void toleratesNullWithoutInventingAReportedRoute() {
        assertNull(RequestPathRedactor.redact(null));
    }

    @Test
    void appliesTheClosedVocabularyAtTheReporterBoundary() {
        ReportedError error = new ReportedError(
            Source.CLIENT,
            "id",
            1,
            2,
            "Render failed",
            "stack",
            "/records/private@example.com");

        assertEquals(RequestPathRedactor.UNKNOWN_ROUTE, error.path());
    }
}
