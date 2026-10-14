package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AuditIntegrityMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.DataSubjectDisclosureAccess;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises POST disclosure admission, durable auditing, retirement and real parent/head locks. */
@SpringBootTest(properties = "connex.security.privileged-mfa.attestation-enforced=true")
class DataSubjectDisclosureHttpIntegrationTest {
    private static final String PASSWORD = "DisclosureHttp9!";
    private static final String ACTION = "appi.subject_request.disclosure";
    private static final String GENESIS_HASH = "0".repeat(64);

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private ObjectMapper objectMapper;
    @MockitoSpyBean private OrganizationMapper organizationMapper;
    @MockitoSpyBean private AuditIntegrityMapper auditIntegrityMapper;
    @MockitoSpyBean private DataSubjectDisclosureAccess disclosureAccess;

    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();
    private MockMvc mockMvc;
    private Organization organization;
    private Workspace workspace;
    private User actor;
    private int personId;
    private long requestId;
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        organization = newOrganization();
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Disclosure HTTP workspace");
        workspace.setSlug("dsr-http-" + UUID.randomUUID());
        workspaceMapper.insert(workspace);
        actor = newUser();
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        jdbc.update("INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'admin')",
            organization.getId(), actor.getId());
        int credentialId = passkey(actor);
        jdbc.update("INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
            + " VALUES (?, ?, 'FOUNDER')", credentialId, organization.getId());
        jdbc.update("INSERT INTO person (workspace_id, name, email) VALUES (?, ?, ?)",
            workspace.getId(), "Disclosure HTTP subject", "dsr-http@example.com");
        personId = jdbc.queryForObject("SELECT id FROM person WHERE workspace_id = ?", Integer.class,
            workspace.getId());
        jdbc.update("""
            INSERT INTO data_subject_request
              (org_id, request_type, status, requester_name, subject_name, subject_workspace_id,
               subject_person_id, received_at, identity_verified_at)
            VALUES (?, 'disclosure', 'received', 'HTTP requester', 'HTTP subject', ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, organization.getId(), workspace.getId(), personId);
        requestId = jdbc.queryForObject("SELECT id FROM data_subject_request WHERE org_id = ?",
            Long.class, organization.getId());
        session = login(actor, credentialId);
        jdbc.update("INSERT INTO audit_log_integrity_head (scope_type, scope_id, current_hash)"
            + " VALUES ('organization', ?, ?)", organization.getId(), GENESIS_HASH);
    }

    /** Removes committed mutable fixtures while retaining append-only audit rows and their heads. */
    @AfterEach
    void cleanUp() {
        if (workspace != null) {
            jdbc.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        for (int orgId : organizationIds) {
            jdbc.update("DELETE FROM data_subject_request WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_member WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM organization WHERE id = ?", orgId);
        }
        for (int userId : userIds) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "owner"})
    void postWithoutBodyCommitsOneScopedAuditAndExplicitRepeatAddsAnother(String role) throws Exception {
        jdbc.update("UPDATE org_member SET org_role = ? WHERE org_id = ? AND user_id = ?",
            role, organization.getId(), actor.getId());
        Head before = headSnapshot();

        mockMvc.perform(disclosurePost(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").value(requestId))
            .andExpect(jsonPath("$.subjectWorkspaceId").value(workspace.getId()))
            .andExpect(jsonPath("$.subjectPersonId").value(personId))
            .andExpect(jsonPath("$.person.id").value(personId))
            .andExpect(jsonPath("$.person.name").value("Disclosure HTTP subject"))
            .andExpect(jsonPath("$.person.email").value("dsr-http@example.com"))
            .andExpect(jsonPath("$.notes").isArray());

        assertAudit(before, 1);
        mockMvc.perform(disclosurePost(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());
        assertAudit(before, 2);
    }

    @Test
    void missingCsrfIsRefusedBeforeAssembly() throws Exception {
        assertRefused(post(disclosurePath()).session(session.httpSession())
            .header("X-Workspace-Id", workspace.getId()), 403);
        assertNoAssembly();
    }

    @Test
    void invalidCsrfIsRefusedBeforeAssembly() throws Exception {
        assertRefused(post(disclosurePath()).session(session.httpSession())
            .header(session.headerName(), "invalid-csrf")
            .header("X-Workspace-Id", workspace.getId()), 403);
        assertNoAssembly();
    }

    @Test
    void anonymousPostWithValidCsrfIsUnauthenticated() throws Exception {
        Session anonymous = csrfSession(new MockHttpSession(context.getServletContext()));
        assertRefused(disclosurePost(anonymous), 401);
        assertNoAssembly();
    }

    @Test
    void workspaceMemberWithoutOrganizationAuthorityIsRefused() throws Exception {
        User member = newUser();
        workspaceMapper.addMember(workspace.getId(), member.getId(), "member");
        Session memberSession = login(member, passkey(member));
        assertRefused(disclosurePost(memberSession), 403);
        assertNoAssembly();
    }

    @Test
    void foreignOrganizationIsRefused() throws Exception {
        Organization foreign = newOrganization();
        assertRefused(post("/api/orgs/" + foreign.getId() + "/data-subject-requests/"
                + requestId + "/disclosure")
            .session(session.httpSession()).header(session.headerName(), session.token())
            .header("X-Workspace-Id", workspace.getId()), 403);
        assertNoAssembly();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND org_id = ?",
            Integer.class, ACTION, foreign.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stale", "wrong-account", "missing"})
    void staleWrongAccountOrMissingStepUpIsRefused(String proof) throws Exception {
        if ("stale".equals(proof)) {
            session.httpSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR,
                System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1));
        } else if ("wrong-account".equals(proof)) {
            session.httpSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR,
                newUser().getId());
        } else {
            session.httpSession().removeAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR);
        }
        assertRefused(disclosurePost(session), 403);
        assertNoAssembly();
    }

    @Test
    void anotherAccountsBoundCredentialProofIsRefused() throws Exception {
        long stampedAt = (Long) session.httpSession().getAttribute(
            SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR);
        session.httpSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_PROOF_ATTR,
            newUser().getId() + ":" + stampedAt + ":1");
        assertRefused(disclosurePost(session), 403);
        assertNoAssembly();
    }

    @Test
    void nonDisclosureRequestIsRefusedBeforeAssembly() throws Exception {
        jdbc.update("UPDATE data_subject_request SET request_type = 'correction' WHERE id = ?", requestId);
        assertRefused(disclosurePost(session), 400);
        assertNoAssembly();
    }

    @Test
    void unverifiedIdentityIsRefusedBeforeAssembly() throws Exception {
        jdbc.update("UPDATE data_subject_request SET identity_verified_at = NULL WHERE id = ?", requestId);
        assertRefused(disclosurePost(session), 400);
        assertNoAssembly();
    }

    @Test
    void missingSubjectLinkIsRefusedBeforeAssembly() throws Exception {
        jdbc.update("UPDATE data_subject_request SET subject_workspace_id = NULL, subject_person_id = NULL"
            + " WHERE id = ?", requestId);
        assertRefused(disclosurePost(session), 400);
        assertNoAssembly();
    }

    @Test
    void missingSubjectFailsAssemblyWithoutSuccessAudit() throws Exception {
        jdbc.update("DELETE FROM person WHERE id = ?", personId);
        assertRefused(disclosurePost(session), 404);
        verify(disclosureAccess).assemble(organization.getId(), actor.getId(), workspace.getId(),
            personId, List.of(workspace.getId()));
    }

    /** The session chain denies anonymous retirement probes; MVC denies authorized methods with 405. */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void retiredMethodsDenyAuthorizedAndAnonymousRequestsWithoutAssemblyOrAudit(String method) throws Exception {
        MockHttpServletRequestBuilder authorized = "GET".equals(method)
            ? get(disclosurePath()) : head(disclosurePath());
        assertRefused(authorized.session(session.httpSession())
            .header("X-Workspace-Id", workspace.getId()), 405);
        assertNoAssembly();
        MockHttpServletRequestBuilder anonymous = "GET".equals(method)
            ? get(disclosurePath()) : head(disclosurePath());
        assertRefused(anonymous, 401);
        assertNoAssembly();
    }

    @Test
    void listAndDetailGetRemainAvailableWithoutAssemblyOrDisclosureAudit() throws Exception {
        mockMvc.perform(get(basePath()).session(session.httpSession())
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(requestId));
        mockMvc.perform(get(basePath() + "/" + requestId).session(session.httpSession())
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(requestId));
        assertNoAssembly();
        assertEquals(0, disclosureAuditCount());
    }

    @Test
    void headAdvanceFailureAfterRealInsertRollsBackAndAFreshRequestSucceeds() throws Exception {
        Head before = headSnapshot();
        int rowsBefore = organizationAuditCount();
        doAnswer(invocation -> {
            assertEquals(rowsBefore + 1, organizationAuditCount());
            assertEquals(1, disclosureAuditCount());
            assertEquals(before, headSnapshot());
            return 0;
        }).when(auditIntegrityMapper).advanceHead(eq("organization"), eq(organization.getId()),
            anyLong(), anyLong(), anyString());

        assertRefused(disclosurePost(session), 503);
        verify(auditIntegrityMapper).advanceHead(eq("organization"), eq(organization.getId()),
            eq(before.nextIndex()), eq(before.nextIndex() + 1), anyString());
        assertEquals(rowsBefore, organizationAuditCount());
        assertEquals(before, headSnapshot());

        AuditIntegrityMapper real = sqlSessionTemplate.getMapper(AuditIntegrityMapper.class);
        doAnswer(invocation -> real.advanceHead(invocation.getArgument(0), invocation.getArgument(1),
            invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4)))
            .when(auditIntegrityMapper).advanceHead(eq("organization"), eq(organization.getId()),
                anyLong(), anyLong(), anyString());
        mockMvc.perform(disclosurePost(session)).andExpect(status().isOk());
        assertAudit(before, 1);
    }

    @Test
    void auditAcquiresActorBeforeWaitingForOrganization() throws Exception {
        Head before = headSnapshot();
        AtomicLong connectionId = new AtomicLong();
        CountDownLatch connectionKnown = new CountDownLatch(1);
        observeAuditOrganizationLock(connectionId, connectionKnown);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lock(holder, "SELECT id FROM organization WHERE id = ? FOR UPDATE", organization.getId());
            try {
                Future<MvcResult> response = executor.submit(() -> mockMvc.perform(disclosurePost(session)).andReturn());
                assertTrue(connectionKnown.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, connectionId.get(), "organization",
                    Integer.toString(organization.getId()));
                assertFalse(response.isDone());
                try (Connection observer = dataSource.getConnection()) {
                    observer.setAutoCommit(false);
                    SQLException failure = assertThrows(SQLException.class, () ->
                        lock(observer, "SELECT id FROM app_user WHERE id = ? FOR UPDATE NOWAIT", actor.getId()));
                    assertEquals(3572, failure.getErrorCode());
                    observer.rollback();
                }
                holder.rollback();
                assertEquals(200, response.get(20, TimeUnit.SECONDS).getResponse().getStatus());
            } finally {
                holder.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertAudit(before, 1);
    }

    @Test
    void waitingForInitializedHeadReleasesPreparationMembershipAndCommitsAfterRelease() throws Exception {
        Head before = headSnapshot();
        AtomicLong connectionId = new AtomicLong();
        CountDownLatch connectionKnown = new CountDownLatch(1);
        observeAuditOrganizationLock(connectionId, connectionKnown);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lock(holder, "SELECT scope_id FROM audit_log_integrity_head"
                + " WHERE scope_type = 'organization' AND scope_id = ? FOR UPDATE", organization.getId());
            try {
                Future<MvcResult> response = executor.submit(() -> mockMvc.perform(disclosurePost(session)).andReturn());
                assertTrue(connectionKnown.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, connectionId.get(), "audit_log_integrity_head",
                    "'organization', " + organization.getId());
                assertFalse(response.isDone());
                assertEquals(0, disclosureAuditCount());
                try (Connection observer = dataSource.getConnection()) {
                    observer.setAutoCommit(false);
                    try (PreparedStatement statement = observer.prepareStatement(
                            "SELECT user_id FROM org_member WHERE org_id = ? AND user_id = ? FOR UPDATE NOWAIT")) {
                        statement.setInt(1, organization.getId());
                        statement.setInt(2, actor.getId());
                        try (var result = statement.executeQuery()) {
                            assertTrue(result.next());
                            assertEquals(actor.getId(), result.getInt(1));
                        }
                    }
                    observer.rollback();
                }
                holder.rollback();
                assertEquals(200, response.get(20, TimeUnit.SECONDS).getResponse().getStatus());
            } finally {
                holder.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertAudit(before, 1);
    }

    private void observeAuditOrganizationLock(AtomicLong connectionId, CountDownLatch known) {
        OrganizationMapper real = sqlSessionTemplate.getMapper(OrganizationMapper.class);
        doAnswer(invocation -> {
            Long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            assertNotNull(id);
            connectionId.set(id);
            known.countDown();
            return real.lockByIdForShare(invocation.getArgument(0));
        }).when(organizationMapper).lockByIdForShare(organization.getId());
    }

    private static void lock(Connection connection, String sql, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
            }
        }
    }

    private void assertRefused(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("\"person\""));
        assertFalse(body.contains("\"subjectPersonId\""));
        assertFalse(body.contains("\"notes\""));
        assertEquals(0, disclosureAuditCount());
    }

    private void assertNoAssembly() {
        verify(disclosureAccess, never()).assemble(anyInt(), anyInt(), anyInt(), anyInt(), anyList());
    }

    private void assertAudit(Head before, int expectedEvents) {
        List<AuditRow> rows = jdbc.query("""
            SELECT actor_id, workspace_id, org_id, entity_type, entity_id, outcome, changes,
                   chain_scope_type, chain_scope_id, chain_index, prev_hash, row_hash
            FROM audit_log WHERE action = ? AND org_id = ? ORDER BY chain_index
            """, (result, index) -> new AuditRow(result.getInt("actor_id"),
                result.getObject("workspace_id", Integer.class), result.getInt("org_id"),
                result.getString("entity_type"), result.getInt("entity_id"), result.getString("outcome"),
                result.getString("changes"), result.getString("chain_scope_type"), result.getInt("chain_scope_id"),
                result.getLong("chain_index"), result.getString("prev_hash"), result.getString("row_hash")),
            ACTION, organization.getId());
        assertEquals(expectedEvents, rows.size());
        String previousHash = before.hash();
        for (int index = 0; index < rows.size(); index++) {
            AuditRow row = rows.get(index);
            assertEquals(actor.getId(), row.actorId());
            assertNull(row.workspaceId());
            assertEquals(organization.getId(), row.orgId());
            assertEquals("organization", row.entityType());
            assertEquals(organization.getId(), row.entityId());
            assertEquals("success", row.outcome());
            assertEquals("organization", row.scopeType());
            assertEquals(organization.getId(), row.scopeId());
            assertEquals(before.nextIndex() + index, row.chainIndex());
            assertEquals(previousHash, row.previousHash());
            JsonNode changes = objectMapper.readTree(row.changes());
            assertEquals(requestId, changes.path("requestId").asLong());
            assertEquals(personId, changes.path("subjectPersonId").asInt());
            assertEquals(workspace.getId(), changes.path("subjectWorkspaceId").asInt());
            assertNotEquals(previousHash, row.hash());
            previousHash = row.hash();
        }
        assertEquals(new Head(before.nextIndex() + expectedEvents, previousHash), headSnapshot());
    }

    private int disclosureAuditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND org_id = ?",
            Integer.class, ACTION, organization.getId());
    }

    private int organizationAuditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE chain_scope_type = 'organization'"
            + " AND chain_scope_id = ?", Integer.class, organization.getId());
    }

    private Head headSnapshot() {
        return jdbc.queryForObject("SELECT next_chain_index, current_hash FROM audit_log_integrity_head"
            + " WHERE scope_type = 'organization' AND scope_id = ?",
            (result, index) -> new Head(result.getLong(1), result.getString(2)), organization.getId());
    }

    private MockHttpServletRequestBuilder disclosurePost(Session requestSession) {
        return post(disclosurePath()).session(requestSession.httpSession())
            .header(requestSession.headerName(), requestSession.token())
            .header("X-Workspace-Id", workspace.getId());
    }

    private String basePath() {
        return "/api/orgs/" + organization.getId() + "/data-subject-requests";
    }

    private String disclosurePath() {
        return basePath() + "/" + requestId + "/disclosure";
    }

    private Session login(User user, int credentialId) throws Exception {
        MockHttpSession authenticated = (MockHttpSession) mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", user.getUsername(), "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        assertNotNull(authenticated);
        long now = System.currentTimeMillis();
        authenticated.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
        authenticated.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, user.getId());
        authenticated.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_PROOF_ATTR,
            user.getId() + ":" + now + ":" + credentialId);
        return csrfSession(authenticated);
    }

    private Session csrfSession(MockHttpSession httpSession) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf").session(httpSession))
            .andExpect(status().isOk()).andReturn();
        JsonNode token = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Session(httpSession, token.path("headerName").asString(), token.path("token").asString());
    }

    private Organization newOrganization() {
        Organization created = new Organization();
        created.setName("Disclosure HTTP organization");
        created.setSlug("dsr-http-org-" + UUID.randomUUID());
        organizationMapper.insert(created);
        organizationIds.add(created.getId());
        return created;
    }

    private User newUser() {
        String unique = UUID.randomUUID().toString();
        User user = new User();
        user.setUsername("dsr-http-" + unique);
        user.setDisplayName("Disclosure HTTP actor");
        user.setEmail("dsr-http-" + unique + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
        return user;
    }

    private int passkey(User user) {
        String handle = "dsr-http-" + UUID.randomUUID();
        jdbc.update("INSERT INTO webauthn_user_entity (id, user_id, name, display_name) VALUES (?, ?, ?, ?)",
            handle, user.getId(), user.getUsername(), user.getDisplayName());
        byte[] credentialId = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO webauthn_credential (credential_id, user_entity_user_id, public_key) VALUES (?, ?, ?)",
            credentialId, handle, new byte[] {1});
        return jdbc.queryForObject("SELECT id FROM webauthn_credential WHERE credential_id = ?",
            Integer.class, credentialId);
    }

    private record Session(MockHttpSession httpSession, String headerName, String token) {}
    private record Head(long nextIndex, String hash) {}
    private record AuditRow(int actorId, Integer workspaceId, int orgId, String entityType, int entityId,
            String outcome, String changes, String scopeType, int scopeId, long chainIndex,
            String previousHash, String hash) {}
}
