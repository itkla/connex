package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "connex.request-limits.max-body-bytes=8",
        "connex.request-limits.import-max-body-bytes=16",
        "connex.request-limits.webauthn-max-body-bytes=4"
    }
)
class ApiRequestBodySizeIntegrationTest {
    @LocalServerPort
    private int port;

    @Test
    void chunkedJsonBodyOverLimitIsRejectedBeforeController() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request("/api/auth/login", "123456789"),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
    }

    @Test
    void underLimitChunkedJsonBodyReachesMvc() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request("/api/auth/login", "{}"),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
    }

    @Test
    void chunkedWebAuthnBodyUsesStricterLimit() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request("/api/auth/webauthn/authenticate", "12345"),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
    }

    @Test
    void chunkedBodyOnNoBodyEndpointIsRejectedBeforeController() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request("/api/auth/webauthn/authenticate/options", "12345"),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
    }

    @Test
    void chunkedMultipartBodyOnNoBodyEndpointIsRejectedBeforeController() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request("/api/auth/webauthn/authenticate/options", "12345", "multipart/form-data; boundary=x"),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
    }

    @Test
    void chunkedPutFormIsRejectedBeforeFormContentFilter() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request("PUT", "/api/auth/webauthn/authenticate/options", "field=123",
                "application/x-www-form-urlencoded"),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
    }

    @Test
    void chunkedPatchFormIsRejectedBeforeFormContentFilter() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request("PATCH", "/api/auth/webauthn/authenticate/options", "field=123",
                "application/x-www-form-urlencoded"),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
    }

    @Test
    void chunkedDeleteFormIsRejectedBeforeFormContentFilter() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request("DELETE", "/api/auth/webauthn/authenticate/options", "field=123",
                "application/x-www-form-urlencoded"),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
    }

    @Test
    void knownLengthJsonBodyOverLimitIsRejectedBeforeController() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("123456789"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
    }

    private HttpRequest request(String path, String body) {
        return request(path, body, "application/json");
    }

    private HttpRequest request(String path, String body, String contentType) {
        return request("POST", path, body, contentType);
    }

    private HttpRequest request(String method, String path, String body, String contentType) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", contentType)
            .method(method, HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
            .build();
    }
}
