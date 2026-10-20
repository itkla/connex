package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import jakarta.servlet.Filter;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.dto.DuplicateReviewDecisionRequest;
import ooo.klae.connex.backend.dto.DuplicateReviewItemRow;
import ooo.klae.connex.backend.mappers.DuplicateReviewMapper;
import ooo.klae.connex.backend.services.DuplicateReviewService;
import ooo.klae.connex.backend.services.IdentityBackfillRunner;
import ooo.klae.connex.backend.services.LegacyWorkflowBackfillRunner;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

/** Exercises real duplicate decisions and authorization against a tenant catalog containing no control tables. */
@SpringBootTest
class DuplicateReviewPlaneRoutingIntegrationTest {
    private static final String PASSWORD = "Visibility-Plane-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter securityFilterChain;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private DuplicateReviewService service;
    @Autowired private DuplicateReviewMapper mapper;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private IdentityBackfillRunner identityBackfillRunner;
    @MockitoBean private LegacyWorkflowBackfillRunner legacyWorkflowBackfillRunner;

    private RecordVisibilityPlaneFixture fixture;
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void routingProperties(DynamicPropertyRegistry registry) {
        registry.add("connex.tenancy.routing.mode", () -> TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        registry.add("connex.tenancy.routing.default-catalog",
            () -> TenantRoutingConfig.databaseFromJdbcUrl(System.getenv("CONNEX_DB_URL")));
    }

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(false, true);
        fixture.controlJdbc.update("UPDATE app_user SET password_hash = ? WHERE id = ?",
            passwordEncoder.encode(PASSWORD), fixture.userId);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void listAndSummaryPreserveCountsAndPagesWithoutControlTables() throws Exception {
        assertNoControlTables();
        List<DuplicateReviewItemRow> expected = fixture.legacy.selectList(
            "DuplicateReviewMapper.findVisibleItems", fixture.parameters());
        long expectedCount = fixture.legacy.count("DuplicateReviewMapper.countVisibleItems", fixture.parameters());
        fixture.controlJdbc.update("UPDATE company SET name = 'Default Catalog Decoy' WHERE workspace_id = ?",
            fixture.workspaceId);
        fixture.controlJdbc.update("DELETE FROM duplicate_review_decision WHERE workspace_id = ? "
            + "AND (record_type = 'company' OR id = ?)", fixture.workspaceId, fixture.pairIds.getFirst());
        assertEquals(7, fixture.legacy.count("DuplicateReviewMapper.countVisibleItems", fixture.parameters()));
        assertEquals(6, fixture.controlJdbc.queryForObject("SELECT COUNT(*) FROM duplicate_review_decision "
            + "WHERE workspace_id = ? AND record_type = 'person' AND state = 'open'", Integer.class, fixture.workspaceId));
        assertEquals(0, fixture.controlJdbc.queryForObject("SELECT COUNT(*) FROM duplicate_review_decision "
            + "WHERE workspace_id = ? AND record_type = 'company' AND state = 'open'", Integer.class, fixture.workspaceId));
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        assertEquals(9, mapper.countVisibleItems(fixture.workspaceId, null, null, null, 20));
        assertEquals(expectedCount,
            mapper.countVisibleItems(fixture.workspaceId, null, null, null, 20));
        assertEquals(8, mapper.countVisibleItems(fixture.workspaceId, null, null, "open", 20));
        assertEquals(7, mapper.countVisibleItems(fixture.workspaceId, "person", null, "open", 20));
        assertEquals(1, mapper.countVisibleItems(fixture.workspaceId, "company", null, "open", 20));
        assertEquals(expected, mapper.findVisibleItems(fixture.workspaceId, scope, null, null, null, 100, 0, 20));
        for (DuplicateReviewItemRow row : expected) {
            assertEquals(row, mapper.findVisibleItemById(fixture.workspaceId, scope, row.getId(), 20));
        }
        fixture.tenantContext.clear();
        MockHttpSession session = login();
        mockMvc.perform(get("/api/duplicate-reviews").header("X-Workspace-Id", fixture.workspaceId)
                .session(session).queryParam("size", "3"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(8))
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].evidenceFingerprint").value(RecordVisibilityPlaneFixture.fingerprint(101)))
            .andExpect(jsonPath("$.items[1].evidenceFingerprint").value(RecordVisibilityPlaneFixture.fingerprint(100)))
            .andExpect(jsonPath("$.items[2].evidenceFingerprint").value(RecordVisibilityPlaneFixture.fingerprint(7)))
            .andExpect(content().string(Matchers.not(Matchers.containsString("Default Catalog Decoy"))));
        mockMvc.perform(get("/api/duplicate-reviews").header("X-Workspace-Id", fixture.workspaceId)
                .session(session).queryParam("size", "3").queryParam("page", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(8))
            .andExpect(jsonPath("$.items[0].evidenceFingerprint").value(RecordVisibilityPlaneFixture.fingerprint(6)))
            .andExpect(jsonPath("$.items[1].members[0].companyName").value("Employer 4"))
            .andExpect(jsonPath("$.items[2].members[0].companyName").doesNotHaveJsonPath());
        mockMvc.perform(get("/api/duplicate-reviews/summary").header("X-Workspace-Id", fixture.workspaceId).session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.personOpenCount").value(7))
            .andExpect(jsonPath("$.companyOpenCount").value(1));
    }

    @Test
    void dismissAndReopenReturnMaskedEmployersFromTenantCatalog() throws Exception {
        assertNoControlTables();
        MockHttpSession session = login();
        List<String> labels = Arrays.asList("Employer 0", "Employer 1", null, null, "Employer 4", null, null);
        for (int i = 0; i < fixture.pairIds.size(); i++) {
            for (String action : List.of("dismiss", "reopen")) {
                ResultActions response = decision(session, action, i).andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value(action.equals("dismiss") ? "dismissed" : "open"))
                    .andExpect(jsonPath("$.members[0].recordId").value(fixture.personIds.get(i * 2)))
                    .andExpect(jsonPath("$.members[1].recordId").value(fixture.personIds.get(i * 2 + 1)));
                for (int member : List.of(0, 1)) {
                    var companyName = jsonPath("$.members[" + member + "].companyName");
                    response.andExpect(labels.get(i) == null
                        ? companyName.doesNotHaveJsonPath()
                        : companyName.value(labels.get(i)));
                }
            }
        }
    }

    @Test
    void pairLockStaysOnTheTenantConnectionBeforeAnyUpdate() {
        fixture.installTenant();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status -> {
            Connection original = DataSourceUtils.getConnection(dataSource);
            assertEquals(fixture.pairIds.getFirst(), mapper.lockCurrentPair(fixture.workspaceId, "person", "email",
                fixture.personIds.get(0), fixture.personIds.get(1), RecordVisibilityPlaneFixture.fingerprint(1)));
            assertSame(original, DataSourceUtils.getConnection(dataSource));
            assertDecisionLockedOnAnotherConnection();
            status.setRollbackOnly();
        });
    }

    @Test
    void decisionLockAndUpdateRemainOnTheTenantTransactionConnection() {
        fixture.installTenant();
        User user = new User();
        user.setId(fixture.userId);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, System.currentTimeMillis());
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, fixture.userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThrows(RollbackProbe.class, () -> transaction.executeWithoutResult(status -> {
            Connection original = DataSourceUtils.getConnection(dataSource);
            service.dismiss(new DuplicateReviewDecisionRequest("person", "email", fixture.personIds.get(0),
                fixture.personIds.get(1), RecordVisibilityPlaneFixture.fingerprint(1), null));
            assertSame(original, DataSourceUtils.getConnection(dataSource));
            try {
                assertEquals(fixture.tenantCatalog, original.getCatalog());
                assertDecisionLockedOnAnotherConnection();
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
            throw new RollbackProbe();
        }));
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        assertEquals("open", mapper.findVisibleItemById(fixture.workspaceId, scope, fixture.pairIds.getFirst(), 20).getState());
    }

    private void assertDecisionLockedOnAnotherConnection() {
        try (Connection contender = fixture.routing.getConnection();
                var statement = contender.prepareStatement(
                    "SELECT id FROM duplicate_review_decision WHERE workspace_id = ? AND id = ? FOR UPDATE NOWAIT")) {
            contender.setAutoCommit(false);
            statement.setInt(1, fixture.workspaceId);
            statement.setLong(2, fixture.pairIds.getFirst());
            SQLException blocked = assertThrows(SQLException.class, statement::executeQuery);
            assertEquals(3572, blocked.getErrorCode());
            contender.rollback();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ResultActions decision(MockHttpSession session, String action, int pair) throws Exception {
        return mockMvc.perform(post("/api/duplicate-reviews/" + action).with(csrf().asHeader())
            .header("X-Workspace-Id", fixture.workspaceId).session(session).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"recordType":"person","kind":"email","recordIdA":%d,"recordIdB":%d,"evidenceFingerprint":"%s"}
                """.formatted(fixture.personIds.get(pair * 2), fixture.personIds.get(pair * 2 + 1),
                    RecordVisibilityPlaneFixture.fingerprint(pair + 1))));
    }

    private MockHttpSession login() throws Exception {
        String username = fixture.controlJdbc.queryForObject("SELECT username FROM app_user WHERE id = ?", String.class, fixture.userId);
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private void assertNoControlTables() throws Exception {
        for (String table : List.of("workspace", "workspace_member", "organization", "app_user")) {
            assertFalse(fixture.tenantTableExists(table), table);
        }
    }

    private static final class RollbackProbe extends RuntimeException {
    }
}
