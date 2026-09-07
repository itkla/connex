package ooo.klae.connex.backend.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.FirewalledRequest;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.UriUtils;

class PublicApiPathsTest {

    @Test
    void rawQuerySeparatorIsRemovedBeforeDecodingButEncodedQuestionMarkIsPathData() {
        MockHttpServletRequest rawQuery = new MockHttpServletRequest("GET", "/api/v1");
        rawQuery.setRequestURI("/api/v1?foo=bar");
        MockHttpServletRequest encodedQuestionMark = request("/api/v1%3Ffoo");

        assertTrue(PublicApiPaths.isPublicRequest(rawQuery));
        assertFalse(PublicApiPaths.isPublicRequest(encodedQuestionMark));
    }

    @Test
    void classifierAgreesWithPublicSecurityMatcherForFirewallAcceptedUris() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        RequestMatcher matcher = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**");
        List<String> rawUris = List.of(
            "/api/v1",
            "/api/v1/",
            "/api/v1/me",
            "/api/v1?view=current",
            "/api/v1%3Ffoo",
            "/api/v1%3ffoo",
            "/api/v1%2Fme",
            "/api/v1;blocked/me",
            "/api/v10",
            "/api/v1x",
            "/api/tasks",
            "/api/v0/me");
        int accepted = 0;

        for (String rawUri : rawUris) {
            try {
                FirewalledRequest firewalledRequest = firewall.getFirewalledRequest(request(rawUri));
                accepted++;
                assertEquals(
                    matcher.matches(firewalledRequest),
                    PublicApiPaths.isPublicRequest(firewalledRequest),
                    rawUri);
            } catch (RequestRejectedException exception) {
                assertTrue(rawUri.contains("%2F") || rawUri.contains(";"), rawUri);
            }
        }

        assertTrue(accepted > 0);
    }

    private static MockHttpServletRequest request(String rawUri) {
        int queryStart = rawUri.indexOf('?');
        String path = queryStart < 0 ? rawUri : rawUri.substring(0, queryStart);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        try {
            request.setServletPath(UriUtils.decode(path, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            request.setServletPath(path);
        }
        if (queryStart >= 0) {
            request.setQueryString(rawUri.substring(queryStart + 1));
        }
        return request;
    }
}
