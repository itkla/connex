package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.exceptions.ForbiddenException;

class PrivilegedMfaPropertiesTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int ACCOUNT_A = 41;
    private static final int ACCOUNT_B = 42;

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
        PrivilegedMfaProperties properties = configured(ACCOUNT_A, "recovery-proof", NOW.plusSeconds(1800));
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "configuration-default", " configuration-default "})
    void disablingBootstrapConfirmationRejectsUnattributedActors(String actor) {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setChangeActor(actor);

        assertTrue(properties.isEnforced());
        assertThrows(IllegalStateException.class,
                () -> properties.validateBootstrapConfirmation(false));
        assertDoesNotThrow(() -> properties.validateBootstrapConfirmation(true));
    }

    @Test
    void disablingBootstrapConfirmationRequiresAnExplicitActor() {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        assertThrows(IllegalStateException.class,
                () -> properties.validateBootstrapConfirmation(false));

        properties.setChangeActor(" security-change-1234 ");
        assertDoesNotThrow(() -> properties.validateBootstrapConfirmation(false));
    }

    @Test
    void recoveryTokenComparisonIsExactAndExpiresClosed() {
        PrivilegedMfaProperties properties = configured(ACCOUNT_A, "recovery-proof", NOW.plusSeconds(1800));

        PrivilegedMfaRecoveryAuthorization authorization =
                properties.requireValidRecoveryToken(ACCOUNT_A, "recovery-proof", CLOCK);
        assertEquals("security-operator", authorization.operator());
        assertRefused(() -> properties.requireValidRecoveryToken(ACCOUNT_A, "wrong-proof", CLOCK));
        assertRefused(() -> properties.requireValidRecoveryToken(
                ACCOUNT_A, "recovery-proof", Clock.fixed(NOW.plusSeconds(1801), ZoneOffset.UTC)));
    }

    @Test
    void blankRecoveryProofIsRejectedEvenIfItsDigestWasConfigured() {
        PrivilegedMfaProperties properties = configured(ACCOUNT_A, "", NOW.plusSeconds(1800));

        assertRefused(() -> properties.requireValidRecoveryToken(ACCOUNT_A, " ", CLOCK));
        assertRefused(() -> properties.requireValidRecoveryToken(ACCOUNT_A, "", CLOCK));
        assertRefused(() -> properties.requireValidRecoveryToken(ACCOUNT_A, null, CLOCK));
    }

    /** The account id is part of the hashed material, so a token issued for A never matches B. */
    @Test
    void aTokenIssuedForOneAccountIsRefusedForAnother() {
        PrivilegedMfaProperties properties = configured(ACCOUNT_A, "recovery-proof", NOW.plusSeconds(1800));

        assertDoesNotThrow(() -> properties.requireValidRecoveryToken(ACCOUNT_A, "recovery-proof", CLOCK));
        assertRefused(() -> properties.requireValidRecoveryToken(ACCOUNT_B, "recovery-proof", CLOCK));
    }

    /** Digest material lacking the purpose prefix, including the legacy bare digest, matches nobody. */
    @ParameterizedTest
    @ValueSource(strings = {"%2$s", "%1$d:%2$s"})
    void digestMaterialWithoutThePurposePrefixIsRefused(String material) {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setRecoveryTokenSha256(sha256Hex(material.formatted(ACCOUNT_A, "recovery-proof")));
        properties.setRecoveryExpiresAt(NOW.plusSeconds(1800).toString());
        properties.setRecoveryActor("security-operator");

        assertRefused(() -> properties.requireValidRecoveryToken(ACCOUNT_A, "recovery-proof", CLOCK));
    }

    /**
     * Wrong, blank, expired, unconfigured and another account's token are indistinguishable to the
     * caller.
     */
    @Test
    void everyRecoveryRefusalCarriesTheSameMessage() {
        PrivilegedMfaProperties properties = configured(ACCOUNT_A, "recovery-proof", NOW.plusSeconds(1800));
        Clock expired = Clock.fixed(NOW.plusSeconds(1801), ZoneOffset.UTC);

        List<ForbiddenException> refusals = List.of(
                assertThrows(ForbiddenException.class,
                        () -> properties.requireValidRecoveryToken(ACCOUNT_A, "wrong-proof", CLOCK)),
                assertThrows(ForbiddenException.class,
                        () -> properties.requireValidRecoveryToken(ACCOUNT_A, " ", CLOCK)),
                assertThrows(ForbiddenException.class,
                        () -> properties.requireValidRecoveryToken(ACCOUNT_A, "recovery-proof", expired)),
                assertThrows(ForbiddenException.class,
                        () -> properties.requireValidRecoveryToken(ACCOUNT_B, "recovery-proof", CLOCK)),
                assertThrows(ForbiddenException.class,
                        () -> new PrivilegedMfaProperties().requireValidRecoveryToken(
                                ACCOUNT_A, "recovery-proof", CLOCK)));

        for (ForbiddenException refusal : refusals) {
            assertEquals(PrivilegedMfaProperties.INVALID_RECOVERY_AUTHORIZATION, refusal.getMessage());
        }
    }

    /** The ledger key is a one-way hash of the configured digest, never the digest or the token. */
    @Test
    void theRedemptionKeyIsAOneWayHashOfTheConfiguredDigest() {
        String configuredDigest = sha256Hex("connex-privileged-mfa-recovery:v1:" + ACCOUNT_A + ":recovery-proof");
        PrivilegedMfaProperties properties = configured(ACCOUNT_A, "recovery-proof", NOW.plusSeconds(1800));
        properties.setRecoveryTokenSha256(configuredDigest.toUpperCase(Locale.ROOT));

        PrivilegedMfaRecoveryAuthorization authorization =
                properties.requireValidRecoveryToken(ACCOUNT_A, "recovery-proof", CLOCK);

        assertEquals(sha256Hex(HexFormat.of().parseHex(configuredDigest)), authorization.redemptionKey());
        assertNotEquals(configuredDigest, authorization.redemptionKey());
        assertFalse(authorization.redemptionKey().contains("recovery-proof"));
    }

    /**
     * The actor is recorded in the redemption ledger's {@code VARCHAR(255)} utf8mb4 column, which
     * counts characters rather than bytes or UTF-16 units. A 255-character actor, even one made of
     * four-byte characters, is accepted; one character more is refused at startup and at redemption
     * instead of being silently truncated by the ledger's {@code INSERT IGNORE}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"a", "\uD83D\uDD10"})
    void theRecoveryActorIsLimitedToTheLedgerColumnLengthInCharacters(String character) {
        PrivilegedMfaProperties properties = configured(ACCOUNT_A, "recovery-proof", NOW.plusSeconds(1800));
        String longestActor = character.repeat(255);
        properties.setRecoveryActor(longestActor);

        assertDoesNotThrow(() -> properties.validate(CLOCK));
        assertEquals(longestActor,
                properties.requireValidRecoveryToken(ACCOUNT_A, "recovery-proof", CLOCK).operator());

        properties.setRecoveryActor(longestActor + character);

        IllegalStateException refusal =
                assertThrows(IllegalStateException.class, () -> properties.validate(CLOCK));
        assertEquals("Privileged MFA recovery actor must be at most 255 characters", refusal.getMessage());
        assertRefused(() -> properties.requireValidRecoveryToken(ACCOUNT_A, "recovery-proof", CLOCK));
    }

    private static void assertRefused(Executable executable) {
        ForbiddenException refusal = assertThrows(ForbiddenException.class, executable);
        assertEquals(PrivilegedMfaProperties.INVALID_RECOVERY_AUTHORIZATION, refusal.getMessage());
    }

    private static PrivilegedMfaProperties configured(int userId, String token, Instant expiry) {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setRecoveryTokenSha256(
                sha256Hex("connex-privileged-mfa-recovery:v1:" + userId + ":" + token));
        properties.setRecoveryExpiresAt(expiry.toString());
        properties.setRecoveryActor("security-operator");
        return properties;
    }

    private static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
