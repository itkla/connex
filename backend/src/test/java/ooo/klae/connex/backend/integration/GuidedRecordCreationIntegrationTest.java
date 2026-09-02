package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.recordcreation.GuidedCompanyCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedCompanyRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.mappers.CustomFieldValueMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.services.CustomFieldDefinitionService;
import ooo.klae.connex.backend.services.GuidedRecordCreationService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.tenant.TenantContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GuidedRecordCreationIntegrationTest {
    @Autowired private GuidedRecordCreationService guidedService;
    @Autowired private CustomFieldDefinitionService customFieldDefinitionService;
    @Autowired private CustomFieldValueMapper customFieldValueMapper;
    @Autowired private AuditLogMapper auditLogMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private TagMapper tagMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private Workspace workspace;
    private User actor;

    @BeforeEach
    void setUpFreshWorkspace() {
        RequestContextHolder.resetRequestAttributes();
        Organization organization = new Organization();
        String suffix = unique();
        organization.setName("Guided integration " + suffix);
        organization.setSlug("guided-integration-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Guided integration " + suffix);
        workspace.setSlug("guided-integration-" + suffix);
        workspaceMapper.insert(workspace);
        actor = new User();
        actor.setUsername("guided_" + suffix);
        actor.setDisplayName("Guided actor " + suffix);
        actor.setEmail(suffix + "@example.com");
        actor.setPasswordHash("fixture");
        actor.setTimezone("UTC");
        userMapper.insert(actor);
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Guided creator " + suffix);
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of(
            "PERSON_CREATE", "COMPANY_CREATE", "DEAL_CREATE", "CUSTOM_FIELD_MANAGE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), actor.getId(), role.getId());
        authenticate();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    @Test
    void personCreateCommitsCoreCustomTagsAndAuditProvenanceTogether() {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setEntityType("person");
        definition.setFieldType("text");
        definition.setFieldKey("guided_required");
        definition.setLabel("Guided required");
        definition.setRequired(true);
        definition = customFieldDefinitionService.create(definition, null);
        int definitionId = definition.getId();
        Tag tag = new Tag();
        tag.setWorkspaceId(workspace.getId());
        tag.setName("guided_" + unique());
        tag.setColor("#abcdef");
        tagMapper.insert(tag);
        String suffix = unique();
        GuidedPersonCreateRequestDto request = new GuidedPersonCreateRequestDto(
            new GuidedPersonRecordDto(
                "Guided " + suffix,
                suffix + "@example.com",
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            new RecordCreationTemplateUseDto(
                "system:person:standard",
                1,
                1,
                RecordCreationEntryPoint.record_list,
                new RecordCreationContextDto(null)),
            Map.of(definitionId, objectMapper.valueToTree("verified")),
            List.of(tag.getId()));

        Person created = guidedService.createPerson(request);

        assertTrue(created.getId() > 0);
        assertEquals(actor.getId(), created.getOwnerId());
        var customValues = customFieldValueMapper.getForEntity(
            workspace.getId(), "person", created.getId());
        assertEquals("verified", customValues.stream()
            .filter(value -> value.getDefinitionId() == definitionId)
            .findFirst().orElseThrow().getValueText());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person_tag WHERE person_id = ? AND tag_id = ?",
            Integer.class,
            created.getId(),
            tag.getId()));
        var audit = auditLogMapper.findByEntity(
            workspace.getId(), "person", created.getId(), 10, 0).stream()
            .filter(entry -> "person.create".equals(entry.getAction()))
            .findFirst().orElseThrow();
        assertNotNull(audit.getChanges());
        assertTrue(audit.getChanges().contains("creationTemplateId"));
        assertTrue(audit.getChanges().contains("system:person:standard"));
        assertTrue(audit.getChanges().contains("creationTemplateVersion"));
        assertTrue(audit.getChanges().contains("creationTemplateEntryPoint"));
        assertTrue(audit.getChanges().contains("record_list"));
    }

    @Test
    void companyAndDealCreationRetainCanonicalOwnershipAndStageHistory() {
        var company = guidedService.createCompany(new GuidedCompanyCreateRequestDto(
            new GuidedCompanyRecordDto(
                "Guided company " + unique(),
                "https://guided.example.com",
                null,
                null,
                null,
                null),
            new RecordCreationTemplateUseDto(
                "system:company:standard",
                1,
                0,
                RecordCreationEntryPoint.quick_create,
                new RecordCreationContextDto(null)),
            Map.of(),
            List.of()));
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Guided pipeline " + unique());
        pipelineMapper.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("Guided stage " + unique());
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);
        String dealName = "Guided deal " + UUID.randomUUID();
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal WHERE workspace_id = ? AND name = ?",
            Integer.class,
            workspace.getId(),
            dealName));

        var deal = guidedService.createDeal(new GuidedDealCreateRequestDto(
            new GuidedDealRecordDto(
                dealName,
                new BigDecimal("1250.00"),
                "USD",
                pipeline.getId(),
                stage.getId(),
                company.getId(),
                null,
                null),
            new RecordCreationTemplateUseDto(
                "system:deal:standard",
                1,
                0,
                RecordCreationEntryPoint.record_detail,
                new RecordCreationContextDto(company.getId())),
            Map.of(),
            List.of()));

        assertEquals(actor.getId(), company.getOwnerId());
        assertEquals(actor.getId(), deal.getOwnerId());
        assertEquals(company.getId(), deal.getCompanyId());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal_stage_history"
                + " WHERE workspace_id = ? AND deal_id = ? AND stage_id = ?",
            Integer.class,
            workspace.getId(),
            deal.getId(),
            stage.getId()));
        assertTrue(auditLogMapper.findByEntity(
            workspace.getId(), "company", company.getId(), 10, 0).stream()
            .anyMatch(entry -> entry.getChanges().contains("creationTemplateId")));
        assertTrue(auditLogMapper.findByEntity(
            workspace.getId(), "deal", deal.getId(), 10, 0).stream()
            .anyMatch(entry -> entry.getChanges().contains("creationTemplateId")));
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        long now = System.currentTimeMillis();
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, actor.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        tenantContext.set(workspace.getId(), workspace.getOrgId(), actor.getId(), "member", null);
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
