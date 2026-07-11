package ooo.klae.connex.backend.ai.provider.vertex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class GoogleAccessTokenClientTest {
    private static final Instant NOW = Instant.parse("2026-07-10T08:15:30Z");
    private static final String CLIENT_EMAIL = "connex-agent@connex-prod1.iam.gserviceaccount.com";
    private static final String ACCESS_TOKEN = "ya29.vertex_access_token_secret";
    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";

    private ObjectMapper objectMapper;
    private KeyPair keyPair;
    private String privateKeyPem;
    private MutableClock clock;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        privateKeyPem = pem(keyPair);
        clock = new MutableClock(NOW);
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    @Test
    void accessToken_buildsPinnedJwtAndVerifiesSignatureWithoutBase64Padding() throws Exception {
        AtomicReference<String> formBody = new AtomicReference<>();
        server.expect(requestTo(GoogleAccessTokenClient.TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(request -> {
                    if (!(request instanceof MockClientHttpRequest mockRequest)) {
                        throw new AssertionError("Expected a buffered mock request");
                    }
                    formBody.set(mockRequest.getBodyAsString(StandardCharsets.UTF_8));
                })
                .andRespond(withSuccess(tokenResponse(ACCESS_TOKEN, 3600), MediaType.APPLICATION_JSON));
        GoogleAccessTokenClient client = client(1024);
        AiCredentials credentials = credentials("https://attacker.example.test/token");

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            assertEquals(ACCESS_TOKEN, client.accessToken(credentials));

            guard.verify(() -> AiEgressGuard.requireFetchableHost(GoogleAccessTokenClient.TOKEN_HOST, false));
        }

        Map<String, String> form = parseForm(formBody.get());
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form.get("grant_type"));
        String assertion = form.get("assertion");
        String[] segments = assertion.split("\\.");
        assertEquals(3, segments.length);
        for (String segment : segments) {
            assertFalse(segment.contains("="));
        }

        JsonNode header = decodeJson(segments[0]);
        JsonNode claims = decodeJson(segments[1]);
        assertEquals(2, header.size());
        assertEquals("RS256", header.path("alg").asString());
        assertEquals("JWT", header.path("typ").asString());
        assertEquals(5, claims.size());
        assertEquals(CLIENT_EMAIL, claims.path("iss").asString());
        assertEquals(GoogleAccessTokenClient.CLOUD_PLATFORM_SCOPE, claims.path("scope").asString());
        assertEquals(GoogleAccessTokenClient.TOKEN_ENDPOINT.toString(), claims.path("aud").asString());
        assertEquals(NOW.getEpochSecond(), claims.path("iat").asLong());
        assertEquals(NOW.plusSeconds(3600).getEpochSecond(), claims.path("exp").asLong());

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(segments[2])));
        server.verify();
    }

    @Test
    void accessToken_reusesCachedTokenBeforeRefreshWindow() throws Exception {
        server.expect(requestTo(GoogleAccessTokenClient.TOKEN_ENDPOINT))
                .andRespond(withSuccess(tokenResponse(ACCESS_TOKEN, 3600), MediaType.APPLICATION_JSON));
        GoogleAccessTokenClient client = client(1024);

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            String first = client.accessToken(credentials(GoogleAccessTokenClient.TOKEN_ENDPOINT.toString()));
            clock.setInstant(NOW.plusSeconds(1800));
            String second = client.accessToken(credentials(GoogleAccessTokenClient.TOKEN_ENDPOINT.toString()));

            assertEquals(ACCESS_TOKEN, first);
            assertEquals(first, second);
            guard.verify(() -> AiEgressGuard.requireFetchableHost(GoogleAccessTokenClient.TOKEN_HOST, false));
        }
        server.verify();
    }

    @Test
    void accessToken_refreshesAtExpiryMinusSixtySeconds() throws Exception {
        server.expect(requestTo(GoogleAccessTokenClient.TOKEN_ENDPOINT))
                .andRespond(withSuccess(tokenResponse("token-one", 120), MediaType.APPLICATION_JSON));
        server.expect(requestTo(GoogleAccessTokenClient.TOKEN_ENDPOINT))
                .andRespond(withSuccess(tokenResponse("token-two", 120), MediaType.APPLICATION_JSON));
        GoogleAccessTokenClient client = client(1024);

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            assertEquals("token-one",
                    client.accessToken(credentials(GoogleAccessTokenClient.TOKEN_ENDPOINT.toString())));
            clock.setInstant(NOW.plusSeconds(60));
            assertEquals("token-two",
                    client.accessToken(credentials(GoogleAccessTokenClient.TOKEN_ENDPOINT.toString())));

            guard.verify(() -> AiEgressGuard.requireFetchableHost(GoogleAccessTokenClient.TOKEN_HOST, false),
                    org.mockito.Mockito.times(2));
        }
        server.verify();
    }

    @Test
    void accessToken_nonSuccessAndInvalidResponsesAreSanitized() throws Exception {
        String serviceAccountJson = serviceAccountJson(GoogleAccessTokenClient.TOKEN_ENDPOINT.toString());
        AiCredentials credentials = AiCredentials.of(Map.of("serviceAccountJson", serviceAccountJson));
        server.expect(requestTo(GoogleAccessTokenClient.TOKEN_ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("SENSITIVE_RESPONSE_BODY " + ACCESS_TOKEN + " " + privateKeyPem));
        GoogleAccessTokenClient client = client(32768);

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.accessToken(credentials));

            assertEquals("Google OAuth token exchange failed with status 401", exception.getMessage());
            assertSecretAbsent(exception, serviceAccountJson);
            assertSecretAbsent(exception, privateKeyPem);
            assertSecretAbsent(exception, ACCESS_TOKEN);
            assertFalse(credentials.toString().contains(privateKeyPem));
            assertFalse(client.toString().contains(privateKeyPem));
            assertFalse(client.toString().contains(ACCESS_TOKEN));
            assertNull(exception.getCause());
        }
        server.verify();
    }

    @Test
    void accessToken_invalidPrivateKeyAndOversizedResponseAreSanitized() throws Exception {
        String invalidPrivateKey = PEM_HEADER + "\nPRIVATE_KEY_SECRET\n" + PEM_FOOTER;
        ObjectNode credential = objectMapper.createObjectNode();
        credential.put("client_email", CLIENT_EMAIL);
        credential.put("private_key", invalidPrivateKey);
        String invalidServiceAccount = objectMapper.writeValueAsString(credential);
        GoogleAccessTokenClient client = client(8);

        AiProviderException invalidKey = assertThrows(AiProviderException.class,
                () -> client.accessToken(AiCredentials.of(Map.of("serviceAccountJson", invalidServiceAccount))));

        assertSecretAbsent(invalidKey, invalidServiceAccount);
        assertSecretAbsent(invalidKey, "PRIVATE_KEY_SECRET");
        assertNull(invalidKey.getCause());

        server.expect(requestTo(GoogleAccessTokenClient.TOKEN_ENDPOINT))
                .andRespond(withSuccess("SENSITIVE_RESPONSE_BODY", MediaType.APPLICATION_JSON));
        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AiProviderException oversized = assertThrows(AiProviderException.class,
                    () -> client.accessToken(credentials(GoogleAccessTokenClient.TOKEN_ENDPOINT.toString())));

            assertFalse(String.valueOf(oversized).contains("SENSITIVE_RESPONSE_BODY"));
            assertNull(oversized.getCause());
        }
        server.verify();
    }

    private GoogleAccessTokenClient client(int maxResponseBytes) {
        return new GoogleAccessTokenClient(restClientBuilder.build(), maxResponseBytes, objectMapper, clock);
    }

    private AiCredentials credentials(String tokenUri) throws Exception {
        return AiCredentials.of(Map.of("serviceAccountJson", serviceAccountJson(tokenUri)));
    }

    private String serviceAccountJson(String tokenUri) throws Exception {
        ObjectNode credential = objectMapper.createObjectNode();
        credential.put("type", "service_account");
        credential.put("client_email", CLIENT_EMAIL);
        credential.put("private_key", privateKeyPem);
        credential.put("token_uri", tokenUri);
        return objectMapper.writeValueAsString(credential);
    }

    private JsonNode decodeJson(String value) throws Exception {
        return objectMapper.readTree(Base64.getUrlDecoder().decode(value));
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] fields = pair.split("=", 2);
            values.put(URLDecoder.decode(fields[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(fields[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String tokenResponse(String accessToken, long expiresIn) {
        return "{\"access_token\":\"" + accessToken + "\",\"expires_in\":" + expiresIn + "}";
    }

    private static String pem(KeyPair keyPair) {
        String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(keyPair.getPrivate().getEncoded());
        return PEM_HEADER + "\n" + encoded + "\n" + PEM_FOOTER;
    }

    private static void assertSecretAbsent(Throwable exception, String secret) {
        assertFalse(String.valueOf(exception).contains(secret));
        assertFalse(String.valueOf(exception.getMessage()).contains(secret));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        private void setInstant(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
