package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

class ApiRequestBodySizeFilterTest {
    private ApiRequestBodySizeFilter filter;

    @BeforeEach
    void setUp() {
        RequestBodySizeProperties properties = new RequestBodySizeProperties();
        properties.setMaxBodyBytes(8);
        properties.setImportMaxBodyBytes(16);
        properties.setUploadMaxBodyBytes(12);
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
    void allowsUnderLimitTransferEncodedApiBody() throws Exception {
        MockHttpServletRequest request = unknownLengthJsonRequest("POST", "/api/tasks", "12345678");
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void rejectsOverLimitTransferEncodedBodyEvenWhenTheEndpointDoesNotReadIt() throws Exception {
        MockHttpServletRequest request = unknownLengthJsonRequest("POST", "/api/tasks/7/complete", "123456789");
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsOverLimitTransferEncodedDeleteBody() throws Exception {
        MockHttpServletRequest request = unknownLengthRequest(
            "DELETE", "/api/tasks/7", "field=123", "application/x-www-form-urlencoded");
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
    void appliesDedicatedUploadLimitToManagedMultipartRoutes() throws Exception {
        List<String> paths = List.of(
            "/api/attachments/upload",
            "/api/users/me/profile-picture",
            "/api/persons/7/profile-picture",
            "/api/companies/7/logo");
        for (String path : paths) {
            MockHttpServletRequest allowed = new MockHttpServletRequest("POST", path);
            allowed.setContentType("multipart/form-data; boundary=x");
            allowed.setContent(new byte[12]);
            MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
            MockFilterChain allowedChain = new MockFilterChain();

            filter.doFilter(allowed, allowedResponse, allowedChain);

            assertEquals(200, allowedResponse.getStatus(), path);
            assertNotNull(allowedChain.getRequest(), path);

            MockHttpServletRequest rejected = new MockHttpServletRequest("POST", path);
            rejected.setContentType("multipart/form-data; boundary=x");
            rejected.setContent(new byte[13]);
            MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
            MockFilterChain rejectedChain = new MockFilterChain();

            filter.doFilter(rejected, rejectedResponse, rejectedChain);

            assertEquals(413, rejectedResponse.getStatus(), path);
            assertNull(rejectedChain.getRequest(), path);
        }
    }

    @Test
    void preservesContainerParsedFormParametersForUnknownLengthBodies() throws Exception {
        RequestBodySizeProperties properties = new RequestBodySizeProperties();
        properties.setMaxBodyBytes(64);
        ApiRequestBodySizeFilter formFilter = new ApiRequestBodySizeFilter(properties);
        MockHttpServletRequest request = unknownLengthRequest(
            "POST", "/api/login/saml2/sso/test", "SAMLResponse=assertion", "application/x-www-form-urlencoded");
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();

        formFilter.doFilter(request, response, (bounded, ignored) ->
            assertEquals("assertion", bounded.getParameter("SAMLResponse")));

        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsOversizedUnknownLengthFormBodiesBeforeParameterParsing() throws Exception {
        MockHttpServletRequest request = unknownLengthRequest(
            "POST", "/api/auth/login", "field=123", "application/x-www-form-urlencoded");
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsUnknownLengthMultipartBeforeContainerParsing() throws Exception {
        MockHttpServletRequest request = unknownLengthRequest(
            "POST", "/api/attachments", "payload", "multipart/form-data; boundary=x");
        request.addHeader("Transfer-Encoding", "chunked");
        request.addPart(new MockPart("file", "file.txt", "payload".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void appliesDedicatedFormLimitToImportRoutes() throws Exception {
        RequestBodySizeProperties properties = new RequestBodySizeProperties();
        properties.setMaxBodyBytes(8);
        properties.setImportMaxBodyBytes(64);
        properties.setFormMaxBodyBytes(12);
        ApiRequestBodySizeFilter formFilter = new ApiRequestBodySizeFilter(properties);
        MockHttpServletRequest request = unknownLengthRequest(
            "POST", "/api/imports/persons/preview", "field=1234567", "application/x-www-form-urlencoded");
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        formFilter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsMalformedUnknownLengthFormAsBadRequest() throws Exception {
        RequestBodySizeProperties properties = new RequestBodySizeProperties();
        properties.setMaxBodyBytes(64);
        ApiRequestBodySizeFilter formFilter = new ApiRequestBodySizeFilter(properties);
        MockHttpServletRequest request = unknownLengthRequest(
            "POST", "/api/auth/login", "field=%ZZ", "application/x-www-form-urlencoded");
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        formFilter.doFilter(request, response, chain);

        assertEquals(400, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsMalformedEncodedFormBytesAsBadRequest() throws Exception {
        RequestBodySizeProperties properties = new RequestBodySizeProperties();
        properties.setMaxBodyBytes(64);
        ApiRequestBodySizeFilter formFilter = new ApiRequestBodySizeFilter(properties);
        MockHttpServletRequest request = unknownLengthRequest(
            "POST", "/api/auth/login", new byte[] { 'x', '=', (byte) 0xff },
            "application/x-www-form-urlencoded");
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        formFilter.doFilter(request, response, chain);

        assertEquals(400, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsUnknownLengthFormWithTooManyParameters() throws Exception {
        RequestBodySizeProperties properties = new RequestBodySizeProperties();
        properties.setMaxBodyBytes(16_000);
        properties.setFormMaxBodyBytes(16_000);
        ApiRequestBodySizeFilter formFilter = new ApiRequestBodySizeFilter(properties);
        MockHttpServletRequest request = unknownLengthRequest(
            "POST", "/api/auth/login", "field=&".repeat(1_001), "application/x-www-form-urlencoded");
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        formFilter.doFilter(request, response, chain);

        assertEquals(400, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void appliesLargerImportLimitWithoutExemptingImports() throws Exception {
        MockHttpServletRequest allowed = jsonRequest("POST", "/api/imports/persons/preview", "123456789012");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        MockFilterChain allowedChain = new MockFilterChain();

        filter.doFilter(allowed, allowedResponse, allowedChain);

        assertEquals(200, allowedResponse.getStatus());
        assertNotNull(allowedChain.getRequest());

        MockHttpServletRequest rejected = unknownLengthJsonRequest(
            "POST", "/api/imports/persons/preview", "12345678901234567");
        rejected.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();

        filter.doFilter(rejected, rejectedResponse, drainingInputStreamChain());

        assertEquals(413, rejectedResponse.getStatus());
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
        return unknownLengthRequest(method, path, body, "application/json");
    }

    private static MockHttpServletRequest unknownLengthRequest(
            String method, String path, String body, String contentType) {
        return unknownLengthRequest(method, path, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private static MockHttpServletRequest unknownLengthRequest(
            String method, String path, byte[] body, String contentType) {
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
        request.setContentType(contentType);
        request.setContent(body);
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
