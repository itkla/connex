package ooo.klae.connex.backend.ai.assistant;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult.Identifier;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry.ResourceRef;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
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
    private static final int MAX_NOTE_FIELD_CHARS = 4_000;
    private static final int MAX_NOTE_RESULT_TEXT_CHARS = 16_000;
    private static final int MAX_SCHEDULE_CONFLICTS = 20;
    private static final int MAX_SCHEDULE_CONFLICT_CANDIDATES = 101;
    private static final int MAX_SCHEDULE_CONFLICT_FIELD_CHARS = 512;
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiAssistantToolCatalog toolCatalog;
    private final SearchService searchService;
    private final PersonService personService;
    private final CompanyService companyService;
    private final DealService dealService;
    private final ActivityService activityService;
    private final TaskService taskService;
    private final ScoringService scoringService;
    private final WorkspaceService workspaceService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final AiAssistantDateResolver dateResolver;

    /** Executes one validated, enabled tool call. */
    public AiAssistantToolResult execute(
            String name,
            JsonNode args,
            AiChatResourceRegistry resources,
            boolean includePrivateNotes) {
        validateReferences(name, args, resources);
        if (!toolCatalog.isExecutable(name)) {
            throw AiAssistantLoopException.malformed(toolCatalog.unavailableReason(name));
        }
        try {
            return switch (name) {
                case "search_records" -> search(args, resources);
                case "get_record" -> getRecord(args, resources, includePrivateNotes);
                case "list_activities" -> listActivities(args, resources);
                case "list_tasks" -> listTasks(args, resources);
                case "aggregate_metric" -> aggregateMetric(args);
                case "find_schedule_conflicts" -> findScheduleConflicts(args, resources);
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
            case "create_activity", "create_task", "create_note" -> Set.of("person", "deal");
            case "change_deal_stage" -> Set.of("deal");
            default -> RECORD_KINDS;
        };
        resources.resolve(handle.asString(), acceptedKinds);
    }

    /** Resolves authorized page context into handles without placing tenant-local ids in prompt data. */
    public AiAssistantToolResult pageContext(
            List<AiChatPageContextDto> pageContext, AiChatResourceRegistry resources) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> personIds = idsForKind(pageContext, "person");
        List<Integer> companyIds = idsForKind(pageContext, "company");
        List<Integer> dealIds = idsForKind(pageContext, "deal");
        Map<Integer, Person> people = personIds.isEmpty()
                ? Map.of()
                : personMapper.getByIds(workspaceId, personIds).stream()
                        .filter(AiAssistantToolExecutor::isProcessable)
                        .collect(java.util.stream.Collectors.toMap(Person::getId, person -> person));
        Map<Integer, Company> companies = companyIds.isEmpty()
                ? Map.of()
                : companyMapper.getByIds(workspaceId, companyIds).stream()
                        .collect(java.util.stream.Collectors.toMap(Company::getId, company -> company));
        Map<Integer, Deal> deals = dealIds.isEmpty()
                ? Map.of()
                : dealMapper.getByIds(workspaceId, dealIds).stream()
                        .collect(java.util.stream.Collectors.toMap(Deal::getId, deal -> deal));
        List<Integer> dealCompanyIds = deals.values().stream()
                .map(Deal::getCompanyId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Integer, Company> dealCompanies = dealCompanyIds.isEmpty()
                ? Map.of()
                : companyMapper.getByIds(workspaceId, dealCompanyIds)
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(Company::getId, company -> company));
        List<Map<String, Object>> records = new ArrayList<>();
        List<Identifier> identifiers = new ArrayList<>();
        for (AiChatPageContextDto context : pageContext) {
            if (context == null || context.kind() == null
                    || !RECORD_KINDS.contains(context.kind()) || context.id() <= 0) {
                continue;
            }
            RecordResult resolved = switch (context.kind()) {
                case "person" -> people.containsKey(context.id())
                        ? personRecord(people.get(context.id()), resources, false)
                        : null;
                case "company" -> companies.containsKey(context.id())
                        ? companyRecord(companies.get(context.id()), resources, false)
                        : null;
                case "deal" -> deals.containsKey(context.id())
                        ? dealRecord(
                                deals.get(context.id()), resources, false, dealCompanies)
                        : null;
                default -> null;
            };
            if (resolved == null) {
                continue;
            }
            records.add(resolved.data());
            identifiers.addAll(resolved.identifiers());
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

    private AiAssistantToolResult getRecord(
            JsonNode args,
            AiChatResourceRegistry resources,
            boolean includePrivateNotes) {
        ResourceRef resource = resources.resolve(requiredText(args, "handle"), RECORD_KINDS);
        RecordResult record = readRecord(
                resource.kind(), resource.id(), resources, true, includePrivateNotes);
        return result(record.data(), record.identifiers());
    }

    private AiAssistantToolResult listActivities(JsonNode args, AiChatResourceRegistry resources) {
        ResourceRef resource = resources.resolve(requiredText(args, "handle"), RECORD_KINDS);
        int limit = integer(args, "limit", DEFAULT_LIMIT);
        List<Activity> activities = switch (resource.kind()) {
            case "person" -> {
                requireProcessable(personService.getPersonById(resource.id()));
                yield filterRestrictedLinkedPeople(
                        activityService.getActivitiesByPersonId(resource.id()),
                        Activity::getPerson,
                        Activity::getReferences,
                        resources.maskingContext());
            }
            case "company" -> filterRestrictedLinkedPeople(
                    companyService.getCompanyTimeline(resource.id(), limit).activities(),
                    Activity::getPerson,
                    Activity::getReferences,
                    resources.maskingContext());
            case "deal" -> {
                dealService.getDealById(resource.id());
                yield filterRestrictedLinkedPeople(
                        activityService.getActivitiesByDealId(resource.id()),
                        Activity::getPerson,
                        Activity::getReferences,
                        resources.maskingContext());
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
                yield filterRestrictedLinkedPeople(
                        taskService.getTasksByPersonId(resource.id()),
                        Task::getPerson,
                        Task::getReferences,
                        resources.maskingContext());
            }
            case "company" -> filterRestrictedLinkedPeople(
                    companyService.getCompanyTimeline(resource.id(), limit).tasks(),
                    Task::getPerson,
                    Task::getReferences,
                    resources.maskingContext());
            case "deal" -> {
                dealService.getDealById(resource.id());
                yield filterRestrictedLinkedPeople(
                        taskService.getTasksByDealId(resource.id()),
                        Task::getPerson,
                        Task::getReferences,
                        resources.maskingContext());
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

    private AiAssistantToolResult findScheduleConflicts(
            JsonNode args, AiChatResourceRegistry resources) {
        ResourceRef resource = resources.resolve(requiredText(args, "handle"), Set.of("person"));
        AiAssistantDateResolver.ResolvedDateTime start =
                dateResolver.resolveDateTime(requiredText(args, "start"));
        AiAssistantDateResolver.ResolvedDateTime end =
                dateResolver.resolveDateTime(requiredText(args, "end"));
        if (!end.utc().isAfter(start.utc())) {
            throw AiAssistantLoopException.malformed("invalid_schedule_window");
        }
        return findScheduleConflicts(
                resource.id(), start.utc(), end.utc(), resources.maskingContext());
    }

    /** Finds point-in-time activities for a processable person inside one UTC meeting window. */
    public AiAssistantToolResult findScheduleConflicts(
            int personId, LocalDateTime startUtc, LocalDateTime endUtc) {
        return findScheduleConflicts(
                personId,
                startUtc,
                endUtc,
                new MaskingContext(AiPrivacyMode.UNMASKED));
    }

    private AiAssistantToolResult findScheduleConflicts(
            int personId,
            LocalDateTime startUtc,
            LocalDateTime endUtc,
            MaskingContext maskingContext) {
        Person person = personService.getPersonById(personId);
        requireProcessable(person);
        new Identifier("person", person.getName()).seed(maskingContext);
        List<Activity> candidates = activityService.getActivitiesByPersonIdInWindow(
                personId,
                startUtc,
                endUtc,
                MAX_SCHEDULE_CONFLICT_CANDIDATES);
        List<Activity> matchingActivities = filterRestrictedLinkedPeople(
                    candidates,
                    Activity::getPerson,
                    Activity::getReferences,
                    maskingContext)
                .stream()
                .limit(MAX_SCHEDULE_CONFLICTS + 1L)
                .toList();
        List<Map<String, Object>> conflicts = matchingActivities.stream()
                .limit(MAX_SCHEDULE_CONFLICTS)
                .map(activity -> scheduleConflictData(activity, maskingContext))
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("start", MYSQL_TIMESTAMP.format(startUtc));
        data.put("end", MYSQL_TIMESTAMP.format(endUtc));
        data.put("conflicts", conflicts);
        data.put(
                "conflictsTruncated",
                candidates.size() == MAX_SCHEDULE_CONFLICT_CANDIDATES
                        || matchingActivities.size() > MAX_SCHEDULE_CONFLICTS);
        return result(data, List.of());
    }

    private RecordResult readRecord(
            String kind,
            int id,
            AiChatResourceRegistry resources,
            boolean includeNotes,
            boolean includePrivateNotes) {
        return switch (kind) {
            case "person" -> personRecord(
                    personService.getPersonById(id), resources,
                    includeNotes, includePrivateNotes);
            case "company" -> companyRecord(
                    companyService.getCompanyById(id), resources,
                    includeNotes, includePrivateNotes);
            case "deal" -> dealRecord(
                    dealService.getDealById(id), resources,
                    includeNotes, includePrivateNotes, null);
            default -> throw AiAssistantLoopException.malformed("unknown_record_kind");
        };
    }

    private RecordResult personRecord(
            Person person, AiChatResourceRegistry resources, boolean includeNotes) {
        return personRecord(person, resources, includeNotes, true);
    }

    private RecordResult personRecord(
            Person person,
            AiChatResourceRegistry resources,
            boolean includeNotes,
            boolean includePrivateNotes) {
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
        seedIdentifiers(identifiers, resources.maskingContext());
        if (includeNotes) {
            List<Note> notes = person.getNotes() == null
                    ? List.of()
                    : Arrays.asList(person.getNotes());
            data.put("notes", noteData(
                    filterRestrictedLinkedPeople(
                            notes,
                            Note::getPerson,
                            Note::getReferences,
                            resources.maskingContext()).toArray(Note[]::new),
                    includePrivateNotes,
                    resources.maskingContext()));
        }
        return new RecordResult(data, identifiers);
    }

    private RecordResult companyRecord(
            Company company, AiChatResourceRegistry resources, boolean includeNotes) {
        return companyRecord(company, resources, includeNotes, true);
    }

    private RecordResult companyRecord(
            Company company,
            AiChatResourceRegistry resources,
            boolean includeNotes,
            boolean includePrivateNotes) {
        String handle = resources.register("company", company.getId());
        Map<String, Object> data = baseRecord(handle, "company", company.getName());
        putIfPresent(data, "industry", company.getIndustry());
        List<Identifier> identifiers = List.of(new Identifier("company", company.getName()));
        seedIdentifiers(identifiers, resources.maskingContext());
        if (includeNotes) {
            CompanyService.CompanyTimelineData timeline = companyService.getCompanyTimeline(
                    company.getId(), MAX_NOTES);
            data.put("notes", noteData(
                    filterRestrictedLinkedPeople(
                            timeline.notes(),
                            Note::getPerson,
                            Note::getReferences,
                            resources.maskingContext())
                            .toArray(Note[]::new),
                    includePrivateNotes,
                    resources.maskingContext()));
        }
        return new RecordResult(data, identifiers);
    }

    private RecordResult dealRecord(
            Deal deal,
            AiChatResourceRegistry resources,
            boolean includeNotes,
            Map<Integer, Company> prefetchedCompanies) {
        return dealRecord(deal, resources, includeNotes, true, prefetchedCompanies);
    }

    private RecordResult dealRecord(
            Deal deal,
            AiChatResourceRegistry resources,
            boolean includeNotes,
            boolean includePrivateNotes,
            Map<Integer, Company> prefetchedCompanies) {
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
                Company company = prefetchedCompanies == null
                        ? companyService.getCompanyById(deal.getCompanyId())
                        : prefetchedCompanies.get(deal.getCompanyId());
                if (company == null) {
                    throw new ResourceNotFoundException("Company is unavailable");
                }
                String companyHandle = resources.register("company", company.getId());
                data.put("company", Map.of("handle", companyHandle, "name", company.getName()));
                identifiers.add(new Identifier("company", company.getName()));
            } catch (ResourceNotFoundException exception) {
                data.remove("company");
            }
        }
        seedIdentifiers(identifiers, resources.maskingContext());
        if (includeNotes) {
            data.put("notes", noteData(
                    filterRestrictedLinkedPeople(
                            dealService.getNotesByDealId(deal.getId()),
                            Note::getPerson,
                            Note::getReferences,
                            resources.maskingContext())
                            .toArray(Note[]::new),
                    includePrivateNotes,
                    resources.maskingContext()));
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

    private static Map<String, Object> scheduleConflictData(
            Activity activity, MaskingContext maskingContext) {
        Map<String, Object> data = new LinkedHashMap<>();
        putBounded(
                data, "type", activity.getType(), MAX_SCHEDULE_CONFLICT_FIELD_CHARS, maskingContext);
        putBounded(
                data, "subject", activity.getSubject(),
                MAX_SCHEDULE_CONFLICT_FIELD_CHARS, maskingContext);
        putBounded(
                data, "notes", activity.getNotes(),
                MAX_SCHEDULE_CONFLICT_FIELD_CHARS, maskingContext);
        putBounded(
                data, "timestamp", activity.getTimestamp(),
                MAX_SCHEDULE_CONFLICT_FIELD_CHARS, maskingContext);
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

    private static List<Map<String, Object>> noteData(
            Note[] notes, boolean includePrivateNotes, MaskingContext maskingContext) {
        if (notes == null) {
            return List.of();
        }
        TextBudget budget = new TextBudget(MAX_NOTE_RESULT_TEXT_CHARS);
        List<Map<String, Object>> result = new ArrayList<>();
        Arrays.stream(notes)
                .filter(note -> note != null)
                .filter(note -> includePrivateNotes || "workspace".equals(note.getVisibility()))
                .limit(MAX_NOTES)
                .forEach(note -> {
                    if (budget.remaining() == 0) {
                        return;
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    putBounded(data, "title", note.getTitle(), budget, maskingContext);
                    putBounded(data, "createdAt", note.getCreatedAt(), budget, maskingContext);
                    boolean complete = putBounded(
                            data, "content", note.getContent(), budget, maskingContext);
                    if (!complete) {
                        data.put("contentTruncated", true);
                    }
                    result.add(data);
                });
        return List.copyOf(result);
    }

    private <T> List<T> filterRestrictedLinkedPeople(
            List<T> records,
            Function<T, Person> personReference,
            Function<T, List<EntityReference>> structuredReferences,
            MaskingContext maskingContext) {
        Set<Integer> personIds = new LinkedHashSet<>();
        for (T record : records) {
            if (record == null) {
                continue;
            }
            Person person = personReference.apply(record);
            if (person != null && person.getId() > 0) {
                personIds.add(person.getId());
            }
            List<EntityReference> references = structuredReferences.apply(record);
            if (references != null) {
                references.stream()
                        .filter(Objects::nonNull)
                        .filter(reference -> "person".equals(reference.getRefType()))
                        .map(EntityReference::getRefId)
                        .filter(id -> id > 0)
                        .forEach(personIds::add);
            }
        }
        if (personIds.isEmpty()) {
            return List.copyOf(records);
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Person> processablePeople = personMapper.getByIds(
                        workspaceId, List.copyOf(personIds)).stream()
                .filter(AiAssistantToolExecutor::isProcessable)
                .toList();
        processablePeople.forEach(person ->
                new Identifier("person", person.getName()).seed(maskingContext));
        Set<Integer> processableIds = processablePeople.stream()
                .map(Person::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return records.stream()
                .filter(Objects::nonNull)
                .filter(record -> {
                    Person person = personReference.apply(record);
                    if (person != null && !processableIds.contains(person.getId())) {
                        return false;
                    }
                    List<EntityReference> references = structuredReferences.apply(record);
                    return references == null || references.stream()
                            .filter(Objects::nonNull)
                            .filter(reference -> "person".equals(reference.getRefType()))
                            .map(EntityReference::getRefId)
                            .allMatch(processableIds::contains);
                })
                .toList();
    }

    private static boolean putBounded(
            Map<String, Object> data,
            String key,
            String value,
            TextBudget budget,
            MaskingContext maskingContext) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String screened = MaskingEngine.screenFreeTextBeforeTruncation(value, maskingContext);
        int retained = Math.min(
                screened.length(), Math.min(MAX_NOTE_FIELD_CHARS, budget.remaining()));
        if (retained > 0) {
            data.put(key, screened.substring(0, retained));
            budget.consume(retained);
        }
        return retained == screened.length();
    }

    private static void putBounded(
            Map<String, Object> data,
            String key,
            String value,
            int maxCharacters,
            MaskingContext maskingContext) {
        if (value == null || value.isBlank()) {
            return;
        }
        String screened = MaskingEngine.screenFreeTextBeforeTruncation(value, maskingContext);
        int retained = Math.min(screened.length(), maxCharacters);
        data.put(key, screened.substring(0, retained));
        if (retained < screened.length()) {
            data.put(key + "Truncated", true);
        }
    }

    private static void requireProcessable(Person person) {
        if (!isProcessable(person)) {
            throw AiAssistantLoopException.accessRevoked("inaccessible_resource");
        }
    }

    private static boolean isProcessable(Person person) {
        return person != null && person.getSuspendedAt() == null
                && person.getProvisionCeasedAt() == null && person.getArchivedAt() == null;
    }

    private static List<Integer> idsForKind(
            List<AiChatPageContextDto> pageContext, String kind) {
        return pageContext.stream()
                .filter(context -> context != null && kind.equals(context.kind()) && context.id() > 0)
                .map(AiChatPageContextDto::id)
                .distinct()
                .toList();
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

    private static void seedIdentifiers(
            List<Identifier> identifiers, MaskingContext maskingContext) {
        identifiers.forEach(identifier -> identifier.seed(maskingContext));
    }

    private record RecordResult(Map<String, Object> data, List<Identifier> identifiers) {
    }

    private static final class TextBudget {
        private int remaining;

        private TextBudget(int remaining) {
            this.remaining = remaining;
        }

        private int remaining() {
            return remaining;
        }

        private void consume(int characters) {
            remaining -= characters;
        }
    }
}
