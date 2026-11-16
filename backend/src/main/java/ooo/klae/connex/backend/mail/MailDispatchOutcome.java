package ooo.klae.connex.backend.mail;

/** Coarse mail submission evidence independent of a recipient's Connex account state. */
public enum MailDispatchOutcome {
    ACCEPTED,
    SKIPPED,
    FAILED,
    UNCONFIRMED
}
