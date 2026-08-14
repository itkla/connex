package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.ForbiddenException;

class PrivilegedMfaPropertiesTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void enforcementDefaultsOnAndMalformedValuesFailClosed() {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        assertTrue(properties.isEnforced());

        properties.setEnforced("definitely-not-a-boolean");
        assertTrue(properties.isEnforced());

        properties.setEnforced(" false ");
        assertFalse(properties.isEnforced());
    }

    @Test
    void recoveryConfigurationMustBeCompleteAndShortLived() {
        PrivilegedMfaProperties properties = configured("recovery-proof", NOW.plusSeconds(1800));
        assertDoesNotThrow(() -> properties.validate(CLOCK));

        properties.setRecoveryExpiresAt(NOW.plusSeconds(3601).toString());
        assertThrows(IllegalStateException.class, () -> properties.validate(CLOCK));

        properties.setRecoveryExpiresAt("");
        assertThrows(IllegalStateException.class, () -> properties.validate(CLOCK));
    }

    @Test
    void disablingEnforcementRequiresAccountableActor() {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setEnforced("false");

        assertThrows(IllegalStateException.class, () -> properties.validate(CLOCK));

        properties.setChangeActor("security-change-1234");
        assertDoesNotThrow(() -> properties.validate(CLOCK));
    }

    @Test
    void recoveryTokenComparisonIsExactAndExpiresClosed() {
        PrivilegedMfaProperties properties = configured("recovery-proof", NOW.plusSeconds(1800));

        assertDoesNotThrow(() -> properties.requireValidRecoveryToken("recovery-proof", CLOCK));
        assertThrows(ForbiddenException.class,
                () -> properties.requireValidRecoveryToken("wrong-proof", CLOCK));
        assertThrows(ForbiddenException.class,
                () -> properties.requireValidRecoveryToken(
                        "recovery-proof", Clock.fixed(NOW.plusSeconds(1801), ZoneOffset.UTC)));
    }

    @Test
    void blankRecoveryProofIsRejectedEvenIfItsDigestWasConfigured() {
        PrivilegedMfaProperties properties = configured("", NOW.plusSeconds(1800));

        assertThrows(ForbiddenException.class,
                () -> properties.requireValidRecoveryToken(" ", CLOCK));
    }

    private static PrivilegedMfaProperties configured(String token, Instant expiry) {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setRecoveryTokenSha256(sha256Hex(token));
        properties.setRecoveryExpiresAt(expiry.toString());
        properties.setRecoveryActor("security-operator");
        return properties;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
