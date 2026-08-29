package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SessionSecurityService;

/**
 * Proves the fail-open revocation race is closed (#1477).
 *
 * <p>The race: a login reads the account, a revocation bumps and enumerates, and only then is the
 * login's session row written — so the enumeration never sees it. The epoch makes that miss
 * harmless, because the session carries the value it authenticated against and no longer matches.
 */
@SpringBootTest
class SessionEpochRaceIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private SessionSecurityService sessionSecurityService;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void aSessionEstablishedBeforeABumpNoLongerMatchesTheAccount() {
        User account = newUser();
        MockHttpServletRequest request = authenticate(account);

        Integer stamped = sessionSecurityService.sessionEpoch(request.getSession(false));
        assertNotNull(stamped);
        assertEquals(userMapper.currentSessionEpoch(account.getId()), stamped);

        userMapper.bumpSessionEpoch(account.getId());

        assertEquals(stamped + 1, userMapper.currentSessionEpoch(account.getId()));
        assertEquals(stamped, sessionSecurityService.sessionEpoch(request.getSession(false)));
    }

    /**
     * The stamp must come from the principal whose credential was verified, not from a row re-read
     * afterwards. This is the assertion that fails if the source regresses to {@code refreshedUser}:
     * the bump lands between the two reads, so a re-read would stamp the post-bump value and the
     * raced session would look current forever.
     */
    @Test
    void theStampIsTheEpochTheCredentialWasVerifiedAgainst() {
        User account = newUser();
        User verifiedPrincipal = userMapper.getUserByUsername(account.getUsername());
        assertNotNull(verifiedPrincipal.getSessionEpoch());
        int atCredentialCheck = verifiedPrincipal.getSessionEpoch();

        userMapper.bumpSessionEpoch(account.getId());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        authService.establishAuthenticatedSession(
                verifiedPrincipal, request, new MockHttpServletResponse());

        assertEquals(atCredentialCheck,
                sessionSecurityService.sessionEpoch(request.getSession(false)));
        assertEquals(atCredentialCheck + 1, userMapper.currentSessionEpoch(account.getId()));
    }

    @Test
    void anAccountStartsAtEpochZeroAndAnUnauthenticatedSessionCarriesNoStamp() {
        User account = newUser();

        assertEquals(0, userMapper.currentSessionEpoch(account.getId()));
        assertNull(sessionSecurityService.sessionEpoch(new MockHttpSession()));
    }

    private MockHttpServletRequest authenticate(User account) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        authService.establishAuthenticatedSession(
                userMapper.getUserByUsername(account.getUsername()),
                request,
                new MockHttpServletResponse());
        return request;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("epoch_" + suffix);
        user.setDisplayName("Epoch " + suffix);
        user.setEmail("epoch_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("Epoch-Test-Pw1!"));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }
}
