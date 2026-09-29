package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the status an anonymous caller receives when the strict firewall refuses the request on a
 * real servlet container.
 *
 * <p>Spring's {@code HttpStatusRequestRejectedHandler} refused with {@code sendError}, which makes
 * the container ERROR-dispatch to {@code /error}; that dispatch has no rule in the application chain
 * and fell to {@code anyRequest().authenticated()}, so an anonymous caller received the entry
 * point's 401 in place of the firewall's 400 (#1780). MockMvc performs no error dispatch and so
 * cannot observe any of this, which is why the refusal is exercised over real HTTP here.
 *
 * <p>Every spelling below is one Tomcat accepts and the firewall then refuses, so the JSON envelope
 * in the response is the evidence that the application's own handler wrote the refusal rather than
 * the container rejecting the request line itself. An encoded percent, separator, backslash or null
 * ({@code %25}, {@code %2F}, {@code %5C}, {@code %00}) cannot be used here: Tomcat refuses those
 * request lines with its own 400 and no filter ever runs, which is why
 * {@code AbstractEncodedSecurityPathIntegrationTest} pins them by status alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1"})
class FirewallRefusalStatusIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/auth/csrf;x=1",
        "/api/auth/csrf%3Bx"
    })
    void anonymousFirewallRefusalKeepsTheFirewallStatus(String rawPath) throws Exception {
        HttpResponse<String> response = get(rawPath);

        assertEquals(400, response.statusCode(), rawPath);
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
            .startsWith("application/json"), rawPath);
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(BadRequestException.CODE, body.path("code").asString(), rawPath);
    }

    /** The refusal must describe neither the rejection nor the request that triggered it. */
    @Test
    void refusalBodyDescribesNothingAboutTheRequest() throws Exception {
        HttpResponse<String> response = get("/api/auth/csrf;probe=firewallrefusalmarker");

        assertEquals(400, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(BadRequestException.CODE, body.path("code").asString());
        assertEquals("Request was rejected", body.path("message").asString());
        assertEquals(2, body.size());
        assertFalse(response.body().contains("firewallrefusalmarker"));
        assertFalse(response.body().contains("csrf"));
    }

    /**
     * The firewall refuses ahead of the chain's header writers, so the handler applies the response
     * header contract itself; without it the refusal would ship without the API's hardening headers.
     */
    @Test
    void refusalCarriesTheApiResponseHeaderContract() throws Exception {
        HttpResponse<String> response = get("/api/auth/csrf;x=1");

        assertEquals(400, response.statusCode());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("")
            .contains("default-src 'none'"));
    }

    private HttpResponse<String> get(String rawPath) throws Exception {
        URI target = URI.create("http://127.0.0.1:" + port + rawPath);
        assertEquals(rawPath, target.getRawPath());
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        }
    }
}
