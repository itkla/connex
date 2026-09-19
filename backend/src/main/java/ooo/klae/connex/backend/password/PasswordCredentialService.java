package ooo.klae.connex.backend.password;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BreachedPasswordCheckUnavailableException;
import ooo.klae.connex.backend.exceptions.BreachedPasswordException;
import ooo.klae.connex.backend.exceptions.PasswordTooLongException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AuditService;

/**
 * Single boundary for screening and encoding every newly written password credential.
 *
 * <p>BCrypt's key schedule reads at most 72 bytes and the encoder refuses longer input, so a longer
 * candidate is rejected with {@link PasswordTooLongException} before it is screened. The bytes
 * screened against the breach corpus are therefore exactly the bytes the stored credential
 * authenticates by: a breached 72-byte password cannot carry a unique suffix past the corpus.
 */
@Service
@RequiredArgsConstructor
public class PasswordCredentialService {
    private static final Logger log = LoggerFactory.getLogger(PasswordCredentialService.class);
    private static final int MAX_CREDENTIAL_BYTES = 72;
    private static final String BREACH_CHECK_UNAVAILABLE_ACTION = "auth.password.breach_check_unavailable";
    private static final String DECISION_TARGET = "password-policy";
    private static final String DECISION_SUMMARY = "Breached-password policy decision";

    private final BreachedPasswordLookup breachedPasswordLookup;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final AuditService auditService;

    /**
     * Screens a candidate and, if policy allows, encodes it.
     *
     * @param candidate the proposed password
     * @param flow the credential-write context whose availability policy applies
     * @param userId the account the credential is written for, or null when there is none yet
     * @return the encoded credential
     * @throws PasswordTooLongException when the candidate exceeds what the encoder can store
     */
    public String encode(String candidate, PasswordScreeningFlow flow, Integer userId) {
        return encodeScreened(screen(candidate, flow), candidate, flow, userId);
    }

    /**
     * Screens a candidate against the breach corpus without applying any flow policy.
     *
     * <p>Callers that hold database locks should screen before acquiring them: under the default
     * {@code REMOTE} source this performs bounded HTTP requests, and holding row locks across them
     * would block unrelated account and role mutations for the duration of an upstream stall.
     *
     * @param candidate the proposed password
     * @param flow the credential-write context, used to name the rejected field
     * @return the screening result to hand to {@link #encodeScreened}
     * @throws PasswordTooLongException when the candidate exceeds what the encoder can store, before
     *         any corpus lookup
     * @throws BreachedPasswordException when the candidate appears in the corpus
     */
    public PasswordScreening screen(String candidate, PasswordScreeningFlow flow) {
        requireEncodable(candidate, flow);
        String sha1Hex = sha1(candidate.getBytes(StandardCharsets.UTF_8));
        try {
            if (breachedPasswordLookup.isBreached(sha1Hex)) {
                throw new BreachedPasswordException(flow.field());
            }
        } catch (BreachedPasswordSourceUnavailableException exception) {
            return new PasswordScreening(exception.getReason());
        }
        return PasswordScreening.clean();
    }

    /**
     * Applies the flow's availability policy to an earlier screening and encodes the candidate.
     *
     * <p>Privilege is read here rather than at screening time so a caller that revalidates under a
     * lock observes a promotion that committed while it was waiting.
     *
     * <p>The decision taken when the corpus could not answer is audited, but never by an independent
     * append while the caller's transaction is open: that append re-locks the actor's
     * {@code app_user} row shared, and a password-reset caller holds the same row exclusively when the
     * redeemer is signed in as the account owner. Inside a transaction a {@code fail_open} decision is
     * therefore appended in that transaction just before it commits, so it lands atomically with the
     * credential and after every other lock the caller takes; a {@code fail_closed} decision is
     * appended independently once the transaction has completed and released its locks. Outside a
     * transaction either decision is appended independently at once.
     *
     * @param screening the result of {@link #screen}
     * @param candidate the proposed password
     * @param flow the credential-write context whose availability policy applies
     * @param userId the account the credential is written for, or null when there is none yet
     * @return the encoded credential
     * @throws PasswordTooLongException when the candidate exceeds what the encoder can store
     * @throws BreachedPasswordCheckUnavailableException when the flow must fail closed
     */
    public String encodeScreened(PasswordScreening screening, String candidate,
            PasswordScreeningFlow flow, Integer userId) {
        requireEncodable(candidate, flow);
        if (!screening.answered()) {
            BreachedPasswordUnavailableReason reason = screening.unavailableReason();
            if (!mayFailOpen(flow, userId, reason)) {
                auditFailClosed(flow, userId, reason);
                throw new BreachedPasswordCheckUnavailableException(flow.field());
            }
            auditFailOpen(flow, userId, reason);
        }
        return passwordEncoder.encode(candidate);
    }

