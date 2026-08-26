package ooo.klae.connex.backend.password;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BreachedPasswordCheckUnavailableException;
import ooo.klae.connex.backend.exceptions.BreachedPasswordException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AuditService;

/**
 * Single boundary for screening and encoding every newly written password credential.
 */
@Service
@RequiredArgsConstructor
public class PasswordCredentialService {
    private static final int BCRYPT_EFFECTIVE_INPUT_BYTES = 72;

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
     * @throws BreachedPasswordException when the candidate appears in the corpus
     */
    public PasswordScreening screen(String candidate, PasswordScreeningFlow flow) {
        String sha1Hex = sha1(effectiveCredential(candidate));
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
     * @param screening the result of {@link #screen}
     * @param candidate the proposed password
     * @param flow the credential-write context whose availability policy applies
     * @param userId the account the credential is written for, or null when there is none yet
     * @return the encoded credential
     * @throws BreachedPasswordCheckUnavailableException when the flow must fail closed
     */
    public String encodeScreened(PasswordScreening screening, String candidate,
            PasswordScreeningFlow flow, Integer userId) {
        if (!screening.answered()) {
            String decision = mayFailOpen(flow, userId, screening.unavailableReason())
                    ? "fail_open"
                    : "fail_closed";
            auditDecision("auth.password.breach_check_unavailable", flow, userId, decision,
                    screening.unavailableReason());
            if (!"fail_open".equals(decision)) {
                throw new BreachedPasswordCheckUnavailableException(flow.field());
            }
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

    private void auditDecision(String action, PasswordScreeningFlow flow, Integer userId,
            String decision, BreachedPasswordUnavailableReason reason) {
        Map<String, String> changes = reason == null
                ? Map.of("flow", flow.auditValue(), "decision", decision)
                : Map.of("flow", flow.auditValue(), "decision", decision,
                        "reason", reason.name().toLowerCase(Locale.ROOT));
        auditService.recordStrictIndependentScoped(
                action,
                "user",
                userId,
                null,
                null,
                "password-policy",
                "Breached-password policy decision",
                changes);
    }

    /**
     * The candidate bytes the encoder will actually consume.
     *
     * <p>BCrypt's key schedule reads at most 72 bytes, so a longer candidate authenticates by its
     * first 72 bytes alone. Screening the whole candidate would let a breached 72-byte password
     * carrying any unique suffix past the corpus while still storing the breached credential, so the
     * screened bytes are the stored credential's bytes rather than the submitted string's.
     */
    private static byte[] effectiveCredential(String candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Password candidate is required");
        }
        byte[] bytes = candidate.getBytes(StandardCharsets.UTF_8);
        return bytes.length <= BCRYPT_EFFECTIVE_INPUT_BYTES
                ? bytes
                : Arrays.copyOf(bytes, BCRYPT_EFFECTIVE_INPUT_BYTES);
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
