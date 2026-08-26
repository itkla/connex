package ooo.klae.connex.backend.signature;

import java.time.LocalDateTime;
import java.util.List;

/** Provider-neutral command for an envelope's recipient delivery. */
public record SendCommand(
        int workspaceId,
        int deliveryId,
        String providerEnvelopeId,
        LocalDateTime expiresAt,
        List<SendRecipient> recipients) {

    public SendCommand {
        recipients = List.copyOf(recipients);
    }
}
