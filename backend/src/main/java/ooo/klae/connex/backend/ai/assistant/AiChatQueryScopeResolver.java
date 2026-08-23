package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatQueryScopeDto;
import ooo.klae.connex.backend.dto.AiChatQueryScopeRequest;
import ooo.klae.connex.backend.dto.AiChatScopeReferenceDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.services.SavedViewService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates and authorizes a caller-declared query scope into the exact interpretation the server
 * will execute.
 *
 * <p>Owners resolve through active workspace membership, stages through the workspace pipeline
 * catalog, and saved views through their own accessibility rules. A saved view is accepted only
 * when the server can apply every part of it that changes which records match: a view that also
 * carries a free-text query or column filters, or that carries no server-evaluable segment at all,
 * is refused with {@code saved_view_scope_unsupported} rather than partially applied, because a
 * scope chip that does not match the executed query is exactly the decorative honesty claim this
 * contract exists to eliminate. An accepted view also binds its own record type into the scope, so
 * the cohort the turn reads is the cohort the view describes.
 *
 * <p>Facets that only a deal cohort can honour — stages and deal statuses — are refused here when
 * the declared record kinds exclude deals, rather than being echoed to the caller and then dropped
 * by the retrieval.
 */
@Service
@RequiredArgsConstructor
public class AiChatQueryScopeResolver {
    private static final String SAVED_VIEW_SCOPE_UNSUPPORTED = AiChatSavedViewScope.UNSUPPORTED;
    private static final Set<String> RECORD_KINDS = AiChatCohortKind.KINDS;
    private static final Set<String> WARMTH_BANDS = Set.of("hot", "warm", "cool", "cold");
    private static final Set<String> DEAL_STATUSES = Set.of("open", "won", "lost");
    private static final int MAX_ACTIVITY_TYPE_CHARS = 32;

    private final WorkspaceService workspaceService;
    private final PipelineMapper pipelineMapper;
    private final SavedViewService savedViewService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** The resolved scope and the exact interpretation echoed to the caller. */
    public record Resolution(AiChatQueryScope scope, AiChatQueryScopeDto interpreted) {
    }

    /** Returns the empty resolution used when a turn declares no scope. */
    public Resolution none() {
        return new Resolution(AiChatQueryScope.none(), interpreted(
                AiChatQueryScope.none(), List.of(), null, List.of()));
    }

    /**
     * Resolves one declared scope under the current session.
     *
     * @param request caller-declared scope, or null
     * @return validated scope plus its exact interpreted echo
     */
    public Resolution resolve(AiChatQueryScopeRequest request) {
        if (request == null || isEmpty(request)) {
            return none();
        }
        List<String> unavailable = new ArrayList<>();
        LocalDate periodEnd = parseDate(request.periodEnd(), "periodEnd");
        LocalDate periodStart = parseDate(request.periodStart(), "periodStart");
        Integer periodDays = request.periodDays();
        if (periodStart != null || periodEnd != null) {
            LocalDate end = periodEnd == null ? today() : periodEnd;
            LocalDate start = periodStart == null
                    ? end.minusDays(AiChatScopeBounds.DEFAULT_PERIOD_DAYS - 1L)
                    : periodStart;
            if (start.isAfter(end)) {
                throw new BadRequestException("Assistant scope period start must precede its end");
            }
            long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
            if (days > AiChatScopeBounds.MAX_PERIOD_DAYS) {
                start = end.minusDays(AiChatScopeBounds.MAX_PERIOD_DAYS - 1L);
                days = AiChatScopeBounds.MAX_PERIOD_DAYS;
                unavailable.add("period_capped");
            }
            periodStart = start;
            periodEnd = end;
            periodDays = (int) days;
        } else if (periodDays != null) {
            periodEnd = today();
            periodStart = periodEnd.minusDays(periodDays - 1L);
        }

        List<User> owners = List.of();
        MemberScope memberScope = MemberScope.allTeam();
        String ownerMode = request.ownerMode() == null ? "all_team" : request.ownerMode();
        if ("me".equals(ownerMode)) {
            memberScope = MemberScope.fromRequest(
                    "me", null, workspaceService.getCurrentUserId());
        } else if ("members".equals(ownerMode)) {
            owners = authorizedMembers(request.ownerMemberIds());
            memberScope = MemberScope.fromRequest(
                    "members",
                    owners.stream().map(User::getId).toList(),
                    workspaceService.getCurrentUserId());
        }

        List<Stage> stages = authorizedStages(request.stageIds());
        SavedView savedView = request.savedViewId() == null
                ? null
                : authorizedSavedView(request.savedViewId());
        List<String> recordKinds = boundRecordKinds(
                normalized(request.recordKinds(), RECORD_KINDS, "recordKinds"), savedView);
        List<String> dealStatuses = normalized(
                request.dealStatuses(), DEAL_STATUSES, "dealStatuses");
        if ((!stages.isEmpty() || !dealStatuses.isEmpty())
                && !recordKinds.isEmpty() && !recordKinds.contains("deal")) {
            throw new BadRequestException(
                    "Assistant scope stage and status filters require a deal cohort: "
                            + AiChatCohortKind.STAGE_SCOPE_UNSUPPORTED);
        }

        AiChatQueryScope scope = new AiChatQueryScope(
                true,
                periodStart,
                periodEnd,
                periodDays,
                memberScope,
                normalized(request.warmthBands(), WARMTH_BANDS, "warmthBands"),
                recordKinds,
                stages.stream().map(Stage::getId).toList(),
                dealStatuses,
                activityTypes(request.activityTypes()),
                savedView == null ? null : savedView.getId());
        return new Resolution(
                scope,
                interpreted(scope, owners, savedView, unavailable));
    }

