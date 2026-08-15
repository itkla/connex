package ooo.klae.connex.backend.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.mail.MailProperties;

class InAppAcceptanceProviderTest {

    @Test
    void sendMintsIndependentWorkspaceRoutedTokensAndReturnsOnlyTheirHashes() {
        MailProperties properties = new MailProperties();
        properties.setAppBaseUrl("https://connex.example");
        InAppAcceptanceProvider provider = new InAppAcceptanceProvider(properties);

        SendOutcome outcome = provider.send(new SendCommand(
            42,
            91,
            null,
            LocalDateTime.now().plusDays(1),
            List.of(
                new SendRecipient(11, "One", "one@example.test", "signer", 1),
                new SendRecipient(12, "Two", "two@example.test", "signer", 2))));

        assertEquals("in_app:42:91", outcome.providerEnvelopeId());
        assertEquals(2, outcome.recipients().size());
        String firstToken = token(outcome.recipients().getFirst().deliveryLink().orElseThrow().url());
        String secondToken = token(outcome.recipients().getLast().deliveryLink().orElseThrow().url());
        assertTrue(firstToken.matches("w42-[a-f0-9]{64}"));
        assertTrue(secondToken.matches("w42-[a-f0-9]{64}"));
        assertNotEquals(firstToken, secondToken);
        assertEquals(sha256(firstToken),
            outcome.recipients().getFirst().deliveryLink().orElseThrow().tokenHash());
        assertEquals(sha256(secondToken),
            outcome.recipients().getLast().deliveryLink().orElseThrow().tokenHash());
        assertTrue(outcome.recipients().getFirst().deliveryLink().orElseThrow().url()
            .startsWith("https://connex.example/document-acceptance/"));
        assertFalse(outcome.recipients().getFirst().deliveryLink().orElseThrow().url()
            .contains("/api/document-acceptance/"));
        assertFalse(provider.parseWebhook("in_app", Map.of(), new byte[0]).isPresent());
    }

    private static String token(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
