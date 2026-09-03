package ooo.klae.connex.backend.signature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
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
import ooo.klae.connex.backend.config.DocumentAcceptanceAdmissionFilter;
import ooo.klae.connex.backend.controllers.DocumentAcceptanceController;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.DocumentAcceptanceAdmissionService;
import ooo.klae.connex.backend.services.DocumentAcceptanceService;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.ClientIpResolver;

@ExtendWith(MockitoExtension.class)
class DocumentAcceptanceAdmissionFilterTest {
    private static final String TOKEN = "w42-" + "a".repeat(64);
    private static final String SOURCE = "198.51.100.20";

    @Mock DocumentAcceptanceRateLimiter rateLimiter;
    @Mock ClientIpResolver clientIpResolver;
    @Mock DocumentAcceptanceAdmissionService admissionService;
    @Mock DocumentAcceptanceService acceptanceService;
    @Mock ErrorReporter errorReporter;
    @Mock TenantContext tenantContext;

    private DocumentAcceptanceAdmissionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new DocumentAcceptanceAdmissionFilter(
            rateLimiter,
            clientIpResolver,
            admissionService);
    }

    @Test
    void malformedTokenGetsTheUniformUnavailableResponseBeforeBodyAccess() throws Exception {
        TrackingJsonRequest request = request("not-a-token", "/accept");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(clientIpResolver.resolve(request)).thenReturn(SOURCE);

        filter.doFilter(request, response, chain);

        assertEquals(404, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertEquals(
            "{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Document link is no longer available\"}",
            response.getContentAsString());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
        verify(rateLimiter).acquire(
            DocumentAcceptanceToken.hashForAdmission("not-a-token"),
            SOURCE);
        verify(admissionService).lookup("not-a-token");
    }

    @Test
    void malformedTokensIncrementTheRealSourceThrottleCounter() throws Exception {
        SignatureProperties properties = new SignatureProperties();
        properties.setMaxRequestsPerSource(1);
        DocumentAcceptanceRateLimiter realRateLimiter = new DocumentAcceptanceRateLimiter(
            properties,
            Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC));
        DocumentAcceptanceAdmissionFilter realFilter =
            new DocumentAcceptanceAdmissionFilter(
                realRateLimiter,
                clientIpResolver,
                admissionService);
        TrackingJsonRequest firstRequest = request("first-malformed", "/accept");
        TrackingJsonRequest secondRequest = request("second-malformed", "/accept");
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
        TrackingJsonRequest request = request(TOKEN, "/decline");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(clientIpResolver.resolve(request)).thenReturn(SOURCE);
        doThrow(new TooManyRequestsException("limited"))
            .when(rateLimiter).acquire(DocumentAcceptanceToken.hash(TOKEN), SOURCE);

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals(
            "Too many document-link requests. Please try again later.",
            response.getContentAsString());
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
    }

    @Test
    void tokenAndSourceAdmissionRunBeforeTheChainReadsTheBody() throws Exception {
        TrackingJsonRequest request = request(TOKEN, "/accept");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(clientIpResolver.resolve(request)).thenReturn(SOURCE);
        doAnswer(invocation -> {
            assertFalse(request.bodyAccessed());
            return null;
        }).when(rateLimiter).acquire(DocumentAcceptanceToken.hash(TOKEN), SOURCE);

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
            servletRequest.getInputStream().readAllBytes());

        assertEquals(200, response.getStatus());
        assertTrue(request.bodyAccessed());
        verify(rateLimiter).acquire(DocumentAcceptanceToken.hash(TOKEN), SOURCE);
    }

    @Test
    void malformedUnknownExpiredAndDecidedTokensHaveByteExactUnavailableResponses() throws Exception {
        String unknown = "w42-" + "b".repeat(64);
        String expired = "w42-" + "c".repeat(64);
        String decided = "w42-" + "d".repeat(64);
        when(clientIpResolver.resolve(any())).thenReturn(SOURCE);
        when(acceptanceService.preview(unknown, SOURCE))
            .thenThrow(new ResourceNotFoundException("Document link is no longer available"));
        when(acceptanceService.preview(expired, SOURCE))
            .thenThrow(new ResourceNotFoundException("Document link is no longer available"));
        when(acceptanceService.preview(decided, SOURCE))
            .thenThrow(new ResourceNotFoundException("Document link is no longer available"));
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new DocumentAcceptanceController(acceptanceService, clientIpResolver))
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, tenantContext))
            .addFilters(filter)
            .build();

        MvcResult malformedResponse = mockMvc.perform(
            get("/api/document-acceptance/not-a-token")).andReturn();
        MvcResult unknownResponse = mockMvc.perform(
            get("/api/document-acceptance/{token}", unknown)).andReturn();
        MvcResult expiredResponse = mockMvc.perform(
            get("/api/document-acceptance/{token}", expired)).andReturn();
        MvcResult decidedResponse = mockMvc.perform(
            get("/api/document-acceptance/{token}", decided)).andReturn();

        assertUniformUnavailable(malformedResponse, unknownResponse);
        assertUniformUnavailable(malformedResponse, expiredResponse);
        assertUniformUnavailable(malformedResponse, decidedResponse);
    }

    private static void assertUniformUnavailable(MvcResult expected, MvcResult actual) {
        assertEquals(expected.getResponse().getStatus(), actual.getResponse().getStatus());
        assertEquals(expected.getResponse().getContentType(), actual.getResponse().getContentType());
        assertArrayEquals(
            expected.getResponse().getContentAsByteArray(),
            actual.getResponse().getContentAsByteArray());
    }

    private static TrackingJsonRequest request(String token, String suffix) {
        TrackingJsonRequest request = new TrackingJsonRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/document-acceptance/" + token + suffix);
        request.setContentType("application/json");
        request.setContent("{\"broken\"".getBytes(StandardCharsets.UTF_8));
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