    /**
     * Whether the flow may store an unscreened credential because the corpus could not answer.
     *
     * <p>Only genuine upstream unavailability qualifies. {@code CAPACITY} does not: it is raised by
     * this instance's own concurrency permits and minimum request interval, never by the corpus, so
     * a caller can induce it at will with concurrent public registrations and then reset to a
     * breached password under the exemption. {@code OFFLINE_SOURCE} and {@code MALFORMED_RESPONSE}
     * are likewise local or untrustworthy answers rather than evidence the corpus is down.
     */
    private boolean mayFailOpen(PasswordScreeningFlow flow, Integer userId,
            BreachedPasswordUnavailableReason reason) {
        return flow == PasswordScreeningFlow.SELF_SERVICE_RESET
                && userId != null
                && !userMapper.isPrivilegedAccount(userId)
                && reason != BreachedPasswordUnavailableReason.CAPACITY
                && reason != BreachedPasswordUnavailableReason.OFFLINE_SOURCE
                && reason != BreachedPasswordUnavailableReason.MALFORMED_RESPONSE;
    }

    /**
     * Audits a decision to store an unscreened credential so that the credential cannot commit
     * without it: in the caller's transaction just before commit, where a failed append aborts the
     * commit, or independently at once when there is no transaction.
     */
    private void auditFailOpen(PasswordScreeningFlow flow, Integer userId,
            BreachedPasswordUnavailableReason reason) {
        Map<String, String> changes = decisionChanges(flow, "fail_open", reason);
        if (!inTransaction()) {
            auditIndependently(userId, changes);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                auditService.recordStrictScoped(BREACH_CHECK_UNAVAILABLE_ACTION, "user", userId, null,
                        null, DECISION_TARGET, DECISION_SUMMARY, changes);
            }
        });
    }

    /**
     * Audits a refusal independently of the transaction the refusal rolls back. Inside a transaction
     * the append waits until completion has released the caller's locks; a failure there is logged
     * rather than thrown because the refusal already stands.
     */
    private void auditFailClosed(PasswordScreeningFlow flow, Integer userId,
            BreachedPasswordUnavailableReason reason) {
        Map<String, String> changes = decisionChanges(flow, "fail_closed", reason);
        if (!inTransaction()) {
            auditIndependently(userId, changes);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    auditIndependently(userId, changes);
                } catch (RuntimeException exception) {
                    log.error("Breached-password fail_closed decision could not be audited: flow={} exception={}",
                            flow.auditValue(), exception.getClass().getName());
                }
            }
        });
    }

    private void auditIndependently(Integer userId, Map<String, String> changes) {
        auditService.recordStrictIndependentScoped(BREACH_CHECK_UNAVAILABLE_ACTION, "user", userId, null,
                null, DECISION_TARGET, DECISION_SUMMARY, changes);
    }

    private static boolean inTransaction() {
        return TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive();
    }

    private static Map<String, String> decisionChanges(PasswordScreeningFlow flow, String decision,
            BreachedPasswordUnavailableReason reason) {
        return reason == null
                ? Map.of("flow", flow.auditValue(), "decision", decision)
                : Map.of("flow", flow.auditValue(), "decision", decision,
                        "reason", reason.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Refuses a candidate the encoder cannot store whole, so the screened bytes and the encoded bytes
     * are always the same bytes.
     */
    private static void requireEncodable(String candidate, PasswordScreeningFlow flow) {
        if (candidate == null) {
            throw new IllegalArgumentException("Password candidate is required");
        }
        if (candidate.getBytes(StandardCharsets.UTF_8).length > MAX_CREDENTIAL_BYTES) {
            throw new PasswordTooLongException(flow.field());
        }
    }

    private static String sha1(byte[] candidate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(candidate);
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable");
        }
    }
}
