package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.StoredObject;

/** HTTP metadata, mutation, and private-content isolation across both tenant dimensions. */
@SpringBootTest
@Transactional
@UnenrolledPrivilegedFixture
class ManagedContentTenantIsolationTest {

    private static final String PASSWORD = "Content-Tenant-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private ManagedObjectService managedObjectService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void metadataContentAndDeleteAreHiddenInSiblingAndForeignOrganizationWorkspaces()
            throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Organization ownerOrganization = newOrganization();
        Workspace ownerWorkspace = newWorkspace(ownerOrganization);
        Workspace siblingWorkspace = newWorkspace(ownerOrganization);
        Workspace foreignWorkspace = newWorkspace(newOrganization());
        User actor = newMember(ownerWorkspace);
        workspaceMapper.addMember(siblingWorkspace.getId(), actor.getId(), "owner");
        workspaceMapper.addMember(foreignWorkspace.getId(), actor.getId(), "owner");
        Company company = newCompany(ownerWorkspace);
        byte[] privateBytes = "private tenant content".getBytes(StandardCharsets.UTF_8);
        String token = UUID.randomUUID() + ".pdf";
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(ownerWorkspace.getId());
        attachment.setEntityType("company");
        attachment.setEntityId(company.getId());
        attachment.setFileName("tenant-matrix.pdf");
        attachment.setUrl("/api/attachments/content/" + token);
        attachment.setContentType("application/pdf");
        attachment.setSize((long) privateBytes.length);
        attachment.setUploadedBy(actor);
        attachmentMapper.insert(attachment);
        when(managedObjectService.openAttachment(eq(ownerWorkspace.getId()), any()))
                .thenAnswer(invocation -> new ManagedContent(
                        new StoredObject(new ByteArrayInputStream(privateBytes), privateBytes.length),
                        "application/pdf",
                        "tenant-matrix.pdf"));
        MockHttpSession session = login(actor.getUsername());

        for (Workspace unauthorized : List.of(siblingWorkspace, foreignWorkspace)) {
            mockMvc.perform(get("/api/attachments/{id}", attachment.getId())
                            .header("X-Workspace-Id", unauthorized.getId())
                            .session(session))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/attachments/content/{token}", token)
                            .header("X-Workspace-Id", unauthorized.getId())
                            .session(session))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete("/api/attachments/{id}", attachment.getId())
                            .header("X-Workspace-Id", unauthorized.getId())
                            .session(session)
                            .with(csrf().asHeader()))
                    .andExpect(status().isNotFound());
        }

        verify(managedObjectService, never()).openAttachment(
                eq(siblingWorkspace.getId()), any());
        verify(managedObjectService, never()).openAttachment(
                eq(foreignWorkspace.getId()), any());
        mockMvc.perform(get("/api/attachments/{id}", attachment.getId())
                        .header("X-Workspace-Id", ownerWorkspace.getId())
                        .session(session))
                .andExpect(status().isOk());
        MvcResult stream = mockMvc.perform(get("/api/attachments/content/{token}", token)
                        .header("X-Workspace-Id", ownerWorkspace.getId())
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().bytes(privateBytes));
    }

    private Company newCompany(Workspace workspace) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName("Content matrix " + unique());
        companyMapper.insert(company);
        return company;
    }

    private Organization newOrganization() {
        Organization organization = new Organization();
        organization.setName("Content matrix " + unique());
        organization.setSlug("content-matrix-org-" + unique());
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspace(Organization organization) {
        Workspace workspace = new Workspace();
        workspace.setName("Content matrix " + unique());
        workspace.setSlug("content-matrix-" + unique());
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace) {
        String suffix = unique();
        User user = new User();
        user.setUsername("content_matrix_" + suffix);
        user.setDisplayName("Content matrix actor " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "owner");
        return user;
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login did not establish a managed-content matrix session");
        return session;
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
