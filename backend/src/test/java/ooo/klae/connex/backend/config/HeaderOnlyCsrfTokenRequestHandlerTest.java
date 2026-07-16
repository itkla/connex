package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class HeaderOnlyCsrfTokenRequestHandlerTest {
    private final HeaderOnlyCsrfTokenRequestHandler handler =
        new HeaderOnlyCsrfTokenRequestHandler();
    private final DefaultCsrfToken token =
        new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "secret");

    @Test
    void resolvesTheConfiguredHeader() {
        TrackingRequest request = new TrackingRequest();
        request.addHeader("X-CSRF-TOKEN", "secret");

        assertEquals("secret", handler.resolveCsrfTokenValue(request, token));
        assertFalse(request.parameterAccessed());
    }

    @Test
    void missingHeaderDoesNotReadFormOrMultipartParameters() {
        TrackingRequest request = new TrackingRequest();

        assertNull(handler.resolveCsrfTokenValue(request, token));
        assertFalse(request.parameterAccessed());
    }

    private static final class TrackingRequest extends MockHttpServletRequest {
        private boolean parameterAccessed;

        @Override
        public String getParameter(String name) {
            parameterAccessed = true;
            return super.getParameter(name);
        }

        private boolean parameterAccessed() {
            return parameterAccessed;
        }
    }
}
