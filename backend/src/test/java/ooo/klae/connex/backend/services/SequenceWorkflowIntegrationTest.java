package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
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

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.dto.TenantResidualReport;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.dto.sequence.SequenceDto;
import ooo.klae.connex.backend.dto.sequence.SequencePreviewDto;
import ooo.klae.connex.backend.dto.sequence.SequencePreviewRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceStepRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceStepType;
import ooo.klae.connex.backend.dto.sequence.SequenceVersionDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.SequenceException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.SequenceMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Real-database sequence workflow, transaction, concurrency, preview, and lifecycle proofs. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "connex.sequences.enabled=true",
        "connex.tenant-lifecycle.teardown-settle-delay=0s",
        "spring.task.scheduling.enabled=false"
    })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SequenceWorkflowIntegrationTest {
    @Autowired private SequenceService sequenceService;
    @Autowired private SequenceVersionService versionService;
    @Autowired private SequencePreviewService previewService;
    @Autowired private TenantExportService exportService;
    @Autowired private TenantTeardownService teardownService;
    @Autowired private UserOffboardingService offboardingService;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private TenantLifecycleControlMapper lifecycleControlMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private GlobalExceptionHandler exceptionHandler;
    @Autowired private TenantContext tenantContext;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private SequenceMapper sequenceMapper;
    @MockitoSpyBean private PersonMapper personMapper;
    @MockitoSpyBean private DeliveryProviderRouter deliveryProviderRouter;
    @MockitoSpyBean private WorkspaceService workspaceService;

    private Organization organization;
    private Workspace workspace;
    private Workspace otherWorkspace;
    private User manager;
    private User viewer;
    private User otherManager;

    @BeforeEach
    void setUp() {
        String suffix = unique();
        organization = new Organization();
        organization.setName("Sequence workflow " + suffix);
        organization.setSlug("sequence-workflow-" + suffix);
        organizationMapper.insert(organization);
        workspace = workspace("Sequence primary", organization.getId());
        otherWorkspace = workspace("Sequence other", organization.getId());
        manager = member(
            workspace,
            "sequence_manager_" + suffix,
            List.of("SEQUENCE_VIEW", "SEQUENCE_MANAGE"));
        viewer = member(
            workspace,
            "sequence_viewer_" + suffix,
            List.of("SEQUENCE_VIEW"));
        otherManager = member(
            otherWorkspace,
            "sequence_other_" + suffix,
            List.of("SEQUENCE_VIEW", "SEQUENCE_MANAGE"));
        orgMemberService.addFoundingOwner(organization.getId(), manager.getId());
    }

    @AfterEach
    void cleanUp() {
        clearContext();
        LocaleContextHolder.resetLocaleContext();
        if (workspace != null && otherWorkspace != null) {
            List<Integer> workspaceIds = List.of(workspace.getId(), otherWorkspace.getId());
            jdbcTemplate.update(
                "DELETE ps FROM person_share ps LEFT JOIN person p ON p.id = ps.person_id"
                    + " WHERE ps.workspace_id IN (?, ?) OR p.workspace_id IN (?, ?)",
                workspace.getId(), otherWorkspace.getId(), workspace.getId(), otherWorkspace.getId());
            jdbcTemplate.update(
                "DELETE dp FROM deal_person dp JOIN deal d ON d.id = dp.deal_id"
                    + " WHERE d.workspace_id IN (?, ?)",
                workspace.getId(), otherWorkspace.getId());
            for (int workspaceId : workspaceIds) {
                jdbcTemplate.update("DELETE FROM sequence WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM task WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM activity WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM notification WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM campaign_delivery WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM campaign_send WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM tenant_operation_lease WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM tenant_cleanup_tombstone WHERE workspace_id = ?", workspaceId);
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
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM org_member WHERE org_id = ?", organization.getId());
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
        if (manager != null && viewer != null && otherManager != null) {
            jdbcTemplate.update(
                "DELETE FROM app_user WHERE id IN (?, ?, ?)",
                manager.getId(), viewer.getId(), otherManager.getId());
        }
    }

    @Test
    void createPublishRepublishAndArchivePreservePriorBytesAndBodyFreeAudits() {
        SequenceDto created = as(manager, workspace,
            () -> sequenceService.create(draft("First sequence", "personal", "FIRST_BODY_SECRET")));
        SequenceVersionDto first = as(manager, workspace, () -> versionService.publish(created.id()));
        VersionBytes before = versionBytes(created.id(), 1);

        SequenceDto updated = as(manager, workspace,
            () -> sequenceService.update(
                created.id(), draft("Updated sequence", "personal", "SECOND_BODY_SECRET")));
        SequenceVersionDto second = as(manager, workspace, () -> versionService.publish(created.id()));
        VersionBytes after = versionBytes(created.id(), 1);
        List<SequenceVersionDto> versions = as(
            manager, workspace, () -> versionService.list(created.id()));
        as(manager, workspace, () -> {
            sequenceService.archive(created.id());
            return null;
        });

        assertEquals(List.of("en", "ja"), created.steps().getFirst().contents().stream()
            .map(content -> content.locale()).toList());
        assertEquals("SECOND_BODY_SECRET", updated.steps().getFirst().contents().getFirst().bodyText());
        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertEquals(List.of(2, 1), versions.stream().map(SequenceVersionDto::version).toList());
        assertNotEquals(first.definitionHash(), second.definitionHash());
        assertArrayEquals(before.definitionJson(), after.definitionJson());
        assertArrayEquals(before.definitionHash(), after.definitionHash());

        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
            "SELECT action, actor_id, entity_id, outcome, summary, changes"
                + " FROM audit_log WHERE workspace_id = ? AND action LIKE 'sequence.%' ORDER BY id",
            workspace.getId());
        assertEquals(
            List.of(
                "sequence.create",
                "sequence.publish",
                "sequence.update",
                "sequence.publish",
                "sequence.archive"),
            audits.stream().map(row -> row.get("action")).toList());
        for (Map<String, Object> audit : audits) {
            assertEquals(manager.getId(), ((Number) audit.get("actor_id")).intValue());
            assertEquals(created.id(), ((Number) audit.get("entity_id")).intValue());
            assertEquals("success", audit.get("outcome"));
            String serialized = String.valueOf(audit.get("summary")) + audit.get("changes");
            assertFalse(serialized.contains("FIRST_BODY_SECRET"));
            assertFalse(serialized.contains("SECOND_BODY_SECRET"));
        }
    }

    @Test
    void publisherErasureLeavesEveryImmutableVersionColumnByteIdentical() {
        SequenceDto created = as(manager, workspace,
            () -> sequenceService.create(draft("Publisher erasure", "personal", "Immutable body")));
        as(manager, workspace, () -> versionService.publish(created.id()));
        long versionId = jdbcTemplate.queryForObject(
            "SELECT id FROM sequence_version WHERE workspace_id = ? AND sequence_id = ?",
            Long.class, workspace.getId(), created.id());
        jdbcTemplate.update(
            "UPDATE sequence_version_publisher SET published_by_id = ?"
                + " WHERE workspace_id = ? AND version_id = ?",
            viewer.getId(), workspace.getId(), versionId);
        byte[] before = completeVersionRowBytes(versionId);

        as(manager, workspace, () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> offboardingService.eraseOrgDataReferences(viewer.getId()));
            return null;
        });

        assertArrayEquals(before, completeVersionRowBytes(versionId));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT published_by_id FROM sequence_version_publisher"
                + " WHERE workspace_id = ? AND version_id = ?",
            Integer.class, workspace.getId(), versionId));
    }

    @Test
    void multibyteBodyThatExceededTextCapacityPersistsThroughTheTransactionalProxy() {
        String japaneseBody = "界".repeat(30_000);
        SequenceRequest request = draft("Multibyte sequence", "personal", japaneseBody);

        SequenceDto created = as(manager, workspace, () -> sequenceService.create(request));

        assertEquals(
            japaneseBody,
            created.steps().getFirst().contents().getFirst().bodyText());
        assertEquals(
            japaneseBody.getBytes(StandardCharsets.UTF_8).length,
            jdbcTemplate.queryForObject(
                "SELECT OCTET_LENGTH(body_text) FROM sequence_step_content"
                    + " WHERE workspace_id = ? AND locale = 'en'",
                Integer.class,
                workspace.getId()));
    }

    @Test
    void concurrentPublishersSerializeToDistinctVersionNumbers() throws Exception {
        SequenceDto created = as(manager, workspace,
            () -> sequenceService.create(draft("Concurrent publish", "personal", "Body")));
        SequenceVersionDto baseline = as(
            manager, workspace, () -> versionService.publish(created.id()));
        assertEquals(1, baseline.version());
        CountDownLatch bothSnapshotsEstablished = new CountDownLatch(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SequenceVersionDto> first = executor.submit(
                () -> publishAfterSnapshotBarrier(created.id(), bothSnapshotsEstablished));
            Future<SequenceVersionDto> second = executor.submit(
                () -> publishAfterSnapshotBarrier(created.id(), bothSnapshotsEstablished));
            List<Integer> versions = List.of(
                first.get(30, TimeUnit.SECONDS).version(),
                second.get(30, TimeUnit.SECONDS).version()).stream().sorted().toList();
            assertEquals(List.of(2, 3), versions);
        } finally {
            while (bothSnapshotsEstablished.getCount() > 0) {
                bothSnapshotsEstablished.countDown();
            }
        }

        assertEquals(3, rowCount("sequence_version"));
        assertEquals(3, rowCount("sequence_version_publisher"));
    }

    @Test
    void createRollsBackRootAndStepsWhenTheSecondLocalizedInsertFails() {
        doThrow(new IllegalStateException("localized insert failed"))
            .when(sequenceMapper)
            .insertStepContent(argThat(content -> "ja".equals(content.getLocale())));

        assertThrows(
            IllegalStateException.class,
            () -> as(manager, workspace,
                () -> sequenceService.create(draft("Rollback create", "personal", "Body"))));

        assertEquals(0, rowCount("sequence"));
        assertEquals(0, rowCount("sequence_step"));
        assertEquals(0, rowCount("sequence_step_content"));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action = 'sequence.create'",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void updateRollsBackMetadataAndDraftWhenTheSecondLocalizedInsertFails() {
        SequenceDto created = as(manager, workspace,
            () -> sequenceService.create(draft("Original", "personal", "Original body")));
        doThrow(new IllegalStateException("localized insert failed"))
            .when(sequenceMapper)
            .insertStepContent(argThat(content -> "ja".equals(content.getLocale())));

        assertThrows(
            IllegalStateException.class,
            () -> as(manager, workspace,
                () -> sequenceService.update(
                    created.id(), draft("Replacement", "personal", "Replacement body"))));

        SequenceDto retained = as(manager, workspace, () -> sequenceService.get(created.id()));
        assertEquals("Original", retained.name());
        assertEquals("Original body", retained.steps().getFirst().contents().getFirst().bodyText());
        assertEquals(1, rowCount("sequence_step"));
        assertEquals(2, rowCount("sequence_step_content"));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action = 'sequence.update'",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void permissionRevokedAfterPreliminaryAuthorizationFailsBeforePayloadLoad() throws Exception {
        SequenceDto created = as(manager, workspace,
            () -> sequenceService.create(draft("Permission snapshot", "shared", "Protected body")));
        int viewerRoleId = jdbcTemplate.queryForObject(
            "SELECT role_id FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
            Integer.class,
            workspace.getId(),
            viewer.getId());
        CountDownLatch preliminaryAuthorizationPassed = new CountDownLatch(1);
        CountDownLatch revocationCommitted = new CountDownLatch(1);
        pauseAfterPreliminaryViewAuthorization(
            preliminaryAuthorizationPassed, revocationCommitted);
        clearInvocations(sequenceMapper);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<SequenceDto> deniedRead = executor.submit(
                () -> as(viewer, workspace, () -> sequenceService.get(created.id())));
            assertTrue(preliminaryAuthorizationPassed.await(10, TimeUnit.SECONDS));
            assertEquals(1, jdbcTemplate.update(
                "DELETE FROM workspace_role_permission"
                    + " WHERE workspace_role_id = ? AND permission = 'SEQUENCE_VIEW'",
                viewerRoleId));
            revocationCommitted.countDown();

            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> deniedRead.get(30, TimeUnit.SECONDS));
            ForbiddenException inaccessible = assertInstanceOf(
                ForbiddenException.class, failure.getCause());
            assertEquals(403, exceptionHandler.forbidden(inaccessible).getStatusCode().value());
        } finally {
            revocationCommitted.countDown();
        }

        verify(sequenceMapper, never()).getVisibleSequence(
            workspace.getId(), created.id(), viewer.getId());
        verify(sequenceMapper, never()).getSteps(workspace.getId(), created.id());
    }

    @Test
    void sharedSequenceMadePersonalAfterPreliminaryAuthorizationFailsBeforeDraftLoad() throws Exception {
        SequenceDto created = as(manager, workspace,
            () -> sequenceService.create(draft("Visibility snapshot", "shared", "Protected body")));
        CountDownLatch preliminaryAuthorizationPassed = new CountDownLatch(1);
        CountDownLatch visibilityChangeCommitted = new CountDownLatch(1);
        pauseAfterPreliminaryViewAuthorization(
            preliminaryAuthorizationPassed, visibilityChangeCommitted);
        clearInvocations(sequenceMapper);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<SequenceDto> deniedRead = executor.submit(
                () -> as(viewer, workspace, () -> sequenceService.get(created.id())));
            assertTrue(preliminaryAuthorizationPassed.await(10, TimeUnit.SECONDS));
            as(manager, workspace, () -> sequenceService.update(
                created.id(), draft("Private replacement", "personal", "Private body")));
            clearInvocations(sequenceMapper);
            visibilityChangeCommitted.countDown();

            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> deniedRead.get(30, TimeUnit.SECONDS));
            SequenceException inaccessible = assertInstanceOf(
                SequenceException.class, failure.getCause());
            assertEquals(404, inaccessible.status().value());
        } finally {
            visibilityChangeCommitted.countDown();
        }

        verify(sequenceMapper, never()).getSteps(workspace.getId(), created.id());
        verify(sequenceMapper, never()).getStepContents(anyInt(), any());
    }

    @Test
    void previewRendersEveryStepWithHonestFallbackAndNoWriteOrEgress() {
        SequenceDto created = as(manager, workspace,
            () -> sequenceService.create(previewDraft("Preview all steps")));
        SequenceVersionDto published = as(
            manager, workspace, () -> versionService.publish(created.id()));
        SequenceVersionDto read = as(
            manager, workspace, () -> versionService.get(created.id(), published.version()));
        Person person = person(workspace, manager.getId(), "<Mina>", null);
        Map<String, Integer> before = sideEffectCounts();
        clearInvocations(deliveryProviderRouter);
        LocaleContextHolder.setLocale(Locale.JAPANESE);

        SequencePreviewDto preview = as(manager, workspace, () -> previewService.preview(
            created.id(), 1, new SequencePreviewRequest(person.getId())));

        assertEquals(5, preview.steps().size());
        List<String> expectedDelays = List.of(
            "0 hours", "1 business_days", "2 hours", "3 hours", "0 hours");
        assertEquals(expectedDelays, created.steps().stream()
            .map(step -> step.delayValue() + " " + step.delayUnit()).toList());
        assertEquals(expectedDelays, published.steps().stream()
            .map(step -> step.delayValue() + " " + step.delayUnit()).toList());
        assertEquals(expectedDelays, read.steps().stream()
            .map(step -> step.delayValue() + " " + step.delayUnit()).toList());
        assertEquals(
            Arrays.asList("ja", "en", "ja", null, "en"),
            preview.steps().stream().map(SequencePreviewDto.RenderedStep::locale).toList());
        assertEquals("こんにちは <Mina>", preview.steps().getFirst().subject());
        assertEquals(
            List.of("company.name", "deal.name", "person.email"),
            preview.unresolvedMergeFields());

        Company company = company(workspace, manager.getId(), "Acme & Partners");
        assertEquals(1, jdbcTemplate.update(
            "UPDATE person SET company_id = ? WHERE workspace_id = ? AND id = ?",
            company.getId(), workspace.getId(), person.getId()));
        deal(workspace, manager.getId(), company.getId(), person.getId(), "Expansion");

        SequencePreviewDto relatedPreview = as(manager, workspace, () -> previewService.preview(
            created.id(), 1, new SequencePreviewRequest(person.getId())));
        assertEquals("会社 Acme & Partners", relatedPreview.steps().getFirst().bodyText());
        assertEquals("Call about Expansion", relatedPreview.steps().get(1).bodyText());
        assertEquals(List.of("person.email"), relatedPreview.unresolvedMergeFields());
        assertEquals(before, sideEffectCounts());
        verifyNoInteractions(deliveryProviderRouter);
    }

    @Test
    void previewDoesNotResolveACompanyOutsideTheCallersOwnerScope() {
        SequenceDto created = as(manager, workspace,
            () -> sequenceService.create(previewDraft("Company owner scope")));
        as(manager, workspace, () -> versionService.publish(created.id()));
        Person person = person(
            workspace, manager.getId(), "Caller contact", "caller-contact@example.com");
        Company teammateCompany = company(
            workspace, viewer.getId(), "Teammate company secret");
        assertEquals(1, jdbcTemplate.update(
            "UPDATE person SET company_id = ? WHERE workspace_id = ? AND id = ?",
            teammateCompany.getId(), workspace.getId(), person.getId()));
        LocaleContextHolder.setLocale(Locale.JAPANESE);

        SequencePreviewDto preview = as(manager, workspace, () -> previewService.preview(
            created.id(), 1, new SequencePreviewRequest(person.getId())));

        assertEquals("会社 {{company.name}}", preview.steps().getFirst().bodyText());
        assertTrue(preview.unresolvedMergeFields().contains("company.name"));
        assertFalse(preview.steps().getFirst().bodyText().contains(teammateCompany.getName()));
    }

    @Test
    void previewRejectsForeignSharedUnassignedOtherOwnerAndRestrictedContacts() {
        SequenceDto created = as(manager, workspace,
            () -> sequenceService.create(draft("Scoped preview", "personal", "Hello {{person.name}}")));
        as(manager, workspace, () -> versionService.publish(created.id()));
        Person readable = person(workspace, manager.getId(), "Readable", "readable@example.com");
        Person unassigned = person(workspace, null, "Unassigned secret", "unassigned@example.com");
        Person otherOwner = person(workspace, viewer.getId(), "Other owner secret", "other@example.com");
        Person suspended = person(workspace, manager.getId(), "Suspended secret", "suspended@example.com");
        Person ceased = person(workspace, manager.getId(), "Ceased secret", "ceased@example.com");
        Person foreign = person(
            otherWorkspace, otherManager.getId(), "Shared foreign secret", "foreign@example.com");
        as(manager, workspace, () -> {
            personMapper.updateProcessingRestrictions(
                workspace.getId(), suspended.getId(), true, false);
            personMapper.updateProcessingRestrictions(
                workspace.getId(), ceased.getId(), false, true);
            return null;
        });
        jdbcTemplate.update(
            "INSERT INTO person_share (person_id, workspace_id, granted_by, can_edit)"
                + " VALUES (?, ?, ?, FALSE)",
            foreign.getId(), workspace.getId(), otherManager.getId());

        SequencePreviewDto allowed = as(manager, workspace, () -> previewService.preview(
            created.id(), 1, new SequencePreviewRequest(readable.getId())));
        assertEquals("Hello Readable", allowed.steps().getFirst().bodyText());
        for (Person refused : List.of(unassigned, otherOwner, suspended, ceased, foreign)) {
            SequenceException failure = assertThrows(
                SequenceException.class,
                () -> as(manager, workspace, () -> previewService.preview(
                    created.id(), 1, new SequencePreviewRequest(refused.getId()))));
            assertEquals(404, failure.status().value());
        }
        verify(personMapper, never()).getPersonById(anyInt(), anyInt());
    }

    @Test
    void exportAndTeardownIncludeAndRemoveEverySeededSequenceTable() throws Exception {
        SequenceDto created = as(manager, workspace,
            () -> sequenceService.create(draft("Lifecycle sequence", "personal", "Lifecycle body")));
        as(manager, workspace, () -> versionService.publish(created.id()));
        WorkspaceLifecycleRef target = lifecycleControlMapper.findWorkspaceInOrg(
            organization.getId(), workspace.getId());
        assertNotNull(target);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var download = as(manager, workspace, () -> exportService.prepare(
            organization.getId(), workspace.getId(), manager.getId()));
        download.writeTo(output);
        Map<String, byte[]> entries = zipEntries(output.toByteArray());
        for (String table : List.of(
                "sequence",
                "sequence_step",
                "sequence_step_content",
                "sequence_version",
                "sequence_version_publisher")) {
            String path = "data/" + table + ".jsonl";
            assertTrue(entries.containsKey(path), path);
            assertTrue(entries.get(path).length > 0, path);
        }
        assertTrue(new String(entries.get("data/sequence.jsonl"), StandardCharsets.UTF_8)
            .contains("Lifecycle sequence"));
        assertTrue(new String(entries.get("data/sequence_step_content.jsonl"), StandardCharsets.UTF_8)
            .contains("Lifecycle body"));

        as(manager, workspace, () -> {
            teardownService.teardownWorkspace(
                organization.getId(), workspace.getId(), manager.getId(), workspace.getSlug());
            return null;
        });

        TenantResidualReport residual = teardownService.verifyWorkspaceDeleted(target, manager.getId());
        assertTrue(residual.clean());
        for (String table : List.of(
                "sequence",
                "sequence_step",
                "sequence_step_content",
                "sequence_version",
                "sequence_version_publisher")) {
            assertEquals(0L, residual.tableRows().get(table), table);
        }
    }

    private SequenceVersionDto publishAfterSnapshotBarrier(
            int sequenceId,
            CountDownLatch bothSnapshotsEstablished) {
        return as(manager, workspace, () -> {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return transaction.execute(status -> {
                assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(version_number), 0) FROM sequence_version"
                        + " WHERE workspace_id = ? AND sequence_id = ?",
                    Integer.class,
                    workspace.getId(),
                    sequenceId));
                bothSnapshotsEstablished.countDown();
                try {
                    assertTrue(bothSnapshotsEstablished.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return versionService.publish(sequenceId);
            });
        });
    }

    private void pauseAfterPreliminaryViewAuthorization(
            CountDownLatch preliminaryAuthorizationPassed,
            CountDownLatch revocationCommitted) {
        AtomicBoolean pause = new AtomicBoolean(true);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (pause.compareAndSet(true, false)) {
                preliminaryAuthorizationPassed.countDown();
                assertTrue(revocationCommitted.await(20, TimeUnit.SECONDS));
            }
            return null;
        }).when(workspaceService).requirePermission(Permission.SEQUENCE_VIEW);
    }

    private Person person(Workspace targetWorkspace, Integer ownerId, String name, String email) {
        User actor = targetWorkspace.getId() == workspace.getId() ? manager : otherManager;
        return as(actor, targetWorkspace, () -> {
            Person person = new Person();
            person.setWorkspaceId(targetWorkspace.getId());
            person.setOwnerId(ownerId);
            person.setName(name);
            person.setEmail(email);
            personMapper.insert(person);
            return person;
        });
    }

    private Company company(Workspace targetWorkspace, Integer ownerId, String name) {
        User actor = targetWorkspace.getId() == workspace.getId() ? manager : otherManager;
        return as(actor, targetWorkspace, () -> {
            Company company = new Company();
            company.setWorkspaceId(targetWorkspace.getId());
            company.setOwnerId(ownerId);
            company.setName(name);
            companyMapper.insert(company);
            return company;
        });
    }

    private Deal deal(
            Workspace targetWorkspace,
            Integer ownerId,
            int companyId,
            int personId,
            String name) {
        User actor = targetWorkspace.getId() == workspace.getId() ? manager : otherManager;
        return as(actor, targetWorkspace, () -> {
            Pipeline pipeline = new Pipeline();
            pipeline.setWorkspaceId(targetWorkspace.getId());
            pipeline.setName("Sequence pipeline " + unique());
            pipelineMapper.insertPipeline(pipeline);
            Stage stage = new Stage();
            stage.setWorkspaceId(targetWorkspace.getId());
            stage.setPipeline(pipeline);
            stage.setName("Sequence stage");
            stage.setPosition(0);
            pipelineMapper.insertStage(stage);
            Deal deal = new Deal();
            deal.setWorkspaceId(targetWorkspace.getId());
            deal.setOwnerId(ownerId);
            deal.setName(name);
            deal.setValue(new BigDecimal("12500.00"));
            deal.setCurrency("JPY");
            deal.setPipelineId(pipeline.getId());
            deal.setStageId(stage.getId());
            deal.setCompanyId(companyId);
            dealMapper.insert(deal);
            dealMapper.addPerson(targetWorkspace.getId(), deal.getId(), personId, null);
            return deal;
        });
    }

    private VersionBytes versionBytes(int sequenceId, int version) {
        return jdbcTemplate.queryForObject(
            "SELECT CAST(definition_json AS BINARY), definition_hash"
                + " FROM sequence_version WHERE workspace_id = ?"
                + " AND sequence_id = ? AND version_number = ?",
            (resultSet, rowNumber) -> new VersionBytes(
                resultSet.getBytes(1), resultSet.getBytes(2)),
            workspace.getId(), sequenceId, version);
    }

    private byte[] completeVersionRowBytes(long versionId) {
        return jdbcTemplate.queryForObject(
            "SELECT CAST(CONCAT_WS(0x1F, id, workspace_id, sequence_id, version_number,"
                + " HEX(CONVERT(definition_json USING utf8mb4)), HEX(definition_hash),"
                + " DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f')) AS BINARY)"
                + " FROM sequence_version WHERE id = ?",
            byte[].class,
            versionId);
    }

    private Map<String, Integer> sideEffectCounts() {
        return Map.of(
            "activity", rowCount("activity"),
            "task", rowCount("task"),
            "notification", rowCount("notification"),
            "campaign_send", rowCount("campaign_send"),
            "campaign_delivery", rowCount("campaign_delivery"));
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?",
            Integer.class,
            workspace.getId());
    }

    private <T> T as(User user, Workspace targetWorkspace, Supplier<T> work) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        long now = System.currentTimeMillis();
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
        request.getSession().setAttribute(
            SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, user.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        tenantContext.set(
            targetWorkspace.getId(), organization.getId(), user.getId(), "member", null);
        try {
            return work.get();
        } finally {
            clearContext();
        }
    }

    private void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    private User member(Workspace targetWorkspace, String username, List<String> permissions) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("fixture");
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(targetWorkspace.getId(), user.getId(), "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(targetWorkspace.getId());
        role.setName("Sequence role " + unique());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(targetWorkspace.getId(), role.getId(), permissions);
        workspaceMapper.setMemberCustomRole(targetWorkspace.getId(), user.getId(), role.getId());
        return user;
    }

    private Workspace workspace(String name, int orgId) {
        String suffix = unique();
        Workspace created = new Workspace();
        created.setOrgId(orgId);
        created.setName(name + " " + suffix);
        created.setSlug(name.toLowerCase(Locale.ROOT).replace(' ', '-') + "-" + suffix);
        workspaceMapper.insert(created);
        return created;
    }

    private static SequenceRequest draft(String name, String visibility, String body) {
        return new SequenceRequest(
            name,
            "Purpose",
            visibility,
            "UTC",
            31,
            LocalTime.of(9, 0),
            LocalTime.of(17, 0),
            List.of(new SequenceStepRequest(
                SequenceStepType.SEND_EMAIL,
                0,
                "hours",
                "automatic",
                List.of(
                    new SequenceStepRequest.Content("en", "Hello", body, null),
                    new SequenceStepRequest.Content("ja", "こんにちは", "日本語 " + body, null)))));
    }

    private static SequenceRequest previewDraft(String name) {
        return new SequenceRequest(
            name,
            null,
            "personal",
            "UTC",
            31,
            LocalTime.of(9, 0),
            LocalTime.of(17, 0),
            List.of(
                new SequenceStepRequest(
                    SequenceStepType.SEND_EMAIL,
                    0,
                    "hours",
                    "automatic",
                    List.of(
                        new SequenceStepRequest.Content(
                            "en", "Hello {{person.name}}", "At {{company.name}}", null),
                        new SequenceStepRequest.Content(
                            "ja", "こんにちは {{person.name}}", "会社 {{company.name}}", null))),
                new SequenceStepRequest(
                    SequenceStepType.CALL_TASK,
                    1,
                    "business_days",
                    "manual_completion",
                    List.of(new SequenceStepRequest.Content(
                        "en", null, "Call about {{deal.name}}", null))),
                new SequenceStepRequest(
                    SequenceStepType.GENERAL_TASK,
                    2,
                    "hours",
                    "manual_completion_or_skip",
                    List.of(new SequenceStepRequest.Content(
                        "ja", null, "メール {{person.email}}", null))),
                new SequenceStepRequest(
                    SequenceStepType.WAIT,
                    3,
                    "hours",
                    "automatic",
                    List.of()),
                new SequenceStepRequest(
                    SequenceStepType.NOTIFY_OWNER,
                    0,
                    "hours",
                    "automatic",
                    List.of(new SequenceStepRequest.Content(
                        "en", null, "Notify {{owner.email}}", null)))));
    }

    private static Map<String, byte[]> zipEntries(byte[] zipBytes) throws Exception {
        java.util.LinkedHashMap<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return Map.copyOf(entries);
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record VersionBytes(byte[] definitionJson, byte[] definitionHash) {
    }
}
