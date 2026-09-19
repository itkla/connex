package ooo.klae.connex.backend.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.ForbiddenException;

/**
 * Fail-closed privileged-MFA enforcement and time-boxed operator recovery configuration.
 */
@Component
@ConfigurationProperties(prefix = "connex.security.privileged-mfa")
public class PrivilegedMfaProperties {
    /** The single refusal for every recovery-token failure, so none reveals which check failed. */
    public static final String INVALID_RECOVERY_AUTHORIZATION = "MFA recovery authorization is invalid or expired";

    private static final String DEFAULT_ACTOR = "configuration-default";
    private static final String RECOVERY_TOKEN_PURPOSE = "connex-privileged-mfa-recovery:v1:";
    private static final int SHA_256_HEX_LENGTH = 64;
    private static final Duration MAX_RECOVERY_WINDOW = Duration.ofHours(1);

    private String enforced = "true";
    private String changeActor = DEFAULT_ACTOR;
    private String recoveryTokenSha256 = "";
    private String recoveryExpiresAt = "";
    private String recoveryActor = "";

    public boolean isEnforced() {
        return !"false".equalsIgnoreCase(normalize(enforced));
    }

    public String configuredEnforcedValue() {
        return normalize(enforced);
    }

    public String getEnforced() {
        return enforced;
    }

    public void setEnforced(String enforced) {
        this.enforced = enforced;
    }

    public String getChangeActor() {
        return normalizeOrDefault(changeActor, DEFAULT_ACTOR);
    }

    public void setChangeActor(String changeActor) {
        this.changeActor = changeActor;
    }

    public String getRecoveryTokenSha256() {
        return recoveryTokenSha256;
    }

    public void setRecoveryTokenSha256(String recoveryTokenSha256) {
        this.recoveryTokenSha256 = recoveryTokenSha256;
    }

    public String getRecoveryExpiresAt() {
        return recoveryExpiresAt;
    }

    public void setRecoveryExpiresAt(String recoveryExpiresAt) {
        this.recoveryExpiresAt = recoveryExpiresAt;
    }

    public String getRecoveryActor() {
        return recoveryActor;
    }

    public void setRecoveryActor(String recoveryActor) {
        this.recoveryActor = recoveryActor;
    }

    /** Requires an accountable actor for a first-passkey confirmation exception. */
    public void validateBootstrapConfirmation(boolean confirmationEnabled) {
        if (!confirmationEnabled && DEFAULT_ACTOR.equals(getChangeActor())) {
            throw new IllegalStateException(
                    "Disabling privileged MFA bootstrap confirmation requires an accountable change actor");
        }
    }

    public void validate(Clock clock) {
        if (!isEnforced() && DEFAULT_ACTOR.equals(getChangeActor())) {
            throw new IllegalStateException(
                    "Disabling privileged MFA requires an accountable change actor");
        }
        validateRecoveryStructure();
        if (normalize(recoveryTokenSha256).isEmpty()) {
            return;
        }
        Instant expiry = recoveryExpiry();
        if (!clock.instant().isBefore(expiry)
                || expiry.isAfter(clock.instant().plus(MAX_RECOVERY_WINDOW))) {
            throw new IllegalStateException(
                    "Privileged MFA recovery expiry must be within the next hour");
        }
    }

    private void validateRecoveryStructure() {
        boolean tokenConfigured = !normalize(recoveryTokenSha256).isEmpty();
        boolean expiryConfigured = !normalize(recoveryExpiresAt).isEmpty();
        boolean actorConfigured = !normalize(recoveryActor).isEmpty();
        if (tokenConfigured != expiryConfigured || tokenConfigured != actorConfigured) {
            throw new IllegalStateException(
                    "Privileged MFA recovery requires token hash, expiry, and actor together");
        }
        if (!tokenConfigured) {
            return;
        }
        if (normalize(recoveryTokenSha256).length() != SHA_256_HEX_LENGTH
                || !normalize(recoveryTokenSha256).matches("[0-9a-fA-F]+")) {
            throw new IllegalStateException("Privileged MFA recovery token hash must be 64 hexadecimal characters");
        }
        recoveryExpiry();
    }

    /**
     * Accepts the operator recovery token only for the account it was issued to (#1532).
     *
     * <p>The configured digest is {@code sha256("connex-privileged-mfa-recovery:v1:" + userId + ":"
     * + token)}, so the account id and a purpose prefix are bound into the hashed material. A
     * token issued for one account therefore matches no other account, and a bare SHA-256 of the
     * token matches none at all. Every refusal — no configuration, blank or wrong token, another
     * account's token, expiry — raises the same message.
     *
     * <p>This check is stateless. The caller must also record the returned redemption key so the
     * token is spent after one successful use.
     *
     * @param userId the account completing the recovery ceremony
     * @param candidate the raw token submitted by that account
     * @param clock the clock the expiry is evaluated against
     * @return the operator who issued the token and the one-way key identifying this redemption
     * @throws ForbiddenException when the token is not valid for this account now
     */
    public PrivilegedMfaRecoveryAuthorization requireValidRecoveryToken(int userId, String candidate, Clock clock) {
        try {
            validateRecoveryStructure();
        } catch (IllegalStateException exception) {
            throw new ForbiddenException(INVALID_RECOVERY_AUTHORIZATION);
        }
        if (normalize(recoveryTokenSha256).isEmpty()
                || candidate == null
                || candidate.isBlank()
                || !clock.instant().isBefore(recoveryExpiry())) {
            throw new ForbiddenException(INVALID_RECOVERY_AUTHORIZATION);
        }
        byte[] expected = HexFormat.of().parseHex(normalize(recoveryTokenSha256));
        byte[] actual = sha256((RECOVERY_TOKEN_PURPOSE + userId + ":" + candidate)
                .getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ForbiddenException(INVALID_RECOVERY_AUTHORIZATION);
        }
        return new PrivilegedMfaRecoveryAuthorization(
                normalize(recoveryActor), HexFormat.of().formatHex(sha256(expected)));
    }

    private Instant recoveryExpiry() {
        try {
            return Instant.parse(normalize(recoveryExpiresAt));
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("Privileged MFA recovery expiry must be an ISO-8601 instant");
        }
    }

    private static byte[] sha256(byte[] material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeOrDefault(String value, String defaultValue) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? defaultValue : normalized;
    }
}
