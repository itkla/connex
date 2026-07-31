package ooo.klae.connex.backend.connectedaccounts.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.IdentityMatchRow;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.ProviderCapturedInteraction;
import ooo.klae.connex.backend.beans.ProviderCapturedParticipant;
import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.dto.ProviderCaptureOverviewDto;
import ooo.klae.connex.backend.dto.ProviderCaptureReviewDto;
import ooo.klae.connex.backend.dto.ProviderCaptureReviewPage;
import ooo.klae.connex.backend.dto.ProviderCaptureReviewRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.DuplicateDecisionLockService;
import ooo.klae.connex.backend.services.IdentityKind;
import ooo.klae.connex.backend.services.MatchingService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Exact-identity review, explicit creation, and interaction admission.
 */
@Service
@RequiredArgsConstructor
public class ProviderCaptureReviewService {
    private final ProviderCaptureMapper captureMapper;
    private final ProviderConnectionMapper connectionMapper;
    private final IdentityMapper identityMapper;
    private final MatchingService matchingService;
    private final PersonService personService;
    private final WorkspaceService workspaceService;
    private final ProviderCapturePolicyService policyService;
    private final ProviderCapturePagePersistence pagePersistence;
    private final ProviderCapturePurgeService purgeService;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;
    private final PlatformTransactionManager transactionManager;
    private final TenantWorkScope tenantWorkScope;
    private final DuplicateDecisionLockService duplicateDecisionLockService;

    /** Lists held ambiguous or unmatched participants with current exact candidates. */
    public ProviderCaptureReviewPage page(String provider, int page, int size) {
        policyService.getCurrentOverview(provider);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        int offset = Math.multiplyExact(page - 1, size);
        List<ProviderCaptureReviewDto> items =
            captureMapper.getReviewPage(workspaceId, userId, provider, size, offset)
                .stream()
                .map(participant -> review(workspaceId, participant))
                .toList();
        return new ProviderCaptureReviewPage(
            items,
            captureMapper.countReviews(workspaceId, userId, provider)
                + captureMapper.countPendingApprovals(
                    workspaceId, userId, provider),
            page,
            size);
    }

    /** Resolves one held participant and returns the updated provider overview. */
    public ProviderCaptureOverviewDto decide(
            String provider,
            long reviewId,
            ProviderCaptureReviewRequest request) {
        policyService.getCurrentOverview(provider);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        ProviderConnection expectedConnection = connection(userId, provider);
        transaction(() -> decideInTransaction(
            provider,
            reviewId,
            request,
            workspaceId,
            userId,
            credentialGeneration(expectedConnection)));
        recordStrict(
            "provider.capture.review",
            workspaceId,
            provider,
            "Resolved captured participant",
            Map.of("provider", provider, "action", request.action()));
        return policyService.getCurrentOverview(provider);
    }

