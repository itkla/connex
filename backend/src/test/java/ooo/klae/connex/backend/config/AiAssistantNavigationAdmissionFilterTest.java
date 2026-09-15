package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AiAssistantNavigationAdmissionFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"/api/%61i/assistant/sessions", "/%61pi/ai/assistant/sessions",
            "/api//ai/assistant/sessions"})
    void alternateAssistantPathsCannotBypassNavigationAdmission(String path) throws Exception {
        AiAssistantNavigationAdmissionFilter filter = new AiAssistantNavigationAdmissionFilter(new String[0]);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/connex" + path);
        request.setContextPath("/connex");
        request.addHeader("Sec-Fetch-Dest", "document");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }
}
