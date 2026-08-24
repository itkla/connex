package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantScopeReadService.TextBudget;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult.Identifier;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * The three server-only reads a personal work brief needs and no model-callable tool provides.
 *
 * <p>Each read projects state a source-owned system already computed — the task projection behind My
 * Work, the deterministic warmth model behind Radar, and the member's own logged forward-dated
 * activities. None of them derives a new signal, and none of them is exposed to the model as a
 * callable tool: they run only inside the declared {@code daily_work_brief_v1} plan, so they cost
 * zero bytes of the fixed prompt envelope.
 *
 * <p>Every result states the counts and caps behind the rows it returned and the categories it could
 * not cover, so a brief assembled from sparse data says so rather than reading as a complete
 * picture of the member's day.
 */
@Service
@RequiredArgsConstructor
public class AiAssistantWorkBriefReadService {

    /**
     * Activity types treated as meeting-shaped.
     *
     * <p>Connex stores meetings as activities, not as a meeting entity with attendees and a
     * preparation state. This list is therefore a presentation convention, and the result labels it
     * as such so nothing downstream can claim a meeting was or was not prepared.
     */
    private static final List<String> MEETING_TYPES = List.of("meeting", "call", "demo");

    private static final int MAX_RESULT_TEXT_CHARS = 8_000;
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskMapper taskMapper;
    private final ActivityMapper activityMapper;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final ScoringService scoringService;
    private final WorkspaceService workspaceService;
    private final OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;
    private final Clock clock;

    /**
     * Reads the actor's overdue and imminently due open commitments.
     *
     * @param userId the member the brief belongs to
     * @param periodDays forward window, in days, the brief covers
     * @param limit maximum commitments, clamped to the declared cap
     * @param resources per-turn handle registry the linked records are registered in
     * @return bounded commitments plus the counts, caps, and exclusions behind them
     */
    public AiAssistantToolResult workCommitments(
            int userId, int periodDays, int limit, AiChatResourceRegistry resources) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int bound = bound(limit, AiChatScopeBounds.MAX_BRIEF_COMMITMENTS);
        LocalDate today = LocalDate.now(clock.withZone(zone()));
        LocalDate dueThrough = today.plusDays(Math.max(1, periodDays) - 1L);
        List<AiAssistantWorkCommitment> commitments =
                taskMapper.getAiAssistantWorkCommitments(
                        workspaceId, userId, today, dueThrough,
                        organizationWorkspaceIds(workspaceId), bound + 1);
        boolean truncated = commitments.size() > bound;
        List<AiAssistantWorkCommitment> bounded = truncated
                ? commitments.subList(0, bound)
                : commitments;
        RecordLabels labels = labelsFor(
                workspaceId,
                bounded.stream().map(AiAssistantWorkCommitment::personId).toList(),
                List.of(),
                bounded.stream().map(AiAssistantWorkCommitment::dealId).toList());
        List<Identifier> identifiers = new ArrayList<>();
        TextBudget budget = new TextBudget(MAX_RESULT_TEXT_CHARS);
        List<Map<String, Object>> rows = new ArrayList<>();
        int overdue = 0;
        for (AiAssistantWorkCommitment commitment : bounded) {
            Map<String, Object> row = new LinkedHashMap<>();
            AiAssistantScopeReadService.putBounded(
                    row, "description", commitment.description(), budget,
                    resources.maskingContext());
            AiAssistantScopeReadService.putTemporal(
                    row, "dueDate", commitment.dueDate(), budget, resources.maskingContext());
            row.put("overdue", commitment.overdue());
            if (commitment.overdue()) {
                overdue++;
            }
            link(row, "person", commitment.personId(), labels.people(), resources, identifiers);
            link(row, "deal", commitment.dealId(), labels.deals(), resources, identifiers);
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", "tasks");
        data.put("owner", "me");
        data.put("periodDays", Math.max(1, periodDays));
        data.put("dueThrough", dueThrough.toString());
        data.put("returnedCommitments", rows.size());
        data.put("overdueCommitments", overdue);
        data.put("commitmentsTruncated", truncated);
        data.put("caps", Map.of("commitments", bound));
        data.put("sort", "due_date_asc");
        data.put("asOf", nowUtc());
        data.put("exclusions", truncated ? List.of("bounded_results") : List.of());
        data.put("commitments", List.copyOf(rows));
        identifiers.forEach(identifier -> identifier.seed(resources.maskingContext()));
        return new AiAssistantToolResult(data, identifiers);
    }