    private void decideInTransaction(
            String provider,
            long reviewId,
            ProviderCaptureReviewRequest request,
            int workspaceId,
            int userId,
            long expectedCredentialGeneration) {
        duplicateDecisionLockService.lockCurrentOrganization();
        workspaceService.requirePermission(
            workspaceId, userId, Permission.ACTIVITY_CREATE);
        ProviderConnection connection = lockedConnection(
            userId, provider, expectedCredentialGeneration);
        requireCaptureEnabled(
            workspaceId, userId, provider, connection);
        ProviderCapturedParticipant preview =
            captureMapper.getParticipant(
                workspaceId, userId, provider, reviewId);
        if (preview == null) {
            throw new ResourceNotFoundException("Capture review item not found");
        }
        ProviderCapturedInteraction interaction =
            captureMapper.getInteractionForUpdate(
                workspaceId, userId, provider, preview.getInteractionId());
        if (interaction == null
                || (!"held".equals(interaction.getAdmissionStatus())
                    && !"admitted".equals(interaction.getAdmissionStatus()))) {
            throw changed();
        }
        ProviderCapturedParticipant participant =
            captureMapper.getParticipantForUpdate(
                workspaceId, userId, provider, reviewId);
        if (participant == null
                || participant.getInteractionId() != interaction.getId()) {
            throw changed();
        }
        requireAllowedAction(participant, request.action());
        Resolution resolution = resolution(
            workspaceId, participant, request);
        if (captureMapper.resolveParticipant(
                workspaceId,
                reviewId,
                request.version(),
                resolution.matchState(),
                resolution.personId()) != 1) {
            throw changed();
        }
        if (request.rememberExact()) {
            if (participant.getNormalizedEmail() == null) {
                throw new BadRequestException(
                    "Only an exact valid email decision can be remembered");
            }
            captureMapper.upsertDecision(
                workspaceId,
                userId,
                provider,
                participant.getNormalizedEmail(),
                resolution.decision(),
                resolution.personId());
        }
        if ("admitted".equals(interaction.getAdmissionStatus())) {
            pagePersistence.projectHistorical(
                workspaceId,
                userId,
                interaction,
                captureMapper.getParticipants(
                    workspaceId, participant.getInteractionId()));
        } else {
            admitIfAutomatic(
                workspaceId,
                userId,
                provider,
                participant.getInteractionId(),
                connection);
        }
    }

    /** Admits one fully resolved interaction after optimistic review. */
    public ProviderCaptureOverviewDto approve(
            String provider, long interactionId, long version) {
        policyService.getCurrentOverview(provider);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        ProviderConnection expectedConnection = connection(userId, provider);
        transaction(() -> approveInTransaction(
            provider,
            interactionId,
            version,
            workspaceId,
            userId,
            credentialGeneration(expectedConnection)));
        recordStrict(
            "provider.capture.approve",
            workspaceId,
            provider,
            "Admitted captured interaction",
            Map.of("provider", provider, "interactionId", interactionId));
        return policyService.getCurrentOverview(provider);
    }

    private void approveInTransaction(
            String provider,
            long interactionId,
            long version,
            int workspaceId,
            int userId,
            long expectedCredentialGeneration) {
        duplicateDecisionLockService.lockCurrentOrganization();
        workspaceService.requirePermission(
            workspaceId, userId, Permission.ACTIVITY_CREATE);
        ProviderConnection connection = lockedConnection(
            userId, provider, expectedCredentialGeneration);
        requireCaptureEnabled(
            workspaceId, userId, provider, connection);
        ProviderCapturedInteraction interaction =
            captureMapper.getInteractionForUpdate(
                workspaceId, userId, provider, interactionId);
        if (interaction == null) {
            throw new ResourceNotFoundException("Captured interaction not found");
        }
        if (interaction.getVersion() != version
                || !"held".equals(interaction.getAdmissionStatus())) {
            throw changed();
        }
        List<ProviderCapturedParticipant> participants =
            captureMapper.getParticipants(workspaceId, interactionId);
        requireResolved(participants);
        if (captureMapper.markInteractionAdmitted(
                workspaceId, interactionId, version) != 1) {
            throw changed();
        }
        interaction.setAdmissionStatus("admitted");
        interaction.setVersion(version + 1);
        pagePersistence.projectHistorical(
            workspaceId, userId, interaction, participants);
    }

    /** Purges the current workspace's captured provider data without disconnecting OAuth. */
    public ProviderCaptureOverviewDto.PurgeState purgeCurrent(String provider) {
        policyService.getCurrentOverview(provider);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        sessionSecurityService.requireRecentAuthentication(userId);
        purgeService.purge(workspaceId, userId, provider);
        recordStrict(
            "provider.capture.purge",
            workspaceId,
            provider,
            "Purged captured provider data",
            Map.of("provider", provider));
        return new ProviderCaptureOverviewDto.PurgeState(false, "idle", null);
    }

