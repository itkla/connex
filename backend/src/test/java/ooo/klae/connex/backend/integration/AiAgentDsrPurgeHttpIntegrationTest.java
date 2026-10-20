package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.ai.mission.AiAgentPrivacyService;
import ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.ApiRequestBodySizeFilter;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.mappers.DataSubjectRequestMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.DataSubjectRequestSnapshot;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Exercises the production HTTP security chain, body filter, validation, and durable DSR protocol. */
@SpringBootTest(properties = "connex.security.privileged-mfa.attestation-enforced=true")
class AiAgentDsrPurgeHttpIntegrationTest {
    private static final String PASSWORD = "MissionPurgeHttp9!";
    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter security;
    @Autowired private RequestBodySizeProperties bodyLimits;
    @Autowired private UserMapper users;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private OrganizationMapper organizations;
    @Autowired private DataSubjectRequestMapper requests;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private TenantWorkScope workScope;
    @Autowired private AiAgentPrivacyService privacy;
    @Autowired private ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService assistantPrivacy;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    private MockMvc mvc;
    private Organization organization;
    private Workspace workspace;
    private User actor;
    private long requestId;
    private Session session;

    @BeforeEach
    void prepare() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new ApiRequestBodySizeFilter(bodyLimits, json), security).build();
        organization = new Organization();
        organization.setName("Mission purge HTTP");
        organization.setSlug("mission-purge-http-" + UUID.randomUUID());
        organizations.insert(organization);
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> assistantPrivacy.provision(organization.getId()));
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Mission purge HTTP");
        workspace.setSlug("mission-purge-http-" + UUID.randomUUID());
        workspaces.insert(workspace);
        actor = new User();
        String key = UUID.randomUUID().toString();
        actor.setUsername("mission-purge-" + key);
        actor.setDisplayName("Mission purge actor");
        actor.setEmail("mission-purge-" + key + "@example.com");
        actor.setPasswordHash(passwords.encode(PASSWORD));
        actor.setTimezone("UTC");
        users.insert(actor);
        workspaces.addMember(workspace.getId(), actor.getId(), "member");
        jdbc.update("INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'admin')",
                organization.getId(), actor.getId());
        int credential = OwnershipOfferTestFixtures.approvedPasskey(jdbc, actor);
        jdbc.update("INSERT INTO person (workspace_id, name) VALUES (?, 'HTTP subject')", workspace.getId());
        int personId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM person WHERE workspace_id=?", Integer.class, workspace.getId()));
        jdbc.update("INSERT INTO data_subject_request (org_id, request_type, status, requester_name, subject_name,"
                + " subject_workspace_id, subject_person_id, received_at, identity_verified_at)"
                + " VALUES (?, 'cease_use', 'received', 'HTTP requester', 'HTTP subject', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                organization.getId(), workspace.getId(), personId);
        requestId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM data_subject_request WHERE org_id=?", Long.class, organization.getId()));
        privacyMaintenance(() -> {
            privacy.initialize(workspace.getId());
            return null;
        });
        MockHttpSession authenticated = (MockHttpSession) mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", actor.getUsername(), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        assertNotNull(authenticated);
        OwnershipOfferTestFixtures.stepUp(jdbc, actor, authenticated, credential);
        session = csrf(authenticated);
    }

    @AfterEach
    void removeFixtures() {
        if (workspace != null) {
            jdbc.update("UPDATE ai_agent_privacy_barrier SET latest_operation_key=NULL WHERE workspace_id=?", workspace.getId());
            jdbc.update("DELETE FROM ai_agent_privacy_object WHERE workspace_id=?", workspace.getId());
            jdbc.update("DELETE FROM ai_agent_privacy_work WHERE workspace_id=?", workspace.getId());
            jdbc.update("DELETE FROM ai_agent_privacy_barrier WHERE workspace_id=?", workspace.getId());
            jdbc.update("DELETE FROM person WHERE workspace_id=?", workspace.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id=?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id=?", workspace.getId());
        }
        if (organization != null) {
            jdbc.update("DELETE FROM data_subject_request WHERE org_id=?", organization.getId());
            jdbc.update("DELETE FROM org_member WHERE org_id=?", organization.getId());
            jdbc.update("DELETE FROM ai_assistant_privacy_operation_workspace WHERE organization_id=?", organization.getId());
            jdbc.update("DELETE FROM ai_assistant_privacy_operation WHERE organization_id=?", organization.getId());
            jdbc.update("DELETE FROM ai_assistant_privacy_fence WHERE organization_id=?", organization.getId());
            jdbc.update("DELETE FROM organization WHERE id=?", organization.getId());
        }
        if (actor != null) {
            jdbc.update("DELETE FROM app_user WHERE id=?", actor.getId());
        }
    }

    @Test
    void sliceOneDsrHttpContractsEnforceAuthCsrfAndBounds() throws Exception {
        mvc.perform(get(progressPath(UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(initiatePath()).session(session.http()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
        mvc.perform(post(initiatePath()).session(session.http()).header(session.header(), "invalid")
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        mvc.perform(postRequest().content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(postRequest().content(body().replace("organization_mission_holdings_v1", "one_subject_only")))
                .andExpect(status().isBadRequest());
        mvc.perform(postRequest().content(body().replace("}", ",\"extra\":true}")))
                .andExpect(status().isBadRequest());
        mvc.perform(postRequest().content("{\"extra\":\"" + "x".repeat(4096) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(get(progressPath(UUID.randomUUID().toString())).session(session.http())
                .header("X-Workspace-Id", workspace.getId())).andExpect(status().isNotFound());
        assertEquals(0, acceptedCount());
    }

    @Test
    void firstAcceptanceNeedsRecentAdminAndReplayKeepsOriginalOperation() throws Exception {
        String body = body();
        session.http().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, 1L);
        mvc.perform(postRequest().content(body)).andExpect(status().isForbidden());
        assertEquals(0, acceptedCount());
        OwnershipOfferTestFixtures.stepUp(jdbc, actor, session.http(),
                OwnershipOfferTestFixtures.approvedPasskey(jdbc, actor));
        String accepted = mvc.perform(postRequest().content(body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("purging"))
                .andExpect(jsonPath("$.requestId").value(Long.toString(requestId)))
                .andExpect(jsonPath("$.workspaceCount").value(1))
                .andReturn().getResponse().getContentAsString();
        String operation = json.readTree(accepted).path("operationId").asString();
        session.http().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, 1L);
        mvc.perform(postRequest().content(body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(operation));
        mvc.perform(get(progressPath(operation)).session(session.http()).header("X-Workspace-Id", workspace.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operationId").value(operation));
        assertEquals(1, acceptedCount());
        assertTrue(privacyMaintenance(() -> {
            for (int batch = 0; batch < 10; batch++) {
                if (privacy.batch(workspace.getId(), operation)) {
                    return true;
                }
            }
            return false;
        }));
        mvc.perform(postRequest().content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("complete"));
        assertEquals("received", requests.findById(organization.getId(), requestId).getStatus());
    }

    @Test
    void organizationAdministratorWithoutWorkspaceMembershipCanInitiateAndReadProgress() throws Exception {
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id=? AND user_id=?", workspace.getId(), actor.getId());
        String accepted = mvc.perform(post(initiatePath()).session(session.http())
                .header(session.header(), session.token()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("purging"))
                .andExpect(jsonPath("$.workspaceCount").value(1))
                .andReturn().getResponse().getContentAsString();
        String operation = json.readTree(accepted).path("operationId").asString();
        mvc.perform(get(progressPath(operation)).session(session.http()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("purging"))
                .andExpect(jsonPath("$.operationId").value(operation));
        assertEquals(1, acceptedCount());
        jdbc.update("DELETE FROM org_member WHERE org_id=? AND user_id=?", organization.getId(), actor.getId());
        mvc.perform(get(progressPath(operation)).session(session.http())).andExpect(status().isForbidden());
        mvc.perform(post(initiatePath()).session(session.http()).header(session.header(), session.token())
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
    }

    @Test
    void currentOrganizationAuthorityIsRequiredForInitiationAndProgress() throws Exception {
        String accepted = mvc.perform(postRequest().content(body())).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String operation = json.readTree(accepted).path("operationId").asString();
        jdbc.update("DELETE FROM org_member WHERE org_id=? AND user_id=?", organization.getId(), actor.getId());
        mvc.perform(postRequest().content(body())).andExpect(status().isForbidden());
        mvc.perform(get(progressPath(operation)).session(session.http()).header("X-Workspace-Id", workspace.getId()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orgs/2147483647/data-subject-requests/" + requestId + "/mission-holdings-purges/" + operation)
                .session(session.http()).header("X-Workspace-Id", workspace.getId()))
                .andExpect(status().isForbidden());
        assertEquals(1, acceptedCount());
    }

    @Test
    void staleSnapshotConflictsWithoutInvalidatingTenant() throws Exception {
        String stale = body();
        jdbc.update("UPDATE data_subject_request SET summary='Concurrent edit', updated_at=updated_at WHERE id=?", requestId);
        mvc.perform(postRequest().content(stale)).andExpect(status().isConflict());
        assertEquals(0, acceptedCount());
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT generation FROM ai_agent_privacy_barrier WHERE workspace_id=?", Long.class, workspace.getId()));
    }

    @Test
    void allChatPurgeRequiresExactAcknowledgments() throws Exception {
        String body = allChatBody();
        mvc.perform(postRequest().content(body.replace(AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY,
                AiAgentPurgeAcceptance.CUTOFF_POLICY))).andExpect(status().isBadRequest());
        assertEquals(0, acceptedCount());
        String accepted = mvc.perform(postRequest().content(body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.schemaVersion").value(2))
                .andExpect(jsonPath("$.scope").value(AiAgentPurgeAcceptance.ASSISTANT_SCOPE))
                .andReturn().getResponse().getContentAsString();
        String operation = json.readTree(accepted).path("operationId").asString();
        long generation = assistantPrivacy.current(organization.getId());
        mvc.perform(postRequest().content(body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(operation));
        assertEquals(generation, assistantPrivacy.current(organization.getId()));
        mvc.perform(get(progressPath(operation)).session(session.http()).header("X-Workspace-Id", workspace.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(2));
        mvc.perform(postRequest().content(body.replace(AiAgentPurgeAcceptance.ASSISTANT_SCOPE,
                AiAgentPurgeAcceptance.SCOPE).replace(AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY,
                AiAgentPurgeAcceptance.CUTOFF_POLICY))).andExpect(status().isConflict());
    }

    @Test
    void allChatPurgePreservesAdminCsrfAndRecentAuthGates() throws Exception {
        String body = allChatBody();
        Session anonymous = csrf(new MockHttpSession());
        mvc.perform(post(initiatePath()).session(anonymous.http()).header(anonymous.header(), anonymous.token())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(initiatePath()).session(session.http()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        session.http().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, 1L);
        mvc.perform(postRequest().content(body)).andExpect(status().isForbidden());
        OwnershipOfferTestFixtures.stepUp(jdbc, actor, session.http(),
                OwnershipOfferTestFixtures.approvedPasskey(jdbc, actor));
        jdbc.update("DELETE FROM org_member WHERE org_id=? AND user_id=?", organization.getId(), actor.getId());
        mvc.perform(postRequest().content(body)).andExpect(status().isForbidden());
        assertEquals(0, acceptedCount());
        assertEquals(1, assistantPrivacy.current(organization.getId()));
    }

    private String allChatBody() {
        return body().replace(AiAgentPurgeAcceptance.SCOPE, AiAgentPurgeAcceptance.ASSISTANT_SCOPE)
                .replace(AiAgentPurgeAcceptance.CUTOFF_POLICY, AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY);
    }

    private String body() {
        return json.writeValueAsString(Map.of("idempotencyKey", UUID.randomUUID().toString(),
                "snapshotToken", DataSubjectRequestSnapshot.token(requests.findById(organization.getId(), requestId)),
                "scopeAcknowledgement", AiAgentPurgeAcceptance.SCOPE,
                "cutoffPolicyAcknowledgement", AiAgentPurgeAcceptance.CUTOFF_POLICY));
    }

    private <T> T privacyMaintenance(Supplier<T> work) {
        var requestAttributes = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            return workScope.inWorkspace(workspace.getId(), work);
        } finally {
            RequestContextHolder.setRequestAttributes(requestAttributes);
        }
    }

    private String initiatePath() {
        return "/api/orgs/" + organization.getId() + "/data-subject-requests/" + requestId + "/mission-holdings-purge";
    }

    private String progressPath(String operation) {
        return "/api/orgs/" + organization.getId() + "/data-subject-requests/" + requestId + "/mission-holdings-purges/" + operation;
    }

    private MockHttpServletRequestBuilder postRequest() {
        return post(initiatePath()).session(session.http()).header(session.header(), session.token())
                .header("X-Workspace-Id", workspace.getId()).contentType(MediaType.APPLICATION_JSON);
    }

    private Session csrf(MockHttpSession http) throws Exception {
        var response = mvc.perform(get("/api/auth/csrf").session(http)).andExpect(status().isOk()).andReturn();
        var token = json.readTree(response.getResponse().getContentAsString());
        return new Session(http, token.path("headerName").asString(), token.path("token").asString());
    }

    private int acceptedCount() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE org_id=? AND action=?",
                Integer.class, organization.getId(), AiAgentPurgeAcceptance.ACCEPTED));
    }

    private record Session(MockHttpSession http, String header, String token) {}
}
