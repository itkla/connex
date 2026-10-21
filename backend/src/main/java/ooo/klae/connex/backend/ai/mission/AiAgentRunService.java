package ooo.klae.connex.backend.ai.mission;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard;
import ooo.klae.connex.backend.ai.assistant.AiAssistantSessionReadAudit;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolExecutor;
import ooo.klae.connex.backend.ai.assistant.AiChatCitationProjector;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.dto.AiAgentOutputDto;
import ooo.klae.connex.backend.dto.AiAgentRunControlRequest;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.dto.AiAgentRunDto;
import ooo.klae.connex.backend.dto.AiAgentRunPage;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.exceptions.AiAgentUnavailableException;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.tenant.WorkspaceRead;
import tools.jackson.databind.ObjectMapper;

/** Owner-only mission API orchestration; immutable scope and privacy are independent access checks. */
@Service
@RequiredArgsConstructor
public class AiAgentRunService {
    private static final Set<String> STATES = Set.of("queued", "running", "waiting_approval", "paused", "done", "failed", "cancelled");
    private final AiChatPrivacyGuard privacyGuard;
    private final AiAssistantSessionReadAudit readAudit;
    private final PlatformTransactionManager transactionManager;
    private final AiAgentRunMapper runs;
    private final AiChatMapper chat;
    private final AiAgentRunPersistenceService persistence;
    private final AiAgentCapabilityGate gate;
    private final AiAgentRealtimePublisher publisher;
    private final AiAssistantToolExecutor tools;
    private final AiChatCitationProjector citations;
    private final AiRestrictionEpoch restriction;
    private final WorkspaceService workspaces;
    private final ObjectMapper json;
    private final Clock clock;
    private final TenantWorkScope tenantWorkScope;
    @org.springframework.beans.factory.annotation.Autowired
    private AiAgentProposalReadService proposalReads;
    @org.springframework.beans.factory.annotation.Autowired
    private AiAgentProposalBudgetService proposalBudget;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<AiAgentRunService> self;

    /** Creates or replays the same immutable request without refreshing its lifetime. */
    @RequirePermission(Permission.AI_USE)
    public AiAgentRunDto create(int sessionId, AiAgentRunCreateRequest request) {
        int workspaceId = workspaces.getCurrentWorkspaceId();
        int owner = workspaces.getCurrentUserId();
        requireMembership(workspaceId, owner);
        String locale = "ja".equals(LocaleContextHolder.getLocale().getLanguage()) ? "ja" : "en";
        byte[] hash = AiAgentCapabilityGate.hash(json.writeValueAsString(List.of(
                1, request.missionType(), request.goal(), locale, request.scope(), request.limits(), sessionId)));
        AiAgentRun existing = runs.findByRequestKey(workspaceId, owner, request.clientRequestKey());
        if (existing != null) {
            requireOwner(existing);
            if (existing.getPurgedAt() != null) {
                throw new AiAgentGoneException("mission_purged");
            }
            if (!Arrays.equals(existing.getRequestHash(), hash)) {
                throw new ConflictException("mission_request_conflict");
            }
            return self.getObject().get(existing.getId());
        }
        if ("record_preparation_v1".equals(request.missionType())) {
            proposalBudget.requireFeasibleCreation(json.writeValueAsBytes(request.goal()).length,
                    request.limits().maxTokens());
        } else {
            AiAgentBudgetService.requireFeasibleCreation(json.writeValueAsBytes(request.goal()).length,
                    request.limits().maxTokens());
        }
        gate.requireReady();
        requirePrivacyReady();
        if (ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen.screen(request.goal()).excluded()) {
            throw new BadRequestException("mission_goal_refused");
        }
        requireScope(request.scope());
        long epoch = restriction.current(workspaceId);
        AiAgentRun run = persistence.create(sessionId, request, locale, hash,
                json.writeValueAsString(request.scope()), epoch);
        publisher.publish(run, "mission_created");
        return self.getObject().get(run.getId());
    }

