package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportAggregateRow;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/**
 * Reconciles the dashboard Java path, detail aggregate SQL, company SQL, and report coverage SQL
 * against one deterministic fixture.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class WarmthReconciliationIntegrationTest {
    private static final LocalDateTime REPORT_END = LocalDateTime.of(2027, 1, 1, 0, 0);
    private static final Instant REPORT_AS_OF =
        REPORT_END.toInstant(ZoneOffset.UTC).minusMillis(1);
    private static final RelationshipWarmthModel MODEL = RelationshipWarmthModel.current();
    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private ActivityMapper activityMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private NoteMapper noteMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private ReportMapper reportMapper;
    @Autowired private ScoringService scoringService;
    @Autowired private TaskMapper taskMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;

    private Workspace workspace;
    private User user;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        workspace = new Workspace();
        workspace.setName("Warmth reconciliation " + suffix);
        workspace.setSlug("warmth-" + suffix);
        workspaceMapper.insert(workspace);
        user = new User();
        user.setUsername("warmth_" + suffix);
        user.setDisplayName("Warmth User");
        user.setEmail("warmth_" + suffix + "@example.com");
        user.setPasswordHash("hash");
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
    }

    @Test
    void javaAggregatesAndCoverageReportClassifyTheSameFixture() {
        Company covered = company("Covered");
        Person coveredMeeting = person("Covered meeting", covered);
        Person coveredNoteTask = person("Covered note task", covered);
        activity(coveredMeeting, "meeting", REPORT_END.minusHours(1));
        Note visible = note(coveredNoteTask, "workspace");
        Note privateNote = note(coveredNoteTask, "private");
        Task task = task(coveredNoteTask);
        setCreatedAt("note", visible.getId(), REPORT_END.minusHours(2));
        setCreatedAt("note", privateNote.getId(), REPORT_END.minusHours(2));
        setCreatedAt("task", task.getId(), REPORT_END.minusHours(2));

        Company gap = company("Gap");
        Person gapWarm = person("Gap warm", gap);
        Person gapCold = person("Gap cold", gap);
        activity(gapWarm, "meeting", REPORT_END.minusHours(1));

        Company cutoff = company("Cutoff");
        Person cutoffWarm = person("Cutoff warm", cutoff);
        Person cutoffBoundary = person("Cutoff boundary", cutoff);
        activity(cutoffWarm, "meeting", REPORT_END.minusHours(1));
        activity(cutoffBoundary, "meeting", REPORT_END);

        Company roundedWarm = company("Rounded warm");
        Person roundedWarmAnchor = person("Rounded warm anchor", roundedWarm);
        Person roundedWarmBoundary = person("Rounded warm boundary", roundedWarm);
        activity(roundedWarmAnchor, "meeting", REPORT_END.minusHours(1));
        activity(roundedWarmBoundary, "meeting", REPORT_END.minusSeconds(3_179_523));

        Company roundedCool = company("Rounded cool");
        Person roundedCoolAnchor = person("Rounded cool anchor", roundedCool);
        Person roundedCoolBoundary = person("Rounded cool boundary", roundedCool);
        activity(roundedCoolAnchor, "meeting", REPORT_END.minusHours(1));
        activity(roundedCoolBoundary, "meeting", REPORT_END.minusSeconds(3_179_524));

        Company privateSensitive = company("Private sensitive");
        Person privateSensitiveAnchor = person("Private sensitive anchor", privateSensitive);
        Person privateSensitiveNote = person("Private sensitive note", privateSensitive);
        activity(privateSensitiveAnchor, "meeting", REPORT_END.minusHours(1));
        Note privateSensitiveVisible = note(privateSensitiveNote, "workspace");
        Note privateSensitiveExcluded = note(privateSensitiveNote, "private");
        setCreatedAt("note", privateSensitiveVisible.getId(), REPORT_END.minusHours(1));
        setCreatedAt("note", privateSensitiveExcluded.getId(), REPORT_END.minusHours(1));

        Company attributed = company("Attributed");
        Person attributedPerson = person("Attributed person", attributed);
        Pipeline pipeline = pipeline();
        Stage stage = stage(pipeline);
        Deal attributedDeal = deal(pipeline, stage, attributed);
        activity(null, attributedDeal, "meeting", REPORT_END.minusHours(2));
        activity(attributedPerson, attributedDeal, "meeting", REPORT_END.minusHours(1));

        List<Integer> personIds = List.of(
            coveredMeeting.getId(),
            coveredNoteTask.getId(),
            gapWarm.getId(),
            gapCold.getId(),
            cutoffWarm.getId(),
            cutoffBoundary.getId(),
            roundedWarmAnchor.getId(),
            roundedWarmBoundary.getId(),
            roundedCoolAnchor.getId(),
            roundedCoolBoundary.getId(),
            privateSensitiveAnchor.getId(),
            privateSensitiveNote.getId(),
            attributedPerson.getId()
        );
        List<Integer> companyIds = List.of(
            covered.getId(),
            gap.getId(),
            cutoff.getId(),
            roundedWarm.getId(),
            roundedCool.getId(),
            privateSensitive.getId(),
            attributed.getId()
        );

        Map<Integer, RelationshipTemperatureDto> javaPeople = byId(
            scoringService.scoreContacts(workspace.getId(), REPORT_AS_OF), personIds);
        Map<Integer, RelationshipTemperatureDto> aggregatePeople = byId(
            scoringService.temperatures(
                personMapper.getRelationshipScoreAggregates(
                    workspace.getId(),
                    LocalDateTime.ofInstant(REPORT_AS_OF, ZoneOffset.UTC),
                    MODEL.sqlParameters()),
                REPORT_AS_OF),
            personIds);
        Map<Integer, RelationshipTemperatureDto> javaCompanies = byId(
            scoringService.scoreCompanies(workspace.getId(), REPORT_AS_OF), companyIds);
        Map<Integer, RelationshipTemperatureDto> aggregateCompanies = byId(
            scoringService.temperatures(
                companyMapper.getRelationshipScoreAggregates(
                    workspace.getId(),
                    LocalDateTime.ofInstant(REPORT_AS_OF, ZoneOffset.UTC),
                    MODEL.sqlParameters()),
                REPORT_AS_OF),
            companyIds);

        assertReconciled(javaPeople, aggregatePeople);
        assertReconciled(javaCompanies, aggregateCompanies);
        assertEquals(2, javaPeople.get(coveredNoteTask.getId()).getTouchCount());
        assertEquals(50, javaPeople.get(coveredNoteTask.getId()).getScore());
        assertEquals(35, javaPeople.get(roundedWarmBoundary.getId()).getScore());
        assertEquals("warm", javaPeople.get(roundedWarmBoundary.getId()).getBand());
        assertEquals(34, javaPeople.get(roundedCoolBoundary.getId()).getScore());
        assertEquals("cool", javaPeople.get(roundedCoolBoundary.getId()).getBand());
        assertEquals("cold", javaPeople.get(cutoffBoundary.getId()).getBand());
        assertEquals(1, javaPeople.get(privateSensitiveNote.getId()).getTouchCount());
        assertEquals(33, javaPeople.get(privateSensitiveNote.getId()).getScore());
        assertEquals("cool", javaPeople.get(privateSensitiveNote.getId()).getBand());
        assertEquals(2, javaCompanies.get(attributed.getId()).getTouchCount());
        assertEquals(2, aggregateCompanies.get(attributed.getId()).getTouchCount());

        Set<Integer> expectedGapIds = Set.of(
            gap.getId(),
            cutoff.getId(),
            roundedCool.getId(),
            privateSensitive.getId(),
            attributed.getId()
        );
        Map<Integer, List<Integer>> personIdsByCompany = Map.of(
            covered.getId(), List.of(coveredMeeting.getId(), coveredNoteTask.getId()),
            gap.getId(), List.of(gapWarm.getId(), gapCold.getId()),
            cutoff.getId(), List.of(cutoffWarm.getId(), cutoffBoundary.getId()),
            roundedWarm.getId(), List.of(roundedWarmAnchor.getId(), roundedWarmBoundary.getId()),
            roundedCool.getId(), List.of(roundedCoolAnchor.getId(), roundedCoolBoundary.getId()),
            privateSensitive.getId(),
                List.of(privateSensitiveAnchor.getId(), privateSensitiveNote.getId()),
            attributed.getId(), List.of(attributedPerson.getId())
        );
        Set<Integer> javaGapIds = personIdsByCompany.entrySet().stream()
            .filter(entry -> entry.getValue().stream()
                .map(javaPeople::get)
                .filter(WarmthReconciliationIntegrationTest::isWarmOrHot)
                .count() <= 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
        Set<Integer> actualGapIds = reportMapper.aggregateCoverageGaps(
                reportQuery(), REPORT_END.minusNanos(1_000_000), MODEL.sqlParameters()).stream()
            .map(ReportAggregateRow::groupKey)
            .map(Integer::valueOf)
            .collect(Collectors.toUnmodifiableSet());

        assertEquals(expectedGapIds, javaGapIds);
        assertEquals(javaGapIds, actualGapIds);
        assertFalse(actualGapIds.contains(covered.getId()));
        assertFalse(actualGapIds.contains(roundedWarm.getId()));

        Instant inclusiveBoundary = REPORT_END.toInstant(ZoneOffset.UTC);
        RelationshipTemperatureDto javaInclusive = byId(
            scoringService.scoreContacts(workspace.getId(), inclusiveBoundary),
            List.of(cutoffBoundary.getId())).get(cutoffBoundary.getId());
        RelationshipTemperatureDto aggregateInclusive = scoringService.temperatures(
            personMapper.getRelationshipScoreAggregates(
                workspace.getId(), REPORT_END, MODEL.sqlParameters()),
            inclusiveBoundary).stream()
            .filter(score -> score.getId() == cutoffBoundary.getId())
            .findFirst()
            .orElseThrow();

        assertEquals("hot", javaInclusive.getBand());
        assertTemperatureEquals(javaInclusive, aggregateInclusive);
        assertTrue(actualGapIds.contains(cutoff.getId()));
    }

    private static Map<Integer, RelationshipTemperatureDto> byId(
            List<RelationshipTemperatureDto> scores, List<Integer> ids) {
        Set<Integer> requested = Set.copyOf(ids);
        return scores.stream()
            .filter(score -> requested.contains(score.getId()))
            .collect(Collectors.toUnmodifiableMap(
                RelationshipTemperatureDto::getId,
                Function.identity()
            ));
    }

    private static void assertReconciled(
            Map<Integer, RelationshipTemperatureDto> javaScores,
            Map<Integer, RelationshipTemperatureDto> aggregateScores) {
        assertEquals(javaScores.keySet(), aggregateScores.keySet());
        for (Integer id : javaScores.keySet()) {
            assertTemperatureEquals(javaScores.get(id), aggregateScores.get(id));
        }
    }

    private static void assertTemperatureEquals(
            RelationshipTemperatureDto expected,
            RelationshipTemperatureDto actual) {
        assertEquals(expected.getScore(), actual.getScore());
        assertEquals(expected.getBand(), actual.getBand());
        assertEquals(expected.getTrend(), actual.getTrend());
        assertEquals(expected.getLastTouchAt(), actual.getLastTouchAt());
        assertEquals(expected.getDaysSinceTouch(), actual.getDaysSinceTouch());
        assertEquals(expected.getTouchCount(), actual.getTouchCount());
        assertEquals(expected.getDaysUntilCold(), actual.getDaysUntilCold());
        assertEquals(expected.getModelVersion(), actual.getModelVersion());
        assertEquals(expected.getAsOf(), actual.getAsOf());
    }

    private static boolean isWarmOrHot(RelationshipTemperatureDto temperature) {
        return Set.of("warm", "hot").contains(temperature.getBand());
    }

    private Company company(String name) {
        Company company = new Company();
        company.setName(name + " " + UUID.randomUUID().toString().substring(0, 8));
        company.setWorkspaceId(workspace.getId());
        companyMapper.insert(company);
        return company;
    }

    private Person person(String name, Company company) {
        Person person = new Person();
        person.setName(name);
        person.setEmail(UUID.randomUUID() + "@example.com");
        person.setCompany(company);
        person.setWorkspaceId(workspace.getId());
        personMapper.insert(person);
        return person;
    }

    private Activity activity(Person person, String type, LocalDateTime timestamp) {
        return activity(person, null, type, timestamp);
    }

    private Activity activity(Person person, Deal deal, String type, LocalDateTime timestamp) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType(type);
        activity.setSubject("Warmth fixture");
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(user);
        activity.setTimestamp(timestamp.format(MYSQL_DATETIME));
        activityMapper.insert(activity);
        return activity;
    }

    private Pipeline pipeline() {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Warmth pipeline " + UUID.randomUUID().toString().substring(0, 8));
        pipeline.setWorkspaceId(workspace.getId());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage stage(Pipeline pipeline) {
        Stage stage = new Stage();
        stage.setName("Warmth stage");
        stage.setPipeline(pipeline);
        stage.setPosition(0);
        stage.setWorkspaceId(workspace.getId());
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private Deal deal(Pipeline pipeline, Stage stage, Company company) {
        Deal deal = new Deal();
        deal.setName("Warmth deal " + UUID.randomUUID().toString().substring(0, 8));
        deal.setWorkspaceId(workspace.getId());
        deal.setValue(1_000.0);
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);
        return deal;
    }

    private Note note(Person person, String visibility) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("Warmth fixture");
        note.setVisibility(visibility);
        note.setAuthor(user);
        note.setPerson(person);
        noteMapper.insert(note);
        return note;
    }

    private Task task(Person person) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Warmth fixture");
        task.setStatus("todo");
        task.setAssignedTo(user);
        task.setPerson(person);
        taskMapper.insert(task);
        return task;
    }

    private void setCreatedAt(String table, int id, LocalDateTime timestamp) {
        Map<String, String> allowlistedTables = new HashMap<>();
        allowlistedTables.put("note", "note");
        allowlistedTables.put("task", "task");
        String resolved = allowlistedTables.get(table);
        if (resolved == null) {
            throw new IllegalArgumentException("Unsupported fixture table");
        }
        jdbcTemplate.update(
            "UPDATE " + resolved + " SET created_at = ? WHERE workspace_id = ? AND id = ?",
            Timestamp.valueOf(timestamp),
            workspace.getId(),
            id
        );
    }

    private ReportAggregateQuery reportQuery() {
        return new ReportAggregateQuery(
            workspace.getId(),
            "coverage_gap_count",
            "company",
            "day",
            LocalDateTime.of(2026, 1, 1, 0, 0),
            REPORT_END,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2027, 1, 1),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new BigDecimal("0.5"),
            10,
            List.of()
        );
    }
}
