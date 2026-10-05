package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("chunkedRequests")
    void chunkedRequestLimits(String name, String method, String path, String body,
            String contentType, int expectedStatus) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request(method, path, body, contentType),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(expectedStatus, response.statusCode());
    }

    private static Stream<Arguments> chunkedRequests() {
        return Stream.of(
            Arguments.of("chunkedJsonBodyOverLimitIsRejectedBeforeController", "POST",
                "/api/auth/login", "123456789", "application/json", 413),
            Arguments.of("underLimitChunkedJsonBodyReachesMvc", "POST",
                "/api/auth/login", "{}", "application/json", 400),
            Arguments.of("chunkedWebAuthnBodyUsesStricterLimit", "POST",
                "/api/auth/webauthn/authenticate", "12345", "application/json", 413),
            Arguments.of("chunkedBodyOnNoBodyEndpointIsRejectedBeforeController", "POST",
                "/api/auth/webauthn/authenticate/options", "12345", "application/json", 413),
            Arguments.of("chunkedMultipartBodyOnNoBodyEndpointIsRejectedBeforeController", "POST",
                "/api/auth/webauthn/authenticate/options", "12345", "multipart/form-data; boundary=x", 413),
            Arguments.of("chunkedPutFormIsRejectedBeforeFormContentFilter", "PUT",
                "/api/auth/webauthn/authenticate/options", "field=123", "application/x-www-form-urlencoded", 413),
            Arguments.of("chunkedPatchFormIsRejectedBeforeFormContentFilter", "PATCH",
                "/api/auth/webauthn/authenticate/options", "field=123", "application/x-www-form-urlencoded", 413),
            Arguments.of("chunkedDeleteFormIsRejectedBeforeFormContentFilter", "DELETE",
                "/api/auth/webauthn/authenticate/options", "field=123", "application/x-www-form-urlencoded", 413));
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

    private HttpRequest request(String method, String path, String body, String contentType) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", contentType)
            .method(method, HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
            .build();
    }
}
