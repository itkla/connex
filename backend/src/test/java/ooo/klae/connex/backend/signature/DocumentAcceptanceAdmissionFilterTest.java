package ooo.klae.connex.backend.signature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import ooo.klae.connex.backend.config.DocumentAcceptanceAdmissionFilter;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.controllers.DocumentAcceptanceController;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.DocumentAcceptanceService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

@ExtendWith(MockitoExtension.class)
class DocumentAcceptanceAdmissionFilterTest {
    private static final String GRANT = "a".repeat(64);
    private static final String LEGACY_TOKEN = "w42-" + "a".repeat(64);
    private static final String SOURCE = "198.51.100.20";
    private static final String UNAVAILABLE_BODY =
        "{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Document link is no longer available\"}";

    @Mock DocumentAcceptanceRateLimiter rateLimiter;
    @Mock ClientIpResolver clientIpResolver;
    @Mock DocumentAcceptanceService acceptanceService;
    @Mock OneTimeLinkFlowService flowService;
    @Mock OneTimeLinkFlowCookie flowCookie;
    @Mock ErrorReporter errorReporter;
    @Mock TenantContext tenantContext;

    private DocumentAcceptanceAdmissionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new DocumentAcceptanceAdmissionFilter(rateLimiter, clientIpResolver);
    }

    @Test
    void missingCookieGetsTheUniformUnavailableResponseBeforeBodyAccess() throws Exception {
        TrackingJsonRequest request = request("/accept", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(clientIpResolver.resolve(request)).thenReturn(SOURCE);

        filter.doFilter(request, response, chain);

        assertEquals(404, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertEquals(UNAVAILABLE_BODY, response.getContentAsString());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
        verify(rateLimiter).acquire(DocumentAcceptanceToken.hashForAdmission(null), SOURCE);
    }

    @Test
    void malformedGrantsIncrementTheRealSourceThrottleCounter() throws Exception {
        SignatureProperties properties = new SignatureProperties();
        properties.setMaxRequestsPerSource(1);
        DocumentAcceptanceRateLimiter realRateLimiter = new DocumentAcceptanceRateLimiter(
            properties,
            Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC));
        DocumentAcceptanceAdmissionFilter realFilter =
            new DocumentAcceptanceAdmissionFilter(realRateLimiter, clientIpResolver);
        TrackingJsonRequest firstRequest = request("/accept", "first-malformed");
        TrackingJsonRequest secondRequest = request("/accept", "second-malformed");
        when(clientIpResolver.resolve(firstRequest)).thenReturn(SOURCE);
        when(clientIpResolver.resolve(secondRequest)).thenReturn(SOURCE);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        realFilter.doFilter(firstRequest, firstResponse, new MockFilterChain());
        realFilter.doFilter(secondRequest, secondResponse, new MockFilterChain());

        assertEquals(404, firstResponse.getStatus());
        assertEquals(429, secondResponse.getStatus());
        assertFalse(firstRequest.bodyAccessed());
        assertFalse(secondRequest.bodyAccessed());
    }

    @Test
    void throttleRejectsBeforeMalformedJsonCanBeParsed() throws Exception {
        TrackingJsonRequest request = request("/decline", GRANT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(clientIpResolver.resolve(request)).thenReturn(SOURCE);
        doThrow(new TooManyRequestsException("limited"))
            .when(rateLimiter).acquire(OneTimeTokenDigest.sha256(GRANT), SOURCE);

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals(
            "Too many document-link requests. Please try again later.",
            response.getContentAsString());
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
    }

    @Test
    void grantAndSourceAdmissionRunBeforeTheChainReadsTheBody() throws Exception {
        TrackingJsonRequest request = request("/accept", GRANT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(clientIpResolver.resolve(request)).thenReturn(SOURCE);
        doAnswer(invocation -> {
            assertFalse(request.bodyAccessed());
            return null;
        }).when(rateLimiter).acquire(OneTimeTokenDigest.sha256(GRANT), SOURCE);

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
            servletRequest.getInputStream().readAllBytes());

        assertEquals(200, response.getStatus());
        assertTrue(request.bodyAccessed());
        verify(rateLimiter).acquire(OneTimeTokenDigest.sha256(GRANT), SOURCE);
    }

    @Test
    void exchangePathBypassesThisFilter() throws Exception {
        TrackingJsonRequest request = request("/exchange", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
        verifyNoInteractions(rateLimiter, clientIpResolver);
    }

    @Test
    void barePreviewPathIsFilteredAndLegacyPathTokensAreNotCredentials() throws Exception {
        MockHttpServletRequest bare = new MockHttpServletRequest("GET", "/api/document-acceptance");
        MockHttpServletRequest legacy = new MockHttpServletRequest(
            "GET", "/api/document-acceptance/" + LEGACY_TOKEN);
        MockHttpServletRequest legacyDecision = new MockHttpServletRequest(
            "POST", "/api/document-acceptance/" + LEGACY_TOKEN + "/accept");
        when(clientIpResolver.resolve(any())).thenReturn(SOURCE);

        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
                bare, legacy, legacyDecision}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertEquals(404, response.getStatus(), request.getRequestURI());
            assertEquals(UNAVAILABLE_BODY, response.getContentAsString());
            assertNull(chain.getRequest());
        }
        verify(rateLimiter, org.mockito.Mockito.times(3))
            .acquire(DocumentAcceptanceToken.hashForAdmission(null), SOURCE);
    }

    @Test
    void missingUnknownExpiredAndDecidedGrantsHaveByteExactUnavailableResponses()
            throws Exception {
        String unknown = "b".repeat(64);
        String expired = "c".repeat(64);
        String decided = "d".repeat(64);
        when(clientIpResolver.resolve(any())).thenReturn(SOURCE);
        for (String grant : new String[] {unknown, expired, decided}) {
            when(acceptanceService.admitGrant(any(), eq(grant)))
                .thenThrow(new ResourceNotFoundException("Document link is no longer available"));
        }
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new DocumentAcceptanceController(
                acceptanceService, clientIpResolver, flowService, flowCookie))
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, tenantContext))
            .addFilters(filter)
            .build();

        MvcResult missingResponse = mockMvc.perform(get("/api/document-acceptance")).andReturn();
        MvcResult unknownResponse = mockMvc.perform(get("/api/document-acceptance")
            .cookie(new Cookie(OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, unknown))).andReturn();
        MvcResult expiredResponse = mockMvc.perform(get("/api/document-acceptance")
            .cookie(new Cookie(OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, expired))).andReturn();
        MvcResult decidedResponse = mockMvc.perform(get("/api/document-acceptance")
            .cookie(new Cookie(OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, decided))).andReturn();

        assertEquals(404, missingResponse.getResponse().getStatus());
        assertUniformUnavailable(missingResponse, unknownResponse);
        assertUniformUnavailable(missingResponse, expiredResponse);
        assertUniformUnavailable(missingResponse, decidedResponse);
    }

    private static void assertUniformUnavailable(MvcResult expected, MvcResult actual) {
        assertEquals(expected.getResponse().getStatus(), actual.getResponse().getStatus());
        assertEquals(expected.getResponse().getContentType(), actual.getResponse().getContentType());
        assertArrayEquals(
            expected.getResponse().getContentAsByteArray(),
            actual.getResponse().getContentAsByteArray());
    }

    private static TrackingJsonRequest request(String suffix, String grant) {
        TrackingJsonRequest request = new TrackingJsonRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/document-acceptance" + suffix);
        request.setContentType("application/json");
        request.setContent("{\"broken\"".getBytes(StandardCharsets.UTF_8));
        if (grant != null) {
            request.setCookies(new Cookie(OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, grant));
        }
        return request;
    }

    private static final class TrackingJsonRequest extends MockHttpServletRequest {
        private boolean bodyAccessed;

        @Override
        public ServletInputStream getInputStream() {
            bodyAccessed = true;
            return super.getInputStream();
        }

        @Override
        public BufferedReader getReader() throws java.io.UnsupportedEncodingException {
            bodyAccessed = true;
            return super.getReader();
        }

        private boolean bodyAccessed() {
            return bodyAccessed;
        }
    }
}
