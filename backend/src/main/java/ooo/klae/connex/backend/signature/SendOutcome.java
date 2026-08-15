package ooo.klae.connex.backend.signature;

import java.util.List;

/** Provider-neutral result of creating or refreshing recipient delivery state. */
public record SendOutcome(
        String providerEnvelopeId,
        List<SendRecipientOutcome> recipients) {

    public SendOutcome {
        recipients = List.copyOf(recipients);
    }
}
