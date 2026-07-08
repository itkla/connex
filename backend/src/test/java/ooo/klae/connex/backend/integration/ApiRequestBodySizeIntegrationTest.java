package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "connex.request-limits.max-body-bytes=8",
        "connex.request-limits.webauthn-max-body-bytes=4"
    }
)
class ApiRequestBodySizeIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    void chunkedJsonBodyOverLimitIsRejectedBeforeController() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            request("/api/auth/login", "123456789"),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
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
    void knownLengthJsonBodyOverLimitIsRejectedBeforeController() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("123456789"))
            .andExpect(status().is(413));
    }

    private HttpRequest request(String path, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
            .build();
    }
}