    private Resolution resolution(
            int workspaceId,
            ProviderCapturedParticipant participant,
            ProviderCaptureReviewRequest request) {
        return switch (request.action()) {
            case "attach" -> attach(workspaceId, participant, request.personId());
            case "create" -> create(participant, request);
            case "ignore" -> new Resolution("ignored", null, "ignore");
            default -> throw new BadRequestException("Unknown capture review action");
        };
    }

    private static void requireAllowedAction(
            ProviderCapturedParticipant participant, String action) {
        if ("restricted_person".equals(participant.getHeldReason())) {
            throw new ConflictException(
                "Restricted participants cannot be resolved until processing resumes");
        }
        if ("invalid_identity".equals(participant.getHeldReason())
                && !"ignore".equals(action)) {
            throw new BadRequestException(
                "Invalid participant identities can only be ignored");
        }
        if ("approval_required".equals(participant.getHeldReason())) {
            throw new BadRequestException(
                "Approval items must be resolved through interaction approval");
        }
    }

    private Resolution attach(
            int workspaceId,
            ProviderCapturedParticipant participant,
            Integer personId) {
        if (personId == null || participant.getNormalizedEmail() == null) {
            throw new BadRequestException("Select an exact candidate to attach");
        }
        boolean exact = exactMatches(workspaceId, participant.getNormalizedEmail())
            .stream()
            .anyMatch(match -> match.getRecordId() == personId);
        if (!exact) {
            throw new ConflictException(
                "The selected exact identity candidate is no longer eligible");
        }
        return new Resolution("matched", personId, "attach");
    }

    private Resolution create(
            ProviderCapturedParticipant participant,
            ProviderCaptureReviewRequest request) {
        if (request.contact() == null) {
            throw new BadRequestException("Contact values are required");
        }
        if (participant.getNormalizedEmail() != null) {
            String contactEmail = matchingService
                .normalizeIdentifier(IdentityKind.EMAIL, request.contact().getEmail())
                .orElseThrow(() -> new BadRequestException(
                    "The contact email must match the captured participant"));
            if (!participant.getNormalizedEmail().equals(contactEmail)) {
                throw new BadRequestException(
                    "The contact email must match the captured participant");
            }
        }
        Person created = personService.createReviewed(
            request.contact().toBean(), request.duplicateReviewToken());
        return new Resolution("matched", created.getId(), "attach");
    }

    private void admitIfAutomatic(
            int workspaceId,
            int userId,
            String provider,
            long interactionId,
            ProviderConnection connection) {
        ProviderCapturedInteraction interaction =
            captureMapper.getInteractionForUpdate(
                workspaceId, userId, provider, interactionId);
        if (interaction == null || !"held".equals(interaction.getAdmissionStatus())) {
            return;
        }
        List<ProviderCapturedParticipant> participants =
            captureMapper.getParticipants(workspaceId, interactionId);
        boolean hasMatched = participants.stream().anyMatch(
            participant -> "matched".equals(participant.getMatchState()));
        if (!hasMatched) {
            if (!allResolved(participants)) {
                return;
            }
            if (captureMapper.markInteractionIgnored(
                    workspaceId,
                    interactionId,
                    interaction.getVersion()) != 1) {
                throw changed();
            }
            return;
        }
        CaptureExecutionPolicy policy =
            policyService.effectivePolicy(workspaceId, userId, provider, connection);
        if (!policy.enabled()
                || !"automatic".equals(policy.admissionMode())) {
            return;
        }
        if (captureMapper.markInteractionAdmitted(
                workspaceId, interactionId, interaction.getVersion()) != 1) {
            throw changed();
        }
        interaction.setAdmissionStatus("admitted");
        interaction.setVersion(interaction.getVersion() + 1);
        pagePersistence.projectHistorical(
            workspaceId, userId, interaction, participants);
    }

