package ooo.klae.connex.backend.work;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.dto.WorkItemAction;
import ooo.klae.connex.backend.dto.WorkItemActionResponse;
import ooo.klae.connex.backend.dto.WorkItemAvailability;
import ooo.klae.connex.backend.dto.WorkItemDto;
import ooo.klae.connex.backend.dto.WorkItemPageDto;
import ooo.klae.connex.backend.dto.WorkItemSource;
import ooo.klae.connex.backend.dto.WorkItemSourceAvailability;
import ooo.klae.connex.backend.dto.WorkItemSourceStatusDto;
import ooo.klae.connex.backend.dto.WorkItemSummaryDto;
import ooo.klae.connex.backend.dto.WorkItemUrgency;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.UserCalendarService;
import ooo.klae.connex.backend.services.WorkspaceService;

/** Orchestrates isolated providers into one deterministic My Work projection. */
@Service
public class WorkItemService {
    private static final Logger log = LoggerFactory.getLogger(WorkItemService.class);
    private static final int DEFAULT_SIZE = 25;
    private static final int MAX_SIZE = 50;
    private static final int MAX_WINDOW = 1000;
    private static final List<WorkItemSource> SOURCE_ORDER = List.of(
        WorkItemSource.task,
        WorkItemSource.document_approval,
        WorkItemSource.notification);

    private final Map<WorkItemSource, WorkItemProvider> providers;
    private final WorkspaceService workspaceService;
    private final UserCalendarService userCalendarService;
    private final Clock clock;

    /** Creates the orchestrator and requires exactly one provider per source. */
    public WorkItemService(
            List<WorkItemProvider> providers,
            WorkspaceService workspaceService,
            UserCalendarService userCalendarService,
            Clock clock) {
        Map<WorkItemSource, WorkItemProvider> indexed = new EnumMap<>(WorkItemSource.class);
        for (WorkItemProvider provider : providers) {
            if (indexed.put(provider.source(), provider) != null) {
                throw new IllegalStateException("Duplicate My Work provider source");
            }
        }
        if (!indexed.keySet().containsAll(EnumSet.allOf(WorkItemSource.class))) {
            throw new IllegalStateException("My Work provider set is incomplete");
        }
        this.providers = Map.copyOf(indexed);
        this.workspaceService = workspaceService;
        this.userCalendarService = userCalendarService;
        this.clock = clock;
    }

    /** Returns one globally ranked page over the selected isolated providers. */
    public WorkItemPageDto getPage(
            List<String> sourceFilters,
            List<String> urgencyFilters,
            Integer requestedPage,
            Integer requestedSize) {
        int page = requestedPage == null ? 1 : requestedPage;
        int size = requestedSize == null ? DEFAULT_SIZE : requestedSize;
        if (page < 1 || size < 1 || size > MAX_SIZE) {
            throw new BadRequestException("My Work page or size is invalid");
        }
        long offsetValue = (long) (page - 1) * size;
        if (offsetValue + size > MAX_WINDOW) {
            throw new BadRequestException("My Work pagination window exceeds 1000 items");
        }
        Set<WorkItemSource> selected = sources(sourceFilters);
        Set<WorkItemUrgency> urgencies = urgencies(urgencyFilters);
        int offset = Math.toIntExact(offsetValue);
        Snapshot snapshot = load(selected, urgencies, offset + size, sourceFilters != null);
        List<WorkItemDto> merged = snapshot.results().values().stream()
            .flatMap(result -> result.items().stream())
            .sorted(WorkItemOrdering.comparator())
            .toList();
        List<WorkItemDto> items = merged.stream().skip(offset).limit(size).toList();
        boolean loadedNext = merged.size() > offset + items.size();
        boolean hasNext = loadedNext
            || snapshot.knownMatchingTotal() > (long) offset + items.size();
        boolean hasNextKnown = snapshot.totalsComplete() || hasNext;
        return new WorkItemPageDto(
            items,
            page,
            size,
            snapshot.knownMatchingTotal(),
            snapshot.knownOverallTotal(),
            snapshot.totalsComplete(),
            hasNext,
            hasNextKnown,
            snapshot.availability(),
            snapshot.statuses(),
            snapshot.asOf());
    }

    /** Returns critical and overall known totals without exposing item bodies. */
    public WorkItemSummaryDto summary() {
        Snapshot snapshot = load(
            EnumSet.allOf(WorkItemSource.class),
            EnumSet.of(WorkItemUrgency.critical),
            MAX_WINDOW,
            false);
        return new WorkItemSummaryDto(
            snapshot.knownOverallTotal(),
            snapshot.knownMatchingTotal(),
            snapshot.totalsComplete(),
            snapshot.availability(),
            snapshot.statuses(),
            snapshot.asOf());
    }

    /** Completes one assigned task through its authoritative service. */
    public WorkItemActionResponse completeTask(int sourceId, String expectedStateHash) {
        return execute(WorkItemSource.task, sourceId, new WorkItemActionCommand(
            WorkItemAction.complete, expectedStateHash, null, null, null, null));
    }

