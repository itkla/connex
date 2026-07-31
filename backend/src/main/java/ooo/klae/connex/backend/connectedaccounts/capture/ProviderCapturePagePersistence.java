package ooo.klae.connex.backend.connectedaccounts.capture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.IdentityMatchRow;
import ooo.klae.connex.backend.beans.ProviderCaptureSyncState;
import ooo.klae.connex.backend.beans.ProviderCapturedInteraction;
import ooo.klae.connex.backend.beans.ProviderCapturedParticipant;
import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.services.DuplicateDecisionLockService;
import ooo.klae.connex.backend.services.IdentityKind;
import ooo.klae.connex.backend.services.MatchingService;
import ooo.klae.connex.backend.services.ProviderCaptureHistoricalBaselineService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Tenant-local page commit, exact matching, replay reconciliation, and activity projection.
 */
@Component
@RequiredArgsConstructor
public class ProviderCapturePagePersistence {
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final ProviderCaptureMapper captureMapper;
    private final ProviderConnectionMapper connectionMapper;
    private final IdentityMapper identityMapper;
    private final MatchingService matchingService;
    private final ConnectedCaptureProperties properties;
    private final ObjectMapper objectMapper;
    private final DuplicateDecisionLockService duplicateDecisionLockService;
    private final WorkspaceService workspaceService;
    private final ProviderCaptureHistoricalBaselineService historicalBaselineService;

    /**
     * Commits one provider page and advances its cursor under the claimed lease.
     */
    @Transactional
    public void commit(
            int workspaceId,
            long syncStateId,
            String leaseOwner,
            ProviderCapturePage page,
            CaptureExecutionPolicy policy,
            String accountEmail) {
        ProviderCaptureSyncState owner = stateOwner(workspaceId, syncStateId);
        ProviderConnection connection = connectionMapper.getByUserAndProviderForShare(
            owner.getUserId(), owner.getProvider());
        if (connection == null
                || !"connected".equals(connection.getStatus())
                || connection.getCredentialGeneration()
                    != owner.getCredentialGeneration()) {
            throw new ProviderCaptureException(
                "connection_changed", true, false,
                "Provider connection changed before the capture page committed");
        }
        duplicateDecisionLockService.lockBackgroundMemberOrganization(
            workspaceId, owner.getUserId());
        workspaceService.requirePermission(
            workspaceId,
            owner.getUserId(),
            Permission.ACTIVITY_CREATE);
        ProviderCaptureSyncState state =
            captureMapper.getSyncStateForUpdate(workspaceId, syncStateId);
        if (state == null
                || state.getUserId() != owner.getUserId()
                || !leaseOwner.equals(state.getLeaseOwner())) {
            throw new ProviderCaptureException(
                "lease_lost", true, false, "Capture page lease is no longer owned");
        }
        Instant evaluationInstant = Instant.now();
        expireOutsideRetention(state, policy, evaluationInstant);
        boolean fullScan = state.getReconciliationMarker() != null;
        Set<Long> affectedInteractionIds = fullScan
            ? existingInteractionIds(state, page.items())
            : new LinkedHashSet<>();
        if (fullScan && page.nextPageCursor() == null) {
            affectedInteractionIds.addAll(
                captureMapper.getMissingReconciliationInteractionIds(
                    workspaceId,
                    state.getUserId(),
                    state.getProvider(),
                    state.getStream(),
                    state.getReconciliationMarker()));
        }
        Set<Integer> beforePersonIds = affectedInteractionIds.isEmpty()
            ? new LinkedHashSet<>()
            : new LinkedHashSet<>(
                captureMapper.getPersonIdsForInteractions(
                    workspaceId, List.copyOf(affectedInteractionIds)));
        Set<Integer> beforeActivityIds = affectedInteractionIds.isEmpty()
            ? Set.of()
            : new LinkedHashSet<>(
                captureMapper.getActivityIdsForInteractions(
                    workspaceId, List.copyOf(affectedInteractionIds)));
        ProviderCaptureHistoricalBaselineService.Snapshot baseline =
            !state.isInitialSyncCompleted()
                ? historicalBaselineService.snapshot(
                    workspaceId,
                    evaluationInstant,
                    beforePersonIds,
                    beforeActivityIds)
                : null;
        long processedItems = state.getProcessedItems();
        for (ProviderCaptureItem item : page.items()) {
            Long affectedInteractionId =
                persistItem(state, item, policy, accountEmail);
            if (baseline != null && affectedInteractionId != null) {
                affectedInteractionIds.add(affectedInteractionId);
            }
            processedItems++;
        }
        boolean more = page.nextPageCursor() != null;
        if (!more && fullScan) {
            reconcileMissing(state);
        }
        if (baseline != null && !affectedInteractionIds.isEmpty()) {
            Set<Integer> personIds = new LinkedHashSet<>(beforePersonIds);
            personIds.addAll(
                captureMapper.getPersonIdsForInteractions(
                    workspaceId, List.copyOf(affectedInteractionIds)));
            Set<Integer> activityIds = new LinkedHashSet<>(
                captureMapper.getActivityIdsForInteractions(
                    workspaceId, List.copyOf(affectedInteractionIds)));
            historicalBaselineService.persist(
                workspaceId,
                evaluationInstant,
                baseline,
                personIds,
                activityIds,
                HexFormat.of().formatHex(sha256(
                    state.getProvider() + "\u0000"
                        + state.getReconciliationMarker())));
        }
        Instant now = Instant.now();
        String stableCursor = more || page.stableCursor() == null
            ? state.getStableCursor()
            : page.stableCursor();
        int saved = captureMapper.saveSyncSuccess(
            workspaceId,
            syncStateId,
            leaseOwner,
            stableCursor,
            page.nextPageCursor(),
            more ? "queued" : "idle",
            processedItems,
            page.estimatedItems() == null
                ? state.getEstimatedItems()
                : page.estimatedItems(),
            mysql(now),
            more
                ? mysql(now)
                : "manual".equals(policy.admissionMode())
                    ? null
                    : mysql(now.plus(properties.getSyncInterval())));
        if (saved != 1) {
            throw new ProviderCaptureException(
                "lease_lost", true, false, "Capture page cursor could not be advanced");
        }
    }

