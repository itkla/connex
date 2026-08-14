package ooo.klae.connex.backend.password;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private final BreachedPasswordLookup breachedPasswordLookup;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final AuditService auditService;

    public String encode(String candidate, PasswordScreeningFlow flow, Integer userId) {
        String sha1Hex = sha1(candidate);
        try {
            if (breachedPasswordLookup.isBreached(sha1Hex)) {
                throw new BreachedPasswordException(flow.field());
            }
        } catch (BreachedPasswordSourceUnavailableException exception) {
            String decision = mayFailOpen(flow, userId, exception.getReason())
                    ? "fail_open"
                    : "fail_closed";
            auditDecision("auth.password.breach_check_unavailable", flow, userId, decision,
                    exception.getReason());
            if (!"fail_open".equals(decision)) {
                throw new BreachedPasswordCheckUnavailableException(flow.field());
            }
        }
        return passwordEncoder.encode(candidate);
    }

    private boolean mayFailOpen(PasswordScreeningFlow flow, Integer userId,
            BreachedPasswordUnavailableReason reason) {
        return flow == PasswordScreeningFlow.SELF_SERVICE_RESET
                && userId != null
                && !userMapper.isPrivilegedAccount(userId)
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

    private static String sha1(String candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Password candidate is required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(candidate.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable");
        }
    }
}
