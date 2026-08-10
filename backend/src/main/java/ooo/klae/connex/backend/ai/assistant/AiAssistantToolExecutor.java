package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult.Identifier;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry.ResourceRef;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.SearchService;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.JsonNode;

/** Dispatches validated read tools exclusively through existing tenant- and visibility-scoped services. */
@Service
@RequiredArgsConstructor
public class AiAssistantToolExecutor {
    private static final Set<String> RECORD_KINDS = Set.of("person", "company", "deal");
    private static final Set<Integer> ANALYTICS_DAYS = Set.of(30, 90, 365);
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_NOTES = 10;

    private final AiAssistantToolCatalog toolCatalog;
    private final SearchService searchService;
    private final PersonService personService;
    private final CompanyService companyService;
    private final DealService dealService;
    private final ActivityService activityService;
    private final TaskService taskService;
    private final ScoringService scoringService;
    private final WorkspaceService workspaceService;

    /** Executes one validated, enabled tool call. */
    public AiAssistantToolResult execute(
            String name, JsonNode args, AiChatResourceRegistry resources) {
        validateReferences(name, args, resources);
        if (!toolCatalog.isExecutable(name)) {
            throw AiAssistantLoopException.malformed(toolCatalog.unavailableReason(name));
        }
        try {
            return switch (name) {
                case "search_records" -> search(args, resources);
                case "get_record" -> getRecord(args, resources);
                case "list_activities" -> listActivities(args, resources);
                case "list_tasks" -> listTasks(args, resources);
                case "aggregate_metric" -> aggregateMetric(args);
                default -> throw AiAssistantLoopException.malformed("unknown_tool");
            };
        } catch (ResourceNotFoundException exception) {
            throw AiAssistantLoopException.accessRevoked("inaccessible_resource");
        }
    }

    /** Validates the closed argument shape and every handle before durable tool proposal. */
    public void validateReferences(
            String name, JsonNode args, AiChatResourceRegistry resources) {
        if (!toolCatalog.isKnown(name) || !toolCatalog.permitsArguments(name, args)) {
            throw AiAssistantLoopException.malformed("invalid_tool_arguments");
        }
        requireHandleKind(name, args, resources);
    }

    private static void requireHandleKind(
            String name, JsonNode args, AiChatResourceRegistry resources) {
        JsonNode handle = args.get("handle");
        if (handle == null || handle.isNull()) {
            return;
        }
        Set<String> acceptedKinds = switch (name) {
            case "get_deal_brief" -> Set.of("deal");
            case "find_schedule_conflicts" -> Set.of("person");
            default -> RECORD_KINDS;
        };
        resources.resolve(handle.asString(), acceptedKinds);
    }

    /** Resolves authorized page context into handles without placing tenant-local ids in prompt data. */
    public AiAssistantToolResult pageContext(
            List<AiChatPageContextDto> pageContext, AiChatResourceRegistry resources) {
        List<Map<String, Object>> records = new ArrayList<>();
        List<Identifier> identifiers = new ArrayList<>();
        for (AiChatPageContextDto context : pageContext) {
            if (context == null || context.kind() == null
                    || !RECORD_KINDS.contains(context.kind()) || context.id() <= 0) {
                continue;
            }
            try {
                RecordResult resolved = readRecord(context.kind(), context.id(), resources, false);
                records.add(resolved.data());
                identifiers.addAll(resolved.identifiers());
            } catch (ResourceNotFoundException | AiAssistantLoopException exception) {
                continue;
            }
        }
        return result(Map.of("records", List.copyOf(records)), identifiers);
    }

    private AiAssistantToolResult search(JsonNode args, AiChatResourceRegistry resources) {
        String query = requiredText(args, "query");
        Set<String> kinds = requestedKinds(args.get("kinds"));
        SearchResultsDto matches = searchService.search(query);
        List<Map<String, Object>> records = new ArrayList<>();
        List<Identifier> identifiers = new ArrayList<>();
        if (kinds.contains("person")) {
            matches.getPeople().stream()
                    .filter(person -> person.getId() != null
                            && person.getSuspendedAt() == null
                            && person.getProvisionCeasedAt() == null
                            && person.getArchivedAt() == null)
                    .forEach(person -> addSearchRecord(
                            records, identifiers, resources, "person", person.getId(),
                            person.getName(), person.getTitle()));
        }
        if (kinds.contains("company")) {
            matches.getCompanies().stream()
                    .filter(company -> company.getId() != null && company.getArchivedAt() == null)
                    .forEach(company -> addSearchRecord(
                            records, identifiers, resources, "company", company.getId(),
                            company.getName(), company.getIndustry()));
        }
        if (kinds.contains("deal")) {
            matches.getDeals().stream()
                    .filter(deal -> deal.getId() != null)
                    .forEach(deal -> addSearchRecord(
                            records, identifiers, resources, "deal", deal.getId(),
                            deal.getName(), deal.getCurrency()));
        }
        return result(Map.of("records", List.copyOf(records)), identifiers);
    }

