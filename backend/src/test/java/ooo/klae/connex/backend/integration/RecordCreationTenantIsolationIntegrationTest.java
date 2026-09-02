package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.RecordCreationTemplate;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationErrorDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.services.GuidedRecordCreationService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.tenant.TenantContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecordCreationTenantIsolationIntegrationTest {
    @Autowired private GuidedRecordCreationService guidedService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private TagMapper tagMapper;
    @Autowired private RecordCreationTemplateMapper templateMapper;
    @Autowired private CustomFieldDefinitionMapper customFieldMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private Organization organization;
    private Workspace workspace;
    private Workspace other;
    private User actor;
    private Company otherCompany;
    private Person otherPerson;
    private Pipeline otherPipeline;
    private Stage otherStage;
    private Tag otherTag;
    private CustomFieldDefinition otherCustom;

    @BeforeEach
    void setUpIsolatedWorkspaces() {
        String suffix = unique();
        organization = new Organization();
        organization.setName("Guided isolation " + suffix);
        organization.setSlug("guided-isolation-" + suffix);
        organizationMapper.insert(organization);
        workspace = workspace("Guided owner", organization.getId());
        other = workspace("Guided other", organization.getId());
        actor = new User();
        actor.setUsername("guided_isolation_" + suffix);
        actor.setDisplayName("Guided isolation " + suffix);
        actor.setEmail(suffix + "@example.com");
        actor.setPasswordHash("fixture");
        actor.setTimezone("UTC");
        userMapper.insert(actor);
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Guided isolation creator " + suffix);
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(
            workspace.getId(), role.getId(), List.of("PERSON_CREATE", "DEAL_CREATE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), actor.getId(), role.getId());
        authenticate();

        Pipeline currentPipeline = pipeline(workspace, "Current pipeline");
        stage(workspace, currentPipeline, "Current stage");
        otherCompany = company(other, "Secret company label");
        otherPerson = person(other, "Secret person label");
        otherPipeline = pipeline(other, "Secret pipeline label");
        otherStage = stage(other, otherPipeline, "Secret stage label");
        otherTag = tag(other, "Secret tag label");
        otherCustom = new CustomFieldDefinition();
        otherCustom.setWorkspaceId(other.getId());
        otherCustom.setEntityType("person");
        otherCustom.setFieldKey("secret_custom");
        otherCustom.setLabel("Secret custom label");
        otherCustom.setFieldType("text");
        otherCustom.setDataClassification("standard");
        customFieldMapper.insert(otherCustom);
    }

    @AfterEach
    void cleanRows() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        if (workspace != null && other != null) {
            for (int workspaceId : List.of(workspace.getId(), other.getId())) {
                jdbcTemplate.update("DELETE FROM custom_field_value WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM person_identity WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM person_employment WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM deal_stage_history WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM tag WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM custom_field_definition WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update(
                    "UPDATE record_creation_template SET current_version_id = NULL WHERE workspace_id = ?",
                    workspaceId);
                jdbcTemplate.update(
                    "DELETE FROM record_creation_template_version WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update(
                    "DELETE FROM record_creation_template WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update(
                    "DELETE FROM record_creation_template_set WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update(
                    "DELETE wrp FROM workspace_role_permission wrp"
                        + " JOIN workspace_role wr ON wr.id = wrp.workspace_role_id"
                        + " WHERE wr.workspace_id = ?",
                    workspaceId);
                jdbcTemplate.update("DELETE FROM workspace_role WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
            }
        }
        if (actor != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", actor.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void anotherWorkspaceTemplateAndCustomFieldAreIndistinguishableFromMissing() {
        templateMapper.insertSetIfAbsent(other.getId(), "person");
        RecordCreationTemplate root = new RecordCreationTemplate();
        root.setWorkspaceId(other.getId());
        root.setRecordType("person");
        root.setStatus("enabled");
        root.setPosition(0);
        root.setCreatedById(actor.getId());
        root.setUpdatedById(actor.getId());
        templateMapper.insertRoot(root);

        RecordCreationErrorDto missingTemplate = personFailure(personRequest(
            "workspace:" + Integer.MAX_VALUE, 1, 0, Map.of(), List.of(), null, null));
        RecordCreationErrorDto otherTemplate = personFailure(personRequest(
            "workspace:" + root.getId(), 1, 0, Map.of(), List.of(), null, null));
        assertEquals(missingTemplate, otherTemplate);
        assertEquals("TEMPLATE_NOT_FOUND", otherTemplate.code());

        RecordCreationErrorDto missingCustom = personFailure(personRequest(
            "system:person:standard",
            1,
            0,
            Map.of(Integer.MAX_VALUE, objectMapper.valueToTree("forged")),
            List.of(),
            null,
            null));
        RecordCreationErrorDto otherWorkspaceCustom = personFailure(personRequest(
            "system:person:standard",
            1,
            0,
            Map.of(otherCustom.getId(), objectMapper.valueToTree("forged")),
            List.of(),
            null,
            null));
        assertEquals(missingCustom, otherWorkspaceCustom);
        assertEquals("CUSTOM_FIELD_NOT_FOUND", otherWorkspaceCustom.code());
    }

    @Test
    void anotherWorkspaceTagCompanyAndPersonMatchMissingShapeAndLeaveNoRows() {
        RecordCreationErrorDto missingTag = personFailure(personRequest(
            "system:person:standard", 1, 0,
            Map.of(), List.of(Integer.MAX_VALUE), null, null));
        RecordCreationErrorDto otherWorkspaceTag = personFailure(personRequest(
            "system:person:standard", 1, 0,
            Map.of(), List.of(otherTag.getId()), null, null));
        assertEquals(missingTag, otherWorkspaceTag);

        RecordCreationErrorDto missingCompany = personFailure(personRequest(
            "system:person:standard", 1, 0,
            Map.of(), List.of(), Integer.MAX_VALUE, null));
        RecordCreationErrorDto otherWorkspaceCompany = personFailure(personRequest(
            "system:person:standard", 1, 0,
            Map.of(), List.of(), otherCompany.getId(), null));
        assertEquals(missingCompany, otherWorkspaceCompany);

        RecordCreationErrorDto missingPerson = personFailure(personRequest(
            "system:person:standard", 1, 0,
            Map.of(), List.of(), null, Integer.MAX_VALUE));
        RecordCreationErrorDto otherWorkspacePerson = personFailure(personRequest(
            "system:person:standard", 1, 0,
            Map.of(), List.of(), null, otherPerson.getId()));
        assertEquals(missingPerson, otherWorkspacePerson);
        assertEquals("RELATED_RECORD_NOT_FOUND", otherWorkspaceTag.code());
        assertEquals("RELATED_RECORD_NOT_FOUND", otherWorkspaceCompany.code());
        assertEquals("RELATED_RECORD_NOT_FOUND", otherWorkspacePerson.code());
        assertEquals(0, personCount());
    }

    @Test
    void anotherWorkspacePipelineAndStageMatchMissingShapeAndLeaveNoRows() {
        RecordCreationErrorDto missing = dealFailure(Integer.MAX_VALUE, Integer.MAX_VALUE);
        RecordCreationErrorDto otherWorkspace = dealFailure(
            otherPipeline.getId(), otherStage.getId());

        assertEquals(missing, otherWorkspace);
        assertEquals("RELATED_RECORD_NOT_FOUND", otherWorkspace.code());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal WHERE workspace_id = ?",
            Integer.class,
            workspace.getId()));
    }

    private RecordCreationErrorDto personFailure(GuidedPersonCreateRequestDto request) {
        RecordCreationTemplateException exception = assertThrows(
            RecordCreationTemplateException.class,
            () -> guidedService.createPerson(request));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person WHERE workspace_id = ? AND name = ?",
            Integer.class,
            workspace.getId(),
            request.record().name()));
        return exception.error();
    }

    private RecordCreationErrorDto dealFailure(int pipelineId, int stageId) {
        String name = "Isolated deal " + unique();
        GuidedDealCreateRequestDto request = new GuidedDealCreateRequestDto(
            new GuidedDealRecordDto(
                name, BigDecimal.ZERO, "USD", pipelineId, stageId, null, null, null),
            new RecordCreationTemplateUseDto(
                "system:deal:standard",
                1,
                0,
                RecordCreationEntryPoint.quick_create,
                new RecordCreationContextDto(null)),
            Map.of(),
            List.of());
        RecordCreationTemplateException exception = assertThrows(
            RecordCreationTemplateException.class,
            () -> guidedService.createDeal(request));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal WHERE workspace_id = ? AND name = ?",
            Integer.class,
            workspace.getId(),
            name));
        return exception.error();
    }

    private GuidedPersonCreateRequestDto personRequest(
            String templateId,
            int templateVersion,
            int setRevision,
            Map<Integer, tools.jackson.databind.JsonNode> customFields,
            List<Integer> tagIds,
            Integer companyId,
            Integer referrerPersonId) {
        return new GuidedPersonCreateRequestDto(
            new GuidedPersonRecordDto(
                "Isolated " + unique(), null, null, companyId, null,
                referrerPersonId == null
                    ? null
                    : ooo.klae.connex.backend.beans.PersonLeadSource.REFERRAL,
                null,
                referrerPersonId,
                null),
            new RecordCreationTemplateUseDto(
                templateId,
                templateVersion,
                setRevision,
                RecordCreationEntryPoint.quick_create,
                new RecordCreationContextDto(null)),
            customFields,
            tagIds);
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

    private Workspace workspace(String prefix, int orgId) {
        Workspace value = new Workspace();
        value.setOrgId(orgId);
        value.setName(prefix + " " + unique());
        value.setSlug(prefix.toLowerCase().replace(' ', '-') + "-" + unique());
        workspaceMapper.insert(value);
        return value;
    }

    private Company company(Workspace owner, String name) {
        Company value = new Company();
        value.setWorkspaceId(owner.getId());
        value.setName(name);
        companyMapper.insert(value);
        return value;
    }

    private Person person(Workspace owner, String name) {
        Person value = new Person();
        value.setWorkspaceId(owner.getId());
        value.setName(name);
        personMapper.insert(value);
        return value;
    }

    private Pipeline pipeline(Workspace owner, String name) {
        Pipeline value = new Pipeline();
        value.setWorkspaceId(owner.getId());
        value.setName(name);
        pipelineMapper.insertPipeline(value);
        return value;
    }

    private Stage stage(Workspace owner, Pipeline pipeline, String name) {
        Stage value = new Stage();
        value.setWorkspaceId(owner.getId());
        value.setPipeline(pipeline);
        value.setName(name);
        value.setPosition(0);
        pipelineMapper.insertStage(value);
        return value;
    }

    private Tag tag(Workspace owner, String name) {
        Tag value = new Tag();
        value.setWorkspaceId(owner.getId());
        value.setName(name);
        value.setColor("#abcdef");
        tagMapper.insert(value);
        return value;
    }

    private int personCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person WHERE workspace_id = ?",
            Integer.class,
            workspace.getId());
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
