package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationDefaultSpecDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefaultRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecordCreationTemplateConcurrencyIntegrationTest extends AbstractServiceTest {

    @Autowired private RecordCreationTemplateService templateService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private GlobalExceptionHandler exceptionHandler;
    @MockitoSpyBean private WorkspaceMapper workspaceMapperSpy;
    @MockitoSpyBean private PersonMapper personMapperSpy;
    @MockitoSpyBean private PipelineMapper pipelineMapperSpy;

    private Organization organization;
    private Workspace isolatedWorkspace;

    @BeforeEach
    void createIsolatedWorkspace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Template concurrency " + suffix);
        organization.setSlug("template-concurrency-" + suffix);
        organizationMapper.insert(organization);
        isolatedWorkspace = new Workspace();
        isolatedWorkspace.setOrgId(organization.getId());
        isolatedWorkspace.setName("Template concurrency " + suffix);
        isolatedWorkspace.setSlug("template-concurrency-" + suffix);
        workspaceMapper.insert(isolatedWorkspace);
        workspaceMapper.addMember(isolatedWorkspace.getId(), currentUser.getId(), "owner");
        workspace = isolatedWorkspace;
        authenticateAs(currentUser, isolatedWorkspace.getId());
    }

    @AfterEach
    void cleanCommittedRows() {
        SecurityContextHolder.clearContext();
        clearRequestContext();
        if (isolatedWorkspace != null) {
            int workspaceId = isolatedWorkspace.getId();
            jdbcTemplate.update(
                "UPDATE record_creation_template SET current_version_id = NULL WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update(
                "DELETE FROM record_creation_template_version WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update(
                "DELETE FROM record_creation_template WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update(
                "DELETE FROM record_creation_template_set WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM workspace_member WHERE user_id = ?", currentUser.getId());
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void personDependencyWaitsOnTheChildBeforeTheCompanyFkParent() throws Exception {
        Company company = newCompany();
        Person person = newPerson(null);
        RecordCreationTemplateDto template = createTemplate(
            RecordCreationRecordType.person,
            field("company", reference(company.getId())),
            field("referrerPerson", reference(person.getId())));
        CountDownLatch childLocked = new CountDownLatch(1);
        CountDownLatch templateLockAttempted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            templateLockAttempted.countDown();
            return realPersonMapper.getVisiblePersonByIdForUpdate(
                isolatedWorkspace.getId(), person.getId());
        }).when(personMapperSpy).getVisiblePersonByIdForUpdate(
            isolatedWorkspace.getId(), person.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Void> writer = executor.submit(() -> {
                transaction().executeWithoutResult(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM person WHERE workspace_id = ? AND id = ? FOR UPDATE",
                        Integer.class,
                        isolatedWorkspace.getId(),
                        person.getId());
                    childLocked.countDown();
                    await(releaseWriter);
                    jdbcTemplate.update(
                        "UPDATE person SET company_id = ? WHERE workspace_id = ? AND id = ?",
                        company.getId(),
                        isolatedWorkspace.getId(),
                        person.getId());
                });
                return null;
            });
            assertTrue(childLocked.await(10, TimeUnit.SECONDS));
            Future<?> mutation = executor.submit(() -> withActor(() -> templateService.setDefault(
                new RecordCreationTemplateDefaultRequestDto(
                    RecordCreationRecordType.person,
                    template.id(),
                    1))));
            assertTrue(templateLockAttempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> mutation.get(500, TimeUnit.MILLISECONDS));
            releaseWriter.countDown();

            writer.get(20, TimeUnit.SECONDS);
            mutation.get(20, TimeUnit.SECONDS);
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(company.getId(), jdbcTemplate.queryForObject(
            "SELECT company_id FROM person WHERE workspace_id = ? AND id = ?",
            Integer.class,
            isolatedWorkspace.getId(),
            person.getId()));
    }

    @Test
    void stageDependencyWaitsOnTheChildBeforeThePipelineFkParent() throws Exception {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        RecordCreationTemplateDto template = createTemplate(
            RecordCreationRecordType.deal,
            field("pipeline", reference(pipeline.getId())),
            field("stage", reference(stage.getId())));
        CountDownLatch childLocked = new CountDownLatch(1);
        CountDownLatch templateLockAttempted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        PipelineMapper realPipelineMapper = sqlSessionTemplate.getMapper(PipelineMapper.class);
        doAnswer(invocation -> {
            templateLockAttempted.countDown();
            return realPipelineMapper.getVisibleStageByIdForUpdate(
                isolatedWorkspace.getId(), stage.getId());
        }).when(pipelineMapperSpy).getVisibleStageByIdForUpdate(
            isolatedWorkspace.getId(), stage.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Void> writer = executor.submit(() -> {
                transaction().executeWithoutResult(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM stage WHERE workspace_id = ? AND id = ? FOR UPDATE",
                        Integer.class,
                        isolatedWorkspace.getId(),
                        stage.getId());
                    childLocked.countDown();
                    await(releaseWriter);
                    jdbcTemplate.update(
                        "INSERT INTO stage"
                            + " (workspace_id, name, pipeline_id, position, is_success, is_failure)"
                            + " VALUES (?, ?, ?, ?, FALSE, FALSE)",
                        isolatedWorkspace.getId(),
                        "Concurrent stage " + unique(),
                        pipeline.getId(),
                        50);
                });
                return null;
            });
            assertTrue(childLocked.await(10, TimeUnit.SECONDS));
            Future<?> mutation = executor.submit(() -> withActor(() -> templateService.setDefault(
                new RecordCreationTemplateDefaultRequestDto(
                    RecordCreationRecordType.deal,
                    template.id(),
                    1))));
            assertTrue(templateLockAttempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> mutation.get(500, TimeUnit.MILLISECONDS));
            releaseWriter.countDown();

            writer.get(20, TimeUnit.SECONDS);
            mutation.get(20, TimeUnit.SECONDS);
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stage WHERE workspace_id = ? AND pipeline_id = ?",
            Integer.class,
            isolatedWorkspace.getId(),
            pipeline.getId()));
    }

    @Test
    void committedPermissionRevocationRejectsTheWaitingMutationWithoutWrites() throws Exception {
        CountDownLatch revocationLocked = new CountDownLatch(1);
        CountDownLatch mutationLockAttempted = new CountDownLatch(1);
        CountDownLatch releaseRevocation = new CountDownLatch(1);
        WorkspaceMapper realWorkspaceMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            mutationLockAttempted.countDown();
            return realWorkspaceMapper.lockAuthorizationMembership(
                isolatedWorkspace.getId(), currentUser.getId());
        }).when(workspaceMapperSpy).lockAuthorizationMembership(
            isolatedWorkspace.getId(), currentUser.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Void> revocation = executor.submit(() -> {
                transaction().executeWithoutResult(status -> {
                    realWorkspaceMapper.lockAuthorizationMembership(
                        isolatedWorkspace.getId(), currentUser.getId());
                    realWorkspaceMapper.updateMemberRole(
                        isolatedWorkspace.getId(), currentUser.getId(), "member");
                    revocationLocked.countDown();
                    await(releaseRevocation);
                });
                return null;
            });
            assertTrue(revocationLocked.await(10, TimeUnit.SECONDS));
            Future<?> mutation = executor.submit(() -> withActor(() -> templateService.create(
                new RecordCreationTemplateCreateRequestDto(
                    RecordCreationRecordType.person,
                    names(),
                    null,
                    definition(field("name", null)),
                    true,
                    0))));
            assertTrue(mutationLockAttempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> mutation.get(500, TimeUnit.MILLISECONDS));
            releaseRevocation.countDown();

            revocation.get(20, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> mutation.get(20, TimeUnit.SECONDS));
            ForbiddenException forbidden = assertInstanceOf(
                ForbiddenException.class,
                failure.getCause());
            assertEquals(HttpStatus.FORBIDDEN, exceptionHandler.forbidden(forbidden).getStatusCode());
        } finally {
            releaseRevocation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(0, rowCount("record_creation_template_set"));
        assertEquals(0, rowCount("record_creation_template"));
        assertEquals(0, rowCount("record_creation_template_version"));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ?"
                + " AND action LIKE 'record_creation_template.%'",
            Integer.class,
            isolatedWorkspace.getId()));
        workspaceMapper.updateMemberRole(
            isolatedWorkspace.getId(), currentUser.getId(), "owner");
    }

    private RecordCreationTemplateDto createTemplate(
            RecordCreationRecordType recordType,
            RecordCreationTemplateFieldDto... fields) {
        return templateService.create(new RecordCreationTemplateCreateRequestDto(
            recordType,
            names(),
            null,
            definition(fields),
            true,
            0));
    }

    private <T> T withActor(java.util.function.Supplier<T> action) {
        authenticateAs(currentUser, isolatedWorkspace.getId());
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
            clearRequestContext();
        }
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?",
            Integer.class,
            isolatedWorkspace.getId());
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

    private static RecordCreationTemplateDefinitionDto definition(
            RecordCreationTemplateFieldDto... fields) {
        return new RecordCreationTemplateDefinitionDto(1, List.of(
            new RecordCreationTemplateGroupDto(
                "basics",
                new LocalizedTextDto("Basics", "基本情報"),
                null,
                List.of(fields))));
    }

    private static RecordCreationTemplateFieldDto field(
            String key,
            RecordCreationDefaultSpecDto defaultSpec) {
        return new RecordCreationTemplateFieldDto(key, false, null, null, defaultSpec);
    }

    private static RecordCreationDefaultSpecDto reference(int id) {
        return new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_reference,
            null,
            null,
            null,
            null,
            id,
            null);
    }

    private static LocalizedTextDto names() {
        return new LocalizedTextDto("Concurrency template", "並行テンプレート");
    }
}
