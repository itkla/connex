package ooo.klae.connex.backend.signature;

import java.util.Objects;
import java.util.Optional;

/** Provider output retained for one recipient after send or resend. */
public record SendRecipientOutcome(
        int recipientId,
        String providerRecipientId,
        Optional<RecipientDeliveryLink> deliveryLink) {

    /** Rejects ambiguous null optionals at the provider boundary. */
    public SendRecipientOutcome {
        Objects.requireNonNull(deliveryLink, "deliveryLink");
    }
}