    /**
     * Reads the relationships the deterministic warmth model currently reports as cooling.
     *
     * <p>The bands, trends, and recency figures are Radar's; this read selects and bounds them and
     * never recomputes one. A brief that names a relationship as cooling is therefore repeating the
     * same figure the record page shows.
     *
     * <p>Each kind is read one row past its cap so truncation is evidence rather than a guess. Asking
     * for exactly the cap makes "returned the cap" and "there were more" indistinguishable, so a
     * workspace with precisely the cap's worth of cooling contacts would be reported to the model as
     * bounded when it had in fact been shown everything — exactly the hedge a brief must not state.
     *
     * @param limit maximum records per kind, clamped to the declared cap
     * @param resources per-turn handle registry the returned records are registered in
     * @return bounded cooling contacts and companies with their authoritative warmth figures
     */
    public AiAssistantToolResult warmthMovement(int limit, AiChatResourceRegistry resources) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int bound = bound(limit, AiChatScopeBounds.MAX_BRIEF_WARMTH_MOVES);
        List<RelationshipTemperatureDto> readContacts =
                scoringService.coolingContacts(workspaceId, bound + 1);
        List<RelationshipTemperatureDto> readCompanies =
                scoringService.coolingCompanies(workspaceId, bound + 1);
        boolean truncated = readContacts.size() > bound || readCompanies.size() > bound;
        List<RelationshipTemperatureDto> contacts = readContacts.size() > bound
                ? readContacts.subList(0, bound)
                : readContacts;
        List<RelationshipTemperatureDto> companies = readCompanies.size() > bound
                ? readCompanies.subList(0, bound)
                : readCompanies;
        RecordLabels labels = labelsFor(
                workspaceId,
                contacts.stream().map(RelationshipTemperatureDto::getId).toList(),
                companies.stream().map(RelationshipTemperatureDto::getId).toList(),
                List.of());
        List<Identifier> identifiers = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        appendWarmth(rows, "person", contacts, labels.people(), resources, identifiers);
        appendWarmth(rows, "company", companies, labels.companies(), resources, identifiers);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", "relationship_warmth");
        data.put("trend", "cooling");
        data.put("returnedRecords", rows.size());
        data.put("caps", Map.of("perKind", bound));
        data.put("recordsTruncated", truncated);
        data.put("sort", "days_since_touch_desc");
        data.put("asOf", nowUtc());
        data.put("records", List.copyOf(rows));
        identifiers.forEach(identifier -> identifier.seed(resources.maskingContext()));
        return new AiAssistantToolResult(data, identifiers);
    }

    /**
     * Reads the member's own forward-dated meeting-shaped activities inside the brief's window.
     *
     * <p>The result declares {@code preparationStateAvailable=false} because Connex owns no meeting
     * preparation state. Saying so is the point: without it a brief could otherwise imply it checked
     * whether each meeting was prepared for.
     *
     * @param userId the member the brief belongs to
     * @param periodDays forward window, in days, the brief covers
     * @param limit maximum meetings, clamped to the declared cap
     * @param resources per-turn handle registry the linked records are registered in
     * @return bounded scheduled activities plus the counts, caps, and exclusions behind them
     */
    public AiAssistantToolResult upcomingMeetings(
            int userId, int periodDays, int limit, AiChatResourceRegistry resources) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int bound = bound(limit, AiChatScopeBounds.MAX_BRIEF_MEETINGS);
        LocalDateTime start = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDateTime end = start.plusDays(Math.max(1, periodDays));
        List<AiAssistantUpcomingMeeting> meetings =
                activityMapper.getAiAssistantUpcomingMeetings(
                        workspaceId, userId, start, end, MEETING_TYPES,
                        organizationWorkspaceIds(workspaceId), bound + 1);
        boolean truncated = meetings.size() > bound;
        List<AiAssistantUpcomingMeeting> bounded = truncated
                ? meetings.subList(0, bound)
                : meetings;
        RecordLabels labels = labelsFor(
                workspaceId,
                bounded.stream().map(AiAssistantUpcomingMeeting::personId).toList(),
                List.of(),
                bounded.stream().map(AiAssistantUpcomingMeeting::dealId).toList());
        List<Identifier> identifiers = new ArrayList<>();
        TextBudget budget = new TextBudget(MAX_RESULT_TEXT_CHARS);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiAssistantUpcomingMeeting meeting : bounded) {
            Map<String, Object> row = new LinkedHashMap<>();
            AiAssistantScopeReadService.putBounded(
                    row, "type", meeting.type(), budget, resources.maskingContext());
            AiAssistantScopeReadService.putBounded(
                    row, "subject", meeting.subject(), budget, resources.maskingContext());
            AiAssistantScopeReadService.putTemporal(
                    row, "at", meeting.timestamp(), budget, resources.maskingContext());
            link(row, "person", meeting.personId(), labels.people(), resources, identifiers);
            link(row, "deal", meeting.dealId(), labels.deals(), resources, identifiers);
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", "activities");
        data.put("owner", "me");
        data.put("periodDays", Math.max(1, periodDays));
        data.put("types", MEETING_TYPES);
        data.put("preparationStateAvailable", false);
        data.put("returnedMeetings", rows.size());
        data.put("meetingsTruncated", truncated);
        data.put("caps", Map.of("meetings", bound));
        data.put("sort", "scheduled_at_asc");
        data.put("asOf", nowUtc());
        data.put("exclusions", truncated ? List.of("bounded_results") : List.of());
        data.put("meetings", List.copyOf(rows));
        identifiers.forEach(identifier -> identifier.seed(resources.maskingContext()));
        return new AiAssistantToolResult(data, identifiers);
    }

    private void appendWarmth(
            List<Map<String, Object>> rows,
            String kind,
            List<RelationshipTemperatureDto> scores,
            Map<Integer, String> labels,
            AiChatResourceRegistry resources,
            List<Identifier> identifiers) {
        for (RelationshipTemperatureDto score : scores) {
            String name = labels.get(score.getId());
            if (name == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("handle", resources.register(kind, score.getId()));
            row.put("kind", kind);
            row.put("name", name);
            row.put("band", score.getBand());
            row.put("trend", score.getTrend());
            row.put("score", score.getScore());
            if (score.getDaysSinceTouch() != null) {
                row.put("daysSinceTouch", score.getDaysSinceTouch());
            }
            if (score.getDaysUntilCold() != null) {
                row.put("daysUntilCold", score.getDaysUntilCold());
            }
            row.put("modelVersion", score.getModelVersion());
            rows.add(row);
            identifiers.add(new Identifier(kind, name));
        }
    }

    private static void link(
            Map<String, Object> row,
            String kind,
            Integer recordId,
            Map<Integer, String> labels,
            AiChatResourceRegistry resources,
            List<Identifier> identifiers) {
        if (recordId == null) {
            return;
        }
        String name = labels.get(recordId);
        if (name == null) {
            return;
        }
        row.put(kind + "Handle", resources.register(kind, recordId));
        row.put(kind + "Name", name);
        identifiers.add(new Identifier(kind, name));
    }

    /**
     * Resolves display names for the records a bounded read linked to.
     *
     * <p>A record whose name cannot be resolved — archived, restricted, or no longer processable —
     * is simply absent from the map, and the caller then omits the link rather than naming an
     * identifier the reader has no authorized label for.
     */
    private RecordLabels labelsFor(
            int workspaceId,
            List<Integer> personIds,
            List<Integer> companyIds,
            List<Integer> dealIds) {
        return new RecordLabels(
                people(workspaceId, distinct(personIds)),
                companies(workspaceId, distinct(companyIds)),
                deals(workspaceId, distinct(dealIds)));
    }

    private Map<Integer, String> people(int workspaceId, List<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> labels = new LinkedHashMap<>();
        for (Person person : personMapper.getByIds(workspaceId, ids)) {
            if (person.getSuspendedAt() != null || person.getProvisionCeasedAt() != null
                    || person.getArchivedAt() != null
                    || person.getName() == null || person.getName().isBlank()) {
                continue;
            }
            labels.put(person.getId(), person.getName());
        }
        return Map.copyOf(labels);
    }

    private Map<Integer, String> companies(int workspaceId, List<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> labels = new LinkedHashMap<>();
        for (Company company : companyMapper.getByIds(workspaceId, ids)) {
            if (company.getArchivedAt() != null
                    || company.getName() == null || company.getName().isBlank()) {
                continue;
            }
            labels.put(company.getId(), company.getName());
        }
        return Map.copyOf(labels);
    }

    private Map<Integer, String> deals(int workspaceId, List<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> labels = new LinkedHashMap<>();
        for (Deal deal : dealMapper.getByIds(workspaceId, ids)) {
            if (deal.getName() == null || deal.getName().isBlank()) {
                continue;
            }
            labels.put(deal.getId(), deal.getName());
        }
        return Map.copyOf(labels);
    }

    private static List<Integer> distinct(List<Integer> ids) {
        Set<Integer> seen = new HashSet<>();
        List<Integer> distinct = new ArrayList<>();
        for (Integer id : ids) {
            if (id != null && id > 0 && seen.add(id)) {
                distinct.add(id);
            }
        }
        return List.copyOf(distinct);
    }

    private List<Integer> organizationWorkspaceIds(int workspaceId) {
        return workspaceScopeControlAccess.getForWorkspace(workspaceId).workspaceIds();
    }

    private ZoneId zone() {
        return AiChatScopeCalendar.zone(workspaceService);
    }

    private String nowUtc() {
        return MYSQL_TIMESTAMP.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private static int bound(int requested, int maximum) {
        if (requested <= 0) {
            return maximum;
        }
        return Math.min(requested, maximum);
    }

    private record RecordLabels(
            Map<Integer, String> people,
            Map<Integer, String> companies,
            Map<Integer, String> deals) {
    }
}
