package ooo.klae.connex.backend.publicapi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.FirewalledRequest;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.UriUtils;

class PublicApiPathsTest {

    @Test
    void queryStringIsIgnoredButEncodedQuestionMarkIsPathData() {
        assertTrue(PublicApiPaths.isPublicRequest(request("/api/v1?foo=bar")));
        assertFalse(PublicApiPaths.isPublicRequest(request("/api/v1%3Ffoo")));
    }

    /**
     * A servlet request URI never carries the query, so a literal question mark in it is path data
     * to the normalizer and to the public security matcher alike; the classifier must agree.
     */
    @Test
    void literalQuestionMarkInAnAcceptedRequestUriIsClassifiedAsTheSecurityMatcherSeesIt() {
        MockHttpServletRequest literalQuestionMark = new MockHttpServletRequest("GET", "/api/v1");
        literalQuestionMark.setRequestURI("/api/v1?foo=bar");
        RequestMatcher matcher = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**");

        assertEquals(matcher.matches(literalQuestionMark), PublicApiPaths.isPublicRequest(literalQuestionMark));
        assertFalse(PublicApiPaths.isPublicRequest(literalQuestionMark));
    }

    @Test
    void lenientFallbackStillRemovesARawQuerySeparatorBeforeDecoding() {
        MockHttpServletRequest rejectedWithRawQuery = new MockHttpServletRequest("GET", "/api/v1");
        rejectedWithRawQuery.setRequestURI("/api/v1?next=%2F");

        assertTrue(PublicApiPaths.isPublicRequest(rejectedWithRawQuery));
    }

    /**
     * Paths the normalizer accepts are classified on its output, the same path the public chain's
     * transaction policy and every normalized policy filter match on, not on a lenient re-parse.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/connex/%61pi//v1/me", "/connex/api;x/v1/me", "/connex/api/v%31/me"})
    void acceptedPathsAreClassifiedOnTheNormalizedPath(String uri) {
        assertTrue(PublicApiPaths.isPublicRequest(inContext(uri, "/connex")));
    }

    @Test
    void deploymentContextIsRemovedOnlyAtASegmentBoundary() {
        assertTrue(PublicApiPaths.isPublicRequest(inContext("/connex/api/v1/me", "/connex")));
        assertTrue(PublicApiPaths.isPublicRequest(inContext("/connex/api/v1%2Fme", "/connex")));
        assertFalse(PublicApiPaths.isPublicRequest(inContext("/connexx/api/v1/me", "/connex")));
        assertFalse(PublicApiPaths.isPublicRequest(inContext("/connexx/api/v1%2Fme", "/connex")));
        assertTrue(PublicApiPaths.isPublicRequest(inContext("/api/v1/me", "/ap")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1;x/me", "/api/v1%2Fme", "/api/v1/%zz", "/api/v1/me%01"})
    void neverThrowsOnPublicShapesTheNormalizerStripsOrRejects(String uri) {
        assertTrue(assertDoesNotThrow(() -> PublicApiPaths.isPublicRequest(request(uri)), uri), uri);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/tasks%2Fme", "/api/v10%2Fme", "/api/v1x/%zz", "/api/v1x%2Fme"})
    void rejectedBrowserShapesStayOutsideThePublicNamespace(String uri) {
        assertFalse(assertDoesNotThrow(() -> PublicApiPaths.isPublicRequest(request(uri)), uri), uri);
    }

    @Test
    void classifierAgreesWithPublicSecurityMatcherForFirewallAcceptedUris() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        RequestMatcher matcher = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**");
        List<String> rawUris = List.of(
            "/api/v1",
            "/api/v1/",
            "/api/v1/me",
            "/api/v%31/me",
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

    private static MockHttpServletRequest inContext(String rawUri, String contextPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", rawUri);
        request.setContextPath(contextPath);
        return request;
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
