package ooo.klae.connex.backend.ai.assistant;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.ai.AiGenerationTerminalListener;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.dto.AiChatQueryScopeDto;
import ooo.klae.connex.backend.dto.AiChatScopePreviewDto;
import ooo.klae.connex.backend.dto.AiChatScopePreviewRequest;
import ooo.klae.connex.backend.dto.AiChatTurnAcceptedDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnDto;
import ooo.klae.connex.backend.dto.AiGenerationStatusDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import tools.jackson.databind.ObjectMapper;

/** Request-thread coordinator for durable turn preparation and bounded generation admission. */
@Service
public class AiAssistantTurnService {
    private static final Logger LOG = LoggerFactory.getLogger(AiAssistantTurnService.class);
    private static final Set<String> PAGE_CONTEXT_KINDS = Set.of("person", "company", "deal");

    private final WorkspaceService workspaceService;
    private final AiFeatureGate featureGate;
    private final AiRestrictionEpoch restrictionEpoch;
    private final AiChatTurnPersistenceService persistenceService;
    private final AiGenerationService generationService;
    private final AiChatAgentLoopService agentLoopService;
    private final AiChatTurnTerminalCoordinator terminalCoordinator;
    private final AiInvocationService invocationService;
    private final AiChatProgressService progressService;
    private final AiChatQueryScopeResolver scopeResolver;
    private final AiSkillRouter skillRouter;
    private final AiAssistantScopeReadService scopeReadService;
    private final AiChatScopePreviewRateLimiter scopePreviewRateLimiter;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiAssistantTurnService(
            WorkspaceService workspaceService,
            AiFeatureGate featureGate,
            AiRestrictionEpoch restrictionEpoch,
            AiChatTurnPersistenceService persistenceService,
            AiGenerationService generationService,
            AiChatAgentLoopService agentLoopService,
            AiChatTurnTerminalCoordinator terminalCoordinator,
            AiInvocationService invocationService,
            AiChatProgressService progressService,
            AiChatQueryScopeResolver scopeResolver,
            AiSkillRouter skillRouter,
            AiAssistantScopeReadService scopeReadService,
            AiChatScopePreviewRateLimiter scopePreviewRateLimiter,
            ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.featureGate = featureGate;
        this.restrictionEpoch = restrictionEpoch;
        this.persistenceService = persistenceService;
        this.generationService = generationService;
        this.agentLoopService = agentLoopService;
        this.terminalCoordinator = terminalCoordinator;
        this.invocationService = invocationService;
        this.progressService = progressService;
        this.scopeResolver = scopeResolver;
        this.skillRouter = skillRouter;
        this.scopeReadService = scopeReadService;
        this.scopePreviewRateLimiter = scopePreviewRateLimiter;
        this.objectMapper = objectMapper;
    }

    /** Creates the legacy buffered coordinator for isolated tests. */
    public AiAssistantTurnService(
            WorkspaceService workspaceService,
            AiFeatureGate featureGate,
            AiRestrictionEpoch restrictionEpoch,
            AiChatTurnPersistenceService persistenceService,
            AiGenerationService generationService,
            AiChatAgentLoopService agentLoopService,
            AiChatTurnTerminalCoordinator terminalCoordinator) {
        this(workspaceService, featureGate, restrictionEpoch, persistenceService,
                generationService, agentLoopService, terminalCoordinator,
                null, null, null, null, null, null, null);
    }

