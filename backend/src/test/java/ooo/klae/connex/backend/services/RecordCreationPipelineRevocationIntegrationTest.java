package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationDefaultSpecDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefaultRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecordCreationPipelineRevocationIntegrationTest extends AbstractServiceTest {
    @Autowired private RecordCreationTemplateService templateService;
    @Autowired private GuidedRecordCreationService guidedService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sessions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private PipelineMapper pipelines;
    @MockitoSpyBean private WorkspaceMapper workspaces;

    private Organization organization;
    private Workspace viewer;
    private Workspace owner;

    @BeforeEach
    void createCommittedWorkspaces() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Pipeline revocation " + suffix);
        organization.setSlug("pipeline-revocation-" + suffix);
        organizationMapper.insert(organization);
        viewer = workspace("Pipeline viewer " + suffix, "pipeline-viewer-" + suffix);
        owner = workspace("Pipeline owner " + suffix, "pipeline-owner-" + suffix);
        workspaceMapper.addMember(viewer.getId(), currentUser.getId(), "owner");
        workspace = viewer;
        authenticateAs(currentUser, viewer.getId());
    }

    @AfterEach
    void removeCommittedWorkspaces() {
        SecurityContextHolder.clearContext();
        clearRequestContext();
        if (viewer != null) {
            jdbc.update("UPDATE record_creation_template SET current_version_id = NULL WHERE workspace_id = ?", viewer.getId());
            jdbc.update("DELETE FROM record_creation_template_version WHERE workspace_id = ?", viewer.getId());
            jdbc.update("DELETE FROM record_creation_template WHERE workspace_id = ?", viewer.getId());
            jdbc.update("DELETE FROM record_creation_template_set WHERE workspace_id = ?", viewer.getId());
            jdbc.update("DELETE FROM pipeline_share WHERE workspace_id = ?", viewer.getId());
            jdbc.update("DELETE FROM deal_stage_history WHERE workspace_id = ?", viewer.getId());
            jdbc.update("DELETE FROM deal WHERE workspace_id = ?", viewer.getId());
        }
        for (Workspace target : List.of(owner, viewer)) {
            jdbc.update("DELETE FROM stage WHERE workspace_id = ?", target.getId());
            jdbc.update("DELETE FROM pipeline WHERE workspace_id = ?", target.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", target.getId());
            jdbc.update("DELETE wrp FROM workspace_role_permission wrp JOIN workspace_role wr"
                + " ON wr.id = wrp.workspace_role_id WHERE wr.workspace_id = ?", target.getId());
            jdbc.update("DELETE FROM workspace_role WHERE workspace_id = ?", target.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", target.getId());
        }
        jdbc.update("DELETE FROM workspace_member WHERE user_id = ?", currentUser.getId());
        jdbc.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
    }

    @Test
    void defaultSelectionRefusesAShareRevokedWhileWaitingOnTheStage() throws Exception {
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(owner.getId());
        pipeline.setName("Shared template pipeline");
        pipelines.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setWorkspaceId(owner.getId());
        stage.setPipeline(pipeline);
        stage.setName("Shared template stage");
        stage.setPosition(0);
        pipelines.insertStage(stage);
        jdbc.update("INSERT INTO pipeline_share (pipeline_id, workspace_id) VALUES (?, ?)", pipeline.getId(), viewer.getId());
        var template = templateService.create(request(pipeline.getId(), stage.getId()));
        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PipelineMapper real = sessions.getMapper(PipelineMapper.class);
        doAnswer(invocation -> {
            attempted.countDown();
            return real.getVisibleStageByIdForUpdate(
                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
        }).when(pipelines).getVisibleStageByIdForUpdate(eq(viewer.getId()), anyString(), eq(stage.getId()));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var revocation = executor.submit(() -> transaction().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM stage WHERE id = ? FOR UPDATE", Integer.class, stage.getId());
                jdbc.queryForObject("SELECT id FROM pipeline WHERE id = ? FOR UPDATE", Integer.class, pipeline.getId());
                jdbc.update("DELETE FROM pipeline_share WHERE pipeline_id = ? AND workspace_id = ?", pipeline.getId(), viewer.getId());
                revoked.countDown();
                await(release);
            }));
            assertTrue(revoked.await(10, TimeUnit.SECONDS));
            var mutation = executor.submit(() -> {
                authenticateAs(currentUser, viewer.getId());
                try {
                    return templateService.setDefault(new RecordCreationTemplateDefaultRequestDto(
                        RecordCreationRecordType.deal, template.id(), 1));
                } finally {
                    SecurityContextHolder.clearContext();
                    clearRequestContext();
                }
            });
            assertTrue(attempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> mutation.get(250, TimeUnit.MILLISECONDS));
            release.countDown();
            revocation.get(20, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> mutation.get(20, TimeUnit.SECONDS));
            var refused = assertInstanceOf(RecordCreationTemplateException.class, failure.getCause());
            assertEquals("TEMPLATE_CATALOG_UNAVAILABLE", refused.error().code());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM record_creation_template_set"
            + " WHERE workspace_id = ? AND default_template_id IS NOT NULL", Integer.class, viewer.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE workspace_id = ?"
            + " AND action = 'record_creation_template.default_set'", Integer.class, viewer.getId()));
    }

    @Test
    void customRoleWithoutTemplateManageCannotValidatePipelineDefaultsIntoWrites() {
        jdbc.update("INSERT INTO workspace_role (workspace_id, name) VALUES (?, 'Pipeline reader')", viewer.getId());
        int roleId = jdbc.queryForObject("SELECT id FROM workspace_role WHERE workspace_id = ? AND name = 'Pipeline reader'", Integer.class, viewer.getId());
        jdbc.update("UPDATE workspace_member SET role = 'member', role_id = ? WHERE workspace_id = ? AND user_id = ?",
            roleId, viewer.getId(), currentUser.getId());
        authenticateAs(currentUser, viewer.getId());

        assertThrows(ForbiddenException.class, () -> templateService.create(request(null, null)));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM record_creation_template WHERE workspace_id = ?", Integer.class, viewer.getId()));
        workspaceMapper.updateMemberRole(viewer.getId(), currentUser.getId(), "owner");
    }

    @Test
    void guidedCreationRefusesPermissionRevokedWhileWaitingForItsAuthorizationLock() throws Exception {
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(viewer.getId());
        pipeline.setName("Guided permission pipeline");
        pipelines.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setWorkspaceId(viewer.getId());
        stage.setPipeline(pipeline);
        stage.setName("Guided permission stage");
        pipelines.insertStage(stage);
        GuidedDealCreateRequestDto request = new GuidedDealCreateRequestDto(
            new GuidedDealRecordDto("Revoked guided deal", BigDecimal.TEN, "USD", pipeline.getId(), stage.getId(), null, null, null),
            new RecordCreationTemplateUseDto("system:deal:standard", 1, 0,
                RecordCreationEntryPoint.quick_create, new RecordCreationContextDto(null)), Map.of(), List.of());
        jdbc.update("INSERT INTO workspace_role (workspace_id, name) VALUES (?, 'Revoked deal creator')", viewer.getId());
        int role = jdbc.queryForObject("SELECT id FROM workspace_role WHERE workspace_id = ? AND name = 'Revoked deal creator'",
            Integer.class, viewer.getId());
        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorkspaceMapper real = sessions.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            attempted.countDown();
            return real.lockAuthorizationMembership(viewer.getId(), currentUser.getId());
        }).when(workspaces).lockAuthorizationMembership(viewer.getId(), currentUser.getId());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var revocation = executor.submit(() -> transaction().executeWithoutResult(status -> {
                real.lockAuthorizationMembership(viewer.getId(), currentUser.getId());
                jdbc.update("UPDATE workspace_member SET role = 'member', role_id = ? WHERE workspace_id = ? AND user_id = ?",
                    role, viewer.getId(), currentUser.getId());
                revoked.countDown();
                await(release);
            }));
            assertTrue(revoked.await(10, TimeUnit.SECONDS));
            var mutation = executor.submit(() -> {
                authenticateAs(currentUser, viewer.getId());
                try {
                    return guidedService.createDeal(request);
                } finally {
                    SecurityContextHolder.clearContext();
                    clearRequestContext();
                }
            });
            assertTrue(attempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> mutation.get(250, TimeUnit.MILLISECONDS));
            release.countDown();
            revocation.get(20, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> mutation.get(20, TimeUnit.SECONDS));
            assertInstanceOf(ForbiddenException.class, failure.getCause());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM deal WHERE workspace_id = ?", Integer.class, viewer.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM deal_stage_history WHERE workspace_id = ?", Integer.class, viewer.getId()));
        workspaceMapper.updateMemberRole(viewer.getId(), currentUser.getId(), "owner");
    }

    private Workspace workspace(String name, String slug) {
        Workspace result = new Workspace();
        result.setOrgId(organization.getId());
        result.setName(name);
        result.setSlug(slug);
        workspaceMapper.insert(result);
        return result;
    }

    private static RecordCreationTemplateCreateRequestDto request(Integer pipelineId, Integer stageId) {
        var names = new LocalizedTextDto("Pipeline template", "パイプライン");
        var fields = List.of(
            new RecordCreationTemplateFieldDto("pipeline", false, null, null, reference(pipelineId)),
            new RecordCreationTemplateFieldDto("stage", false, null, null, reference(stageId)));
        return new RecordCreationTemplateCreateRequestDto(RecordCreationRecordType.deal, names, null,
            new RecordCreationTemplateDefinitionDto(1, List.of(
                new RecordCreationTemplateGroupDto("basics", names, null, fields))), true, 0);
    }

    private static RecordCreationDefaultSpecDto reference(Integer id) {
        return id == null ? null : new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_reference, null, null, null, null, id, null);
    }

    private TransactionTemplate transaction() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Revocation did not resume");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
