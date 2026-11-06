package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.DealCurrencyMetricsDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.MyWorkDealKpisDto;
import ooo.klae.connex.backend.dto.MyWorkHistoryDto;
import ooo.klae.connex.backend.dto.MyWorkRelationshipsDto;
import ooo.klae.connex.backend.dto.MyWorkRisksDto;
import ooo.klae.connex.backend.dto.NoteActivityDayDto;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.MyWorkReadMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.util.AnalyticsPeriods;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/** Self-only, bounded supporting reads for /me; source services retain their authorization rules. */
@Service
@RequiredArgsConstructor
public class MyWorkReadService {
    private final WorkspaceService workspaceService;
    private final MyWorkReadMapper mapper;
    private final PersonMapper personMapper;
    private final DealMapper dealMapper;
    private final UserService userService;
    private final ReferenceService referenceService;
    private final RecordVisibilityScopeControlAccess visibility;
    private final ScoringService scoringService;
    private final DealService dealService;
    private final DealRiskService dealRiskService;
    private final Clock clock;

    /** Returns the latest 25 records per source without expanding any workspace collection. */
    public MyWorkHistoryDto history() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        return new MyWorkHistoryDto(
            referenceService.hydrateTasks(workspaceId, mapper.recentTasks(workspaceId, userId))
                .stream().map(TaskDto::from).toList(),
            referenceService.hydrateActivities(workspaceId, mapper.recentActivities(workspaceId, userId))
                .stream().map(ActivityDto::from).toList(),
            userService.getNotesByUserId(userId, 25, 0).stream().map(NoteDto::from).toList());
    }

    /** Returns at most 84 daily totals, independent of the bounded timeline preview. */
    public List<NoteActivityDayDto> pulse() {
        int userId = workspaceService.getCurrentUserId();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<NoteActivityDayDto> days = new ArrayList<>(mapper.activityPulse(
            workspaceService.getCurrentWorkspaceId(), userId, today.minusDays(83).atStartOfDay(),
            today.plusDays(1).atStartOfDay()));
        days.addAll(userService.getNoteActivityByUserId(userId));
        return days.stream().collect(java.util.stream.Collectors.groupingBy(NoteActivityDayDto::date,
                java.util.stream.Collectors.summingLong(NoteActivityDayDto::count)))
            .entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .map(entry -> new NoteActivityDayDto(entry.getKey(), entry.getValue())).toList();
    }

    /** Counts the complete personal network in SQL, hydrating at most 24 display contacts. */
    public MyWorkRelationshipsDto relationships() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        var instant = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        LocalDateTime reference = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        var model = RelationshipWarmthModel.current().sqlParameters();
        var counts = mapper.relationshipCounts(workspaceId, userId, reference, model);
        LinkedHashSet<Integer> ids = new LinkedHashSet<>(
            mapper.relationshipIds(workspaceId, userId, reference, model, false, 18));
        ids.addAll(mapper.relationshipIds(workspaceId, userId, reference, model, true, 6));
        if (ids.isEmpty()) return new MyWorkRelationshipsDto(counts, List.of(), List.of());
        String workspaceIds = visibility.getForWorkspace(workspaceId).workspaceIdsJson();
        return new MyWorkRelationshipsDto(counts,
            personMapper.getByIds(workspaceId, workspaceIds, List.copyOf(ids)).stream().map(PersonDto::from).toList(),
            scoringService.temperatures(personMapper.getRelationshipScoreAggregatesByIds(
                workspaceId, workspaceIds, reference, model, List.copyOf(ids)), instant));
    }

    /** Reuses Analytics' calendar-window KPI service, binding ownership exclusively to the caller. */
    public MyWorkDealKpisDto dealKpis(String requestedCurrency) {
        MemberScope scope = personalScope();
        var metrics = dealService.queryDealMetrics(null, null, null, null, null, null, false, null, null, scope);
        String currency = metrics.byCurrency().stream()
            .filter(bucket -> bucket.currency().equals(requestedCurrency)).findFirst()
            .orElseGet(() -> metrics.byCurrency().stream()
                .max(Comparator.comparingLong((DealCurrencyMetricsDto bucket) -> bucket.openCount() + bucket.closedCount())
                    .thenComparing(DealCurrencyMetricsDto::currency, Comparator.reverseOrder()))
                .orElse(new DealCurrencyMetricsDto("USD", 0, 0, 0, 0, 0, 0, 0)))
            .currency();
        String timezone = workspaceService.getCurrentAnalyticsTimezone();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(timezone)));
        var window = AnalyticsPeriods.requiredWindow(today.minusDays(89).toString(), today.toString(), timezone, null);
        return new MyWorkDealKpisDto(currency, metrics,
            dealService.getDealKpis(currency, window, AnalyticsPeriods.periods(window, "week"), scope));
    }

    /** Keeps the risk service's interactive ceiling and refuses incomplete personal rankings. */
    public MyWorkRisksDto risks() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        var result = dealRiskService.assessPersonal(workspaceId, personalScope());
        if (result.inputLimitExceeded()) return new MyWorkRisksDto(List.of(), List.of(), true);
        var risks = result.assessments().stream().limit(6).toList();
        var ids = risks.stream().map(risk -> risk.getDealId()).toList();
        return new MyWorkRisksDto(risks, ids.isEmpty() ? List.of()
            : dealMapper.getByIds(workspaceId, ids).stream().map(DealDto::from).toList(), false);
    }

    private MemberScope personalScope() {
        return MemberScope.fromRequest("me", null, workspaceService.getCurrentUserId());
    }
}
