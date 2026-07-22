package ooo.klae.connex.backend.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AiCompletionRequestTest {

    @Test
    void constructor_rejectsMissingOutputMode() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> new AiCompletionRequest(
                        new AiProviderTarget(
                                "vertex", "us-central1", "gemini-2.5-flash",
                                null, null, null, "connex-prod1", false),
                        AiCredentials.of(Map.of()),
                        null,
                        List.of(new AiMessage("user", "Hello")),
                        List.of(),
                        null,
                        64,
                        0.2));

        assertEquals("outputMode", exception.getMessage());
    }
}
