package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.ImportPreviewResult;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.dto.RelationshipEvidenceDto;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ImportPersistenceReplayTest extends AbstractServiceTest {

    @Autowired private ImportService importService;
    @Autowired private ScoringService scoringService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private AuditService auditService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;

    private String companyName;
    private String companyWebsite;
    private String companyTag;
    private String personName;
    private String personEmail;
    private String personTag;
    private String dealName;
    private String dealTag;
    private Integer pipelineId;
    private Integer stageId;

    @AfterEach
    void cleanUpCommittedFixtures() {
        if (workspace != null && currentUser != null) {
            jdbcTemplate.update(
                "DELETE FROM activity WHERE workspace_id = ? AND created_by_id = ?",
                workspace.getId(),
                currentUser.getId());
            jdbcTemplate.update(
                "DELETE FROM task WHERE workspace_id = ? AND assigned_to_id = ?",
                workspace.getId(),
                currentUser.getId());
            jdbcTemplate.update(
                "DELETE FROM notification WHERE workspace_id = ? AND recipient_id = ?",
                workspace.getId(),
                currentUser.getId());
        }
        if (workspace != null && dealName != null) {
            for (int dealId : jdbcTemplate.queryForList(
                    "SELECT id FROM deal WHERE workspace_id = ? AND name = ?",
                    Integer.class,
                    workspace.getId(),
                    dealName)) {
                jdbcTemplate.update(
                    "DELETE FROM deal WHERE workspace_id = ? AND id = ?",
                    workspace.getId(),
                    dealId);
            }
        }
        if (workspace != null && personEmail != null) {
            for (int personId : jdbcTemplate.queryForList(
                    "SELECT id FROM person WHERE workspace_id = ? AND email = ?",
                    Integer.class,
                    workspace.getId(),
                    personEmail)) {
                jdbcTemplate.update(
                    "DELETE FROM person WHERE workspace_id = ? AND id = ?",
                    workspace.getId(),
                    personId);
            }
        }
        if (workspace != null && companyName != null) {
            for (int companyId : jdbcTemplate.queryForList(
                    "SELECT id FROM company WHERE workspace_id = ? AND name = ?",
                    Integer.class,
                    workspace.getId(),
                    companyName)) {
                jdbcTemplate.update(
                    "DELETE FROM company WHERE workspace_id = ? AND id = ?",
                    workspace.getId(),
                    companyId);
            }
        }
        deleteTag(companyTag);
        deleteTag(personTag);
        deleteTag(dealTag);
        if (workspace != null && stageId != null) {
            jdbcTemplate.update(
                "DELETE FROM stage WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                stageId);
        }
        if (workspace != null && pipelineId != null) {
            jdbcTemplate.update(
                "DELETE FROM pipeline WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                pipelineId);
        }
        if (workspace != null && currentUser != null) {
            jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                workspace.getId(),
                currentUser.getId());
            jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?",
                currentUser.getId());
        }
    }

    @Test
    void ordinaryCsvThreeCommittedCyclesKeepEveryArtifactStable() {
        String suffix = unique();
        companyName = "CSV replay company " + suffix;
        companyWebsite = "https://csv-replay-" + suffix + ".example.test";
        companyTag = "csv_company_" + suffix;
        personName = "CSV replay person " + suffix;
        personEmail = "csv-replay-" + suffix + "@example.test";
        personTag = "csv_person_" + suffix;
        dealName = "CSV replay deal " + suffix;
        dealTag = "csv_deal_" + suffix;
        Pipeline pipeline = newPipeline();
        pipelineId = pipeline.getId();
        Stage stage = newStage(pipeline, 0);
        stageId = stage.getId();
        ReplayDefinition definition = new ReplayDefinition(
            companyName,
            companyWebsite,
            companyTag,
            personName,
            personEmail,
            personTag,
            dealName,
            dealTag,
            pipeline.getName(),
            stage.getName());

        ImportCycle first = runCycle(definition);
        int personId = importedPersonIds().getFirst();
        int dealId = importedDealIds().getFirst();
        Person person = personMapper.getPersonById(workspace.getId(), personId);
        newActivity(currentUser, person, dealMapper.getDealById(workspace.getId(), dealId));
        newTask(currentUser, person, dealMapper.getDealById(workspace.getId(), dealId));
        newNotification(workspace.getId(), currentUser.getId());
        ReplayState afterFirst = replayState();

        ImportCycle second = runCycle(definition);
        ReplayState afterSecond = replayState();

        ImportCycle third = runCycle(definition);
        ReplayState afterThird = replayState();

        assertCreatedCycle(first);
        assertReplayCycle(second);
        assertReplayCycle(third);
        assertEquals(3, afterFirst.artifacts().records());
        assertEquals(1, afterFirst.artifacts().activities());
        assertEquals(1, afterFirst.artifacts().tasks());
        assertEquals(6, afterFirst.artifacts().tags());
        assertEquals(2, afterFirst.artifacts().relationships());
        assertEquals(1, afterFirst.artifacts().notifications());
        assertEquals(2, afterFirst.artifacts().relationshipEvidenceEvents());
        assertEquals(afterFirst, afterSecond);
        assertEquals(afterFirst, afterThird);
    }

    private ImportCycle runCycle(ReplayDefinition definition) {
        ImportResult company = reviewAndCommitCompany(definition.companyRequest());
        ImportResult person = reviewAndCommitPerson(definition.personRequest());
        ImportResult deal = reviewAndCommitDeal(definition.dealRequest());
        return new ImportCycle(company, person, deal);
    }

    private ImportResult reviewAndCommitCompany(ImportRequest request) {
        ImportPreviewResult preview = importService.previewCompanies(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        return importService.commitCompanies(request);
    }

    private ImportResult reviewAndCommitPerson(ImportRequest request) {
        ImportPreviewResult preview = importService.previewPersons(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        return importService.commitPersons(request);
    }

    private ImportResult reviewAndCommitDeal(ImportRequest request) {
        ImportPreviewResult preview = importService.previewDeals(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        return importService.commitDeals(request);
    }

    private ReplayState replayState() {
        List<Map<String, Object>> people = jdbcTemplate.queryForList(
            "SELECT id, workspace_id, owner_id, name, email, phone, company_id, title, "
                + "image_url, created_at, updated_at FROM person "
                + "WHERE workspace_id = ? AND email = ? ORDER BY id",
            workspace.getId(),
            personEmail);
        List<Map<String, Object>> companies = jdbcTemplate.queryForList(
            "SELECT id, workspace_id, owner_id, name, website, industry, phone, address, "
                + "logo_url, created_at, updated_at FROM company "
                + "WHERE workspace_id = ? AND (name = ? OR website = ?) ORDER BY id",
            workspace.getId(),
            companyName,
            companyWebsite);
        List<Map<String, Object>> deals = jdbcTemplate.queryForList(
            "SELECT id, workspace_id, owner_id, name, value, actual_value, currency, "
                + "pipeline_id, stage_id, company_id, expected_close_date, closed_at, "
                + "closed_reason, won, created_at, updated_at FROM deal "
                + "WHERE workspace_id = ? AND name = ? ORDER BY id",
            workspace.getId(),
            dealName);
        List<Map<String, Object>> activities = jdbcTemplate.queryForList(
            "SELECT a.id, a.workspace_id, a.type, a.subject, a.notes, a.person_id, "
                + "a.deal_id, a.created_by_id, a.timestamp FROM activity a "
                + "LEFT JOIN person p ON p.id = a.person_id "
                + "LEFT JOIN deal d ON d.id = a.deal_id "
                + "WHERE a.workspace_id = ? AND (p.email = ? OR d.name = ?) ORDER BY a.id",
            workspace.getId(),
            personEmail,
            dealName);
        List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
            "SELECT t.id, t.workspace_id, t.description, t.completed, t.status, t.position, "
                + "t.due_date, t.assigned_to_id, t.person_id, t.deal_id, t.created_at, "
                + "t.updated_at FROM task t LEFT JOIN person p ON p.id = t.person_id "
                + "LEFT JOIN deal d ON d.id = t.deal_id "
                + "WHERE t.workspace_id = ? AND (p.email = ? OR d.name = ?) ORDER BY t.id",
            workspace.getId(),
            personEmail,
            dealName);
        List<Map<String, Object>> tags = jdbcTemplate.queryForList(
            "SELECT id, workspace_id, name, color FROM tag WHERE workspace_id = ? "
                + "AND name IN (?, ?, ?) ORDER BY id",
            workspace.getId(),
            companyTag,
            personTag,
            dealTag);
        List<Map<String, Object>> tagLinks = jdbcTemplate.queryForList(
            "SELECT 'person' AS record_type, p.id AS record_id, pt.tag_id "
                + "FROM person_tag pt JOIN person p ON p.id = pt.person_id "
                + "WHERE p.workspace_id = ? AND p.email = ? "
                + "UNION ALL SELECT 'company', c.id, ct.tag_id "
                + "FROM company_tag ct JOIN company c ON c.id = ct.company_id "
                + "WHERE c.workspace_id = ? AND c.name = ? "
                + "UNION ALL SELECT 'deal', d.id, dt.tag_id "
                + "FROM deal_tag dt JOIN deal d ON d.id = dt.deal_id "
                + "WHERE d.workspace_id = ? AND d.name = ? "
                + "ORDER BY record_type, record_id, tag_id",
            workspace.getId(),
            personEmail,
            workspace.getId(),
            companyName,
            workspace.getId(),
            dealName);
        List<Map<String, Object>> employmentRelationships = jdbcTemplate.queryForList(
            "SELECT pe.id, pe.workspace_id, pe.person_id, pe.company_id, pe.company_name, "
                + "pe.title, pe.started_at, pe.ended_at, pe.created_at "
                + "FROM person_employment pe JOIN person p ON p.id = pe.person_id "
                + "WHERE pe.workspace_id = ? AND p.email = ? ORDER BY pe.id",
            workspace.getId(),
            personEmail);
        List<Map<String, Object>> dealRelationships = jdbcTemplate.queryForList(
            "SELECT dp.deal_id, dp.person_id, dp.role FROM deal_person dp "
                + "JOIN deal d ON d.id = dp.deal_id JOIN person p ON p.id = dp.person_id "
                + "WHERE d.workspace_id = ? AND d.name = ? AND p.email = ? "
                + "ORDER BY dp.deal_id, dp.person_id",
            workspace.getId(),
            dealName,
            personEmail);
        List<Map<String, Object>> notifications = jdbcTemplate.queryForList(
            "SELECT id, workspace_id, recipient_id, type, category, severity, "
                + "template_version, title, body, actor_id, actor_label, source_type, "
                + "source_id, source_label, context_type, context_id, context_label, "
                + "action_url, data, dedupe_key, triggered_at, read_at, dismissed_at, "
                + "resolved_at, created_at, updated_at FROM notification "
                + "WHERE workspace_id = ? AND recipient_id = ? ORDER BY id",
            workspace.getId(),
            currentUser.getId());
        List<Map<String, Object>> personIdentities = jdbcTemplate.queryForList(
            "SELECT pi.id, pi.person_id, pi.kind, pi.`value`, pi.normalized_value, "
                + "pi.source_system, pi.source_channel, pi.source_row_ref, pi.acquired_at, "
                + "pi.superseded_at FROM person_identity pi "
                + "JOIN person p ON p.id = pi.person_id "
                + "WHERE pi.workspace_id = ? AND p.email = ? ORDER BY pi.id",
            workspace.getId(),
            personEmail);
        List<Map<String, Object>> companyIdentities = jdbcTemplate.queryForList(
            "SELECT ci.id, ci.company_id, ci.kind, ci.`value`, ci.normalized_value, "
                + "ci.source_system, ci.source_channel, ci.source_row_ref, ci.acquired_at, "
                + "ci.superseded_at FROM company_identity ci "
                + "JOIN company c ON c.id = ci.company_id "
                + "WHERE ci.workspace_id = ? AND c.name = ? ORDER BY ci.id",
            workspace.getId(),
            companyName);
        List<Map<String, Object>> dealStageHistory = jdbcTemplate.queryForList(
            "SELECT h.id, h.workspace_id, h.deal_id, h.stage_id, h.stage_name, "
                + "h.achieved_at, h.conversion_eligible FROM deal_stage_history h "
                + "JOIN deal d ON d.id = h.deal_id "
                + "WHERE h.workspace_id = ? AND d.name = ? ORDER BY h.id",
            workspace.getId(),
            dealName);
        List<RelationshipEvidenceEvent> relationshipEvidence = relationshipEvidenceEvents();
        ArtifactCounts artifacts = new ArtifactCounts(
            people.size() + companies.size() + deals.size(),
            activities.size(),
            tasks.size(),
            tags.size() + tagLinks.size(),
            employmentRelationships.size() + dealRelationships.size(),
            notifications.size(),
            relationshipEvidence.size());
        return new ReplayState(
            artifacts,
            people,
            companies,
            deals,
            activities,
            tasks,
            tags,
            tagLinks,
            employmentRelationships,
            dealRelationships,
            notifications,
            personIdentities,
            companyIdentities,
            dealStageHistory,
            relationshipEvidence);
    }

    private List<RelationshipEvidenceEvent> relationshipEvidenceEvents() {
        List<RelationshipEvidenceEvent> events = new ArrayList<>();
        for (int personId : importedPersonIds()) {
            RelationshipEvidenceDto evidence = scoringService.contactEvidence(
                workspace.getId(), personId, currentUser.getId());
            events.addAll(evidence.contributors().stream()
                .map(contributor -> new RelationshipEvidenceEvent(
                    personId,
                    contributor.sourceType(),
                    contributor.sourceId(),
                    contributor.interactionType(),
                    contributor.occurredAt(),
                    contributor.baseWeight()))
                .toList());
        }
        return events.stream()
            .sorted(Comparator
                .comparing(RelationshipEvidenceEvent::sourceType)
                .thenComparingInt(RelationshipEvidenceEvent::sourceId))
            .toList();
    }

    private List<Integer> importedPersonIds() {
        return jdbcTemplate.queryForList(
            "SELECT id FROM person WHERE workspace_id = ? AND email = ? ORDER BY id",
            Integer.class,
            workspace.getId(),
            personEmail);
    }

    private List<Integer> importedDealIds() {
        return jdbcTemplate.queryForList(
            "SELECT id FROM deal WHERE workspace_id = ? AND name = ? ORDER BY id",
            Integer.class,
            workspace.getId(),
            dealName);
    }

    private void deleteTag(String tagName) {
        if (workspace != null && tagName != null) {
            jdbcTemplate.update(
                "DELETE FROM tag WHERE workspace_id = ? AND name = ?",
                workspace.getId(),
                tagName);
        }
    }

    private static void assertCreatedCycle(ImportCycle cycle) {
        assertResult(cycle.company(), 1, 0, 0, 0);
        assertResult(cycle.person(), 1, 0, 0, 0);
        assertResult(cycle.deal(), 1, 0, 0, 0);
    }

    private static void assertReplayCycle(ImportCycle cycle) {
        assertResult(cycle.company(), 0, 0, 0, 0);
        assertResult(cycle.person(), 0, 0, 0, 0);
        assertResult(cycle.deal(), 0, 0, 0, 0);
    }

    private static void assertResult(
            ImportResult result,
            int created,
            int updated,
            int skipped,
            int failed) {
        assertEquals(created, result.getCreated());
        assertEquals(updated, result.getUpdated());
        assertEquals(skipped, result.getSkipped());
        assertEquals(failed, result.getFailed().size());
    }

    private static ColumnMapping mapping(String column, String field) {
        return new ColumnMapping(column, field, null, null, null);
    }

    private record ReplayDefinition(
        String companyName,
        String companyWebsite,
        String companyTag,
        String personName,
        String personEmail,
        String personTag,
        String dealName,
        String dealTag,
        String pipelineName,
        String stageName
    ) {
        private ImportRequest companyRequest() {
            return new ImportRequest(
                List.of(Map.of(
                    "Company", companyName,
                    "Website", companyWebsite,
                    "Tags", companyTag)),
                List.of(
                    mapping("Company", "name"),
                    mapping("Website", "website"),
                    mapping("Tags", "tags")),
                "fill_empty",
                null);
        }

        private ImportRequest personRequest() {
            return new ImportRequest(
                List.of(Map.of(
                    "Name", personName,
                    "Email", personEmail,
                    "Title", "Import lead",
                    "Company", companyName,
                    "Tags", personTag)),
                List.of(
                    mapping("Name", "name"),
                    mapping("Email", "email"),
                    mapping("Title", "title"),
                    mapping("Company", "company"),
                    mapping("Tags", "tags")),
                "fill_empty",
                null);
        }

        private ImportRequest dealRequest() {
            return new ImportRequest(
                List.of(Map.of(
                    "Deal", dealName,
                    "Value", "2400.00",
                    "Currency", "USD",
                    "Pipeline", pipelineName,
                    "Stage", stageName,
                    "Company", companyName,
                    "People", personEmail,
                    "Tags", dealTag)),
                List.of(
                    mapping("Deal", "name"),
                    mapping("Value", "value"),
                    mapping("Currency", "currency"),
                    mapping("Pipeline", "pipeline"),
                    mapping("Stage", "stage"),
                    mapping("Company", "company"),
                    mapping("People", "people"),
                    mapping("Tags", "tags")),
                "fill_empty",
                null);
        }
    }

    private record ImportCycle(
        ImportResult company,
        ImportResult person,
        ImportResult deal
    ) {}

    private record ArtifactCounts(
        int records,
        int activities,
        int tasks,
        int tags,
        int relationships,
        int notifications,
        int relationshipEvidenceEvents
    ) {}

    private record RelationshipEvidenceEvent(
        int personId,
        RelationshipEvidenceDto.SourceType sourceType,
        int sourceId,
        String interactionType,
        Instant occurredAt,
        double baseWeight
    ) {}

    private record ReplayState(
        ArtifactCounts artifacts,
        List<Map<String, Object>> people,
        List<Map<String, Object>> companies,
        List<Map<String, Object>> deals,
        List<Map<String, Object>> activities,
        List<Map<String, Object>> tasks,
        List<Map<String, Object>> tags,
        List<Map<String, Object>> tagLinks,
        List<Map<String, Object>> employmentRelationships,
        List<Map<String, Object>> dealRelationships,
        List<Map<String, Object>> notifications,
        List<Map<String, Object>> personIdentities,
        List<Map<String, Object>> companyIdentities,
        List<Map<String, Object>> dealStageHistory,
        List<RelationshipEvidenceEvent> relationshipEvidence
    ) {}
}
