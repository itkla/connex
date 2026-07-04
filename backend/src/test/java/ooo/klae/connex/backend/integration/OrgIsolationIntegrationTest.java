package ooo.klae.connex.backend.integration;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Full-stack organization-isolation backstop (#97, #313 Phase 2): over the real
 * login + security filter chain, proves the org wall holds where workspaces
 * deliberately connect — sharing stops at the org boundary even for a user who
 * belongs to workspaces on both sides (the consultant case, #313: cross-org
 * membership is allowed) — and locks the decided cross-org behaviors: a
 * multi-org user's inbox spans their organizations, and a workspace in another
 * org can never be pinned. Complements {@code ShareMapperTest} (SQL refuses)
 * and {@code ShareServiceTest} (service throws).
 */
@SpringBootTest
@Transactional
class OrgIsolationIntegrationTest {

    private static final String PASSWORD = "Org-Test-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void shareStopsAtTheOrgBoundaryEvenForADualMember() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace wsA = newWorkspaceInOrg(newOrganization());
        Workspace wsB = newWorkspaceInOrg(newOrganization());
        User consultant = newUser();
        workspaceMapper.addMember(wsA.getId(), consultant.getId(), "owner");
        workspaceMapper.addMember(wsB.getId(), consultant.getId(), "member");
        Company companyA = newCompany(wsA);

        MockHttpSession session = login(consultant.getUsername());
        mockMvc.perform(post("/api/shares/company/" + companyA.getId())
                .header("X-Workspace-Id", wsA.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspaceId\":" + wsB.getId() + ",\"canEdit\":false}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/companies/" + companyA.getId())
                .header("X-Workspace-Id", wsB.getId())
                .session(session))
            .andExpect(status().isNotFound());
    }

    @Test
    void shareWithinTheOrgStillWorks() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Organization org = newOrganization();
        Workspace wsA1 = newWorkspaceInOrg(org);
        Workspace wsA2 = newWorkspaceInOrg(org);
        User owner = newUser();
        workspaceMapper.addMember(wsA1.getId(), owner.getId(), "owner");
        workspaceMapper.addMember(wsA2.getId(), owner.getId(), "member");
        Company company = newCompany(wsA1);

        MockHttpSession session = login(owner.getUsername());
        mockMvc.perform(post("/api/shares/company/" + company.getId())
                .header("X-Workspace-Id", wsA1.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspaceId\":" + wsA2.getId() + ",\"canEdit\":false}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/companies/" + company.getId())
                .header("X-Workspace-Id", wsA2.getId())
                .session(session))
            .andExpect(status().isOk());
    }

    @Test
    void workspaceInAnotherOrgCannotBePinned() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace wsA = newWorkspaceInOrg(newOrganization());
        Workspace wsB = newWorkspaceInOrg(newOrganization());
        User alice = newUser();
        workspaceMapper.addMember(wsA.getId(), alice.getId(), "owner");
        Company companyB = newCompany(wsB);

        MockHttpSession session = login(alice.getUsername());
        mockMvc.perform(get("/api/companies/" + companyB.getId())
                .header("X-Workspace-Id", wsB.getId())
                .session(session))
            .andExpect(status().isForbidden());
    }

    @Test
    void inboxSpansOrganizationsForAMultiOrgMember() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace wsA = newWorkspaceInOrg(newOrganization());
        Workspace wsB = newWorkspaceInOrg(newOrganization());
        User consultant = newUser();
        workspaceMapper.addMember(wsA.getId(), consultant.getId(), "member");
        workspaceMapper.addMember(wsB.getId(), consultant.getId(), "member");
        notificationMapper.upsert(reminder(wsA, consultant));
        notificationMapper.upsert(reminder(wsB, consultant));

        MockHttpSession session = login(consultant.getUsername());
        mockMvc.perform(get("/api/notifications")
                .header("X-Workspace-Id", wsA.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$..workspaceName", hasItem(wsA.getName())))
            .andExpect(jsonPath("$..workspaceName", hasItem(wsB.getName())));
    }

    private Notification reminder(Workspace workspace, User recipient) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Notification notification = new Notification();
        notification.setWorkspaceId(workspace.getId());
        notification.setRecipientId(recipient.getId());
        notification.setType("task.due");
        notification.setCategory("task");
        notification.setSeverity("warning");
        notification.setTemplateVersion(1);
        notification.setTitle("Task due " + suffix);
        notification.setBody("Task body");
        notification.setDedupeKey("task.due:" + suffix);
        notification.setTriggeredAt("2026-07-01 00:00:00");
        return notification;
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login did not establish a session for " + username);
        return session;
    }

    private Organization newOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Org " + suffix);
        organization.setSlug("org-" + suffix);
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspaceInOrg(Organization organization) {
        String slug = "ws-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("user_" + suffix);
        user.setDisplayName("User " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private Company newCompany(Workspace workspace) {
        Company company = new Company();
        company.setName("Company " + UUID.randomUUID().toString().substring(0, 8));
        company.setWorkspaceId(workspace.getId());
        companyMapper.insert(company);
        return company;
    }
}
