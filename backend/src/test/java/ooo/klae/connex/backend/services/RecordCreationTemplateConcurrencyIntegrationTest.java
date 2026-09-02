package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationDefaultSpecDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefaultRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecordCreationTemplateConcurrencyIntegrationTest extends AbstractServiceTest {

    @Autowired private RecordCreationTemplateService templateService;
    @Autowired private GuidedRecordCreationService guidedService;
    @Autowired private ImportService importService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private GlobalExceptionHandler exceptionHandler;
    @MockitoSpyBean private WorkspaceMapper workspaceMapperSpy;
    @MockitoSpyBean private PersonMapper personMapperSpy;
    @MockitoSpyBean private PipelineMapper pipelineMapperSpy;
    @MockitoSpyBean private RecordCreationTemplateMapper templateMapperSpy;
    @MockitoSpyBean private DealMapper dealMapperSpy;

    private Organization organization;
    private Workspace isolatedWorkspace;
    private User secondaryActor;

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
            jdbcTemplate.update("DELETE FROM custom_field_value WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM custom_field_definition WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update(
                "DELETE FROM record_creation_template_set WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update("DELETE FROM deal_stage_history WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update(
                "DELETE wrp FROM workspace_role_permission wrp"
                    + " JOIN workspace_role wr ON wr.id = wrp.workspace_role_id"
                    + " WHERE wr.workspace_id = ?",
                workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_role WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM workspace_member WHERE user_id = ?", currentUser.getId());
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
        if (secondaryActor != null) {
            jdbcTemplate.update("DELETE FROM workspace_member WHERE user_id = ?", secondaryActor.getId());
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", secondaryActor.getId());
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
    void guidedDealWaitsForSetDefaultBeforeAnyCoreInsert() throws Exception {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        RecordCreationTemplateDto template = createTemplate(
            RecordCreationRecordType.deal,
            field("pipeline", reference(pipeline.getId())),
            field("stage", reference(stage.getId())));
        User guidedActor = newUser();
        secondaryActor = guidedActor;
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(isolatedWorkspace.getId());
        role.setName("Guided deal creator " + unique());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(
            isolatedWorkspace.getId(), role.getId(), List.of("DEAL_CREATE"));
        workspaceMapper.setMemberCustomRole(
            isolatedWorkspace.getId(), guidedActor.getId(), role.getId());
        int templateRootId = Integer.parseInt(template.id().substring("workspace:".length()));
        CountDownLatch rootLocked = new CountDownLatch(1);
        CountDownLatch releaseDefaultMutation = new CountDownLatch(1);
        CountDownLatch guidedSetFenceAttempted = new CountDownLatch(1);
        CountDownLatch dealInsertAttempted = new CountDownLatch(1);
        AtomicBoolean pauseFirstRootLock = new AtomicBoolean(true);
        AtomicInteger setFenceCalls = new AtomicInteger();
        RecordCreationTemplateMapper realTemplateMapper =
            sqlSessionTemplate.getMapper(RecordCreationTemplateMapper.class);
        DealMapper realDealMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            if (setFenceCalls.incrementAndGet() == 2) {
                guidedSetFenceAttempted.countDown();
            }
            realTemplateMapper.insertSetIfAbsent(
                isolatedWorkspace.getId(), RecordCreationRecordType.deal.name());
            return null;
        }).when(templateMapperSpy).insertSetIfAbsent(
            isolatedWorkspace.getId(), RecordCreationRecordType.deal.name());
        doAnswer(invocation -> {
            var root = realTemplateMapper.getRootForUpdate(
                isolatedWorkspace.getId(), templateRootId);
            if (pauseFirstRootLock.compareAndSet(true, false)) {
                rootLocked.countDown();
                await(releaseDefaultMutation);
            }
            return root;
        }).when(templateMapperSpy).getRootForUpdate(
            isolatedWorkspace.getId(), templateRootId);
        doAnswer(invocation -> {
            dealInsertAttempted.countDown();
            return realDealMapper.insert(invocation.getArgument(0));
        }).when(dealMapperSpy).insert(any(Deal.class));
        String dealName = "Concurrent guided deal " + unique();
        GuidedDealCreateRequestDto request = new GuidedDealCreateRequestDto(
            new GuidedDealRecordDto(
                dealName,
                new BigDecimal("1000.00"),
                "USD",
                pipeline.getId(),
                stage.getId(),
                null,
                null,
                null),
            new RecordCreationTemplateUseDto(
                template.id(),
                1,
                1,
                RecordCreationEntryPoint.quick_create,
                new RecordCreationContextDto(null)),
            Map.of(),
            List.of());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> defaultMutation = executor.submit(() -> withActor(() -> templateService.setDefault(
                new RecordCreationTemplateDefaultRequestDto(
                    RecordCreationRecordType.deal,
                    template.id(),
                    1))));
            assertTrue(rootLocked.await(10, TimeUnit.SECONDS));
            Future<?> creation = executor.submit(
                () -> withActor(guidedActor, () -> guidedService.createDeal(request)));
            assertTrue(guidedSetFenceAttempted.await(10, TimeUnit.SECONDS));
            assertFalse(dealInsertAttempted.await(500, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> creation.get(500, TimeUnit.MILLISECONDS));
            releaseDefaultMutation.countDown();

            defaultMutation.get(20, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> creation.get(20, TimeUnit.SECONDS));
            RecordCreationTemplateException stale = assertInstanceOf(
                RecordCreationTemplateException.class,
                failure.getCause());
            assertEquals("TEMPLATE_SET_STALE", stale.error().code());
        } finally {
            releaseDefaultMutation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal WHERE workspace_id = ? AND name = ?",
            Integer.class,
            isolatedWorkspace.getId(),
            dealName));
    }

    @Test
    void personImportCustomFieldFencePrecedesTargetAndComposesWithSetDefault()
            throws Exception {
        Person target = newPerson(null);
        RecordCreationTemplateDto template = createTemplate(
            RecordCreationRecordType.person,
            field("referrerPerson", reference(target.getId())));
        User importActor = newUser();
        secondaryActor = importActor;
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(isolatedWorkspace.getId());
        role.setName("Import custom field " + unique());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(
            isolatedWorkspace.getId(),
            role.getId(),
            List.of("PERSON_CREATE", "PERSON_UPDATE", "CUSTOM_FIELD_MANAGE"));
        workspaceMapper.setMemberCustomRole(
            isolatedWorkspace.getId(), importActor.getId(), role.getId());
        String customLabel = "Import fence " + unique();
        ImportRequest request = new ImportRequest(
            List.of(Map.of(
                "Name", target.getName(),
                "Custom", "locked value")),
            List.of(
                new ColumnMapping("Name", "name", null, null, null),
                new ColumnMapping("Custom", null, true, "text", customLabel)),
            "overwrite",
            Map.of(0, target.getId()));
        request.setDuplicateReviewProof(withActor(
            importActor,
            () -> importService.previewPersons(request).getDuplicateReviewProof()));
        CountDownLatch writerPersonDependencyAttempted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch importSetFenceAttempted = new CountDownLatch(1);
        CountDownLatch importPersonLockAttempted = new CountDownLatch(1);
        AtomicInteger setFenceCalls = new AtomicInteger();
        RecordCreationTemplateMapper realTemplateMapper =
            sqlSessionTemplate.getMapper(RecordCreationTemplateMapper.class);
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            if (setFenceCalls.incrementAndGet() == 2) {
                importSetFenceAttempted.countDown();
            }
            realTemplateMapper.insertSetIfAbsent(
                isolatedWorkspace.getId(), RecordCreationRecordType.person.name());
            return null;
        }).when(templateMapperSpy).insertSetIfAbsent(
            isolatedWorkspace.getId(), RecordCreationRecordType.person.name());
        doAnswer(invocation -> {
            writerPersonDependencyAttempted.countDown();
            await(releaseWriter);
            return realPersonMapper.getVisiblePersonByIdForUpdate(
                isolatedWorkspace.getId(), target.getId());
        }).when(personMapperSpy).getVisiblePersonByIdForUpdate(
            isolatedWorkspace.getId(), target.getId());
        doAnswer(invocation -> {
            importPersonLockAttempted.countDown();
            return realPersonMapper.getOwnedPersonByIdForUpdate(
                isolatedWorkspace.getId(), target.getId());
        }).when(personMapperSpy).getOwnedPersonByIdForUpdate(
            isolatedWorkspace.getId(), target.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> setDefault = executor.submit(() -> withActor(() -> templateService.setDefault(
                new RecordCreationTemplateDefaultRequestDto(
                    RecordCreationRecordType.person,
                    template.id(),
                    1))));
            assertTrue(writerPersonDependencyAttempted.await(10, TimeUnit.SECONDS));
            Future<ImportResult> importCommit = executor.submit(() -> withActor(
                importActor,
                () -> importService.commitPersons(request)));
            assertTrue(importSetFenceAttempted.await(10, TimeUnit.SECONDS));
            assertFalse(importPersonLockAttempted.await(500, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> importCommit.get(500, TimeUnit.MILLISECONDS));
            releaseWriter.countDown();

            setDefault.get(20, TimeUnit.SECONDS);
            ImportResult result = importCommit.get(20, TimeUnit.SECONDS);
            assertEquals(1, result.getUpdated());
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM custom_field_definition"
                + " WHERE workspace_id = ? AND entity_type = 'person' AND label = ?",
            Integer.class,
            isolatedWorkspace.getId(),
            customLabel));
    }

    @Test
    void personImportRejectsADefinitionThatAppearsAfterTheFenceIsTaken() throws Exception {
        Person target = newPerson(null);
        User importActor = newUser();
        secondaryActor = importActor;
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(isolatedWorkspace.getId());
        role.setName("Import appearance " + unique());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(
            isolatedWorkspace.getId(),
            role.getId(),
            List.of("PERSON_CREATE", "PERSON_UPDATE", "CUSTOM_FIELD_MANAGE"));
        workspaceMapper.setMemberCustomRole(
            isolatedWorkspace.getId(), importActor.getId(), role.getId());
        String customLabel = "appearfield" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ImportRequest request = new ImportRequest(
            List.of(Map.of(
                "Name", target.getName(),
                "Custom", "locked value")),
            List.of(
                new ColumnMapping("Name", "name", null, null, null),
                new ColumnMapping("Custom", null, true, "text", customLabel)),
            "overwrite",
            Map.of(0, target.getId()));
        request.setDuplicateReviewProof(withActor(
            importActor,
            () -> importService.previewPersons(request).getDuplicateReviewProof()));
        CountDownLatch importSetFenceAttempted = new CountDownLatch(1);
        CountDownLatch releaseImport = new CountDownLatch(1);
        RecordCreationTemplateMapper realTemplateMapper =
            sqlSessionTemplate.getMapper(RecordCreationTemplateMapper.class);
        doAnswer(invocation -> {
            importSetFenceAttempted.countDown();
            await(releaseImport);
            realTemplateMapper.insertSetIfAbsent(
                isolatedWorkspace.getId(), RecordCreationRecordType.person.name());
            return null;
        }).when(templateMapperSpy).insertSetIfAbsent(
            isolatedWorkspace.getId(), RecordCreationRecordType.person.name());
        ExecutorService executor = Executors.newFixedThreadPool(1);

        try {
            Future<ImportResult> importCommit = executor.submit(() -> withActor(
                importActor,
                () -> importService.commitPersons(request)));
            assertTrue(importSetFenceAttempted.await(10, TimeUnit.SECONDS));
            transaction().executeWithoutResult(status -> jdbcTemplate.update(
                "INSERT INTO custom_field_definition"
                    + " (workspace_id, entity_type, field_key, label, field_type)"
                    + " VALUES (?, 'person', ?, ?, 'text')",
                isolatedWorkspace.getId(),
                customLabel,
                customLabel));
            releaseImport.countDown();

            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> importCommit.get(20, TimeUnit.SECONDS));
            assertInstanceOf(ConflictException.class, failure.getCause());
        } finally {
            releaseImport.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM custom_field_value cfv"
                + " JOIN custom_field_definition cfd ON cfd.id = cfv.definition_id"
                + " WHERE cfv.workspace_id = ? AND cfd.label = ?",
            Integer.class,
            isolatedWorkspace.getId(),
            customLabel));
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
        return withActor(currentUser, action);
    }

    private <T> T withActor(User actor, java.util.function.Supplier<T> action) {
        authenticateAs(actor, isolatedWorkspace.getId());
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
