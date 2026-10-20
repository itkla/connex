package ooo.klae.connex.backend.ai.mission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCallReadService;
import ooo.klae.connex.backend.ai.assistant.AiChatCitationProjector;
import ooo.klae.connex.backend.beans.AiAgentAction;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.dto.AiAgentOutputDto;
import ooo.klae.connex.backend.dto.AiAgentProposalDecisionRequest;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiAgentActionMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import tools.jackson.databind.ObjectMapper;

/** Requester-only content projections retain ordered authorization, session, run and privacy roots. */
@Service
@RequiredArgsConstructor
public class AiAgentProposalReadService {
    private final ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard privacyGuard;
    private final WorkspaceService workspaces;
    private final AiChatMapper chat;
    private final AiAgentRunMapper runs;
    private final AiAgentActionMapper actions;
    private final AiAgentProposalSetPort proposals;
    private final AiAssistantToolCallReadService cards;
    private final AiChatCitationProjector citations;
    private final ooo.klae.connex.backend.ai.assistant.AiAssistantToolExecutor tools;
    private final ObjectMapper json;
    private final Clock clock;

    /** Exact content authorization deliberately leaves metadata and safe-stop authorization separate. */
    public record Authorized(AiAgentRun run, AiChatSession session) {}

    /** Content availability and counts for an independently authorized metadata projection. */
    public record Metadata(boolean contentWithheld, AiAgentOutputDto.Counts counts) {}

    /** Expected content refusals settle inside this participant without poisoning metadata reads. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Metadata metadata(AiAgentRun run) {
        try {
            requireContent(run, true);
            if (run.getReportMessageId() == null) {
                return new Metadata(false, null);
            }
            var session = chat.getAccessibleSessionById(run.getWorkspaceId(), workspaces.getCurrentUserId(), run.getSessionId(), run.getOrganizationPrivacyGeneration());
            if (session == null) {
                throw inaccessible();
            }
            return new Metadata(false, output(new Authorized(run, session)).proposalSet().derivedCounts());
        } catch (AiAgentGoneException | ConflictException | ResourceNotFoundException
                | ooo.klae.connex.backend.exceptions.ForbiddenException
                | ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException
                | IllegalArgumentException | IllegalStateException exception) {
            return new Metadata(true, null);
        }
    }

    /** Locks content read roots and refuses unsupported or withheld content before materialization. */
    public Authorized authorize(int id) {
        return authorize(id, true);
    }

    private Authorized authorize(int id, boolean requireSource) {
        int workspaceId = workspaces.getCurrentWorkspaceId();
        int owner = workspaces.getCurrentUserId();
        AiAgentRun discovered = runs.getRun(workspaceId, id);
        if (discovered == null || !Objects.equals(discovered.getOwnerUserId(), owner) || discovered.getSessionId() == null) {
            throw inaccessible();
        }
        workspaces.lockAndRequirePermissionsSnapshot(workspaceId, Map.of(owner, Set.of(Permission.AI_USE)));
        AiChatSession session = chat.getMissionSessionForShare(workspaceId, owner, discovered.getSessionId());
        AiAgentRun run = runs.lockRunForShare(workspaceId, id);
        if (session == null || run == null || !Objects.equals(run.getSessionId(), session.getId())
                || !Objects.equals(run.getOwnerUserId(), owner) || session.getCreatedByUserId() == null
                || !workspaces.isMember(workspaceId, session.getCreatedByUserId())
                || !"active".equals(session.getStatus())
                || session.getOrganizationPrivacyGeneration() != run.getOrganizationPrivacyGeneration()
                || (!Objects.equals(session.getCreatedByUserId(), owner)
                    && (!"shared".equals(session.getVisibility()) || !chat.isParticipant(workspaceId, session.getId(), owner)))) {
            throw inaccessible();
        }
        requireContent(run, requireSource);
        return new Authorized(run, session);
    }