    private void expireOutsideRetention(
            ProviderCaptureSyncState state,
            CaptureExecutionPolicy policy,
            Instant evaluationInstant) {
        String before = mysql(
            evaluationInstant.minus(java.time.Duration.ofDays(policy.backfillDays())));
        captureMapper.deleteExpiredProviderActivities(
            state.getWorkspaceId(),
            state.getUserId(),
            state.getProvider(),
            state.getStream(),
            before);
        captureMapper.deleteExpiredInteractions(
            state.getWorkspaceId(),
            state.getUserId(),
            state.getProvider(),
            state.getStream(),
            before);
    }

    private ProviderCaptureSyncState stateOwner(int workspaceId, long syncStateId) {
        ProviderCaptureSyncState state =
            captureMapper.getSyncState(workspaceId, syncStateId);
        if (state == null) {
            throw new ProviderCaptureException(
                "sync_state_missing", false, false,
                "Capture sync state is unavailable");
        }
        return state;
    }

    private Set<Long> existingInteractionIds(
            ProviderCaptureSyncState state,
            List<ProviderCaptureItem> items) {
        Set<Long> interactionIds = new LinkedHashSet<>();
        for (ProviderCaptureItem item : items) {
            validateSource(item.sourceId());
            ProviderCapturedInteraction interaction =
                captureMapper.getInteractionBySourceHash(
                    state.getWorkspaceId(),
                    state.getUserId(),
                    state.getProvider(),
                    sha256(
                        state.getProvider() + "\u0000" + state.getStream()
                            + "\u0000" + item.sourceId()));
            if (interaction != null) {
                interactionIds.add(interaction.getId());
            }
        }
        return interactionIds;
    }

