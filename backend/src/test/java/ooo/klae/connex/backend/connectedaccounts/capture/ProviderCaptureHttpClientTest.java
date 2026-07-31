package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class ProviderCaptureHttpClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recognizesDocumentedMicrosoftDeltaCursorExpiryCodes() {
        assertTrue(deltaError("syncStateNotFound"));
        assertTrue(deltaError("InvalidDeltaToken"));
        assertFalse(deltaError("ErrorAccessDenied"));
    }

    @Test
    void malformedProviderErrorsDoNotTriggerAFullResynchronization() {
        assertFalse(ProviderCaptureHttpClient.isDeltaCursorError(
            objectMapper, "not-json".getBytes(StandardCharsets.UTF_8)));
    }

    private boolean deltaError(String code) {
        return ProviderCaptureHttpClient.isDeltaCursorError(
            objectMapper,
            ("{\"error\":{\"code\":\"" + code + "\"}}")
                .getBytes(StandardCharsets.UTF_8));
    }
}