    /** Snoozes one current-recipient deal-close notification. */
    public WorkItemActionResponse snoozeNotification(
            int sourceId,
            SnoozeRequest request,
            String expectedStateHash) {
        return execute(WorkItemSource.notification, sourceId, new WorkItemActionCommand(
            WorkItemAction.snooze, expectedStateHash, request, null, null, null));
    }

    /** Dismisses one current-recipient deal-close notification. */
    public WorkItemActionResponse dismissNotification(int sourceId, String expectedStateHash) {
        return execute(WorkItemSource.notification, sourceId, new WorkItemActionCommand(
            WorkItemAction.dismiss, expectedStateHash, null, null, null, null));
    }

    /** Decides one exact actionable approval step through its authoritative service. */
    public WorkItemActionResponse decideApproval(
            int sourceId,
            int stepId,
            String decision,
            String comment,
            String expectedStateHash) {
        WorkItemAction action = "approved".equals(decision)
            ? WorkItemAction.approve
            : WorkItemAction.reject;
        return execute(WorkItemSource.document_approval, sourceId, new WorkItemActionCommand(
            action, expectedStateHash, null, decision, comment, stepId));
    }

    private WorkItemActionResponse execute(
            WorkItemSource source,
            int sourceId,
            WorkItemActionCommand command) {
        if (sourceId < 1) {
            throw new BadRequestException("My Work source id must be positive");
        }
        return providers.get(source).execute(sourceId, command);
    }

    private Snapshot load(
            Set<WorkItemSource> selected,
            Set<WorkItemUrgency> urgencies,
            int candidateLimit,
            boolean explicitlySelected) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        LocalDate actorToday = userCalendarService.today();
        Instant asOf = clock.instant();
        WorkItemProviderQuery query = new WorkItemProviderQuery(
            workspaceId, actorId, actorToday, asOf, Set.copyOf(urgencies), candidateLimit);
        Map<WorkItemSource, WorkItemProviderResult> results = new LinkedHashMap<>();
        List<WorkItemSourceStatusDto> statuses = new ArrayList<>();
        int forbidden = 0;
        int unavailable = 0;
        boolean totalsComplete = true;
        long matchingTotal = 0;
        long overallTotal = 0;
        for (WorkItemSource source : SOURCE_ORDER) {
            if (!selected.contains(source)) {
                continue;
            }
            try {
                WorkItemProviderResult result = providers.get(source).load(query);
                results.put(source, result);
                matchingTotal += result.matchingTotal();
                overallTotal += result.overallTotal();
                totalsComplete &= result.totalsComplete();
                statuses.add(new WorkItemSourceStatusDto(
                    source,
                    WorkItemSourceAvailability.available,
                    result.matchingTotal(),
                    result.overallTotal(),
                    result.asOf(),
                    null));
            } catch (ForbiddenException exception) {
                forbidden++;
                statuses.add(new WorkItemSourceStatusDto(
                    source,
                    WorkItemSourceAvailability.forbidden,
                    null,
                    null,
                    null,
                    null));
            } catch (RuntimeException exception) {
                unavailable++;
                totalsComplete = false;
                String errorCode = exception instanceof InvalidWorkItemSourceRowsException
                    ? "invalid_source_rows"
                    : "provider_unavailable";
                statuses.add(new WorkItemSourceStatusDto(
                    source,
                    WorkItemSourceAvailability.unavailable,
                    null,
                    null,
                    null,
                    errorCode));
                log.warn(
                    "My Work provider failed source={} exceptionClass={}",
                    source,
                    exception.getClass().getSimpleName());
            }
        }
        if (explicitlySelected && forbidden == selected.size()) {
            throw new ForbiddenException("The selected My Work source is forbidden");
        }
        int available = results.size();
        WorkItemAvailability availability = unavailable == 0
            ? WorkItemAvailability.available
            : available == 0
                ? WorkItemAvailability.unavailable
                : WorkItemAvailability.partial;
        return new Snapshot(
            Map.copyOf(results),
            List.copyOf(statuses),
            matchingTotal,
            overallTotal,
            totalsComplete,
            availability,
            asOf);
    }

    private static Set<WorkItemSource> sources(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return EnumSet.allOf(WorkItemSource.class);
        }
        EnumSet<WorkItemSource> selected = EnumSet.noneOf(WorkItemSource.class);
        for (String filter : filters) {
            try {
                selected.add(WorkItemSource.valueOf(filter));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new BadRequestException("Unknown My Work source");
            }
        }
        return Set.copyOf(selected);
    }

    private static Set<WorkItemUrgency> urgencies(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return Set.of();
        }
        EnumSet<WorkItemUrgency> selected = EnumSet.noneOf(WorkItemUrgency.class);
        for (String filter : filters) {
            try {
                selected.add(WorkItemUrgency.valueOf(filter));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new BadRequestException("Unknown My Work urgency");
            }
        }
        return Set.copyOf(selected);
    }

    private record Snapshot(
        Map<WorkItemSource, WorkItemProviderResult> results,
        List<WorkItemSourceStatusDto> statuses,
        long knownMatchingTotal,
        long knownOverallTotal,
        boolean totalsComplete,
        WorkItemAvailability availability,
        Instant asOf
    ) {
    }
}
