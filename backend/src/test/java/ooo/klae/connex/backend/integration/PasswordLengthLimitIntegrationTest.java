package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Pins the published password length to the 72 bytes the BCrypt encoder stores whole. A longer
 * policy-conforming password is a field error at the API boundary rather than an encoder failure
 * surfacing as a server error, and a password of exactly 72 characters is stored without truncation.
 */
@SpringBootTest
class PasswordLengthLimitIntegrationTest {

    private static final String OLD_PASSWORD = "Length-Old-Pw1!";
    private static final String LENGTH_MESSAGE = "Password must be between 8 and 72 characters";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private PasswordResetTokenMapper passwordResetTokenMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private User user;
    private MockHttpSession session;
    private Cookie binding;
    private Cookie grant;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user = new User();
        user.setUsername("length_limit_" + suffix);
        user.setDisplayName("Length Limit " + suffix);
        user.setEmail("length_limit_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(OLD_PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        MvcResult bootstrap = mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();
        if (!(bootstrap.getRequest().getSession(false) instanceof MockHttpSession browserSession)) {
            throw new IllegalStateException("Expected a browser session");
        }
        session = browserSession;
        binding = responseCookie(bootstrap, OneTimeLinkFlowService.BROWSER_BINDING_COOKIE);
        String rawToken = OneTimeTokenDigest.generate();
        passwordResetTokenMapper.insert(user.getId(), OneTimeTokenDigest.sha256(rawToken), "198.51.100.72", 30,
            userMapper.currentSessionEpoch(user.getId()));
        MvcResult exchanged = mockMvc.perform(post("/api/auth/reset-password/exchange")
                .session(session)
                .cookie(binding)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\"}"))
            .andExpect(status().isSeeOther())
            .andReturn();
        grant = responseCookie(exchanged, OneTimeLinkFlowCookie.PASSWORD_RESET);
    }

    @Test
    void registrationOverSeventyTwoCharactersIsAFieldErrorNotAServerError() throws Exception {
        String username = "length_register_" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/auth/register")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"displayName\":\"Length Register\","
                    + "\"email\":\"" + username + "@example.com\",\"password\":\"" + password(73) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.password").value(LENGTH_MESSAGE));

        assertNull(userMapper.getUserByUsername(username));
    }

    @Test
    void resetOverSeventyTwoCharactersIsAFieldErrorAndLeavesTheGrantRedeemable() throws Exception {
        reset(password(73))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.newPassword").value(LENGTH_MESSAGE));

        assertTrue(passwordEncoder.matches(OLD_PASSWORD, storedHash()));
        mockMvc.perform(get("/api/auth/reset-password/validate")
                .session(session)
                .cookie(binding, grant))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void resetOfExactlySeventyTwoCharactersIsStoredWhole() throws Exception {
        String password = password(72);

        reset(password).andExpect(status().isOk());

        String hash = storedHash();
        assertTrue(passwordEncoder.matches(password, hash));
        assertFalse(passwordEncoder.matches(password.substring(0, 71) + "b", hash));
    }

    private ResultActions reset(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/reset-password")
            .session(session)
            .cookie(binding, grant)
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"newPassword\":\"" + password + "\"}"));
    }

    private String storedHash() {
        User stored = userMapper.getUserById(user.getId());
        assertNotNull(stored);
        return stored.getPasswordHash();
    }

    private static String password(int length) {
        return "Aa1!" + "a".repeat(length - 4);
    }

    private static Cookie responseCookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(name + "="))
            .findFirst()
            .orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }
}
