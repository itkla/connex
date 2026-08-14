package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.OneTimeLinkFlowMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.PasswordResetService;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Exercises the complete password-reset fragment exchange and token-free redemption contract. */
@SpringBootTest
class PasswordResetLinkExchangeIntegrationTest {

    private static final String NEW_PASSWORD = "ResetExchangePw2!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private PasswordResetTokenMapper passwordResetTokenMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private PasswordResetService passwordResetService;
    @MockitoSpyBean private OneTimeLinkFlowMapper oneTimeLinkFlowMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void exchangeRedirectsWithoutSecretAndRejectsReplayExpiryAndWrongPurpose() throws Exception {
        User user = newUser();
        String rawToken = token("reset");
        passwordResetTokenMapper.insert(
            user.getId(), OneTimeTokenDigest.sha256(rawToken), "198.51.100.40", 30);

        MvcResult missingBinding = mockMvc.perform(post("/api/auth/reset-password/exchange")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\"}"))
            .andExpect(status().isBadRequest())
            .andReturn();
        assertResponseSecretFree(missingBinding, rawToken);
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM password_reset_token WHERE token_hash = ? AND exchanged_at IS NOT NULL",
            Integer.class,
            OneTimeTokenDigest.sha256(rawToken)));

        Browser browser = bootstrapBrowser();
        MockHttpSession session = browser.session();
        MvcResult exchanged = exchange(
            "/api/auth/reset-password/exchange", rawToken, 303, browser);
        assertEquals("/auth/reset-password", exchanged.getResponse().getHeader(HttpHeaders.LOCATION));
        assertResponseSecretFree(exchanged, rawToken);
        Cookie flowCookie = flowCookie(exchanged, OneTimeLinkFlowCookie.PASSWORD_RESET);

        MvcResult copiedBindingReplay = exchange(
            "/api/auth/reset-password/exchange",
            rawToken,
            400,
            new Browser(
                new MockHttpSession(context.getServletContext()),
                browser.bindingCookie()));
        assertResponseSecretFree(copiedBindingReplay, rawToken);

        jdbcTemplate.update(
            "UPDATE one_time_link_flow SET expires_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 MINUTE) WHERE grant_hash = ?",
            OneTimeTokenDigest.sha256(flowCookie.getValue()));

        MvcResult recovered = exchange(
            "/api/auth/reset-password/exchange", rawToken, 303, browser);
        assertEquals(flowCookie.getValue(),
            flowCookie(recovered, OneTimeLinkFlowCookie.PASSWORD_RESET).getValue());

        mockMvc.perform(get("/api/auth/reset-password/validate")
                .session(session)
                .cookie(flowCookie, browser.bindingCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true));

        MvcResult replay = exchange(
            "/api/auth/reset-password/exchange", rawToken, 400, bootstrapBrowser());
        assertResponseSecretFree(replay, rawToken);

        String expiredToken = token("expired-reset");
        passwordResetTokenMapper.insert(
            user.getId(), OneTimeTokenDigest.sha256(expiredToken), "198.51.100.41", -1);
        MvcResult expired = exchange(
            "/api/auth/reset-password/exchange", expiredToken, 400, bootstrapBrowser());
        assertResponseSecretFree(expired, expiredToken);

        MvcResult wrongPurpose = exchange(
            "/api/invites/exchange", rawToken, 400, bootstrapBrowser());
        assertResponseSecretFree(wrongPurpose, rawToken);

        String tokenHash = OneTimeTokenDigest.sha256(rawToken);
        doThrow(new IllegalStateException("transient database failure"))
            .doCallRealMethod()
            .when(passwordResetService)
            .resetPasswordByHash(eq(tokenHash), eq(NEW_PASSWORD));

        mockMvc.perform(post("/api/auth/reset-password")
                .session(session)
                .cookie(flowCookie, browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isConflict());

        mockMvc.perform(get("/api/auth/reset-password/validate")
                .session(session)
                .cookie(flowCookie, browser.bindingCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true));

        doThrow(new IllegalStateException("flow completion failure"))
            .when(oneTimeLinkFlowMapper)
            .complete(anyString(), anyString());

        mockMvc.perform(post("/api/auth/reset-password")
                .session(session)
                .cookie(flowCookie, browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isConflict());

        reset(oneTimeLinkFlowMapper);

        assertTrue(passwordEncoder.matches(
            "Reset-Old-Pw1!", userMapper.getUserById(user.getId()).getPasswordHash()));

        mockMvc.perform(get("/api/auth/reset-password/validate")
                .session(session)
                .cookie(flowCookie, browser.bindingCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(post("/api/auth/reset-password")
                .session(session)
                .cookie(flowCookie, browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isOk());

        MvcResult consumedReplay = mockMvc.perform(post("/api/auth/reset-password")
                .session(session)
                .cookie(flowCookie, browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isBadRequest())
            .andReturn();
        assertResponseSecretFree(consumedReplay, rawToken);
        assertNoAuditSecret(rawToken);
    }

    private MvcResult exchange(
            String path, String rawToken, int expectedStatus, Browser browser) throws Exception {
        return mockMvc.perform(post(path)
                .session(browser.session())
                .cookie(browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\"}"))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    }

    private Browser bootstrapBrowser() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();
        return new Browser(
            session(result),
            responseCookie(result, OneTimeLinkFlowService.BROWSER_BINDING_COOKIE));
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("reset_exchange_" + suffix);
        user.setDisplayName("Reset Exchange " + suffix);
        user.setEmail("reset_exchange_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("Reset-Old-Pw1!"));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private static String token(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "");
    }

    private static MockHttpSession session(MvcResult result) {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private static Cookie flowCookie(MvcResult result, String name) {
        return responseCookie(result, name);
    }

    private static Cookie responseCookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(name + "="))
            .findFirst()
            .orElseThrow();
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
        String expectedPath = name.equals(OneTimeLinkFlowService.BROWSER_BINDING_COOKIE)
            ? "Path=/api"
            : "Path=/api/auth/reset-password";
        assertTrue(header.contains(expectedPath));
        assertFalse(header.contains("token="));
        String value = header.substring(name.length() + 1, header.indexOf(';'));
        return new Cookie(name, value);
    }

    private static void assertResponseSecretFree(MvcResult result, String rawToken) throws Exception {
        assertFalse(result.getResponse().getContentAsString().contains(rawToken));
        for (String value : result.getResponse().getHeaderNames().stream()
                .flatMap(name -> result.getResponse().getHeaders(name).stream())
                .toList()) {
            assertFalse(value.contains(rawToken));
        }
    }

    private void assertNoAuditSecret(String rawToken) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM audit_log
            WHERE CONCAT_WS('|', action, entity_type, entity_id, actor_id, actor_label,
                target_label, outcome, summary, changes, context, ip_address,
                user_agent, session_id, request_id) LIKE ?
            """, Integer.class, "%" + rawToken + "%");
        assertEquals(0, count);
    }

    private record Browser(MockHttpSession session, Cookie bindingCookie) {
    }
}