    /** Builds the exact interpreted echo for a resolved scope. */
    public AiChatQueryScopeDto interpreted(
            AiChatQueryScope scope,
            List<User> owners,
            SavedView savedView,
            List<String> unavailable) {
        return new AiChatQueryScopeDto(
                scope.declared(),
                scope.periodStart() == null ? null : scope.periodStart().toString(),
                scope.periodEnd() == null ? null : scope.periodEnd().toString(),
                scope.periodDays(),
                scope.memberScope().mode().name().toLowerCase(Locale.ROOT),
                owners.stream()
                        .map(owner -> new AiChatScopeReferenceDto(
                                owner.getId(), label(owner.getDisplayName())))
                        .toList(),
                scope.warmthBands(),
                scope.recordKinds(),
                stageReferences(scope.stageIds()),
                scope.dealStatuses(),
                scope.activityTypes(),
                savedView == null
                        ? null
                        : new AiChatScopeReferenceDto(
                                savedView.getId(), label(savedView.getName())),
                null,
                false,
                AiChatScopeBounds.MAX_COHORT_RECORDS,
                AiChatScopeBounds.MAX_ACTIVITY_ROWS,
                AiChatScopeBounds.MAX_ACTIVITY_ROWS_PER_RECORD,
                unavailable);
    }

    /**
     * Binds an accepted saved view's own record type into the scope's record kinds.
     *
     * <p>A view the server cannot execute in full, a view of a record type this contract does not
     * cover, and a view whose type contradicts the declared kinds are all refused. Without the
     * binding a person view could be paired with a derived company cohort and the retrieval would
     * find no applicable definition, leaving the cohort workspace-wide behind a chip still naming
     * the view.
     */
    private List<String> boundRecordKinds(List<String> declaredKinds, SavedView savedView) {
        if (savedView == null) {
            return declaredKinds;
        }
        String viewKind = savedView.getRecordType() == null ? "" : savedView.getRecordType();
        if (!RECORD_KINDS.contains(viewKind)
                || AiChatSavedViewScope.definition(objectMapper, savedView).isEmpty()
                || (!declaredKinds.isEmpty() && !declaredKinds.contains(viewKind))) {
            throw new BadRequestException(
                    "Saved view scope cannot be applied to an assistant query: "
                            + SAVED_VIEW_SCOPE_UNSUPPORTED);
        }
        return List.of(viewKind);
    }

    /**
     * Re-resolves the display labels of a stored interpretation under the reader's authorization.
     *
     * <p>Durable turn scope stores identifiers only, so an erased member never remains named in a
     * stored turn and a renamed saved view is never restated under its old name. A reference that no
     * longer resolves keeps its identifier and loses its label rather than dropping the whole scope.
     *
     * @param stored interpreted scope read back from durable state
     * @return the same interpretation with current labels
     */
    public AiChatQueryScopeDto relabel(AiChatQueryScopeDto stored) {
        if (stored == null) {
            return null;
        }
        return new AiChatQueryScopeDto(
                stored.declared(),
                stored.periodStart(),
                stored.periodEnd(),
                stored.periodDays(),
                stored.ownerMode(),
                relabelledOwners(stored.owners()),
                stored.warmthBands(),
                stored.recordKinds(),
                stageReferences(stored.stages().stream()
                        .map(AiChatScopeReferenceDto::id)
                        .toList()),
                stored.dealStatuses(),
                stored.activityTypes(),
                relabelledSavedView(stored.savedView()),
                stored.matchedRecordCount(),
                stored.matchedRecordCountTruncated(),
                stored.recordCap(),
                stored.activityCap(),
                stored.perRecordCap(),
                stored.unavailable());
    }