    /** Returns only metadata when privacy has permanently or temporarily withheld the content. */
    @RequirePermission(Permission.AI_USE)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiAgentRunDto get(int id) {
        return readTransaction(() -> {
            readAudit.retainTranscriptAuditParents(workspaces.getCurrentWorkspaceId(), workspaces.getCurrentUserId());
            AiAgentRun run = ownedForRead(id);
            privacyGuard.retainCurrentOrganization(run.getWorkspaceId());
            requireBarrier(run.getWorkspaceId());
            return project(run);
        });
    }

    /** Lists independently of transcript/tool limits with an owner/filter-bound keyset cursor. */
    @RequirePermission(Permission.AI_USE)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiAgentRunPage list(int limit, Integer sessionId, String state, String cursor) {
        if (limit < 1 || limit > 50 || (sessionId != null && sessionId <= 0)
                || (state != null && !STATES.contains(state))) {
            throw new BadRequestException("Invalid mission page");
        }
        int workspaceId = workspaces.getCurrentWorkspaceId();
        int owner = workspaces.getCurrentUserId();
        requireMembership(workspaceId, owner);
        Integer before = null;
        if (cursor != null) {
            try {
                if (cursor.length() > 1024) {
                    throw new IllegalArgumentException("Cursor exceeds bound");
                }
                Cursor parsed = json.readValue(Base64.getUrlDecoder().decode(cursor), Cursor.class);
                if (parsed.version() != 1 || parsed.workspaceId() != workspaceId || parsed.owner() != owner
                        || !Objects.equals(parsed.sessionId(), sessionId) || !Objects.equals(parsed.state(), state)
                        || parsed.lastId() <= 0) {
                    throw new IllegalArgumentException("Cursor binding changed");
                }
                before = parsed.lastId();
            } catch (RuntimeException exception) {
                throw new BadRequestException("Invalid mission cursor");
            }
        }
        List<AiAgentRun> found = runs.listRuns(workspaceId, owner, sessionId, state, before, limit + 1, privacyGuard.currentGeneration(workspaceId));
        boolean more = found.size() > limit;
        List<AiAgentRun> page = more ? found.subList(0, limit) : found;
        List<AiAgentRunDto> items = new ArrayList<>();
        for (AiAgentRun candidate : page) {
            if (!sessionAccessible(candidate, owner)) {
                continue;
            }
            try {
                AiAgentRunDto item = self.getObject().get(candidate.getId());
                if (state == null || state.equals(item.state())) {
                    items.add(item);
                }
            } catch (ResourceNotFoundException inaccessible) {
                continue;
            }
        }
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsString(
                new Cursor(1, workspaceId, owner, sessionId, state, page.getLast().getId())).getBytes(StandardCharsets.UTF_8)) : null;
        return new AiAgentRunPage(List.copyOf(items), next);
    }

    /** Reads the canonical authorized report; a stopped or missing generation cannot disclose it. */
    @RequirePermission(Permission.AI_USE)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiAgentOutputDto output(int id) {
        AiAgentRun run = owned(id);
        if (withheld(run)) {
            throw new AiAgentGoneException("mission_output_withheld");
        }
        if (AiAgentMissionCatalog.proposals(run)) {
            return proposalReads.output(id);
        }
        return readProjection(() -> outputCurrent(id));
    }

    private AiAgentOutputDto outputCurrent(int id) {
        AiAgentRun run = owned(id);
        if (withheld(run)) {
            throw new AiAgentGoneException("mission_output_withheld");
        }
        if (run.getReportMessageId() == null) {
            throw new ConflictException("mission_output_not_ready");
        }
        var report = chat.getMissionMessage(run.getWorkspaceId(), id, "report");
        if (report == null || report.getId() != run.getReportMessageId()) {
            throw new ConflictException("mission_output_not_ready");
        }
        var projection = citations.project(run.getWorkspaceId(), List.of(report));
        if (projection.withheldMessageIds().contains(report.getId()) || withheld(run)) {
            throw new AiAgentGoneException("mission_output_withheld");
        }
        return new AiAgentOutputDto(1, id, "1", new AiAgentOutputDto.Report(report.getId(), report.getContent(),
                projection.citationsByMessage().getOrDefault(report.getId(), List.of()),
                new AiAgentOutputDto.Coverage(1, 0, 0, 0), instant(run.getFinishedAt())),
                new AiAgentOutputDto.ProposalSet(id, "0", true, null, List.of(), new AiAgentOutputDto.Counts(0, 0, 0, 0)), List.of());
    }

    /** Performs a versioned owner control without granting another tool or larger allowance. */
    @RequirePermission(Permission.AI_USE)
    public AiAgentRunDto control(int id, AiAgentRunControlRequest request, String action) {
        AiAgentRun run = owned(id);
        if ("waiting_approval".equals(run.getStatus()) && !"cancel".equals(action)) {
            throw new ConflictException("mission_state_conflict");
        }
        if ("resume".equals(action)) {
            gate.requireReady();
            requirePrivacyReady();
            if (withheld(run)) {
                throw new AiAgentGoneException("mission_output_withheld");
            }
            requireScope(json.readValue(run.getScopeJson(), AiAgentRunCreateRequest.Scope.class));
        }
        long version;
        try {
            version = Long.parseLong(request.expectedStateVersion());
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Invalid mission version");
        }
        AiAgentRun changed = persistence.control(run, version, action);
        publisher.publish(changed, changed.getReportMessageId() == null ? "mission_state" : "mission_output");
        return self.getObject().get(changed.getId());
    }

    /** Missing or blocked privacy state refuses admission before any attempt is reserved. */
    public void requirePrivacyReady() {
        var barrier = chat.getPrivacyBarrier(workspaces.getCurrentWorkspaceId());
        if (barrier == null || barrier.isBlocked() || barrier.getGeneration() <= 0) {
            throw new AiAgentUnavailableException();
        }
    }

    /** Revalidates the one selected record through existing processability and RBAC reads. */
    public void requireScope(AiAgentRunCreateRequest.Scope scope) {
        AiChatResourceRegistry registry = new AiChatResourceRegistry();
        new AiAgentScopePolicy(scope).evaluateRead(() -> tools.pageContext(
                List.of(new AiChatPageContextDto(scope.recordKind(), scope.recordId())), registry));
        if (registry.handleFor(scope.recordKind(), scope.recordId()).isEmpty()) {
            throw new ResourceNotFoundException("Mission scope inaccessible");
        }
    }

    private AiAgentRun ownedForRead(int id) {
        AiAgentRun discovered = owned(id);
        int workspaceId = discovered.getWorkspaceId();
        int owner = workspaces.getCurrentUserId();
        workspaces.lockAndRequirePermissionsSnapshot(workspaceId, Map.of(owner, Set.of(Permission.AI_USE)));
        if (discovered.getSessionId() != null && chat.getMissionSessionForShare(workspaceId, owner, discovered.getSessionId()) == null) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
        AiAgentRun run = runs.lockRunForShare(workspaceId, id);
        requireOwner(run);
        return run;
    }

    private <T> T readTransaction(WorkspaceRead<T> projection) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tenantWorkScope.inWorkspaceRead(workspaces.getCurrentWorkspaceId(),
                () -> transaction.execute(status -> projection.read()));
    }

    private <T> T readProjection(WorkspaceRead<T> projection) {
        return readTransaction(() -> {
            int workspaceId = workspaces.getCurrentWorkspaceId();
            int userId = workspaces.getCurrentUserId();
            requireMembership(workspaceId, userId);
            readAudit.retainTranscriptAuditParents(workspaceId, userId);
            privacyGuard.retainCurrentOrganization(workspaceId);
            requireBarrier(workspaceId);
            return projection.read();
        });
    }

    private void requireBarrier(int workspaceId) {
        if (chat.lockPrivacyBarrierForShare(workspaceId) == null) {
            throw new AiAgentUnavailableException();
        }
    }

    private AiAgentRun owned(int id) {
        int workspaceId = workspaces.getCurrentWorkspaceId();
        requireMembership(workspaceId, workspaces.getCurrentUserId());
        AiAgentRun run = runs.getRun(workspaceId, id);
        requireOwner(run);
        return run;
    }

    private void requireOwner(AiAgentRun run) {
        int owner = workspaces.getCurrentUserId();
        if (run == null || !Objects.equals(run.getOwnerUserId(), owner)
                || !sessionAccessible(run, owner)) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
    }

    private boolean sessionAccessible(AiAgentRun run, int owner) {
        if (run.getSessionId() == null) {
            return true;
        }
        var session = chat.getAccessibleSessionById(run.getWorkspaceId(), owner, run.getSessionId(), privacyGuard.currentGeneration(run.getWorkspaceId()));
        return session != null && session.getCreatedByUserId() != null
                && workspaces.isMember(run.getWorkspaceId(), session.getCreatedByUserId());
    }

    private void requireMembership(int workspaceId, int owner) {
        if (!workspaces.isMember(workspaceId, owner)) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
    }

    private boolean withheld(AiAgentRun run) {
        var barrier = chat.getPrivacyBarrier(run.getWorkspaceId());
        return run.getOrganizationPrivacyGeneration() != privacyGuard.currentGeneration(run.getWorkspaceId())
                || !supportedContent(run)
                || run.getWithheldAt() != null || run.getPurgedAt() != null || barrier == null
                || barrier.isBlocked() || barrier.getGeneration() != run.getPrivacyGeneration();
    }

    private boolean supportedContent(AiAgentRun run) {
        try {
            var codec = new AiAgentContinuationCodec();
            var snapshot = codec.decode(run);
            if (snapshot != null) {
                codec.validateExecutable(run, snapshot);
            }
            return true;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }

    private AiAgentRunDto project(AiAgentRun run) {
        boolean hidden = withheld(run);
        AiAgentOutputDto.Counts proposalCounts = null;
        if (!hidden && AiAgentMissionCatalog.proposals(run)) {
            var metadata = proposalReads.metadata(run);
            hidden = metadata.contentWithheld();
            proposalCounts = metadata.counts();
        }
        boolean output = !hidden && run.getReportMessageId() != null;
        List<String> actions = new ArrayList<>();
        if (!hidden && run.getSessionId() != null && ("running".equals(run.getStatus()) || "queued".equals(run.getStatus())
                || ("paused".equals(run.getStatus()) && !"member".equals(run.getReasonCode())))) {
            actions.add("pause");
        }
        if (!hidden && "paused".equals(run.getStatus()) && instant(run.getExecutionDeadlineAt()).isAfter(clock.instant())
                && run.getConsumedSteps() < run.getMaxSteps() && run.getConsumedTokens() < run.getMaxTokens()
                && persistence.attemptsForNextSegment(run) < 3) {
            actions.add("resume");
        }
        if (!Set.of("done", "failed", "cancelled").contains(run.getStatus()) && run.getSessionId() != null) {
            actions.add("cancel");
        }
        if (output) {
            actions.add("view_output");
        }
        AiAgentRunDto.Progress progress = hidden ? null : new AiAgentRunDto.Progress(
                "queued".equals(run.getStatus()) ? "queued" : "waiting_approval".equals(run.getStatus()) ? "waiting_review"
                        : output ? "writing_report" : AiAgentMissionCatalog.proposals(run) ? "preparing_changes" : "reading", output ? 1 : 0, 1);
        return new AiAgentRunDto(1, run.getId(), run.getSessionId(), run.getMissionType(), run.getDefinitionVersion(),
                run.getStatus(), run.getReasonCode(), Long.toString(run.getStateVersion()), hidden ? null : run.getGoal(),
                hidden ? null : json.readValue(run.getScopeJson(), AiAgentRunCreateRequest.Scope.class),
                instant(run.getCreatedAt()), instant(run.getExecutionDeadlineAt()), instant(run.getHardDeadlineAt()),
                instant(run.getStartedAt()), instant(run.getFinishedAt()), output, hidden, List.copyOf(actions), progress,
                hidden ? null : Long.toString(run.getProposalMembershipRevision()), hidden ? null : instant(run.getProposalFrozenAt()),
                hidden ? null : instant(run.getProposalFirstPreparedAt()), hidden ? null : instant(run.getReviewExpiresAt()),
                proposalCounts, output ? AiAgentMissionCatalog.proposals(run) ? Long.toString(run.getStateVersion()) : "1" : null);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private record Cursor(int version, int workspaceId, int owner, Integer sessionId, String state, int lastId) {}
}
