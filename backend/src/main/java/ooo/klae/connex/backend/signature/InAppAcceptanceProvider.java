package ooo.klae.connex.backend.signature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import ooo.klae.connex.backend.mail.MailProperties;

/** Built-in token acceptance adapter; it performs no network I/O. */
@Component
public class InAppAcceptanceProvider implements DocumentSignatureProvider {
    public static final String KEY = "in_app";

    private final MailProperties mailProperties;
    private final SecureRandom secureRandom;

    /**
     * The marker is required because the class declares two constructors: Spring only auto-selects
     * one when a class declares exactly one, and otherwise looks for a no-arg constructor that does
     * not exist here. The second constructor is the deterministic-randomness seam for tests.
     */
    @Autowired
    public InAppAcceptanceProvider(MailProperties mailProperties) {
        this(mailProperties, new SecureRandom());
    }

    InAppAcceptanceProvider(MailProperties mailProperties, SecureRandom secureRandom) {
        this.mailProperties = mailProperties;
        this.secureRandom = secureRandom;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public SendOutcome send(SendCommand command) {
        String envelopeId = command.providerEnvelopeId() == null
            ? KEY + ":" + command.workspaceId() + ":" + command.deliveryId()
            : command.providerEnvelopeId();
        ArrayList<SendRecipientOutcome> outcomes = new ArrayList<>();
        for (SendRecipient recipient : command.recipients()) {
            byte[] secret = new byte[32];
            secureRandom.nextBytes(secret);
            String token = "w" + command.workspaceId() + "-" + HexFormat.of().formatHex(secret);
            String acceptanceUrl = UriComponentsBuilder
                .fromUriString(mailProperties.getAppBaseUrl())
                .path("/document-acceptance/{token}")
                .encode()
                .buildAndExpand(token)
                .toUriString();
            outcomes.add(new SendRecipientOutcome(
                recipient.recipientId(),
                Integer.toString(recipient.recipientId()),
                Optional.of(new RecipientDeliveryLink(sha256(token), acceptanceUrl))));
        }
        return new SendOutcome(envelopeId, outcomes);
    }

    @Override
    public void voidEnvelope(VoidCommand command) {
    }

    @Override
    public Optional<ProviderEvent> parseWebhook(
            String provider, Map<String, String> headers, byte[] body) {
        return Optional.empty();
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
