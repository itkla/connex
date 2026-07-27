package ooo.klae.connex.backend.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.observability.ReportedError.Source;

class RequestPathRedactorTest {

    @Test
    void masksTheSegmentAfterEveryTokenBearingClientRoute() {
        assertEquals("/invite/{token}", RequestPathRedactor.redact("/invite/aBc123defGhi456jklMno"));
        assertEquals("/invite-link/{token}", RequestPathRedactor.redact("/invite-link/short"));
        assertEquals("/unsubscribe/{token}", RequestPathRedactor.redact("/unsubscribe/short"));
        assertEquals("/ja/invite/{token}", RequestPathRedactor.redact("/ja/invite/short"));
    }

    @Test
    void masksTheSegmentAfterEveryTokenBearingServerRoute() {
        assertEquals("/api/invites/{token}", RequestPathRedactor.redact("/api/invites/short"));
        assertEquals("/api/invites/{token}/accept",
                RequestPathRedactor.redact("/api/invites/short/accept"));
        assertEquals("/api/invite-links/{token}/accept",
                RequestPathRedactor.redact("/api/invite-links/short/accept"));
        assertEquals("/api/delivery/unsubscribe/{token}",
                RequestPathRedactor.redact("/api/delivery/unsubscribe/deadbeef"));
        assertEquals("/api/attachments/content/{token}",
                RequestPathRedactor.redact("/api/attachments/content/opaque"));
        assertEquals("/api/companies/12/logo/{token}",
                RequestPathRedactor.redact("/api/companies/12/logo/opaque"));
        assertEquals("/api/people/12/profile-picture/{token}",
                RequestPathRedactor.redact("/api/people/12/profile-picture/opaque"));
    }

    @Test
    void masksGeneratedCredentialsOnRoutesTheAllowlistDoesNotKnow() {
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[] {
                    1, 35, 69, 103, -119, -85, -51, -17, 1, 35, 69, 103,
                    -119, -85, -51, -17, 1, 35, 69, 103, -119, -85, -51, -17 });

        assertEquals("/api/future/{token}", RequestPathRedactor.redact("/api/future/" + token));
    }

    @Test
    void masksRealInviteTokensWhateverRouteTheyAppearOn() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        String redacted = RequestPathRedactor.redact("/api/invites/" + token + "/accept");

        assertEquals("/api/invites/{token}/accept", redacted);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/nonexistent",
        "/api/companies/1234567890",
        "/docs/using-connex/notifications-and-mentions",
        "/docs/using-connex/connections-and-employment",
        "/docs/getting-started/add-your-first-company",
        "/records/companies/42/deals",
        "/api/attachments/by-url",
        "/api/business-cards/9f1d7c3e-4b21-4a0e-9c2f-6d5b8e7a1c04"
    })
    void keepsLegitimatePathsLegible(String path) {
        assertEquals(path, RequestPathRedactor.redact(path));
    }

    @Test
    void toleratesEmptyAndNullPaths() {
        assertNull(RequestPathRedactor.redact(null));
        assertEquals("", RequestPathRedactor.redact(""));
        assertEquals("/", RequestPathRedactor.redact("/"));
        assertEquals("/invite/{token}/", RequestPathRedactor.redact("/invite/short/"));
    }

    @Test
    void redactsAtTheReportBoundarySoNoReporterCanSeeARawToken() {
        ReportedError error = new ReportedError(
                Source.CLIENT, "id", 1, 2, "Render failed", "stack", "/invite/aBc123defGhi456jklMno");

        assertEquals("/invite/{token}", error.path());
    }
}