    private List<AiChatScopeReferenceDto> relabelledOwners(
            List<AiChatScopeReferenceDto> owners) {
        if (owners.isEmpty()) {
            return List.of();
        }
        Map<Integer, String> namesById = workspaceService
                .getMembers(workspaceService.getCurrentWorkspaceId()).stream()
                .filter(member -> member != null && member.getId() > 0)
                .collect(java.util.stream.Collectors.toMap(
                        User::getId,
                        member -> label(member.getDisplayName()),
                        (first, second) -> first));
        return owners.stream()
                .map(owner -> new AiChatScopeReferenceDto(
                        owner.id(), namesById.getOrDefault(owner.id(), "")))
                .toList();
    }

    private AiChatScopeReferenceDto relabelledSavedView(AiChatScopeReferenceDto savedView) {
        if (savedView == null) {
            return null;
        }
        try {
            return new AiChatScopeReferenceDto(
                    savedView.id(), label(savedViewService.getById(savedView.id()).getName()));
        } catch (ResourceNotFoundException | ForbiddenException exception) {
            // A view deleted, or no longer shared with this reader, keeps its identifier and loses
            // its name. Dropping the whole scope would erase a breadth statement the reader is
            // still entitled to, over a label they were never entitled to.
            return new AiChatScopeReferenceDto(savedView.id(), "");
        }
    }

    private List<User> authorizedMembers(List<Integer> memberIds) {
        if (memberIds.isEmpty()) {
            throw new BadRequestException(
                    "Assistant scope owner members require at least one member id");
        }
        List<User> members = membersById(memberIds);
        if (members.size() != new LinkedHashSet<>(memberIds).size()) {
            throw new BadRequestException(
                    "Assistant scope owners must be active members of this workspace");
        }
        return members;
    }

    private List<User> membersById(List<Integer> memberIds) {
        Set<Integer> requested = new LinkedHashSet<>(memberIds);
        Map<Integer, User> byId = workspaceService
                .getMembers(workspaceService.getCurrentWorkspaceId()).stream()
                .filter(member -> member != null && member.getId() > 0)
                .collect(java.util.stream.Collectors.toMap(
                        User::getId, Function.identity(), (first, second) -> first));
        return requested.stream()
                .filter(byId::containsKey)
                .map(byId::get)
                .toList();
    }

    private List<Stage> authorizedStages(List<Integer> stageIds) {
        if (stageIds.isEmpty()) {
            return List.of();
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Stage> stages = new ArrayList<>();
        for (Integer stageId : new LinkedHashSet<>(stageIds)) {
            Stage stage = pipelineMapper.getVisibleStageById(workspaceId, stageId);
            if (stage == null) {
                throw new BadRequestException(
                        "Assistant scope stages must exist in this workspace");
            }
            stages.add(stage);
        }
        return List.copyOf(stages);
    }

    private List<AiChatScopeReferenceDto> stageReferences(List<Integer> stageIds) {
        if (stageIds.isEmpty()) {
            return List.of();
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<AiChatScopeReferenceDto> references = new ArrayList<>();
        for (Integer stageId : stageIds) {
            Stage stage = pipelineMapper.getVisibleStageById(workspaceId, stageId);
            if (stage != null) {
                references.add(new AiChatScopeReferenceDto(
                        stage.getId(), label(stage.getName())));
            }
        }
        return List.copyOf(references);
    }

    private SavedView authorizedSavedView(int savedViewId) {
        try {
            return savedViewService.getById(savedViewId);
        } catch (ResourceNotFoundException exception) {
            throw new BadRequestException(
                    "Assistant scope saved view is not accessible in this workspace");
        }
    }

    private static List<String> normalized(
            List<String> values, Set<String> allowed, String field) {
        if (values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String candidate = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!allowed.contains(candidate)) {
                throw new BadRequestException("Assistant scope " + field + " value is unsupported");
            }
            normalized.add(candidate);
        }
        return List.copyOf(normalized);
    }

    private static List<String> activityTypes(List<String> values) {
        if (values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String candidate = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (candidate.isEmpty() || candidate.length() > MAX_ACTIVITY_TYPE_CHARS) {
                throw new BadRequestException("Assistant scope activity type is unsupported");
            }
            normalized.add(candidate);
        }
        return List.copyOf(normalized);
    }

    private static boolean isEmpty(AiChatQueryScopeRequest request) {
        return request.periodStart() == null && request.periodEnd() == null
                && request.periodDays() == null
                && (request.ownerMode() == null || "all_team".equals(request.ownerMode()))
                && request.ownerMemberIds().isEmpty()
                && request.warmthBands().isEmpty()
                && request.recordKinds().isEmpty()
                && request.stageIds().isEmpty()
                && request.dealStatuses().isEmpty()
                && request.activityTypes().isEmpty()
                && request.savedViewId() == null;
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new BadRequestException("Assistant scope " + field + " is not an ISO-8601 date");
        }
    }

    private static String label(String value) {
        return value == null ? "" : value;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