    /** Starts one whole assistant turn after committing its durable queued state. */
    @RequirePermission(Permission.AI_USE)
    public AiChatTurnAcceptedDto start(int sessionId, AiChatTurnCreateRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()
                || request.content().length() > 16_000 || request.pageContext().size() > 10
                || request.pageContext().stream().anyMatch(context -> context == null
                        || context.id() <= 0
                        || context.kind() == null
                        || !PAGE_CONTEXT_KINDS.contains(context.kind()))) {
            throw new BadRequestException("Assistant turn request is invalid");
        }
        featureGate.requireAiUsable(AiFeature.ASSISTANT_CHAT);
        AiPrivacyMode privacyMode = featureGate.privacyModeIfUsable(AiFeature.ASSISTANT_CHAT);
        if (privacyMode == null) {
            privacyMode = AiPrivacyMode.MASKED;
        }
        boolean streamed = streamingAvailable();
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        long expectedRestrictionEpoch = restrictionEpoch.current(workspaceId);
        AiChatQueryScopeResolver.Resolution resolution = scopeResolver == null
                ? null
                : scopeResolver.resolve(request.scope());
        AiChatQueuedTurn turn = invocationService == null
                ? persistenceService.queue(sessionId, request, expectedRestrictionEpoch)
                : persistenceService.queue(
                        sessionId, request, expectedRestrictionEpoch, privacyMode, streamed,
                        resolution == null ? AiChatQueryScope.none() : resolution.scope(),
                        declaredScopeJson(resolution));
        AiGenerationTerminalListener terminalListener = terminalCoordinator.listener(turn);
        AiGenerationStatusDto generation;
        try {
            generation = generationService.startAtRestrictionEpoch(
                    AiFeature.ASSISTANT_CHAT,
                    new TurnIdentity(turn.turnId()),
                    Set.of(Permission.AI_USE),
                    new AiChatTurnGenerationResult(turn.turnId(), "unavailable"),
                    () -> agentLoopService.run(turn),
                    expectedRestrictionEpoch,
                    terminalListener);
        } catch (TooManyRequestsException exception) {
            terminalCoordinator.generationCapacity(turn);
            throw exception;
        } catch (ConflictException exception) {
            terminalCoordinator.restrictionsChanged(turn);
            throw exception;
        } catch (RuntimeException exception) {
            terminalListener.onTerminal(
                    AiGenerationTaskResult.Outcome.FAILED,
                    "internal_error");
            throw exception;
        }
        if (!"accepted".equals(generation.status()) && !"running".equals(generation.status())) {
            terminalListener.onTerminal(
                    AiGenerationTaskResult.Outcome.FAILED,
                    "access_revoked");
            throw new ForbiddenException("Requires the AI_USE permission in this workspace");
        }
        return new AiChatTurnAcceptedDto(
                turn.turnId(), turn.sessionId(), generation.handle(), generation.status(),
                resolution == null || !resolution.scope().declared()
                        ? null
                        : resolution.interpreted());
    }

    /**
     * Evaluates one declared scope without starting a turn, so a broad request can state its real
     * breadth before it runs.
     *
     * <p>The preview performs only the cohort resolution the turn itself would perform, through the
     * same shared rule and the same refusals, and returns the same caps the retrieval will apply, so
     * confirming the sentence confirms the query. A scope the retrieval would decline is refused
     * here rather than counted and then declined after the member has confirmed it.
     *
     * @param request the request the member is about to send and its declared scope
     * @return interpreted scope, evaluated cohort size, and the skill that would run
     */
    @RequirePermission(Permission.AI_USE)
    public AiChatScopePreviewDto previewScope(AiChatScopePreviewRequest request) {
        if (request == null) {
            throw new BadRequestException("Assistant scope preview request is invalid");
        }
        if (request.pageContext().stream().anyMatch(context -> context == null
                || context.id() <= 0
                || context.kind() == null
                || !PAGE_CONTEXT_KINDS.contains(context.kind()))) {
            throw new BadRequestException("Assistant scope preview request is invalid");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        scopePreviewRateLimiter.acquire(workspaceId, userId);
        featureGate.requireAiUsable(AiFeature.ASSISTANT_CHAT);
        AiChatQueryScopeResolver.Resolution resolution = scopeResolver.resolve(request.scope());
        AiSkillRouter.Routing routing = skillRouter.route(
                workspaceId, userId, request.content(),
                request.pageContext(), resolution.scope());
        AiAssistantScopeReadService.Cohort cohort = previewCohort(routing, resolution);
        AiChatQueryScopeDto interpreted = resolution.interpreted()
                .withMatchedRecords(cohort.matchedCount(), cohort.truncated());
        boolean confirmation = routing.previewRecommended()
                || cohort.matchedCount() >= AiChatScopeBounds.SCOPE_PREVIEW_RECORD_THRESHOLD;
        return new AiChatScopePreviewDto(
                interpreted,
                routing.routed() ? routing.skill().key() : null,
                routing.routed() ? routing.skill().version() : null,
                confirmation);
    }

    /**
     * Evaluates the cohort the routed turn will read, refusing what its retrieval would refuse.
     *
     * <p>A pipeline attention review reads open deals through the deterministic risk model rather
     * than the warmth cohort, so a preview of that turn must count the same open-deal set the review
     * reads instead of a cohort the review would never touch.
     */
    private AiAssistantScopeReadService.Cohort previewCohort(
            AiSkillRouter.Routing routing, AiChatQueryScopeResolver.Resolution resolution) {
        boolean dealAttentionPlan = routing.routed()
                && routing.skill().plan().stream()
                        .anyMatch(step -> step.kind()
                                == AiSkillCatalog.PlanStepKind.DEAL_ATTENTION);
        String contextKind = routing.subject() == null ? null : routing.subject().kind();
        try {
            return scopeReadService.previewCohort(
                    resolution.scope(), contextKind, dealAttentionPlan);
        } catch (AiAssistantLoopException exception) {
            throw new BadRequestException(
                    "Assistant scope cannot be executed as declared: "
                            + exception.detailReason());
        }
    }

    private String declaredScopeJson(AiChatQueryScopeResolver.Resolution resolution) {
        if (resolution == null || !resolution.scope().declared() || objectMapper == null) {
            return null;
        }
        return objectMapper.writeValueAsString(resolution.interpreted().withoutLabels());
    }

    /** Returns one authorized durable turn after applying lazy expiry. */
    @RequirePermission(Permission.AI_USE)
    public AiChatTurnDto get(int sessionId, int turnId) {
        var turn = persistenceService.readTurn(sessionId, turnId);
        return AiChatTurnDto.from(
                turn,
                progressService == null ? java.util.List.of() : progressService.project(turn),
                workspaceService.getCurrentUserId(),
                storedScope(turn.getScopeJson()));
    }

    /**
     * Reads back the interpreted scope exactly as it was committed, with current display labels.
     *
     * <p>A stored echo that no longer parses is dropped rather than partially restated, because a
     * half-restated scope claim is worse than none — but the drop is logged, since a scope that
     * silently stops appearing looks identical to a turn that never declared one.
     */
    private AiChatQueryScopeDto storedScope(String scopeJson) {
        if (scopeJson == null || scopeJson.isBlank() || objectMapper == null
                || scopeResolver == null) {
            return null;
        }
        try {
            return scopeResolver.relabel(
                    objectMapper.readValue(scopeJson, AiChatQueryScopeDto.class));
        } catch (RuntimeException exception) {
            LOG.warn("Stored assistant turn scope could not be restated", exception);
            return null;
        }
    }

    /** Cancels one active turn without requiring AI readiness to remain enabled. */
    public void cancel(int sessionId, int turnId) {
        persistenceService.cancel(sessionId, turnId);
    }

    private record TurnIdentity(int turnId) {
    }
    /**
     * Whether this turn can stream, answering only what the provider supports.
     *
     * <p>Deliberately fail-soft: streaming is an enhancement, and asking whether we can stream
     * must never change what a caller is told about a session they may not reach. The probe
     * resolves the organization's provider, which can refuse for reasons of its own, and it runs
     * before the session authorization that owes the caller a generic not-found — so a refusal
     * here means "do not stream", never "fail the turn with a different error".
     */
    private boolean streamingAvailable() {
        if (invocationService == null) {
            return false;
        }
        try {
            AiProviderCapabilities capabilities =
                    invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT);
            return capabilities != null && capabilities.streaming();
        } catch (RuntimeException exception) {
            return false;
        }
    }

}