    private AiAssistantToolResult getRecord(JsonNode args, AiChatResourceRegistry resources) {
        ResourceRef resource = resources.resolve(requiredText(args, "handle"), RECORD_KINDS);
        RecordResult record = readRecord(resource.kind(), resource.id(), resources, true);
        return result(record.data(), record.identifiers());
    }

    private AiAssistantToolResult listActivities(JsonNode args, AiChatResourceRegistry resources) {
        ResourceRef resource = resources.resolve(requiredText(args, "handle"), RECORD_KINDS);
        int limit = integer(args, "limit", DEFAULT_LIMIT);
        List<Activity> activities = switch (resource.kind()) {
            case "person" -> {
                requireProcessable(personService.getPersonById(resource.id()));
                yield activityService.getActivitiesByPersonId(resource.id());
            }
            case "company" -> companyService.getCompanyTimeline(resource.id(), limit).activities();
            case "deal" -> {
                dealService.getDealById(resource.id());
                yield activityService.getActivitiesByDealId(resource.id());
            }
            default -> throw AiAssistantLoopException.malformed("wrong_handle_kind");
        };
        List<Map<String, Object>> data = activities.stream()
                .limit(limit)
                .map(AiAssistantToolExecutor::activityData)
                .toList();
        return result(Map.of("handle", requiredText(args, "handle"), "activities", data), List.of());
    }

    private AiAssistantToolResult listTasks(JsonNode args, AiChatResourceRegistry resources) {
        ResourceRef resource = resources.resolve(requiredText(args, "handle"), RECORD_KINDS);
        int limit = integer(args, "limit", DEFAULT_LIMIT);
        List<Task> tasks = switch (resource.kind()) {
            case "person" -> {
                requireProcessable(personService.getPersonById(resource.id()));
                yield taskService.getTasksByPersonId(resource.id());
            }
            case "company" -> companyService.getCompanyTimeline(resource.id(), limit).tasks();
            case "deal" -> {
                dealService.getDealById(resource.id());
                yield taskService.getTasksByDealId(resource.id());
            }
            default -> throw AiAssistantLoopException.malformed("wrong_handle_kind");
        };
        List<Map<String, Object>> data = tasks.stream()
                .limit(limit)
                .map(AiAssistantToolExecutor::taskData)
                .toList();
        return result(Map.of("handle", requiredText(args, "handle"), "tasks", data), List.of());
    }

    private AiAssistantToolResult aggregateMetric(JsonNode args) {
        String metric = requiredText(args, "metric");
        String currency = optionalText(args, "currency");
        int days = integer(args, "days", 90);
        if (!ANALYTICS_DAYS.contains(days)) {
            throw AiAssistantLoopException.malformed("unsupported_analytics_range");
        }
        MemberScope memberScope = MemberScope.fromRequest(
                optionalText(args, "scope"), null, workspaceService.getCurrentUserId());
        Object value = switch (metric) {
            case "deal_metrics" -> dealService.queryDealMetrics(
                    null, currency, null, null, null, false, null, null, memberScope);
            case "deal_kpis" -> dealService.getDealKpis(currency, days, memberScope);
            case "activity_volume" -> activityService.getActivityVolume(days, memberScope);
            case "task_summary" -> taskService.getTaskSummary(memberScope);
            case "warmth_summary" -> scoringService.summarize(workspaceService.getCurrentWorkspaceId());
            default -> throw AiAssistantLoopException.malformed("unknown_metric");
        };
        return result(Map.of("metric", metric, "value", value), List.of());
    }

    private RecordResult readRecord(
            String kind, int id, AiChatResourceRegistry resources, boolean includeNotes) {
        return switch (kind) {
            case "person" -> personRecord(personService.getPersonById(id), resources, includeNotes);
            case "company" -> companyRecord(companyService.getCompanyById(id), resources, includeNotes);
            case "deal" -> dealRecord(dealService.getDealById(id), resources, includeNotes);
            default -> throw AiAssistantLoopException.malformed("unknown_record_kind");
        };
    }

    private RecordResult personRecord(
            Person person, AiChatResourceRegistry resources, boolean includeNotes) {
        requireProcessable(person);
        String handle = resources.register("person", person.getId());
        Map<String, Object> data = baseRecord(handle, "person", person.getName());
        putIfPresent(data, "title", person.getTitle());
        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(new Identifier("person", person.getName()));
        if (person.getCompany() != null && person.getCompany().getId() > 0
                && person.getCompany().getName() != null) {
            String companyHandle = resources.register("company", person.getCompany().getId());
            data.put("company", Map.of(
                    "handle", companyHandle,
                    "name", person.getCompany().getName()));
            identifiers.add(new Identifier("company", person.getCompany().getName()));
        }
        if (includeNotes) {
            data.put("notes", noteData(person.getNotes()));
        }
        return new RecordResult(data, identifiers);
    }

