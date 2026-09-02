package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.services.GuidedRecordCreationService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.tenant.TenantContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecordCreationConcurrencyIntegrationTest {
    @Autowired private GuidedRecordCreationService guidedService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private RecordCreationTemplateMapper templateMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private PersonMapper personMapperSpy;

    private Organization organization;
    private Workspace workspace;
    private User actor;

    @BeforeEach
    void createIsolatedWorkspace() {
        String suffix = unique();
        organization = new Organization();
        organization.setName("Guided concurrency " + suffix);
        organization.setSlug("guided-concurrency-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Guided concurrency " + suffix);
        workspace.setSlug("guided-concurrency-" + suffix);
        workspaceMapper.insert(workspace);
        actor = new User();
        actor.setUsername("guided_concurrency_" + suffix);
        actor.setDisplayName("Guided concurrency " + suffix);
        actor.setEmail(suffix + "@example.com");
        actor.setPasswordHash("fixture");
        actor.setTimezone("UTC");
        userMapper.insert(actor);
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Guided concurrency creator " + suffix);
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("PERSON_CREATE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), actor.getId(), role.getId());
        authenticate();
        templateMapper.insertSetIfAbsent(workspace.getId(), "person");
    }

    @AfterEach
    void cleanCommittedRows() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        if (workspace != null) {
            int workspaceId = workspace.getId();
            jdbcTemplate.update("DELETE FROM person_identity WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person_employment WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM custom_field_definition WHERE workspace_id = ?", workspaceId);
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
        if (actor != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", actor.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void customSchemaRevisionCommittedWhileCreateWaitsRollsBackTheInsertedRecord() throws Exception {
        CountDownLatch setLocked = new CountDownLatch(1);
        CountDownLatch personInserted = new CountDownLatch(1);
        CountDownLatch releaseSchemaMutation = new CountDownLatch(1);
        RecordCreationTemplateMapper realTemplateMapper =
            sqlSessionTemplate.getMapper(RecordCreationTemplateMapper.class);
        CustomFieldDefinitionMapper realCustomFieldMapper =
            sqlSessionTemplate.getMapper(CustomFieldDefinitionMapper.class);
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            int result = realPersonMapper.insert(invocation.getArgument(0));
            personInserted.countDown();
            return result;
        }).when(personMapperSpy).insert(any(Person.class));
        String name = "Concurrent guided " + unique();
        GuidedPersonCreateRequestDto request = new GuidedPersonCreateRequestDto(
            new GuidedPersonRecordDto(name, null, null, null, null, null, null, null, null),
            new RecordCreationTemplateUseDto(
                "system:person:standard",
                1,
                0,
                RecordCreationEntryPoint.quick_create,
                new RecordCreationContextDto(null)),
            Map.of(),
            List.of());
        var executor = Executors.newFixedThreadPool(2);

        try {
            var schemaMutation = executor.submit(() -> {
                transaction().executeWithoutResult(status -> {
                    realTemplateMapper.getSetForUpdate(workspace.getId(), "person");
                    setLocked.countDown();
                    await(releaseSchemaMutation);
                    CustomFieldDefinition definition = new CustomFieldDefinition();
                    definition.setWorkspaceId(workspace.getId());
                    definition.setEntityType("person");
                    definition.setFieldKey("concurrent_schema");
                    definition.setLabel("Concurrent schema");
                    definition.setFieldType("text");
                    definition.setDataClassification("standard");
                    realCustomFieldMapper.insert(definition);
                    assertEquals(1, realTemplateMapper.advanceSetRevision(
                        workspace.getId(), "person", 0));
                });
                return null;
            });
            assertTrue(setLocked.await(10, TimeUnit.SECONDS));
            var creation = executor.submit(() -> withActor(() -> guidedService.createPerson(request)));
            assertTrue(personInserted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> creation.get(500, TimeUnit.MILLISECONDS));
            releaseSchemaMutation.countDown();

            schemaMutation.get(20, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> creation.get(20, TimeUnit.SECONDS));
            RecordCreationTemplateException stale = assertInstanceOf(
                RecordCreationTemplateException.class,
                failure.getCause());
            assertEquals("TEMPLATE_SET_STALE", stale.error().code());
        } finally {
            releaseSchemaMutation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person WHERE workspace_id = ? AND name = ?",
            Integer.class,
            workspace.getId(),
            name));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action = 'person.create'"
                + " AND target_label = ?",
            Integer.class,
            workspace.getId(),
            name));
    }

    private <T> T withActor(java.util.function.Supplier<T> action) {
        authenticate();
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
            tenantContext.clear();
        }
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

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent operation did not resume");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent operation was interrupted", exception);
        }
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
