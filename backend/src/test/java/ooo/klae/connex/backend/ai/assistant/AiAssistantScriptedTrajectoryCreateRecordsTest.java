package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationDefaultSpecDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.DuplicateReviewException;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.services.RecordCreationTemplateService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** End-to-end create goldens distinguish preparation controls from authoritative approval checks. */
class AiAssistantScriptedTrajectoryCreateRecordsTest extends AbstractScriptedTrajectoryTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RecordCreationTemplateService templateService;
    @Autowired private AiAssistantToolCallReadService readService;
    @Autowired private IdentityMapper identityMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void removeTemplateReferencesBeforeTenantCleanup() {
        jdbcTemplate.update("UPDATE record_creation_template SET current_version_id = NULL WHERE workspace_id = ?", workspaceId());
        jdbcTemplate.update("UPDATE record_creation_template_set SET default_template_id = NULL WHERE workspace_id = ?", workspaceId());
        jdbcTemplate.update("DELETE FROM record_creation_template_version WHERE workspace_id = ?", workspaceId());
        jdbcTemplate.update("DELETE FROM record_creation_template WHERE workspace_id = ?", workspaceId());
        jdbcTemplate.update("DELETE FROM record_creation_template_set WHERE workspace_id = ?", workspaceId());
    }

    @Test
    void aNameOnlyPersonCandidateRefusesBeforeAnyProposalExists() {
        company("Alderwick Shipping");
        person("Morgan Vale", null, null);
        Trajectory trajectory = run("connex_script_create_person_duplicate", "add a colleague");
        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        assertRefused(trajectory, "create_person", "possible_duplicate");
        assertEquals(1, contactCount());
        assertEquals(0, auditRows("person.create"));
    }

    @Test
    void approvalWaitsForTheDuplicateMutexAndRefusesTheCompetitorCommittedByItsHolder() throws Exception {
        company("Alderwick Shipping");
        Trajectory trajectory = run("connex_script_create_person_proposal", "add a colleague");
        AiChatToolCall proposal = proposal(trajectory, "create_person");
        assertEquals(0, contactCount());
        approveAfterMutexWait(trajectory, proposal, DuplicateReviewException.class, holder -> {
            try (PreparedStatement insert = holder.prepareStatement(
                    "INSERT INTO person (workspace_id, name) VALUES (?, ?)")) {
                insert.setInt(1, workspaceId());
                insert.setString(2, "Morgan Vale");
                assertEquals(1, insert.executeUpdate());
            }
        });
        assertEquals(1, contactCount());
        assertEquals(0, auditRows("person.create"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "phone"})
    void aTemplateDefaultIdentityCandidateRefusesBeforeAnyProposal(String field) {
        company("Alderwick Shipping");
        String value = "email".equals(field) ? "private@example.test" : "+12025550123";
        Person existing = person("Existing contact", "email".equals(field) ? value : null,
                "phone".equals(field) ? value : null);
        if ("email".equals(field)) {
            identityMapper.upsertPersonEmailIdentity(workspaceId(), existing.getId(), value, value,
                    "interactive_create", null, LocalDateTime.now());
        } else {
            identityMapper.upsertPersonPhoneIdentity(workspaceId(), existing.getId(), value, value,
                    "interactive_create", null, LocalDateTime.now());
        }
        createTemplate(RecordCreationRecordType.person,
                new RecordCreationTemplateFieldDto(field, false, null, null,
                        new RecordCreationDefaultSpecDto(RecordCreationDefaultKind.literal_string,
                                value, null, null, null, null, null)));

        Trajectory trajectory = run("connex_script_create_person_proposal", "add a colleague");

        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        assertRefused(trajectory, "create_person", "possible_duplicate");
        assertEquals(1, contactCount());
        assertEquals(0, auditRows("person.create"));
    }

    @Test
    void aDealIsCreatedOnlyOnApprovalWithItsReviewedStageAndLiveLink() {
        Company anchor = company("Alderwick Shipping");
        Pipeline pipeline = pipeline("Sales");
        Stage stage = stage(pipeline, "Discovery", 0);
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        AiChatToolCall proposal = proposal(trajectory, "create_deal");
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
        JsonNode arguments = objectMapper.readTree(proposal.getArgumentsJson());
        assertEquals(stage.getId(), arguments.path("resolution").path("id").asInt());
        assertEquals(stage.getId(), arguments.path("pinned").path("stageId").asInt());
        assertEquals(pipeline.getId(), arguments.path("pinned").path("stagePipelineId").asInt());
        assertEquals("Discovery", arguments.path("pinned").path("stageName").asString());
        assertTrue(arguments.path("pinned").path("templateVersion").asInt() > 0);
        jdbcTemplate.update("UPDATE company SET updated_at = NOW() WHERE workspace_id = ? AND id = ?",
                workspaceId(), anchor.getId());
        authenticate();
        try {
            var card = readService.get(trajectory.sessionId(), proposal.getId());
            assertTrue(card.changes().stream().allMatch(change -> "ready".equals(change.state())));
            assertTrue(card.changes().stream().anyMatch(change -> "templateDefaults".equals(change.field())));
            var approved = writeToolService().approve(trajectory.sessionId(), proposal.getId());
            assertEquals("executed", approved.status());
            assertEquals(approved.result(), writeToolService().approve(trajectory.sessionId(), proposal.getId()).result());
            assertEquals("deal", readService.get(trajectory.sessionId(), proposal.getId()).createdRecord().kind());
        } finally {
            clearAuthentication();
        }
        assertEquals(1, dealCount());
        assertEquals(1, auditRows("deal.create"));
        assertEquals(stage.getId(), jdbcTemplate.queryForObject(
                "SELECT stage_id FROM deal WHERE workspace_id = ?", Integer.class, workspaceId()));
    }

    @Test
    void aStageNameSwapRefusesThroughTheFrameworkPin() {
        company("Alderwick Shipping");
        Pipeline pipeline = pipeline("Sales");
        Stage reviewed = stage(pipeline, "Discovery", 0);
        Stage successor = stage(pipeline, "Qualified", 1);
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        AiChatToolCall proposal = proposal(trajectory, "create_deal");
        jdbcTemplate.update("UPDATE stage SET name = ? WHERE workspace_id = ? AND id = ?", "Retired", workspaceId(), reviewed.getId());
        jdbcTemplate.update("UPDATE stage SET name = ? WHERE workspace_id = ? AND id = ?", "Discovery", workspaceId(), successor.getId());
        authenticate();
        try {
            assertEquals("Assistant proposal target changed", assertThrows(ConflictException.class,
                    () -> writeToolService().approve(trajectory.sessionId(), proposal.getId())).getMessage());
        } finally {
            clearAuthentication();
        }
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
    }

    @Test
    void aStageRenamedAfterResolutionRefusesUnderTheCanonicalStageLock() throws Exception {
        company("Alderwick Shipping");
        Stage reviewed = stage(pipeline("Sales"), "Discovery", 0);
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        AiChatToolCall proposal = proposal(trajectory, "create_deal");

        ConflictException refused = approveAfterMutexWait(trajectory, proposal, ConflictException.class, holder -> {
            try (PreparedStatement rename = holder.prepareStatement(
                    "UPDATE stage SET name = ? WHERE workspace_id = ? AND id = ?")) {
                rename.setString(1, "DISCOVERY");
                rename.setInt(2, workspaceId());
                rename.setInt(3, reviewed.getId());
                assertEquals(1, rename.executeUpdate());
            }
        });

        assertEquals("Assistant proposal target changed", refused.getMessage());
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
    }

    @Test
    void aStageMovedBeforeApprovalRefusesEvenWithTheSameIdAndName() {
        company("Alderwick Shipping");
        Stage reviewed = stage(pipeline("Sales"), "Discovery", 0);
        Pipeline destination = pipeline("Other sales");
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        AiChatToolCall proposal = proposal(trajectory, "create_deal");
        jdbcTemplate.update("UPDATE stage SET pipeline_id = ? WHERE workspace_id = ? AND id = ?",
                destination.getId(), workspaceId(), reviewed.getId());
        authenticate();
        try {
            assertEquals("unresolved", readService.get(trajectory.sessionId(), proposal.getId()).changes().stream()
                    .filter(change -> "stage".equals(change.field())).findFirst().orElseThrow().state());
            assertEquals("Assistant proposal target changed", assertThrows(ConflictException.class,
                    () -> writeToolService().approve(trajectory.sessionId(), proposal.getId())).getMessage());
        } finally {
            clearAuthentication();
        }
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
    }

    @Test
    void aStageMovedAfterResolutionRefusesThePinnedPipelineUnderTheCanonicalLocks() throws Exception {
        company("Alderwick Shipping");
        Stage reviewed = stage(pipeline("Sales"), "Discovery", 0);
        Pipeline destination = pipeline("Other sales");
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        AiChatToolCall proposal = proposal(trajectory, "create_deal");

        RecordCreationTemplateException refused = approveAfterMutexWait(
                trajectory, proposal, RecordCreationTemplateException.class, holder -> {
                    try (PreparedStatement move = holder.prepareStatement(
                            "UPDATE stage SET pipeline_id = ? WHERE workspace_id = ? AND id = ?")) {
                        move.setInt(1, destination.getId());
                        move.setInt(2, workspaceId());
                        move.setInt(3, reviewed.getId());
                        assertEquals(1, move.executeUpdate());
                    }
                });

        assertEquals("VALIDATION_FAILED", refused.error().code());
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
    }

    @Test
    void aTemplateVersionChangeAloneRefusesThePinnedVersion() {
        company("Alderwick Shipping");
        stage(pipeline("Sales"), "Discovery", 0);
        RecordCreationTemplateDto template = createTemplate(RecordCreationRecordType.deal,
                new RecordCreationTemplateFieldDto("expectedCloseDate", false, null, null, null));
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        AiChatToolCall proposal = proposal(trajectory, "create_deal");
        assertEquals(template.id(), objectMapper.readTree(proposal.getArgumentsJson()).path("pinned").path("templateId").asString());
        assertEquals(1, jdbcTemplate.update(
                "UPDATE record_creation_template_version SET version_number = version_number + 1 WHERE workspace_id = ?",
                workspaceId()));
        assertEquals(objectMapper.readTree(proposal.getArgumentsJson()).path("pinned").path("templateSetRevision").asInt(),
                jdbcTemplate.queryForObject("SELECT revision FROM record_creation_template_set WHERE workspace_id = ? AND record_type = 'deal'",
                        Integer.class, workspaceId()));
        authenticate();
        try {
            RecordCreationTemplateException refused = assertThrows(RecordCreationTemplateException.class,
                    () -> writeToolService().approve(trajectory.sessionId(), proposal.getId()));
            assertEquals("TEMPLATE_VERSION_STALE", refused.error().code());
        } finally {
            clearAuthentication();
        }
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
    }

    @Test
    void aTemplateSetRevisionChangeAloneRefusesThePinnedSet() {
        company("Alderwick Shipping");
        stage(pipeline("Sales"), "Discovery", 0);
        RecordCreationTemplateDto template = createTemplate(RecordCreationRecordType.deal,
                new RecordCreationTemplateFieldDto("expectedCloseDate", false, null, null, null));
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        AiChatToolCall proposal = proposal(trajectory, "create_deal");
        assertEquals(template.version(), objectMapper.readTree(proposal.getArgumentsJson()).path("pinned").path("templateVersion").asInt());
        assertEquals(1, jdbcTemplate.update(
                "UPDATE record_creation_template_set SET revision = revision + 1 WHERE workspace_id = ? AND record_type = 'deal'",
                workspaceId()));
        assertEquals(template.version(), jdbcTemplate.queryForObject(
                "SELECT version_number FROM record_creation_template_version WHERE workspace_id = ?",
                Integer.class, workspaceId()));
        authenticate();
        try {
            RecordCreationTemplateException refused = assertThrows(RecordCreationTemplateException.class,
                    () -> writeToolService().approve(trajectory.sessionId(), proposal.getId()));
            assertEquals("TEMPLATE_SET_STALE", refused.error().code());
        } finally {
            clearAuthentication();
        }
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
    }

    @Test
    void anUnsuppliedRequiredTemplateFieldRefusesBeforeDuplicateCheckingOrProposal() {
        company("Alderwick Shipping");
        stage(pipeline("Sales"), "Discovery", 0);
        createTemplate(RecordCreationRecordType.deal,
                new RecordCreationTemplateFieldDto("expectedCloseDate", true, null, null, null));
        Trajectory trajectory = run("connex_script_create_deal_proposal", "add an opportunity");
        assertRefused(trajectory, "create_deal", "template_requires_fields");
        assertEquals(0, dealCount());
        assertEquals(0, auditRows("deal.create"));
    }

    private RecordCreationTemplateDto createTemplate(
            RecordCreationRecordType kind, RecordCreationTemplateFieldDto field) {
        authenticate();
        try {
            return templateService.create(new RecordCreationTemplateCreateRequestDto(kind,
                    new LocalizedTextDto("Reviewed", "確認済み"), null,
                    new RecordCreationTemplateDefinitionDto(1, List.of(new RecordCreationTemplateGroupDto(
                            "basics", new LocalizedTextDto("Basics", "基本情報"), null, List.of(field)))), true, 0));
        } finally {
            clearAuthentication();
        }
    }

    /** Commits a competitor only after observing approval waiting on the exact organization mutex. */
    private <T extends Throwable> T approveAfterMutexWait(
            Trajectory trajectory, AiChatToolCall proposal, Class<T> refusal, SqlMutation competitor) throws Exception {
        jdbcTemplate.update("INSERT INTO organization_duplicate_decision_lock (organization_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE organization_id = ?", organizationId(), organizationId());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Throwable primaryFailure = null;
        try (Connection holder = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (PreparedStatement lock = holder.prepareStatement(
                        "SELECT organization_id FROM organization_duplicate_decision_lock WHERE organization_id = ? FOR UPDATE")) {
                    lock.setInt(1, organizationId());
                    try (var row = lock.executeQuery()) {
                        assertTrue(row.next());
                    }
                }
                CountDownLatch connectionReady = new CountDownLatch(1);
                AtomicLong connectionId = new AtomicLong();
                Future<T> approval = executor.submit(() -> {
                    authenticate();
                    try {
                        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                        return assertThrows(refusal, () -> transaction.execute(status -> {
                            connectionId.set(Objects.requireNonNull(
                                    jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class)));
                            connectionReady.countDown();
                            return writeToolService().approve(trajectory.sessionId(), proposal.getId());
                        }));
                    } finally {
                        clearAuthentication();
                    }
                });
                assertTrue(connectionReady.await(30, TimeUnit.SECONDS), "Approval did not open its transaction");
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbcTemplate, connectionId.get(),
                        "organization_duplicate_decision_lock", Integer.toString(organizationId()));
                assertFalse(approval.isDone(), "Approval completed while the competing transaction held the mutex");
                competitor.apply(holder);
                holder.commit();
                return approval.get(30, TimeUnit.SECONDS);
            } finally {
                holder.rollback();
            }
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Approval worker did not terminate");
                }
            } catch (Exception | Error cleanupFailure) {
                if (primaryFailure == null) {
                    throw cleanupFailure;
                }
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    @FunctionalInterface
    private interface SqlMutation {
        void apply(Connection connection) throws SQLException;
    }

    private int contactCount() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM person WHERE workspace_id = ?", Integer.class, workspaceId()));
    }

    private int dealCount() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deal WHERE workspace_id = ?", Integer.class, workspaceId()));
    }

    private static AiChatToolCall proposal(Trajectory trajectory, String tool) {
        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        List<AiChatToolCall> rows = trajectory.toolCalls().stream().filter(call -> tool.equals(call.getToolName())).toList();
        assertEquals(1, rows.size());
        assertEquals("proposed", rows.getFirst().getStatus());
        return rows.getFirst();
    }

    private static void assertRefused(Trajectory trajectory, String tool, String reason) {
        List<AiChatToolCall> rows = trajectory.toolCalls().stream().filter(call -> tool.equals(call.getToolName())).toList();
        assertEquals(1, rows.size());
        assertEquals("failed", rows.getFirst().getStatus());
        assertTrue(rows.getFirst().getResultJson().contains(reason));
        assertFalse(trajectory.toolCalls().stream().anyMatch(call -> "proposed".equals(call.getStatus())));
    }
}
