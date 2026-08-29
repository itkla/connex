package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AccountSessionRevocationService;
import ooo.klae.connex.backend.session.AccountSessionIndex;

/**
 * Pins the session index to the immutable account id against the real store.
 *
 * <p>Nothing else in the suite exercises principal-name to session resolution, which is why the
 * original defect — revocation enumerating by a mutable username — passed every test. The mock-based
 * coverage could not catch it: {@code User} is equal by id, so a renamed principal still matched a
 * stubbed {@code getAllSessions(user, …)}.
 */
@SpringBootTest
class AccountSessionIndexIntegrationTest {

    @Autowired private FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    @Autowired private SessionRegistry sessionRegistry;
    @Autowired private AccountSessionRevocationService accountSessionRevocationService;
    @Autowired private SessionRepository<? extends Session> sessionStore;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void sessionIsFiledUnderTheAccountIdNotTheUsername() {
        User account = newUser();
        String sessionId = saveAuthenticatedSession(account);

        assertEquals("uid:" + account.getId(), storedPrincipalName(sessionId));
        assertTrue(sessionRepository.findByPrincipalName(account.getUsername()).isEmpty());
        assertEquals(1, sessionRepository.findByPrincipalName("uid:" + account.getId()).size());
    }

    @Test
    void renamingTheAccountLeavesTheSessionRevocable() {
        User account = newUser();
        String sessionId = saveAuthenticatedSession(account);

        account.setUsername(account.getUsername() + "_renamed");
        userMapper.update(account);

        accountSessionRevocationService.expireAll(account.getId());

        assertTrue(sessionRegistry.getAllSessions(
                new AccountSessionIndex(account.getId()), false).isEmpty());
        assertEquals("uid:" + account.getId(), storedPrincipalName(sessionId));
    }

    @Test
    void aSessionWithoutAConnexPrincipalIsNotIndexed() {
        String sessionId = save(sessionStore,
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                        "an-identity-provider-subject", null)));

        assertNull(storedPrincipalName(sessionId));
        assertTrue(sessionRepository.findByPrincipalName("an-identity-provider-subject").isEmpty());
    }

    @Test
    void expireAllExceptRetainsTheNamedSession() {
        User account = newUser();
        String retained = saveAuthenticatedSession(account);
        String other = saveAuthenticatedSession(account);

        accountSessionRevocationService.expireAllExcept(account.getId(), retained);

        List<SessionInformation> sessions = sessionRegistry.getAllSessions(
                new AccountSessionIndex(account.getId()), true);
        assertEquals(2, sessions.size());
        assertTrue(sessions.stream()
                .filter(session -> session.getSessionId().equals(other))
                .allMatch(SessionInformation::isExpired));
        assertTrue(sessions.stream()
                .filter(session -> session.getSessionId().equals(retained))
                .noneMatch(SessionInformation::isExpired));
    }

    private String saveAuthenticatedSession(User account) {
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(account, null, account.getAuthorities()));
        return save(sessionStore,
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    }

    private static <S extends Session> String save(
            SessionRepository<S> repository, String name, Object value) {
        S session = repository.createSession();
        session.setAttribute(name, value);
        repository.save(session);
        return session.getId();
    }

    private String storedPrincipalName(String sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT PRINCIPAL_NAME FROM SPRING_SESSION WHERE SESSION_ID = ?",
                String.class, sessionId);
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("session_index_" + suffix);
        user.setDisplayName("Session Index " + suffix);
        user.setEmail("session_index_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("Session-Index-Pw1!"));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }
}
