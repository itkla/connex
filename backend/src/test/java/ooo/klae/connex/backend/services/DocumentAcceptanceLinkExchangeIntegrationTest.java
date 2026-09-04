package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.config.DocumentAcceptanceAdmissionFilter;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.dto.DocumentDeliveryDto;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Drives the fragment exchange and grant-only document-acceptance contract through the real
 * admission filter, security filter chain, and interceptors against committed delivery fixtures:
 * the raw bearer is accepted only in the exchange body, every other request is keyed on the
 * purpose-bound grant cookie, and path or query tokens are ignored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DocumentAcceptanceLinkExchangeIntegrationTest
        extends AbstractCommittedDocumentDeliveryServiceTest {
    private static final String UNAVAILABLE_BODY =
        "{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Document link is no longer available\"}";
    private static final String PASSWORD = "Acceptance-Exchange-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private FilterRegistrationBean<DocumentAcceptanceAdmissionFilter> admissionFilter;
    @Autowired private OneTimeLinkFlowService flowService;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    /**
     * Detaches any request context a previous MockMvc exchange left bound before the committed
     * fixture runs, because {@code TenantScopeInterceptor} only refuses an unresolved scope on a
     * request thread and the fixture seeds its workspace role before it authenticates.
     */
    @Override
    @BeforeEach
    protected void setUpWorkspaceAndAuthentication() {
        clearRequestContext();
        super.setUpWorkspaceAndAuthentication();
    }

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(admissionFilter.getFilter(), springSecurityFilterChain)
            .build();
    }

    /** Restores the fixture's own request context so the committed teardown stays workspace-scoped. */
    @AfterEach
    void restoreFixtureRequestContext() {
        authenticateAs(currentUser, workspace.getId());
    }

    @Test
    void exchangeIssuesRoutedGrantWithoutSecret() throws Exception {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());
        Browser browser = bootstrapBrowser();

        MvcResult exchanged = exchange(token, 303, browser);

        assertEquals("/document-acceptance", exchanged.getResponse().getHeader(HttpHeaders.LOCATION));
        assertResponseSecretFree(exchanged, token);
        Cookie grant = flowCookie(exchanged);
        assertTrue(exchanged.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .anyMatch(value -> value.startsWith(OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE + "=")
                && value.contains("Max-Age=3600")));
        Map<String, Object> flow = jdbcTemplate.queryForMap(
            "SELECT purpose, routing_workspace_id, source_token_hash FROM one_time_link_flow "
                + "WHERE grant_hash = ?",
            OneTimeTokenDigest.sha256(grant.getValue()));
        assertEquals(Purpose.DOCUMENT_ACCEPTANCE.name(), flow.get("purpose"));
        assertEquals(workspace.getId(), ((Number) flow.get("routing_workspace_id")).intValue());
        assertEquals(sha256(token), flow.get("source_token_hash"));

        MvcResult preview = mockMvc.perform(get("/api/document-acceptance")
                .session(browser.session())
                .cookie(grant, browser.bindingCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dealName").value(fixture.deal().getName()))
            .andExpect(jsonPath("$.workspaceName").value(workspace.getName()))
            .andExpect(jsonPath("$.recipientStatus").value("pending"))
            .andReturn();
        assertResponseSecretFree(preview, token);
        assertEquals(0, countEvents(delivery.id(), "viewed"));
        assertNoAuditSecret(token);
    }

    @Test
    void queryAndPathTokensAreIgnoredWithAndWithoutAGrant() throws Exception {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());
        DocumentFixture otherFixture = finalDocument();
        DocumentDeliveryDto otherDelivery = send(otherFixture, signer("other@example.test", 1));
        String otherToken = installToken(otherDelivery.recipients().getFirst().id());
        Browser browser = bootstrapBrowser();
        MockHttpSession session = browser.session();

        MvcResult queryToken = mockMvc.perform(get("/api/document-acceptance")
                .session(session)
                .queryParam("token", token))
            .andExpect(status().isNotFound())
            .andReturn();
        MvcResult pathToken = mockMvc.perform(get("/api/document-acceptance/" + token)
                .session(session))
            .andExpect(status().isNotFound())
            .andReturn();
        MvcResult pathDecision = mockMvc.perform(post("/api/document-acceptance/" + token + "/accept")
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typedName\":\"Signer\"}"))
            .andExpect(status().isNotFound())
            .andReturn();
        MvcResult grantlessDecision = mockMvc.perform(post("/api/document-acceptance/accept")
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typedName\":\"Signer\"}"))
            .andExpect(status().isNotFound())
            .andReturn();

        for (MvcResult result : new MvcResult[] {queryToken, pathToken, pathDecision, grantlessDecision}) {
            assertEquals(UNAVAILABLE_BODY, result.getResponse().getContentAsString());
            assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
            assertResponseSecretFree(result, token);
        }
        Cookie grant = flowCookie(exchange(otherToken, 303, browser));
        mockMvc.perform(get("/api/document-acceptance")
                .session(session)
                .cookie(grant, browser.bindingCookie())
                .queryParam("token", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dealName").value(otherFixture.deal().getName()));

        assertEquals("pending", recipientStatus(delivery));
        assertEquals(0, countEvents(delivery.id(), "viewed"));
        assertEquals(0, countEvents(delivery.id(), "completed"));
    }

    @Test
    void foreignBrowserExpiryAndWrongPurposeFailClosed() throws Exception {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());
        Browser browser = bootstrapBrowser();
        Cookie grant = flowCookie(exchange(token, 303, browser));

        mockMvc.perform(get("/api/document-acceptance")
                .session(new MockHttpSession(context.getServletContext()))
                .cookie(grant, browser.bindingCookie()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/document-acceptance")
                .session(browser.session())
                .cookie(grant))
            .andExpect(status().isNotFound());

        jdbcTemplate.update(
            "UPDATE one_time_link_flow SET expires_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 MINUTE) "
                + "WHERE grant_hash = ?",
            OneTimeTokenDigest.sha256(grant.getValue()));
        mockMvc.perform(get("/api/document-acceptance")
                .session(browser.session())
                .cookie(grant, browser.bindingCookie()))
            .andExpect(status().isNotFound());

        Cookie renewed = flowCookie(exchange(token, 303, browser));
        assertEquals(grant.getValue(), renewed.getValue());
        mockMvc.perform(get("/api/document-acceptance")
                .session(browser.session())
                .cookie(renewed, browser.bindingCookie()))
            .andExpect(status().isOk());

        MockHttpServletRequest ownerRequest = new MockHttpServletRequest();
        ownerRequest.setSession(browser.session());
        ownerRequest.setCookies(browser.bindingCookie());
        String inviteGrant = flowService.issue(
            ownerRequest, Purpose.WORKSPACE_INVITE, sha256(token)).value();
        mockMvc.perform(get("/api/document-acceptance")
                .session(browser.session())
                .cookie(
                    new Cookie(OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, inviteGrant),
                    browser.bindingCookie()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/delivery/unsubscribe")
                .session(browser.session())
                .cookie(
                    new Cookie(OneTimeLinkFlowCookie.DELIVERY_UNSUBSCRIBE, renewed.getValue()),
                    browser.bindingCookie()))
            .andExpect(status().isBadRequest());
        assertEquals("pending", recipientStatus(delivery));
    }

    @Test
    void decidedLinkCannotBeExchangedAgainButRepeatIsIdempotent() throws Exception {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());
        Browser browser = bootstrapBrowser();
        Cookie grant = flowCookie(exchange(token, 303, browser));

        mockMvc.perform(post("/api/document-acceptance/viewed")
                .session(browser.session())
                .cookie(grant, browser.bindingCookie())
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recipientStatus").value("viewed"));
        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult accepted = mockMvc.perform(post("/api/document-acceptance/accept")
                    .session(browser.session())
                    .cookie(grant, browser.bindingCookie())
                    .with(csrf().asHeader())
                    .header("User-Agent", "acceptance-exchange-agent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"typedName\":\"Rina Sato\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientStatus").value("completed"))
                .andExpect(jsonPath("$.completed").value(true))
                .andReturn();
            assertResponseSecretFree(accepted, token);
        }

        assertEquals(1, countEvents(delivery.id(), "completed"));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? "
                + "AND summary = 'Repeated a completed document acceptance'",
            Integer.class,
            workspace.getId()));
        MvcResult decided = exchange(token, 404, browser);
        assertEquals(UNAVAILABLE_BODY, decided.getResponse().getContentAsString());
        assertResponseSecretFree(decided, token);
        assertNoAuditSecret(token);
    }

    @Test
    void acceptWithoutCsrfHeaderIsForbidden() throws Exception {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());
        Browser browser = bootstrapBrowser();
        Cookie grant = flowCookie(exchange(token, 303, browser));

        mockMvc.perform(post("/api/document-acceptance/accept")
                .session(browser.session())
                .cookie(grant, browser.bindingCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typedName\":\"Rina Sato\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/document-acceptance/exchange")
                .session(browser.session())
                .cookie(browser.bindingCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isForbidden());

        assertEquals("pending", recipientStatus(delivery));
        assertEquals(0, countEvents(delivery.id(), "completed"));
    }

    @Test
    void exchangeIsThrottledPerTokenAndSource() throws Exception {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());
        Browser browser = bootstrapBrowser();
        int previousLimit = signatureProperties.getMaxRequestsPerToken();
        signatureProperties.setMaxRequestsPerToken(1);
        try {
            exchange(token, 303, browser);
            MvcResult throttled = exchange(token, 429, browser);
            assertEquals(
                "{\"code\":\"TOO_MANY_REQUESTS\",\"message\":"
                    + "\"Too many document-link requests. Please try again later.\"}",
                throttled.getResponse().getContentAsString());
            assertResponseSecretFree(throttled, token);
        } finally {
            signatureProperties.setMaxRequestsPerToken(previousLimit);
        }
    }

    @Test
    void signedInMemberWithAWorkspaceCookieStillReachesThePreview() throws Exception {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());
        demoteFixtureActorToPlainMember();
        userMapper.updatePasswordHash(currentUser.getId(), passwordEncoder.encode(PASSWORD));
        Browser anonymous = bootstrapBrowser();
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .session(anonymous.session())
                .cookie(anonymous.bindingCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + currentUser.getUsername()
                    + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session);
        Browser browser = new Browser(session, anonymous.bindingCookie());
        Cookie grant = flowCookie(exchange(token, 303, browser));

        mockMvc.perform(get("/api/document-acceptance")
                .session(session)
                .cookie(
                    grant,
                    browser.bindingCookie(),
                    new Cookie(WorkspaceCookie.NAME, Integer.toString(workspace.getId()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dealName").value(fixture.deal().getName()));
    }

    private void demoteFixtureActorToPlainMember() {
        jdbcTemplate.update(
            "DELETE wrp FROM workspace_role_permission wrp "
                + "JOIN workspace_member wm ON wm.role_id = wrp.workspace_role_id "
                + "WHERE wm.workspace_id = ? AND wm.user_id = ?",
            workspace.getId(),
            currentUser.getId());
    }

    private MvcResult exchange(String rawToken, int expectedStatus, Browser browser)
            throws Exception {
        return mockMvc.perform(post("/api/document-acceptance/exchange")
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
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return new Browser(
            session,
            responseCookie(result, OneTimeLinkFlowService.BROWSER_BINDING_COOKIE, "Path=/api"));
    }

    private static Cookie flowCookie(MvcResult result) {
        return responseCookie(
            result, OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, "Path=/api/document-acceptance");
    }

    private static Cookie responseCookie(MvcResult result, String name, String expectedPath) {
        String header = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(name + "="))
            .findFirst()
            .orElseThrow();
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
        assertTrue(header.contains(expectedPath + ";") || header.endsWith(expectedPath), header);
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

    private String recipientStatus(DocumentDeliveryDto delivery) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery_recipient WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.recipients().getFirst().id());
    }

    private int countEvents(int deliveryId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_event WHERE workspace_id = ? "
                + "AND delivery_id = ? AND event_type = ?",
            Integer.class,
            workspace.getId(),
            deliveryId,
            eventType);
    }

    private record Browser(MockHttpSession session, Cookie bindingCookie) {
    }
}
