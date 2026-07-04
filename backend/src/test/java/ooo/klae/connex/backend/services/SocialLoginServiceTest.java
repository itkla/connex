package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.FederatedIdentityMapper;

/**
 * Exercises the consumer social-login federation core: a new verified email provisions a
 * fresh passwordless personal account with a non-org-scoped identity, a returning identity
 * signs in, an existing password account demands linking (never auto-linked), and an
 * unverified email is refused.
 */
class SocialLoginServiceTest extends AbstractServiceTest {

    private static final String PROVIDER = "google";
    private static final String ISSUER = "https://accounts.google.com";

    @Autowired private SocialLoginService socialLoginService;
    @Autowired private FederatedIdentityMapper federatedIdentityMapper;

    private User passwordlessUser(String email) {
        User user = new User();
        user.setUsername("social-" + email.replaceAll("[^a-z0-9]", ""));
        user.setDisplayName(email);
        user.setEmail(email);
        user.setEmailVerified(true);
        user.setTimezone("UTC");
        user.setPasswordHash(null);
        userMapper.insert(user);
        return user;
    }

    @Test
    void newVerifiedEmail_provisionsPasswordlessAccountWithOrglessIdentity() {
        String email = "newbie-" + unique() + "@gmail.com";

        SsoLoginResult result = socialLoginService.resolve(PROVIDER, ISSUER, "sub-new", email, true, "New Bie");

        SsoLoginResult.Login login = assertInstanceOf(SsoLoginResult.Login.class, result);
        User created = userMapper.getUserByEmail(email);
        assertNotNull(created, "a new social user must be provisioned");
        assertEquals(created.getId(), login.user().getId());
        assertNull(created.getPassword(), "a social-provisioned account carries no password");

        FederatedIdentity identity = federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-new");
        assertNotNull(identity, "a federated identity must be recorded");
        assertEquals(created.getId(), identity.getUserId());
        assertNull(identity.getOrgId(), "a social identity is not scoped to an organization");
    }

    @Test
    void returningIdentity_signsIn() {
        User existing = passwordlessUser("returning-" + unique() + "@gmail.com");
        FederatedIdentity seed = new FederatedIdentity();
        seed.setUserId(existing.getId());
        seed.setOrgId(null);
        seed.setProvider(PROVIDER);
        seed.setIssuer(ISSUER);
        seed.setExternalSubject("sub-returning");
        federatedIdentityMapper.insert(seed);

        SsoLoginResult result = socialLoginService.resolve(PROVIDER, ISSUER, "sub-returning",
                existing.getEmail(), true, "Returning");

        SsoLoginResult.Login login = assertInstanceOf(SsoLoginResult.Login.class, result);
        assertEquals(existing.getId(), login.user().getId());
    }

    @Test
    void existingPasswordAccount_requiresLinkAndWritesNothing() {
        User password = newUser();
        assertNotNull(password.getPassword(), "the fixture account must have a password");

        SsoLoginResult result = socialLoginService.resolve(PROVIDER, ISSUER, "sub-collision",
                password.getEmail(), true, "Password User");

        SsoLoginResult.LinkRequired link = assertInstanceOf(SsoLoginResult.LinkRequired.class, result);
        assertEquals(password.getId(), link.existingUserId());
        assertNull(link.orgId(), "a social link challenge is not scoped to an organization");
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-collision"),
                "no identity may be minted for a link-required outcome");
    }

    @Test
    void passwordlessAccountWithMatchingEmail_isRefusedNotAutoLinked() {
        User existing = passwordlessUser("shared-" + unique() + "@gmail.com");

        assertThrows(ForbiddenException.class, () -> socialLoginService.resolve(PROVIDER, ISSUER,
                "sub-different", existing.getEmail(), true, "Someone Else"),
                "a passwordless account must never be auto-adopted by a new social identity on an email match");
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-different"),
                "no identity may be linked when refusing a passwordless email collision");
    }

    @Test
    void unverifiedEmail_isRefusedWithNothingWritten() {
        String email = "unverified-" + unique() + "@gmail.com";

        assertThrows(ForbiddenException.class, () ->
                socialLoginService.resolve(PROVIDER, ISSUER, "sub-unverified", email, false, "Unverified"));

        assertNull(userMapper.getUserByEmail(email), "an unverified email must not provision an account");
        assertNull(federatedIdentityMapper.findByProviderIssuerSubject(PROVIDER, ISSUER, "sub-unverified"),
                "an unverified email must not mint an identity");
    }
}
