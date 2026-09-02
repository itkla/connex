package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.Filter;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.RecordCreationTemplateSet;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

@SpringBootTest(properties = "connex.record-creation.guided-cutover-enabled=true")
@Transactional
class GuidedRecordCreationValidationBypassIntegrationTest {
    private static final String PASSWORD = "Guided-Validation-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private CustomFieldDefinitionMapper customFieldMapper;
    @Autowired private RecordCreationTemplateMapper templateMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private MockHttpSession session;
    private Workspace workspace;

    @BeforeEach
    void setUpHttp() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        String suffix = unique();
        Organization organization = new Organization();
        organization.setName("Guided validation " + suffix);
        organization.setSlug("guided-validation-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Guided validation " + suffix);
        workspace.setSlug("guided-validation-" + suffix);
        workspaceMapper.insert(workspace);
        User actor = new User();
        actor.setUsername("guided_validation_" + suffix);
        actor.setDisplayName("Guided validation " + suffix);
        actor.setEmail(suffix + "@example.com");
        actor.setPasswordHash(passwordEncoder.encode(PASSWORD));
        actor.setTimezone("UTC");
        userMapper.insert(actor);
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Guided validation creator " + suffix);
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(
            workspace.getId(), role.getId(), List.of("PERSON_CREATE", "DEAL_CREATE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), actor.getId(), role.getId());
        session = login(actor.getUsername());
    }

    @Test
    void flatMissingTemplateAndUnknownPropertiesCannotReachLegacyCreation() throws Exception {
        perform("{\"name\":\"Legacy flat\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
        perform("""
            {"record":{"name":"Missing template"},"customFields":{},"tagIds":[]}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
        perform(personBody(
            "\"name\":\"Owner forge\",\"ownerId\":999",
            "\"templateSetRevision\":0",
            "{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
        mockMvc.perform(post("/api/deals")
                .with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"record":{"name":"Actual forge","value":1.00,"actualValue":1.00,
                      "currency":"USD","pipeline":1,"stage":1},
                     "templateUse":{"templateId":"system:deal:standard","templateVersion":1,
                      "templateSetRevision":0,"entryPoint":"quick_create","context":{"relatedCompanyId":null}},
                     "customFields":{},"tagIds":[]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
        assertEquals(0, personCount());
    }

    @Test
    void forgedSetRevisionReturnsTheContractedConflictShape() throws Exception {
        perform(personBody(
            "\"name\":\"Stale request\"",
            "\"templateSetRevision\":99",
            "{}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TEMPLATE_SET_STALE"))
            .andExpect(jsonPath("$.currentSetRevision").value(0));
        assertEquals(0, personCount());
    }

    @Test
    void schemaRequiredCustomFieldCannotBeHiddenOrOmitted() throws Exception {
        templateMapper.insertSetIfAbsent(workspace.getId(), "person");
        RecordCreationTemplateSet set = templateMapper.getSet(workspace.getId(), "person");
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setWorkspaceId(workspace.getId());
        definition.setEntityType("person");
        definition.setFieldType("text");
        definition.setFieldKey("bypass_required");
        definition.setLabel("Bypass required");
        definition.setDataClassification("standard");
        definition.setRequired(true);
        customFieldMapper.insert(definition);
        assertEquals(1, templateMapper.advanceSetRevision(
            workspace.getId(), "person", set.getRevision()));

        perform(personBody(
            "\"name\":\"Required omission\"",
            "\"templateSetRevision\":1",
            "{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TEMPLATE_FIELD_NOT_SUBMITTED"))
            .andExpect(jsonPath("$.fieldErrors['custom:" + definition.getId() + "']")
                .value("A value is required"));
        assertEquals(0, personCount());
    }

    private org.springframework.test.web.servlet.ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post("/api/persons")
            .with(csrf().asHeader())
            .header("X-Workspace-Id", workspace.getId())
            .session(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", username, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession authenticated = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(authenticated);
        return authenticated;
    }

    private int personCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person WHERE workspace_id = ?",
            Integer.class,
            workspace.getId());
    }

    private static String personBody(
            String recordFields,
            String revisionField,
            String customFields) {
        return """
            {"record":{%s},
             "templateUse":{"templateId":"system:person:standard","templateVersion":1,
              %s,"entryPoint":"quick_create","context":{"relatedCompanyId":null}},
             "customFields":%s,"tagIds":[]}
            """.formatted(recordFields, revisionField, customFields);
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