    private RecordResult companyRecord(
            Company company, AiChatResourceRegistry resources, boolean includeNotes) {
        String handle = resources.register("company", company.getId());
        Map<String, Object> data = baseRecord(handle, "company", company.getName());
        putIfPresent(data, "industry", company.getIndustry());
        if (includeNotes) {
            CompanyService.CompanyTimelineData timeline = companyService.getCompanyTimeline(
                    company.getId(), MAX_NOTES);
            data.put("notes", noteData(timeline.notes().toArray(Note[]::new)));
        }
        return new RecordResult(data, List.of(new Identifier("company", company.getName())));
    }

    private RecordResult dealRecord(
            Deal deal, AiChatResourceRegistry resources, boolean includeNotes) {
        String handle = resources.register("deal", deal.getId());
        Map<String, Object> data = baseRecord(handle, "deal", deal.getName());
        data.put("value", deal.getValue());
        putIfPresent(data, "currency", deal.getCurrency());
        putIfPresent(data, "expectedCloseDate", deal.getExpectedCloseDate());
        putIfPresent(data, "closedAt", deal.getClosedAt());
        if (deal.getWon() != null) {
            data.put("won", deal.getWon());
        }
        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(new Identifier("deal", deal.getName()));
        if (deal.getCompanyId() != null) {
            try {
                Company company = companyService.getCompanyById(deal.getCompanyId());
                String companyHandle = resources.register("company", company.getId());
                data.put("company", Map.of("handle", companyHandle, "name", company.getName()));
                identifiers.add(new Identifier("company", company.getName()));
            } catch (ResourceNotFoundException exception) {
                data.remove("company");
            }
        }
        if (includeNotes) {
            data.put("notes", noteData(
                    dealService.getNotesByDealId(deal.getId()).toArray(Note[]::new)));
        }
        return new RecordResult(data, identifiers);
    }

    private static void addSearchRecord(
            List<Map<String, Object>> records,
            List<Identifier> identifiers,
            AiChatResourceRegistry resources,
            String kind,
            int id,
            String name,
            String detail) {
        if (name == null || name.isBlank()) {
            return;
        }
        Map<String, Object> record = baseRecord(resources.register(kind, id), kind, name);
        putIfPresent(record, "detail", detail);
        records.add(record);
        if (RECORD_KINDS.contains(kind)) {
            identifiers.add(new Identifier(kind, name));
        }
    }

    private static Map<String, Object> baseRecord(String handle, String kind, String name) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("handle", handle);
        record.put("kind", kind);
        record.put("name", name);
        return record;
    }

    private static Map<String, Object> activityData(Activity activity) {
        Map<String, Object> data = new LinkedHashMap<>();
        putIfPresent(data, "type", activity.getType());
        putIfPresent(data, "subject", activity.getSubject());
        putIfPresent(data, "notes", activity.getNotes());
        putIfPresent(data, "timestamp", activity.getTimestamp());
        return data;
    }

    private static Map<String, Object> taskData(Task task) {
        Map<String, Object> data = new LinkedHashMap<>();
        putIfPresent(data, "description", task.getDescription());
        putIfPresent(data, "status", task.getStatus());
        data.put("completed", task.isCompleted());
        putIfPresent(data, "dueDate", task.getDueDate());
        return data;
    }

    private static List<Map<String, Object>> noteData(Note[] notes) {
        if (notes == null) {
            return List.of();
        }
        return Arrays.stream(notes)
                .filter(note -> note != null)
                .limit(MAX_NOTES)
                .map(note -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    putIfPresent(data, "title", note.getTitle());
                    putIfPresent(data, "content", note.getContent());
                    putIfPresent(data, "createdAt", note.getCreatedAt());
                    return data;
                })
                .toList();
    }

    private static void requireProcessable(Person person) {
        if (person == null || person.getSuspendedAt() != null
                || person.getProvisionCeasedAt() != null || person.getArchivedAt() != null) {
            throw AiAssistantLoopException.accessRevoked("inaccessible_resource");
        }
    }

    private static Set<String> requestedKinds(JsonNode node) {
        if (node == null || node.isNull()) {
            return RECORD_KINDS;
        }
        Set<String> kinds = new LinkedHashSet<>();
        for (JsonNode value : node) {
            kinds.add(value.asString());
        }
        return Set.copyOf(kinds);
    }

    private static String requiredText(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw AiAssistantLoopException.malformed("invalid_tool_arguments");
        }
        return value.asString();
    }

    private static String optionalText(JsonNode args, String name) {
        JsonNode value = args.get(name);
        return value == null || value.isNull() || value.asString().isBlank()
                ? null
                : value.asString();
    }

    private static int integer(JsonNode args, String name, int fallback) {
        JsonNode value = args.get(name);
        return value == null || value.isNull() ? fallback : value.asInt();
    }

    private static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value instanceof String text) {
            if (!text.isBlank()) {
                data.put(key, value);
            }
            return;
        }
        if (value != null) {
            data.put(key, value);
        }
    }

    private static AiAssistantToolResult result(
            Map<String, Object> data, List<Identifier> identifiers) {
        return new AiAssistantToolResult(data, identifiers);
    }

    private record RecordResult(Map<String, Object> data, List<Identifier> identifiers) {
    }
}