    private Long persistItem(
            ProviderCaptureSyncState state,
            ProviderCaptureItem item,
            CaptureExecutionPolicy policy,
            String accountEmail) {
        validateSource(item.sourceId());
        byte[] sourceHash = sha256(
            state.getProvider() + "\u0000" + state.getStream() + "\u0000" + item.sourceId());
        ProviderCapturedInteraction existing =
            captureMapper.getInteractionBySourceHash(
                state.getWorkspaceId(),
                state.getUserId(),
                state.getProvider(),
                sourceHash);
        if (existing != null) {
            existing = captureMapper.getInteractionForUpdate(
                state.getWorkspaceId(),
                state.getUserId(),
                state.getProvider(),
                existing.getId());
            if (existing == null) {
                throw new ProviderCaptureException(
                    "source_changed", true, false,
                    "Captured source changed during reconciliation");
            }
        }
        List<String> itemExclusions = itemExclusions(item, policy, accountEmail);
        if (item.tombstone() || !itemExclusions.isEmpty()) {
            withdrawExisting(
                existing, item, itemExclusions, state.getReconciliationMarker());
            return existing == null ? null : existing.getId();
        }
        ProviderCapturedInteraction interaction = interaction(
            state, item, policy, sourceHash);
        if (existing != null
                && !"withdrawn".equals(existing.getAdmissionStatus())
                && Arrays.equals(existing.getPayloadHash(), interaction.getPayloadHash())) {
            captureMapper.touchInteractionReconciliationMarker(
                state.getWorkspaceId(),
                existing.getId(),
                state.getReconciliationMarker(),
                interaction.getSourceVersion());
            captureMapper.touchInteractionActivitiesCapturedAt(
                state.getWorkspaceId(), existing.getId());
            return existing.getId();
        }
        List<ProviderCapturedParticipant> existingParticipants = existing == null
            ? List.of()
            : captureMapper.getParticipants(state.getWorkspaceId(), existing.getId());
        if (existing == null) {
            captureMapper.insertInteraction(interaction);
            interaction.setVersion(1);
        } else {
            interaction.setAdmissionStatus(
                "admitted".equals(existing.getAdmissionStatus())
                    ? "admitted"
                    : "held");
            interaction.setId(existing.getId());
            List<ProviderCapturedParticipant> resolvedParticipants =
                resolveParticipants(
                    state,
                    interaction,
                    item.participants(),
                    accountEmail,
                    existingParticipants);
            List<Integer> projectedPersonIds = resolvedParticipants.stream()
                .filter(participant -> "matched".equals(participant.getMatchState()))
                .map(ProviderCapturedParticipant::getPersonId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
            if (projectedPersonIds.isEmpty()) {
                interaction.setAdmissionStatus(
                    allResolved(resolvedParticipants) ? "ignored" : "held");
            }
            captureMapper.deleteInteractionActivitiesExceptPeople(
                state.getWorkspaceId(), existing.getId(), projectedPersonIds);
            captureMapper.deleteParticipants(
                state.getWorkspaceId(), existing.getId());
            interaction.setVersion(existing.getVersion() + 1);
            captureMapper.updateInteraction(interaction);
            insertParticipants(resolvedParticipants);
        }
        List<ProviderCapturedParticipant> participants = existing == null
            ? persistParticipants(
                state, interaction, item.participants(), accountEmail)
            : captureMapper.getParticipants(
                state.getWorkspaceId(), interaction.getId());
        if (allResolved(participants) && !hasMatched(participants)) {
            if ("ignored".equals(interaction.getAdmissionStatus())) {
                return interaction.getId();
            }
            if (captureMapper.markInteractionIgnored(
                    state.getWorkspaceId(),
                    interaction.getId(),
                    interaction.getVersion()) != 1) {
                throw new ProviderCaptureException(
                    "admission_conflict", true, false,
                    "Captured interaction changed during ignored admission");
            }
            return interaction.getId();
        }
        if ("admitted".equals(interaction.getAdmissionStatus())
                && hasMatched(participants)) {
            project(state.getWorkspaceId(), state.getUserId(), interaction, participants);
        } else if ("automatic".equals(policy.admissionMode())
                && hasMatched(participants)) {
            if (captureMapper.markInteractionAdmitted(
                    state.getWorkspaceId(),
                    interaction.getId(),
                    interaction.getVersion()) != 1) {
                throw new ProviderCaptureException(
                    "admission_conflict", true, false,
                    "Captured interaction changed during automatic admission");
            }
            interaction.setAdmissionStatus("admitted");
            interaction.setVersion(interaction.getVersion() + 1);
            project(state.getWorkspaceId(), state.getUserId(), interaction, participants);
        }
        return interaction.getId();
    }

    private void withdrawExisting(
            ProviderCapturedInteraction existing,
            ProviderCaptureItem item,
            List<String> exclusions,
            String reconciliationMarker) {
        if (existing == null) {
            return;
        }
        captureMapper.deleteInteractionActivities(
            existing.getWorkspaceId(), existing.getId());
        captureMapper.deleteParticipants(
            existing.getWorkspaceId(), existing.getId());
        existing.setPayloadHash(sha256(
            item.sourceId() + "\u0000withdrawn\u0000" + item.sourceVersion()
                + "\u0000" + String.join(",", exclusions)));
        existing.setSourceVersion(item.sourceVersion());
        existing.setSubject(null);
        existing.setBody(null);
        existing.setAdmissionStatus("withdrawn");
        existing.setAdmittedFieldsJson("[]");
        existing.setMaterialExclusionsJson(objectMapper.writeValueAsString(exclusions));
        existing.setLastSeenReconciliationMarker(reconciliationMarker);
        existing.setTombstonedAt(mysql(Instant.now()));
        captureMapper.updateInteraction(existing);
    }

    private ProviderCapturedInteraction interaction(
            ProviderCaptureSyncState state,
            ProviderCaptureItem item,
            CaptureExecutionPolicy policy,
            byte[] sourceHash) {
        List<String> admittedFields = new ArrayList<>(
            List.of("provider_source_id", "subject", "occurred_at", "participants"));
        List<String> materialExclusions = new ArrayList<>(
            List.of("attachments", "raw_mime", "remote_images"));
        String body = null;
        if (policy.includeBodies() && item.body() != null) {
            admittedFields.add("body");
            body = item.body();
        } else {
            materialExclusions.add("body");
        }
        ProviderCapturedInteraction interaction = new ProviderCapturedInteraction();
        interaction.setWorkspaceId(state.getWorkspaceId());
        interaction.setUserId(state.getUserId());
        interaction.setProvider(state.getProvider());
        interaction.setStream(state.getStream());
        interaction.setProviderSourceId(item.sourceId());
        interaction.setProviderConversationId(limit(item.conversationId(), 512));
        interaction.setSourceKeyHash(sourceHash);
        interaction.setSourceVersion(limit(item.sourceVersion(), 512));
        interaction.setInteractionType(item.interactionType());
        interaction.setSubject(limit(item.subject(), 255));
        interaction.setBody(body);
        interaction.setOccurredAt(mysql(item.occurredAt()));
        interaction.setEndedAt(item.endedAt() == null ? null : mysql(item.endedAt()));
        interaction.setVisibility(item.privateItem() ? "private" : "workspace");
        interaction.setAdmissionStatus("held");
        interaction.setAdmittedFieldsJson(objectMapper.writeValueAsString(admittedFields));
        interaction.setMaterialExclusionsJson(
            objectMapper.writeValueAsString(materialExclusions));
        interaction.setPolicyVersion(Math.max(1, policy.policyVersion()));
        interaction.setLastSeenReconciliationMarker(
            state.getReconciliationMarker());
        interaction.setTombstonedAt(null);
        interaction.setCapturedAt(mysql(Instant.now()));
        interaction.setPayloadHash(payloadHash(interaction, item.participants()));
        return interaction;
    }

    private List<ProviderCapturedParticipant> persistParticipants(
            ProviderCaptureSyncState state,
            ProviderCapturedInteraction interaction,
            List<ProviderCaptureParticipant> values,
            String accountEmail) {
        String normalizedAccountEmail = matchingService
            .normalizeIdentifier(IdentityKind.EMAIL, accountEmail)
            .orElse(null);
        List<ProviderCapturedParticipant> participants = values.stream()
            .map(value -> participant(
                state, interaction, value, normalizedAccountEmail))
            .toList();
        insertParticipants(participants);
        return participants;
    }

    private List<ProviderCapturedParticipant> resolveParticipants(
            ProviderCaptureSyncState state,
            ProviderCapturedInteraction interaction,
            List<ProviderCaptureParticipant> values,
            String accountEmail,
            List<ProviderCapturedParticipant> existing) {
        String normalizedAccountEmail = matchingService
            .normalizeIdentifier(IdentityKind.EMAIL, accountEmail)
            .orElse(null);
        Map<String, ProviderCapturedParticipant> previous = new LinkedHashMap<>();
        for (ProviderCapturedParticipant participant : existing) {
            if (participant.getNormalizedEmail() != null) {
                previous.put(
                    participant.getParticipantRole() + "\u0000"
                        + participant.getNormalizedEmail(),
                    participant);
            }
        }
        List<ProviderCapturedParticipant> resolved = new ArrayList<>();
        for (ProviderCaptureParticipant value : values) {
            ProviderCapturedParticipant participant =
                participant(state, interaction, value, normalizedAccountEmail);
            ProviderCapturedParticipant prior = participant.getNormalizedEmail() == null
                ? null
                : previous.get(
                    participant.getParticipantRole() + "\u0000"
                        + participant.getNormalizedEmail());
            if (prior != null && "ignored".equals(prior.getMatchState())) {
                participant.setMatchState("ignored");
                participant.setHeldReason(null);
                participant.setPersonId(null);
            } else if (prior != null
                    && "matched".equals(prior.getMatchState())
                    && prior.getPersonId() != null
                    && !captureMapper.isPersonProcessingRestricted(
                        state.getWorkspaceId(), prior.getPersonId())
                    && participant.getNormalizedEmail() != null
                    && identityMapper.findCurrentPersonIdentityMatches(
                        state.getWorkspaceId(),
                        IdentityKind.EMAIL.getDatabaseValue(),
                        List.of(participant.getNormalizedEmail()))
                        .stream()
                        .anyMatch(match ->
                            match.getRecordId() == prior.getPersonId())) {
                participant.setMatchState("matched");
                participant.setHeldReason(null);
                participant.setPersonId(prior.getPersonId());
            }
            resolved.add(participant);
        }
        return resolved;
    }

    private void insertParticipants(
            List<ProviderCapturedParticipant> participants) {
        for (ProviderCapturedParticipant participant : participants) {
            captureMapper.insertParticipant(participant);
        }
    }

    private ProviderCapturedParticipant participant(
            ProviderCaptureSyncState state,
            ProviderCapturedInteraction interaction,
            ProviderCaptureParticipant value,
            String normalizedAccountEmail) {
        ProviderCapturedParticipant participant = new ProviderCapturedParticipant();
        participant.setWorkspaceId(state.getWorkspaceId());
        participant.setInteractionId(interaction.getId());
        participant.setParticipantRole(value.role());
        participant.setDisplayName(limit(value.displayName(), 255));
        participant.setEmail(limit(value.email(), 254));
        String normalized = matchingService
            .normalizeIdentifier(IdentityKind.EMAIL, value.email())
            .orElse(null);
        participant.setNormalizedEmail(normalized);
        if (normalized == null) {
            participant.setMatchState("unmatched");
            participant.setHeldReason("invalid_identity");
            return participant;
        }
        if (normalized.equals(normalizedAccountEmail)) {
            participant.setMatchState("ignored");
            return participant;
        }
        if (captureMapper.isRememberedIgnore(
                state.getWorkspaceId(),
                state.getUserId(),
                state.getProvider(),
                normalized)) {
            participant.setMatchState("ignored");
            return participant;
        }
        List<IdentityMatchRow> matches = identityMapper.findCurrentPersonIdentityMatches(
            state.getWorkspaceId(),
            IdentityKind.EMAIL.getDatabaseValue(),
            List.of(normalized));
        Integer remembered = captureMapper.getRememberedPersonId(
            state.getWorkspaceId(),
            state.getUserId(),
            state.getProvider(),
            normalized);
        if (remembered != null
                && matches.size() == 1
                && matches.getFirst().getRecordId() == remembered
                && !captureMapper.isPersonProcessingRestricted(
                    state.getWorkspaceId(), remembered)) {
            participant.setPersonId(remembered);
            participant.setMatchState("matched");
        } else if (matches.size() == 1) {
            int personId = matches.getFirst().getRecordId();
            if (captureMapper.isPersonProcessingRestricted(
                    state.getWorkspaceId(), personId)) {
                participant.setMatchState("unmatched");
                participant.setHeldReason("restricted_person");
            } else {
                participant.setPersonId(personId);
                participant.setMatchState("matched");
            }
        } else if (matches.isEmpty()) {
            participant.setMatchState("unmatched");
            participant.setHeldReason("no_match");
        } else {
            participant.setMatchState("ambiguous");
            participant.setHeldReason("multiple_matches");
        }
        return participant;
    }

    /** Projects one admitted interaction idempotently for each exact matched person. */
    public void project(
            int workspaceId,
            int actorId,
            ProviderCapturedInteraction interaction,
            List<ProviderCapturedParticipant> participants) {
        workspaceService.requirePermission(
            workspaceId, actorId, Permission.ACTIVITY_CREATE);
        Set<Integer> projectedPeople = new LinkedHashSet<>();
        for (ProviderCapturedParticipant participant : participants) {
            Integer personId = participant.getPersonId();
            if (!"matched".equals(participant.getMatchState())
                    || personId == null
                    || !projectedPeople.add(personId)) {
                continue;
            }
            String projectionKey = HexFormat.of().formatHex(sha256(
                interaction.getProvider() + "\u0000"
                    + interaction.getStream() + "\u0000"
                    + interaction.getUserId() + "\u0000"
                    + interaction.getId() + "\u0000" + personId));
            captureMapper.insertProviderActivity(
                workspaceId, interaction, participant, actorId, projectionKey);
            captureMapper.updateProviderActivity(
                workspaceId, interaction, participant, projectionKey);
            Integer activityId =
                captureMapper.getActivityIdByProjectionKey(workspaceId, projectionKey);
            if (activityId != null) {
                captureMapper.insertProjection(
                    workspaceId,
                    interaction.getId(),
                    participant.getId(),
                    activityId);
            }
        }
    }

    /** Projects delayed historical review evidence without creating notification floods. */
    public void projectHistorical(
            int workspaceId,
            int actorId,
            ProviderCapturedInteraction interaction,
            List<ProviderCapturedParticipant> participants) {
        List<Long> interactionIds = List.of(interaction.getId());
        Set<Integer> personIds = new LinkedHashSet<>(
            captureMapper.getPersonIdsForInteractions(
                workspaceId, interactionIds));
        Set<Integer> beforeActivityIds = new LinkedHashSet<>(
            captureMapper.getActivityIdsForInteractions(
                workspaceId, interactionIds));
        Instant evaluationInstant = Instant.now();
        ProviderCaptureHistoricalBaselineService.Snapshot baseline =
            historicalBaselineService.snapshot(
                workspaceId,
                evaluationInstant,
                personIds,
                beforeActivityIds);
        project(workspaceId, actorId, interaction, participants);
        Set<Integer> afterActivityIds = new LinkedHashSet<>(
            captureMapper.getActivityIdsForInteractions(
                workspaceId, interactionIds));
        historicalBaselineService.persist(
            workspaceId,
            evaluationInstant,
            baseline,
            personIds,
            afterActivityIds,
            HexFormat.of().formatHex(sha256(
                interaction.getProvider() + "\u0000review\u0000"
                    + interaction.getId())));
    }

    private List<String> itemExclusions(
            ProviderCaptureItem item,
            CaptureExecutionPolicy policy,
            String accountEmail) {
        List<String> exclusions = new ArrayList<>();
        if (item.privateItem()) {
            exclusions.add("private_event");
        }
        Set<String> domains = new LinkedHashSet<>();
        boolean excludedDomain = false;
        for (ProviderCaptureParticipant participant : item.participants()) {
            String domain = matchingService
                .extractCompanyDomainFromEmail(participant.email())
                .orElse(null);
            if (domain != null) {
                domains.add(domain);
                excludedDomain |= policy.excludedDomains().contains(domain);
            }
        }
        if (excludedDomain) {
            exclusions.add("excluded_domain");
        }
        boolean excludedPerson = item.participants().stream()
            .map(ProviderCaptureParticipant::email)
            .map(email -> matchingService
                .normalizeIdentifier(IdentityKind.EMAIL, email)
                .orElse(null))
            .filter(java.util.Objects::nonNull)
            .anyMatch(policy.excludedPeople()::contains);
        if (excludedPerson) {
            exclusions.add("excluded_person");
        }
        if (policy.excludedConversations().contains(item.sourceId())
                || item.conversationId() != null
                    && policy.excludedConversations().contains(item.conversationId())) {
            exclusions.add("excluded_conversation");
        }
        String ownerDomain =
            matchingService.extractCompanyDomainFromEmail(accountEmail).orElse(null);
        if (policy.excludeInternalOnly()) {
            if (ownerDomain == null) {
                exclusions.add("internal_only_identity_unavailable");
            } else if (!domains.isEmpty()
                    && domains.stream().allMatch(ownerDomain::equals)) {
                exclusions.add("internal_only");
            }
        }
        return exclusions;
    }

    boolean bodyAllowed(
            ProviderCaptureItem item,
            CaptureExecutionPolicy policy,
            String accountEmail) {
        return !item.tombstone()
            && itemExclusions(item, policy, accountEmail).isEmpty();
    }

    private void reconcileMissing(ProviderCaptureSyncState state) {
        String marker = state.getReconciliationMarker();
        if (marker == null) {
            throw new ProviderCaptureException(
                "reconciliation_missing", true, false,
                "Full provider reconciliation has no durable marker");
        }
        captureMapper.deleteMissingReconciliationActivities(
            state.getWorkspaceId(),
            state.getUserId(),
            state.getProvider(),
            state.getStream(),
            marker);
        captureMapper.deleteMissingReconciliationParticipants(
            state.getWorkspaceId(),
            state.getUserId(),
            state.getProvider(),
            state.getStream(),
            marker);
        captureMapper.withdrawMissingReconciliationItems(
            state.getWorkspaceId(),
            state.getUserId(),
            state.getProvider(),
            state.getStream(),
            marker);
    }

    private byte[] payloadHash(
            ProviderCapturedInteraction interaction,
            List<ProviderCaptureParticipant> participants) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("type", interaction.getInteractionType());
        canonical.put("conversationId", interaction.getProviderConversationId());
        canonical.put("subject", interaction.getSubject());
        canonical.put("body", interaction.getBody());
        canonical.put("occurredAt", interaction.getOccurredAt());
        canonical.put("endedAt", interaction.getEndedAt());
        canonical.put("visibility", interaction.getVisibility());
        canonical.put("admittedFields", interaction.getAdmittedFieldsJson());
        canonical.put("materialExclusions", interaction.getMaterialExclusionsJson());
        canonical.put(
            "participants",
            participants.stream()
                .map(participant -> {
                    String normalized = matchingService
                        .normalizeIdentifier(
                            IdentityKind.EMAIL, participant.email())
                        .orElseGet(() -> participant.email() == null
                            ? ""
                            : participant.email().strip().toLowerCase(
                                java.util.Locale.ROOT));
                    return participant.role() + "\u0000"
                        + normalized + "\u0000"
                        + (participant.displayName() == null
                            ? ""
                            : participant.displayName());
                })
                .distinct()
                .sorted()
                .toList());
        return sha256(objectMapper.writeValueAsString(canonical));
    }

    private static boolean hasMatched(
            List<ProviderCapturedParticipant> participants) {
        return participants.stream()
            .anyMatch(participant -> "matched".equals(participant.getMatchState()));
    }

    private static boolean allResolved(
            List<ProviderCapturedParticipant> participants) {
        return participants.stream().allMatch(participant ->
            "matched".equals(participant.getMatchState())
                || "ignored".equals(participant.getMatchState()));
    }

    private static void validateSource(String sourceId) {
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 512) {
            throw new ProviderCaptureException(
                "provider_malformed", false, false, "Provider source id is invalid");
        }
    }

    private static String limit(String value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        int count = value.codePointCount(0, value.length());
        return count <= maxCodePoints
            ? value
            : value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String mysql(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC).format(MYSQL_TIMESTAMP);
    }
}
