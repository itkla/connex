package ooo.klae.connex.backend.password;

/**
 * The result of screening a candidate password against the breach corpus, before any flow policy is
 * applied to it.
 *
 * <p>Screening is separated from the policy decision so the corpus lookup — which reaches a remote
 * service under the default {@code REMOTE} source — can run before a caller takes database locks,
 * while the fail-open decision that depends on account privilege still runs under them.
 *
 * @param unavailableReason why the corpus could not answer, or null when it answered that the
 *        candidate is not breached
 */
public record PasswordScreening(BreachedPasswordUnavailableReason unavailableReason) {

    /** A screening the corpus answered: the candidate does not appear in it. */
    public static PasswordScreening clean() {
        return new PasswordScreening(null);
    }

    /**
     * Whether the corpus answered at all.
     *
     * @return true when the candidate was screened, false when the source was unavailable
     */
    public boolean answered() {
        return unavailableReason == null;
    }
}
