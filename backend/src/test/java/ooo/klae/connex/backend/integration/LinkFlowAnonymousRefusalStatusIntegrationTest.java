package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.dto.CsrfBootstrapDto;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the statuses an anonymous caller receives from refusals on the one-time-link routes when
 * the full filter chain runs on a real servlet container.
 *
 * <p>MockMvc never performs the container's ERROR dispatch, so a refusal written with
 * {@code sendError} looks correct there while a live anonymous caller receives the entry point's
 * 401 from the re-dispatched {@code /error} request. Only a real HTTP round trip observes that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", "server.servlet.session.cookie.secure=false"})
class LinkFlowAnonymousRefusalStatusIntegrationTest {

    private static final String WELL_FORMED_GRANT = "ab".repeat(32);

    private static final String EXCHANGE_BODY = "{\"token\":\"not-a-real-link-token\"}";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void admitExchangesByDefault() {
        when(loginRateLimiter.tryAcquireOneTimeLinkExchange(any(), anyLong())).thenReturn(true);
    }

    /**
     * The document-acceptance admission filter answers 404 before CSRF runs unless the request
     * carries a well-formed grant cookie, so the caller presents one to reach the CSRF check.
     */
    @Test
    void documentViewedWithoutCsrfHeaderIsForbidden() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpCookie grant = new HttpCookie(OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, WELL_FORMED_GRANT);
        grant.setPath("/api/document-acceptance");
        grant.setVersion(0);
        cookies.getCookieStore().add(uri("/api/document-acceptance"), grant);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        bootstrap(client);

        HttpResponse<String> response = client.send(
            post("/api/document-acceptance/viewed", "{}").build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

    @Test
    void documentExchangeWithAForeignCsrfTokenIsForbidden() throws Exception {
        HttpClient client = newClient();
        CsrfBootstrapDto own = bootstrap(client);
        CsrfBootstrapDto foreign = bootstrap(newClient());
        assertNotEquals(own.token(), foreign.token());

        HttpResponse<String> response = client.send(
            post("/api/document-acceptance/exchange", EXCHANGE_BODY)
                .header(foreign.headerName(), foreign.token())
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

    @Test
    void unsubscribeWithoutCsrfHeaderIsForbidden() throws Exception {
        HttpClient client = newClient();
        bootstrap(client);

        HttpResponse<String> response = client.send(
            post("/api/delivery/unsubscribe", "{}").build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

    /**
     * The exchange throttle runs after CSRF validation, so a correctly bootstrapped anonymous
     * browser reaches it; the refusal must stay a 429 carrying the rate-limit error body rather
     * than being rewritten to 401 by an ERROR dispatch.
     */
    @Test
    void throttledDocumentExchangeIsTooManyRequestsNotUnauthorized() throws Exception {
        when(loginRateLimiter.tryAcquireOneTimeLinkExchange(any(), anyLong())).thenReturn(false);
        HttpClient client = newClient();
        CsrfBootstrapDto csrf = bootstrap(client);

        HttpResponse<String> response = client.send(
            post("/api/document-acceptance/exchange", EXCHANGE_BODY)
                .header(csrf.headerName(), csrf.token())
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(429, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
            .startsWith("application/json"));
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(TooManyRequestsException.CODE, body.path("code").asString());
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    }

    private CsrfBootstrapDto bootstrap(HttpClient client) throws Exception {
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        CsrfBootstrapDto csrf = objectMapper.readValue(response.body(), CsrfBootstrapDto.class);
        assertNotNull(csrf.headerName());
        assertNotNull(csrf.token());
        return csrf;
    }

    private HttpRequest.Builder post(String path, String body) {
        return HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
