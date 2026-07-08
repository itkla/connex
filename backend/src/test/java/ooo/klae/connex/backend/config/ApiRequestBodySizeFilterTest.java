package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

class ApiRequestBodySizeFilterTest {
    private ApiRequestBodySizeFilter filter;

    @BeforeEach
    void setUp() {
        RequestBodySizeProperties properties = new RequestBodySizeProperties();
        properties.setMaxBodyBytes(8);
        properties.setWebauthnMaxBodyBytes(4);
        filter = new ApiRequestBodySizeFilter(properties);
    }

    @Test
    void rejectsKnownOversizedApiBodyBeforeChainRuns() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/api/tasks", "123456789");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsTransferEncodedApiBodyBeforeChainRuns() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/api/tasks", "123");
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsUnknownLengthBodyWhileInputStreamIsRead() throws Exception {
        MockHttpServletRequest request = unknownLengthJsonRequest("POST", "/api/tasks", "123456789");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, drainingInputStreamChain());

        assertEquals(413, response.getStatus());
    }

    @Test
    void rejectsUnknownLengthBodyWhileReaderIsRead() throws Exception {
        MockHttpServletRequest request = unknownLengthJsonRequest("POST", "/api/tasks", "123456789");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, drainingReaderChain());

        assertEquals(413, response.getStatus());
    }

    @Test
    void allowsUnderLimitBody() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/api/tasks", "12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void countsMultipartBodies() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/attachments");
        request.setContentType("multipart/form-data; boundary=x");
        request.setContent(new byte[9]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void appliesStricterWebAuthnLimit() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/api/auth/webauthn/authenticate", "12345");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void appliesLimitWhenAppHasContextPath() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/connex/api/tasks", "123456789");
        request.setContextPath("/connex");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void appliesLimitWhenSecurityRequestUsesServletPath() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "", "123456789");
        request.setServletPath("/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    private static MockHttpServletRequest jsonRequest(String method, String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest unknownLengthJsonRequest(String method, String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static FilterChain drainingInputStreamChain() {
        return (ServletRequest request, ServletResponse response) -> request.getInputStream().readAllBytes();
    }

    private static FilterChain drainingReaderChain() {
        return (ServletRequest request, ServletResponse response) -> {
            BufferedReader reader = request.getReader();
            while (reader.read() != -1) {
                continue;
            }
        };
    }
}