    private void requireContent(AiAgentRun run, boolean requireSource) {
        try {
            privacyGuard.retainOrganization(run.getWorkspaceId(), run.getOrganizationPrivacyGeneration());
        } catch (ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException stale) {
            throw new AiAgentGoneException("mission_output_withheld");
        }
        var barrier = chat.lockPrivacyBarrierForShare(run.getWorkspaceId());
        if (barrier == null || barrier.isBlocked() || barrier.getGeneration() != run.getPrivacyGeneration()
                || run.getWithheldAt() != null || run.getPurgedAt() != null) {
            throw new AiAgentGoneException("mission_output_withheld");
        }
        try {
            var codec = new AiAgentContinuationCodec();
            var snapshot = codec.decode(run);
            if (snapshot != null) {
                codec.validateExecutable(run, snapshot);
                if (AiAgentMissionCatalog.proposals(run)) {
                    proposals.validateInventory(run, snapshot);
                }
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new AiAgentGoneException("mission_output_withheld");
        }
        if (requireSource) {
            var scope = json.readValue(run.getScopeJson(), ooo.klae.connex.backend.dto.AiAgentRunCreateRequest.Scope.class);
            var resources = new ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry();
            new AiAgentScopePolicy(scope).evaluate(() -> tools.pageContext(List.of(
                    new ooo.klae.connex.backend.dto.AiChatPageContextDto(scope.recordKind(), scope.recordId())), resources));
            if (resources.handleFor(scope.recordKind(), scope.recordId()).isEmpty()) {
                throw inaccessible();
            }
        }
    }

    /** Rechecks current disclosure authority without depending on content/card projection infrastructure. */
    @RequirePermission(Permission.AI_USE)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String currentVersion(int id) {
        return Long.toString(authorize(id, true).run().getStateVersion());
    }

    /** Reads exact output independently of every transcript and tool-history bound. */
    @RequirePermission(Permission.AI_USE)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AiAgentOutputDto output(int id) {
        return output(authorize(id));
    }

    private AiAgentOutputDto output(Authorized authorized) {
        AiAgentRun run = authorized.run();
        int id = run.getId();
        if (run.getReportMessageId() == null) {
            throw new ConflictException("mission_output_not_ready");
        }
        var report = chat.getMissionMessage(run.getWorkspaceId(), id, "report");
        if (report == null || report.getId() != run.getReportMessageId()) {
            throw new ConflictException("mission_output_not_ready");
        }
        var projection = citations.project(run.getWorkspaceId(), List.of(report));
        if (projection.withheldMessageIds().contains(report.getId())) {
            throw new AiAgentGoneException("mission_output_withheld");
        }
        var items = new ArrayList<AiAgentOutputDto.ProposalItem>();
        if (AiAgentMissionCatalog.proposals(run)) {
            if (run.getProposalFrozenAt() == null) {
                throw new ConflictException("mission_output_not_ready");
            }
            for (AiAgentAction action : frozen(run)) {
                items.add(project(authorized, action));
            }
        }
        var counts = counts(items);
        return new AiAgentOutputDto(1, id, AiAgentMissionCatalog.proposals(run) ? Long.toString(run.getStateVersion()) : "1",
                new AiAgentOutputDto.Report(report.getId(), report.getContent(),
                        projection.citationsByMessage().getOrDefault(report.getId(), List.of()),
                        new AiAgentOutputDto.Coverage(1, 0, 0, 0), timestamp(report.getCreatedAt())),
                new AiAgentOutputDto.ProposalSet(id, Long.toString(run.getProposalMembershipRevision()), true,
                        instant(run.getProposalFrozenAt()), instant(run.getProposalFirstPreparedAt()),
                        instant(run.getReviewExpiresAt()), List.copyOf(items), counts), List.of());
    }

    /** Returns one member only when it belongs to the frozen requester-owned output. */
    @RequirePermission(Permission.AI_USE)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AiAgentOutputDto.ProposalItem item(int id, String slot) {
        requireUuid(slot);
        Authorized authorized = authorize(id);
        AiAgentAction action = actions.getBySlot(authorized.run().getWorkspaceId(), id, slot);
        requireFrozen(authorized.run(), action);
        if (frozen(authorized.run()).stream().noneMatch(member -> member.getId() == action.getId())) {
            throw new ConflictException("mission_membership_conflict");
        }
        return project(authorized, action);
    }

    /** Prevalidates the complete immutable submitted contract before any item may commit. */
    @RequirePermission(Permission.AI_USE)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void validateBatch(int id, AiAgentProposalDecisionRequest request) {
        Authorized authorized = authorize(id, false);
        AiAgentRun run = authorized.run();
        long revision;
        try {
            revision = AiAgentContinuationCodec.decimal(request.expectedProposalRevision());
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid mission proposal revision");
        }
        if (!AiAgentMissionCatalog.proposals(run) || run.getProposalFrozenAt() == null
                || run.getProposalMembershipRevision() != revision) {
            throw new ConflictException("mission_proposal_conflict");
        }
        List<AiAgentAction> inventory = frozen(run);
        var seen = new java.util.HashSet<String>();
        if (request.items() == null || request.items().isEmpty() || request.items().size() > 50) {
            throw new BadRequestException("Invalid mission proposal items");
        }
        for (var selected : request.items()) {
            requireUuid(selected.actionSlot());
            requireUuid(selected.expectedPreparationId());
            if (!seen.add(selected.actionSlot()) || !Set.of("approve", "discard").contains(selected.decision())) {
                throw new BadRequestException("Invalid mission proposal items");
            }
            AiAgentAction action = actions.getBySlot(run.getWorkspaceId(), id, selected.actionSlot());
            requireFrozen(run, action);
            if (inventory.stream().noneMatch(member -> member.getId() == action.getId())) {
                throw new ConflictException("mission_membership_conflict");
            }
            if (!selected.expectedPreparationId().equals(action.getPreparationId())) {
                throw new ConflictException("mission_preparation_conflict");
            }
        }
    }

    private List<AiAgentAction> frozen(AiAgentRun run) {
        try {
            return proposals.readFrozen(run);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new AiAgentGoneException("mission_output_withheld");
        }
    }

    private AiAgentOutputDto.ProposalItem project(Authorized authorized, AiAgentAction action) {
        AiAgentRun run = authorized.run();
        requireFrozen(run, action);
        AiChatToolCall call = chat.getMissionToolCall(run.getWorkspaceId(), run.getId(), action.getId(), run.getOrganizationPrivacyGeneration());
        if (call == null || !Integer.valueOf(action.getId()).equals(call.getMissionActionId())
                || !Integer.valueOf(run.getId()).equals(call.getMissionRunId()) || !"mission".equals(call.getProposalOrigin())) {
            throw new ConflictException("mission_proposal_conflict");
        }
        var segment = action.getOriginSegmentId() == null ? null
                : runs.getSegment(run.getWorkspaceId(), action.getOriginSegmentId());
        if (segment == null || segment.getTurnId() == null || segment.getRunId() != run.getId()
                || segment.getOrganizationPrivacyGeneration() != run.getOrganizationPrivacyGeneration()
                || !Long.valueOf(segment.getExecutionGeneration()).equals(action.getOriginExecutionGeneration())) {
            throw new ConflictException("mission_proposal_conflict");
        }
        var card = cards.projectMission(authorized.session(), call, segment.getTurnId(),
                action.getPreparedAt().toString(), instant(run.getReviewExpiresAt()));
        return new AiAgentOutputDto.ProposalItem(action.getActionSlot(), action.getPreparationId(),
                action.getOutputOrdinal(), instant(action.getPreparedAt()), card);
    }

    private AiAgentOutputDto.Counts counts(List<AiAgentOutputDto.ProposalItem> items) {
        int pending = 0;
        int executed = 0;
        int rejected = 0;
        int withdrawn = 0;
        int undone = 0;
        for (var item : items) {
            switch (item.card().reviewState()) {
                case "open" -> pending++;
                case "expired", "withdrawn" -> withdrawn++;
                case "applied" -> executed++;
                case "discarded" -> rejected++;
                case "undone" -> undone++;
                default -> { }
            }
        }
        return new AiAgentOutputDto.Counts(pending, executed, rejected, withdrawn, undone);
    }

    private static void requireFrozen(AiAgentRun run, AiAgentAction action) {
        if (action == null) {
            throw inaccessible();
        }
        if (run.getProposalFrozenAt() == null || action.getOutputOrdinal() == null || action.getPreparationId() == null) {
            throw new ConflictException("mission_proposal_not_ready");
        }
    }

    private static void requireUuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new BadRequestException("Invalid mission proposal identity");
        }
    }

    private static Instant timestamp(String value) {
        return value == null ? null : LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static ResourceNotFoundException inaccessible() {
        return new ResourceNotFoundException("Mission inaccessible");
    }
}
