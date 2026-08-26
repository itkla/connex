package ooo.klae.connex.backend.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletInputStream;
import ooo.klae.connex.backend.config.DocumentAcceptanceAdmissionFilter;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.util.ClientIpResolver;

@ExtendWith(MockitoExtension.class)
class DocumentAcceptanceAdmissionFilterTest {
    private static final String TOKEN = "w42-" + "a".repeat(64);
    private static final String SOURCE = "198.51.100.20";

    @Mock DocumentAcceptanceRateLimiter rateLimiter;
    @Mock ClientIpResolver clientIpResolver;

    private DocumentAcceptanceAdmissionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new DocumentAcceptanceAdmissionFilter(rateLimiter, clientIpResolver);
    }

    @Test
    void malformedTokenGetsTheUniformUnavailableResponseBeforeBodyAccess() throws Exception {
        TrackingJsonRequest request = request("not-a-token", "/accept");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(404, response.getStatus());
        assertEquals("Document link is no longer available", response.getContentAsString());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(chain.getRequest());
        assertFalse(request.bodyAccessed());
        verify(rateLimiter, never()).acquire(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
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