    private ProviderCaptureReviewDto review(
            int workspaceId, ProviderCapturedParticipant participant) {
        List<ProviderCaptureReviewDto.Candidate> candidates =
            "approval_required".equals(participant.getHeldReason())
                || participant.getNormalizedEmail() == null
                ? List.of()
                : exactMatches(workspaceId, participant.getNormalizedEmail()).stream()
                    .map(match -> new ProviderCaptureReviewDto.Candidate(
                        match.getRecordId(), match.getName()))
                    .toList();
        List<String> actions = new ArrayList<>();
        if ("restricted_person".equals(participant.getHeldReason())) {
            actions = List.of();
        } else if ("invalid_identity".equals(participant.getHeldReason())) {
            actions.add("ignore");
        } else if (!"approval_required".equals(participant.getHeldReason())) {
            if (!candidates.isEmpty()) {
                actions.add("attach");
            }
            actions.add("create");
            actions.add("ignore");
        }
        return new ProviderCaptureReviewDto(
            participant.getId(),
            participant.getVersion(),
            participant.getInteractionId(),
            participant.getInteractionVersion(),
            participant.getProvider(),
            participant.getStream(),
            participant.getInteractionType(),
            participant.getSubject(),
            participant.getOccurredAt(),
            participant.getParticipantRole(),
            participant.getDisplayName(),
            participant.getEmail(),
            participant.getMatchState(),
            participant.getHeldReason(),
            candidates,
            actions);
    }

    private List<IdentityMatchRow> exactMatches(
            int workspaceId, String normalizedEmail) {
        return identityMapper.findCurrentPersonIdentityMatches(
            workspaceId,
            IdentityKind.EMAIL.getDatabaseValue(),
            List.of(normalizedEmail));
    }

    private static void requireResolved(
            List<ProviderCapturedParticipant> participants) {
        if (!allResolved(participants)
                || participants.stream().noneMatch(
                    participant -> "matched".equals(participant.getMatchState()))) {
            throw new BadRequestException(
                "Resolve at least one exact participant before admitting this interaction");
        }
    }

    private static boolean allResolved(
            List<ProviderCapturedParticipant> participants) {
        return participants.stream().allMatch(participant ->
            "matched".equals(participant.getMatchState())
                || "ignored".equals(participant.getMatchState()));
    }

    private static ConflictException changed() {
        return new ConflictException(
            "Captured evidence changed; reload and retry");
    }

    private void requireCaptureEnabled(
            int workspaceId,
            int userId,
            String provider,
            ProviderConnection connection) {
        if (!policyService.effectivePolicy(
                workspaceId, userId, provider, connection).enabled()) {
            throw new ConflictException(
                "Capture must be enabled before reviewing captured evidence");
        }
    }

    private ProviderConnection connection(int userId, String provider) {
        return tenantWorkScope.unrouted(
            () -> connectionMapper.getByUserAndProvider(userId, provider));
    }

    private ProviderConnection lockedConnection(
            int userId,
            String provider,
            long expectedCredentialGeneration) {
        ProviderConnection connection =
            connectionMapper.getByUserAndProviderForShare(userId, provider);
        if (connection == null
                || !"connected".equals(connection.getStatus())
                || connection.getCredentialGeneration()
                    != expectedCredentialGeneration) {
            throw new ConflictException(
                "Provider connection changed before captured evidence could be reviewed");
        }
        return connection;
    }

    private static long credentialGeneration(ProviderConnection connection) {
        return connection == null ? 0 : connection.getCredentialGeneration();
    }

    private void transaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
            status -> work.run());
    }

    private void recordStrict(
            String action,
            int workspaceId,
            String provider,
            String summary,
            Map<String, Object> changes) {
        tenantWorkScope.unrouted(() -> {
            auditService.recordStrictIndependentScoped(
                action,
                "workspace",
                workspaceId,
                workspaceId,
                workspaceService.getOrgId(workspaceId),
                provider,
                summary,
                changes);
            return null;
        });
    }

    private record Resolution(
        String matchState, Integer personId, String decision) {
    }
}
