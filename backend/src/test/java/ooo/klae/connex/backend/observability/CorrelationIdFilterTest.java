package ooo.klae.connex.backend.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;
import ooo.klae.connex.backend.config.ApiRequestBodySizeFilter;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;

class CorrelationIdFilterTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void reusesOneValidInboundValueAndMakesItVisibleDuringRequest() throws Exception {
        String inbound = "request_ID-1234";
        MockHttpServletRequest request = request();
        request.addHeader(CorrelationIds.HEADER_NAME, inbound);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringRequest = new AtomicReference<>();

        filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> duringRequest.set(MDC.get(CorrelationIds.MDC_KEY)));

        assertEquals(inbound, response.getHeader(CorrelationIds.HEADER_NAME));
        assertEquals(inbound, duringRequest.get());
        assertNull(MDC.get(CorrelationIds.MDC_KEY));
    }

    @Test
    void generatesForMissingMalformedShortLongAndDuplicateValues() throws Exception {
        assertGenerated(null);
        assertGenerated("short");
        assertGenerated("contains space");
        assertGenerated("line\nbreak");
        assertGenerated("a".repeat(65));

        MockHttpServletRequest duplicate = request();
        duplicate.addHeader(CorrelationIds.HEADER_NAME, "valid_id_123");
        duplicate.addHeader(CorrelationIds.HEADER_NAME, "other_id_456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(duplicate, response, new MockFilterChain());

        String generated = response.getHeader(CorrelationIds.HEADER_NAME);
        assertTrue(CorrelationIds.isValid(generated));
        assertNotEquals("valid_id_123", generated);
    }

    @Test
    void setsHeaderAndCleansMdcWhenDownstreamThrows() {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServletException.class, () -> filter.doFilter(
                request,
                response,
                (ignoredRequest, ignoredResponse) -> {
                    throw new ServletException("boom");
                }));

        assertTrue(CorrelationIds.isValid(response.getHeader(CorrelationIds.HEADER_NAME)));
        assertNull(MDC.get(CorrelationIds.MDC_KEY));
    }

    @Test
    void replacesAndRemovesStaleMdcValue() throws Exception {
        MDC.put(CorrelationIds.MDC_KEY, "stale_id");
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringRequest = new AtomicReference<>();

        filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> duringRequest.set(MDC.get(CorrelationIds.MDC_KEY)));

        assertNotEquals("stale_id", duringRequest.get());
        assertNull(MDC.get(CorrelationIds.MDC_KEY));
    }

    @Test
    void wrapsEarlyBodySizeRejection() throws Exception {
        RequestBodySizeProperties properties = new RequestBodySizeProperties();
        properties.setMaxBodyBytes(1);
        ApiRequestBodySizeFilter bodyFilter = new ApiRequestBodySizeFilter(properties);
        MockHttpServletRequest request = request();
        request.setMethod("POST");
        request.setContent(new byte[] { 1, 2 });
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response,
                (boundedRequest, boundedResponse) ->
                        bodyFilter.doFilter(boundedRequest, boundedResponse, new MockFilterChain()));

        assertEquals(413, response.getStatus());
        assertTrue(CorrelationIds.isValid(response.getHeader(CorrelationIds.HEADER_NAME)));
        assertNull(MDC.get(CorrelationIds.MDC_KEY));
    }

    private void assertGenerated(String inbound) throws Exception {
        MockHttpServletRequest request = request();
        if (inbound != null) {
            request.addHeader(CorrelationIds.HEADER_NAME, inbound);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(CorrelationIds.HEADER_NAME);
        assertTrue(CorrelationIds.isValid(generated));
        if (inbound != null) {
            assertNotEquals(inbound, generated);
        }
        assertNull(MDC.get(CorrelationIds.MDC_KEY));
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/health");
    }
}
