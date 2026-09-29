package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.PasswordResetToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Exercises the verified email-change service: current-password step-up, uniqueness,
 * single active token, and the ownership-proving request → confirm flow that applies
 * the new address. Also asserts the unverified profile-update path cannot change email.
 *
 * <p>Runs outside the shared rolled-back transaction, with its own organization per test: a refused
 * request records its confirmation failure through the independent audit transaction, which locks
 * the actor and workspace rows the entry references. Under the inherited rolled-back fixture those
 * rows are still uncommitted, so that append would wait on the test's own transaction until the
 * InnoDB timeout and then be discarded.
 */
@Import(EmailChangeServiceTest.CapturingEmailConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmailChangeServiceTest extends AbstractServiceTest {

    @Autowired private EmailChangeService emailChangeService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PasswordResetTokenMapper passwordResetTokenMapper;
    @Autowired private UserService userService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SessionSecurityService sessionSecurityService;
    @Autowired private CapturingEmailChangeService email;

    private static final String PASSWORD = "Str0ng-Pw1!";
    private static final ResolvedClientIp CLIENT_IP = new ResolvedClientIp("1.2.3.4", false);

    @Override
    @BeforeEach
    protected void setUpWorkspaceAndAuthentication() {
        String suffix = unique();
        Organization organization = new Organization();
        organization.setName("Email change " + suffix);
        organization.setSlug("email-change-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Email change " + suffix);
        workspace.setSlug("email-change-" + suffix);
        workspaceMapper.insert(workspace);
        currentUser = newUser();
        authenticateAs(currentUser, workspace.getId());
        userMapper.updatePasswordHash(currentUser.getId(), passwordEncoder.encode(PASSWORD));
        ServletRequestAttributes attributes = assertInstanceOf(ServletRequestAttributes.class,
            RequestContextHolder.currentRequestAttributes());
        Integer epoch = userMapper.currentSessionEpoch(currentUser.getId());
        assertNotNull(epoch);
        sessionSecurityService.stampSessionEpoch(attributes.getRequest(), epoch);
        email.reset();
    }

    @Test
    void requestChange_wrongPassword_forbidden() {
        assertThrows(ForbiddenException.class,
            () -> emailChangeService.requestChange("new_" + unique() + "@example.com", "wrong-password", CLIENT_IP));
        assertNull(email.lastToken, "no verification email should be sent when the password is wrong");
    }

    @Test
    void requestChange_missingSessionEpoch_forbidden() {
        ServletRequestAttributes attributes = assertInstanceOf(ServletRequestAttributes.class,
            RequestContextHolder.currentRequestAttributes());
        attributes.getRequest().getSession().removeAttribute(SessionSecurityService.SESSION_EPOCH_ATTR);

        assertThrows(ForbiddenException.class,
            () -> emailChangeService.requestChange("new_" + unique() + "@example.com", PASSWORD, CLIENT_IP));
        assertNull(email.lastToken);
    }

    @Test
    void requestChange_staleSessionEpoch_forbidden() {
        assertEquals(1, userMapper.bumpSessionEpoch(currentUser.getId()));

        assertThrows(ForbiddenException.class,
            () -> emailChangeService.requestChange("new_" + unique() + "@example.com", PASSWORD, CLIENT_IP));
        assertNull(email.lastToken);
    }

    @Test
    void requestChange_addressAlreadyInUse_rejected() {
        User other = newUser();
        assertThrows(DuplicateResourceException.class,
            () -> emailChangeService.requestChange(other.getEmail(), PASSWORD, CLIENT_IP));
        assertNull(email.lastToken);
    }

    @Test
    void requestChange_sameAddress_rejected() {
        assertThrows(BadRequestException.class,
            () -> emailChangeService.requestChange(currentUser.getEmail(), PASSWORD, CLIENT_IP));
    }

    @Test
    void requestThenConfirm_appliesNewEmail_andTokenIsSingleUse() {
        String newEmail = "new_" + unique() + "@example.com";
        emailChangeService.requestChange(newEmail, PASSWORD, CLIENT_IP);

        assertNotNull(email.lastToken);
        assertEquals(newEmail, email.lastNewEmail);

        emailChangeService.confirmChange(email.lastToken);

        assertEquals(newEmail, userMapper.getUserById(currentUser.getId()).getEmail());
        assertTrue(userMapper.getUserById(currentUser.getId()).isEmailVerified(),
            "redeeming the change token proves control of the new address, so the account is verified");
        assertFalse(emailChangeService.validateToken(email.lastToken), "a consumed token must not be reusable");
    }

    @Test
    void confirmChange_invalidatesOutstandingPasswordResetTokens() {
        String oldMailboxReset = OneTimeTokenDigest.generate();
        String tokenHash = OneTimeTokenDigest.sha256(oldMailboxReset);
        passwordResetTokenMapper.insert(currentUser.getId(),
                tokenHash, "1.2.3.4", 30,
                userMapper.currentSessionEpoch(currentUser.getId()));
        emailChangeService.requestChange("new_" + unique() + "@example.com", PASSWORD, CLIENT_IP);
        assertTrue(passwordResetTokenMapper.existsRedeemableByHash(tokenHash));
        PasswordResetToken pending = passwordResetTokenMapper.findByHash(tokenHash);
        assertNotNull(pending);
        assertNull(pending.getConsumedAt());

        emailChangeService.confirmChange(email.lastToken);

        PasswordResetToken invalidated = passwordResetTokenMapper.findByHash(tokenHash);
        assertNotNull(invalidated);
        assertNotNull(invalidated.getConsumedAt(), "email change must explicitly consume the other token family");
        assertFalse(
                passwordResetTokenMapper.existsRedeemableByHash(tokenHash),
                "moving the mailbox must evict a reset token addressed to the old one");
    }

    @Test
    void confirmChange_invalidToken_rejected() {
        assertThrows(BadRequestException.class, () -> emailChangeService.confirmChange("not-a-real-token"));
    }

    @Test
    void profileUpdate_cannotChangeCredentials() {
        User before = userMapper.getUserById(currentUser.getId());
        assertNotNull(before);
        User edit = new User();
        edit.setUsername(currentUser.getUsername());
        edit.setDisplayName("Renamed " + unique());
        edit.setEmail("hijack_" + unique() + "@example.com");
        edit.setPasswordHash(passwordEncoder.encode("Injected-Credential-Pw1!"));
        edit.setTimezone("UTC");

        userService.update(currentUser.getId(), edit);

        User after = userMapper.getUserById(currentUser.getId());
        assertNotNull(after);
        assertEquals(before.getEmail(), after.getEmail(),
            "email must not change through the unverified profile-update path");
        assertEquals(before.getPasswordHash(), after.getPasswordHash(),
            "password must not change through the unverified profile-update path");
    }

    /**
     * Test double that captures the raw token the service would email.
     */
    @TestConfiguration
    static class CapturingEmailConfig {
        @Bean
        @Primary
        CapturingEmailChangeService capturingEmailChangeService() {
            return new CapturingEmailChangeService();
        }
    }

    static class CapturingEmailChangeService implements EmailChangeEmailService {
        volatile String lastToken;
        volatile String lastNewEmail;
        volatile User lastUser;
        volatile int calls;

        @Override
        public void sendVerificationEmail(User user, String newEmail, String rawToken) {
            this.lastUser = user;
            this.lastNewEmail = newEmail;
            this.lastToken = rawToken;
            this.calls++;
        }

        void reset() {
            lastToken = null;
            lastNewEmail = null;
            lastUser = null;
            calls = 0;
        }
    }
}
