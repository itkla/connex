package ooo.klae.connex.backend.integration;

import static org.mockito.Mockito.reset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Shares recovery wiring while restoring each ceremony's mutable properties and mapper spies. */
@SpringBootTest
abstract class AbstractPrivilegedMfaRecoveryIntegrationTest {
    private static final Duration RECOVERY_WINDOW = Duration.ofMinutes(55);

    @Autowired protected PrivilegedMfaProperties privilegedMfaProperties;
    @MockitoSpyBean protected SpringSessionMapper springSessionMapper;
    @MockitoSpyBean protected UserMapper userMapper;

    private String startupDigest;
    private String startupExpiry;

    /** Resolves the initial recovery window when properties are bound instead of at class loading. */
    @DynamicPropertySource
    static void recoveryProperties(DynamicPropertyRegistry registry) {
        registry.add("connex.security.privileged-mfa.recovery-token-sha256",
                AbstractPrivilegedMfaRecoveryIntegrationTest::startupRecoveryDigest);
        registry.add("connex.security.privileged-mfa.recovery-expires-at",
                () -> Instant.now().plus(RECOVERY_WINDOW).toString());
        registry.add("connex.security.privileged-mfa.recovery-actor",
                () -> "integration-security-operator");
    }

    /** A cached context may outlive its startup recovery window. */
    @BeforeEach
    void refreshRecoveryWindow() {
        startupDigest = privilegedMfaProperties.getRecoveryTokenSha256();
        startupExpiry = privilegedMfaProperties.getRecoveryExpiresAt();
        privilegedMfaProperties.setRecoveryExpiresAt(Instant.now().plus(RECOVERY_WINDOW).toString());
        reset(springSessionMapper, userMapper);
    }

    @AfterEach
    void restoreRecoveryState() {
        privilegedMfaProperties.setRecoveryTokenSha256(startupDigest);
        privilegedMfaProperties.setRecoveryExpiresAt(startupExpiry);
        reset(springSessionMapper, userMapper);
        SecurityContextHolder.clearContext();
    }

    private static String startupRecoveryDigest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("unused-startup-recovery-token".getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
