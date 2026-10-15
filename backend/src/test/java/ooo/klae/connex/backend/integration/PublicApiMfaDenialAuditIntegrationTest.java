package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditIntegrityService;
import ooo.klae.connex.backend.services.DenialAuditRateLimiter;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.PrivilegedMfaStanding;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Proves account-scoped denial evidence through the routed, stateless public security chain. */
@SpringBootTest(properties = {
    "connex.public-api.enabled=true",
    "connex.security.privileged-mfa.enforced=true",
    "connex.security.privileged-mfa.attestation-enforced=true",
    "connex.security.trusted-proxies=10.0.0.1",
    "connex.tenancy.routing.mode=catalog-per-placement"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class PublicApiMfaDenialAuditIntegrationTest {
    private static final String PASSWORD = "Public-Api-Mfa-Pw1!";
    private static final String ACTION = "auth.mfa.policy.denied";
    private static final String ADDRESS = "192.0.2.42";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private PrivilegedAccountService privilegedAccountService;
    @MockitoSpyBean private AuditIntegrityService auditIntegrityService;
    @MockitoSpyBean private DenialAuditRateLimiter denialAuditRateLimiter;

    private PublicApiFixtureSupport fixtures;
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void routingProperties(DynamicPropertyRegistry registry) {
        registry.add("connex.tenancy.routing.default-catalog", PublicApiMfaDenialAuditIntegrationTest::controlCatalog);
    }

    @BeforeEach
    void setUp() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        fixtures = new PublicApiFixtureSupport(organizationMapper, workspaceMapper, userMapper,
            passwordEncoder, jdbcTemplate, PASSWORD);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    }

    @AfterEach
    void cleanUp() {
        tenantContext.clear();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        fixtures.cleanUp(false);
    }

    @ParameterizedTest
    @EnumSource(value = PrivilegedMfaStanding.class, names = {"ENROLLMENT_REQUIRED", "ATTESTATION_REQUIRED"})
    void repeatedRefusalsWriteOneAccountAuditInTheControlCatalog(PrivilegedMfaStanding standing)
            throws Exception {
        Confined fixture = confined(standing);

        for (int attempt = 0; attempt < 3; attempt++) {
            refuse(fixture, standing, ADDRESS, "198.51.100.99");
        }

        assertAccountAudits(fixture, reason(standing), List.of(ADDRESS));
    }

    @ParameterizedTest
    @EnumSource(value = PrivilegedMfaStanding.class, names = {"ENROLLMENT_REQUIRED", "ATTESTATION_REQUIRED"})
    void appendFailurePreservesTheRefusalAndReleasesAdmission(PrivilegedMfaStanding standing,
            CapturedOutput output) throws Exception {
        Confined fixture = confined(standing);
        doThrow(new IllegalStateException("Simulated denial append failure"))
            .doCallRealMethod().when(auditIntegrityService).appendIndependent(any(AuditLog.class));

        refuse(fixture, standing, ADDRESS, null);

        assertAccountAudits(fixture, reason(standing), List.of());
        verify(denialAuditRateLimiter, times(1)).release(any(DenialAuditRateLimiter.Admission.class));
        assertTrue(output.getAll().contains("Failed to record access denial action=" + ACTION));
        assertFalse(output.getAll().contains(fixture.token()));

        refuse(fixture, standing, ADDRESS, null);
        refuse(fixture, standing, ADDRESS, null);

        assertAccountAudits(fixture, reason(standing), List.of(ADDRESS));
    }

    @Test
    void aChangedConfinementReasonGetsItsOwnAdmissionInsideTheWindow() throws Exception {
        Confined fixture = confined(PrivilegedMfaStanding.ENROLLMENT_REQUIRED);
        refuse(fixture, PrivilegedMfaStanding.ENROLLMENT_REQUIRED, ADDRESS, null);
        PublicApiTestSecuritySupport.enrollPasskey(jdbcTemplate, fixture.user());
        assertEquals(PrivilegedMfaStanding.ATTESTATION_REQUIRED,
            privilegedAccountService.enforcedStanding(fixture.user().getId()));

        refuse(fixture, PrivilegedMfaStanding.ATTESTATION_REQUIRED, ADDRESS, null);
        refuse(fixture, PrivilegedMfaStanding.ATTESTATION_REQUIRED, ADDRESS, null);

        assertAccountAudits(fixture, "enrollment_required", List.of(ADDRESS));
        assertAccountAudits(fixture, "attestation_required", List.of(ADDRESS));
        assertEquals(2, auditCount(controlCatalog(), fixture));
    }

    @Test
    void trustedProxyClientsHaveSeparateAddressWindows() throws Exception {
        Confined fixture = confined(PrivilegedMfaStanding.ENROLLMENT_REQUIRED);
        for (String address : List.of(ADDRESS, ADDRESS, "192.0.2.43", "192.0.2.43")) {
            refuse(fixture, PrivilegedMfaStanding.ENROLLMENT_REQUIRED, "10.0.0.1", address);
        }

        assertAccountAudits(fixture, "enrollment_required", List.of(ADDRESS, "192.0.2.43"));
    }

    @ParameterizedTest
    @EnumSource(value = PrivilegedMfaStanding.class, names = {"ENROLLMENT_REQUIRED", "ATTESTATION_REQUIRED"})
    void firewallRejectedForwardedHeadersStillWriteOneAuditKeyedOnThePeer(PrivilegedMfaStanding standing)
            throws Exception {
        Confined fixture = confined(standing);

        refuse(fixture, standing, "10.0.0.1", "198.51.100.1\u0085");
        assertAccountAudits(fixture, reason(standing), List.of("10.0.0.1"));

        refuse(fixture, standing, "10.0.0.1", "198.51.100.2\u0085");
        refuse(fixture, standing, "10.0.0.1", null);

        assertAccountAudits(fixture, reason(standing), List.of("10.0.0.1"));
    }

    @Test
    void untrustedForwardingHeadersCannotReopenTheAddressWindow() throws Exception {
        Confined fixture = confined(PrivilegedMfaStanding.ENROLLMENT_REQUIRED);
        refuse(fixture, PrivilegedMfaStanding.ENROLLMENT_REQUIRED, ADDRESS, "198.51.100.1");
        refuse(fixture, PrivilegedMfaStanding.ENROLLMENT_REQUIRED, ADDRESS, "198.51.100.2");

        assertAccountAudits(fixture, "enrollment_required", List.of(ADDRESS));
    }

    private Confined confined(PrivilegedMfaStanding standing) throws Exception {
        Workspace workspace = fixtures.newWorkspace("mfa-audit");
        User user = fixtures.newMember(workspace, "owner", "mfa-audit");
        int passkey = PublicApiTestSecuritySupport.enrollApprovedPasskey(jdbcTemplate, user);
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", user.getUsername(), "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session);
        PublicApiTestSecuritySupport.stepUpApproved(jdbcTemplate, session, user.getId());
        MvcResult issued = mockMvc.perform(post("/api/api-credentials")
                .session(session).header("X-Workspace-Id", workspace.getId()).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"MFA audit test\",\"scopes\":[\"crm.read\"],\"expiresAt\":\"2099-01-01T00:00:00\"}"))
            .andExpect(status().isCreated()).andReturn();
        String token = objectMapper.readTree(issued.getResponse().getContentAsString()).path("token").textValue();
        tenantContext.clear();
        if (standing == PrivilegedMfaStanding.ENROLLMENT_REQUIRED) {
            jdbcTemplate.update("DELETE FROM webauthn_user_entity WHERE user_id = ?", user.getId());
        } else {
            jdbcTemplate.update("UPDATE webauthn_credential SET privileged_assurance = NULL,"
                + " privileged_assured_at = NULL WHERE id = ?", passkey);
        }
        assertEquals(standing, privilegedAccountService.enforcedStanding(user.getId()));
        String catalog = fixtures.createScratchCatalog("mfa");
        for (String table : List.of("audit_log", "audit_log_integrity_head")) {
            jdbcTemplate.execute("CREATE TABLE `" + catalog + "`.`" + table + "` LIKE `"
                + controlCatalog() + "`.`" + table + "`");
        }
        jdbcTemplate.update("INSERT INTO org_placement (org_id, placement_mode, database_handle)"
            + " VALUES (?, 'dedicated_database', ?)", workspace.getOrgId(), catalog);
        return new Confined(user, token, catalog);
    }

    private void refuse(Confined fixture, PrivilegedMfaStanding standing, String peer, String forwarded)
            throws Exception {
        var request = get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.token())
            .with(servletRequest -> {
                servletRequest.setRemoteAddr(peer);
                return servletRequest;
            });
        if (forwarded != null) {
            request.header("X-Forwarded-For", forwarded);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(1, body.size());
        JsonNode error = body.path("error");
        assertEquals(3, error.size());
        assertEquals("privileged_mfa_" + reason(standing), error.path("code").textValue());
        assertEquals(standing == PrivilegedMfaStanding.ENROLLMENT_REQUIRED
            ? "A passkey must be enrolled before this privileged account can continue"
            : "A passkey must be approved before this privileged account can continue", error.path("message").textValue());
        assertTrue(error.path("request_id").isTextual());
        assertFalse(error.path("request_id").textValue().isBlank());
        assertFalse(tenantContext.isResolved());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private void assertAccountAudits(Confined fixture, String reason, List<String> addresses) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM `" + controlCatalog()
            + "`.audit_log WHERE action = ? AND entity_type = 'user' AND entity_id = ?"
            + " AND JSON_UNQUOTE(JSON_EXTRACT(context, '$.error')) = ? ORDER BY ip_address",
            ACTION, fixture.user().getId(), reason);
        assertEquals(addresses.size(), rows.size());
        assertEquals(addresses, rows.stream().map(row -> (String) row.get("ip_address")).toList());
        for (Map<String, Object> row : rows) {
            assertNull(row.get("workspace_id"));
            assertNull(row.get("org_id"));
            assertEquals(fixture.user().getId(), row.get("actor_id"));
            assertEquals("failure", row.get("outcome"));
            assertTrue(((String) row.get("summary")).startsWith("Public API "));
            assertEquals("system", row.get("chain_scope_type"));
            assertEquals(0, ((Number) row.get("chain_scope_id")).intValue());
        }
        assertEquals(0, auditCount(fixture.catalog(), fixture));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + fixture.catalog()
            + "`.audit_log_integrity_head", Integer.class));
    }

    private int auditCount(String catalog, Confined fixture) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + catalog
            + "`.audit_log WHERE action = ? AND entity_id = ?", Integer.class, ACTION, fixture.user().getId());
    }

    private static String reason(PrivilegedMfaStanding standing) {
        return standing == PrivilegedMfaStanding.ENROLLMENT_REQUIRED ? "enrollment_required" : "attestation_required";
    }

    private static String controlCatalog() {
        String catalog = TenantRoutingConfig.databaseFromJdbcUrl(System.getenv("CONNEX_DB_URL"));
        if (catalog == null || !catalog.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalStateException("A valid test control catalog must be configured");
        }
        return catalog;
    }

    private record Confined(User user, String token, String catalog) {
    }
}
