package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.RequestRejectedException;

class RequestPathNormalizerTest {
    @ParameterizedTest
    @ValueSource(strings = {"/api/%65xports/persons", "/%61pi/exports/persons",
            "/api//exports/persons", "/api;x/exports;y/persons;z", "/api/exports/persons%3Bx"})
    void normalizesRoutedExportPaths(String uri) {
        assertEquals("/api/exports/persons",
                RequestPathNormalizer.apiPath(new MockHttpServletRequest("GET", uri)));
    }

    @Test
    void publicApiTransactionPolicyUsesTheNormalizedPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/connex/%61pi//v1/me");
        request.setContextPath("/connex");
        assertEquals(ooo.klae.connex.backend.publicapi.ApiCredentialAuthenticationFilter.RouteTransactionMode.READ,
                PublicApiSecurityConfig.transactionMode(request));
    }

    @Test
    void removesDeploymentContextBeforeDecoding() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/connex/%61pi/auth/me");
        request.setContextPath("/connex");
        assertEquals("/api/auth/me", RequestPathNormalizer.apiPath(request));
    }

    @Test
    void preservesPlusAndDecodesUnicodePathSegmentsOnce() {
        assertEquals("/api/orgs/+1/audit/export", RequestPathNormalizer.apiPath(
                new MockHttpServletRequest("GET", "/api/orgs/+1/audit/export")));
        assertEquals("/api/日本", RequestPathNormalizer.apiPath(
                new MockHttpServletRequest("GET", "/api/%E6%97%A5%E6%9C%AC")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/%2565xports/persons", "/api/exports/persons%253Bx", "/api/exports%2fpersons",
            "/api/exports%2Fpersons", "/api/exports%5Cpersons", "/api/exports%5cpersons",
            "/api/%00exports", "/api/%1fexports", "/api/%7fexports", "/api/%C2%85exports",
            "/api/%", "/api/%zz", "/api/%2", "/api/../exports", "/api/%2e%2e/exports",
            "/api/..;x/exports", "/api/exports/..", "/api/./exports", "/api/x;%252f/exports"})
    void rejectsAmbiguousPaths(String uri) {
        assertThrows(RequestRejectedException.class, () -> RequestPathNormalizer.apiPath(
                new MockHttpServletRequest("GET", uri)));
    }

    @Test
    void rejectsContextPrefixLookalikes() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/connextra/api/auth/me");
        request.setContextPath("/connex");
        assertThrows(RequestRejectedException.class, () -> RequestPathNormalizer.apiPath(request));
    }
}
