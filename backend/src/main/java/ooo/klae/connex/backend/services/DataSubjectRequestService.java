package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.ThirdPartyProvisionDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestUpsertRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.services.DataSubjectRequestControlOperations.DisclosureControlData;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RequiredArgsConstructor
public class DataSubjectRequestService {
    static final int DISCLOSURE_AUDIT_LIMIT = 1_000;
    /** Bounds retained snapshots; each disclosure may borrow a second connection for its audit. */
    static final int MAX_CONCURRENT_DISCLOSURES = 2;
    /** Reserves capacity for another organization even while one client downloads slowly. */
    static final int MAX_CONCURRENT_DISCLOSURES_PER_ORGANIZATION = 1;
    /** Gives a refused organization the next single-capacity slot without waiting on a request thread. */
    static final Duration DISCLOSURE_TURNOVER_RESERVATION = Duration.ofSeconds(30);
    /** Bounds first-refusal timestamps retained during a single-capacity holder's lifetime. */
    static final int MAX_REFUSED_DISCLOSURE_ORGANIZATIONS = 128;
    private static final int MIN_DISCLOSURE_POOL_SIZE = 2;
    private static final Set<String> REQUEST_TYPES = Set.of(
        "disclosure", "correction", "cease_use", "cease_provision");
    private static final Set<String> STATUSES = Set.of(
        "received", "verifying", "in_progress", "responded", "refused", "closed");

    private final DataSubjectRequestControlOperations controlOperations;
    private final DataSubjectDisclosureAccess disclosureAccess;
    private final TenantWorkScope tenantWorkScope;
    private final DataSubjectDisclosureAuditAccess disclosureAuditAccess;
    private final DataSubjectDisclosureJsonWriter disclosureJsonWriter;
    private final Map<Integer, Integer> disclosureOrganizations = new HashMap<>();
    private final LinkedHashMap<Integer, Long> refusedDisclosureOrganizations = new LinkedHashMap<>();
    private LongSupplier disclosureTicker = System::nanoTime;
    private Integer reservedDisclosureOrganization;
    private long disclosureReservationStartedAt;
    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int maximumPoolSize = 10;

    public List<DataSubjectRequestDto> list(int orgId, int actorId, String status, int limit, int offset) {
        return tenantWorkScope.unroutedRead(
            () -> controlOperations.list(orgId, actorId, status, limit, offset));
    }

    public DataSubjectRequestDto get(int orgId, long requestId, int actorId) {
        return tenantWorkScope.unroutedRead(() -> controlOperations.get(orgId, requestId, actorId));
    }

    public DataSubjectRequestDto create(int orgId, int actorId, DataSubjectRequestUpsertRequest request) {
        tenantWorkScope.unrouted(() -> {
            controlOperations.requireMutationAccess(orgId, actorId);
            return null;
        });
        DataSubjectRequest subjectRequest = new DataSubjectRequest();
        apply(subjectRequest, orgId, actorId, request, true);
        validateSubjectLink(orgId,
            subjectRequest.getSubjectWorkspaceId(), subjectRequest.getSubjectPersonId());
        return withLockedSubjectPerson(
            orgId,
            actorId,
            subjectWorkspaceIds(null, subjectRequest.getSubjectWorkspaceId()),
            subjectRequest.getSubjectWorkspaceId(),
            subjectRequest.getSubjectPersonId(),
            () -> tenantWorkScope.unrouted(
                () -> controlOperations.create(orgId, actorId, subjectRequest)));
    }

    /**
     * Merges the supplied representation into the stored request: omitted ({@code null}) fields
     * keep their stored values, so a status-only update can never silently strip
     * identity-verification evidence or the subject link from a compliance record. Text fields
     * are cleared by sending a blank string. Field-level changes to the workflow timestamps and
     * the subject link are recorded in the audit log.
     */
    public DataSubjectRequestDto update(int orgId, long requestId, int actorId,
            DataSubjectRequestUpsertRequest request) {
        DataSubjectRequest subjectRequest = tenantWorkScope.unrouted(
            () -> controlOperations.loadForMutation(orgId, requestId, actorId));
        DataSubjectRequest before = auditSnapshot(subjectRequest);
        apply(subjectRequest, orgId, actorId, request, false);
        validateSubjectLink(orgId,
            subjectRequest.getSubjectWorkspaceId(), subjectRequest.getSubjectPersonId());
        return withLockedSubjectPerson(
            orgId,
            actorId,
            subjectWorkspaceIds(
                before.getSubjectWorkspaceId(),
                subjectRequest.getSubjectWorkspaceId()),
            subjectRequest.getSubjectWorkspaceId(),
            subjectRequest.getSubjectPersonId(),
            () -> tenantWorkScope.unrouted(
                () -> controlOperations.update(
                    orgId,
                    requestId,
                    actorId,
                    before,
                    subjectRequest)));
    }

    /**
     * Assembles the operator-facing disclosure record for the linked subject person (APPI Art. 33).
     * The result is raw assembly material for the handling operator — it may contain third-party
     * personal data and confidential business information, so statutory redaction (Art. 33(2))
     * must be applied by the operator before anything is released to the data subject. Attachments
     * are listed as metadata only; binaries stay behind the attachment endpoints. The audit-trail
     * section is capped at {@link #DISCLOSURE_AUDIT_LIMIT} entries with the uncapped total exposed
     * for truncation detection.
     *
     * <p>Two synchronous serialization passes share one explicit REPEATABLE READ snapshot.
     * Notes retain one bounded ID/body page. The strict control audit commits independently
     * between passes, before the response is opened; a failed release requires an explicit retry.
     */
    public void disclosure(int orgId, long requestId, int actorId,
            DataSubjectDisclosureJsonWriter.Output output) {
        DisclosureControlData control = tenantWorkScope.unroutedRead(
            () -> controlOperations.prepareDisclosure(orgId, requestId, actorId));
        if (maximumPoolSize < MIN_DISCLOSURE_POOL_SIZE || !acquireDisclosure(orgId)) {
            throw new ServiceUnavailableException("Disclosure capacity is unavailable");
        }
        try {
            int workspaceId = control.request().getSubjectWorkspaceId();
            int personId = control.request().getSubjectPersonId();
            disclosureAccess.withSnapshot(orgId, actorId, workspaceId, personId,
                control.workspaces().ids(), disclosure -> {
                    enrichDisclosure(disclosure, control, requestId, workspaceId, personId);
                    var fingerprint = disclosureJsonWriter.measure(disclosure);
                    try {
                        disclosureAuditAccess.record(orgId, requestId, personId, workspaceId, fingerprint);
                    } catch (RuntimeException exception) {
                        throw new ServiceUnavailableException(
                            "Disclosure requires a durable audit record and none could be written", exception);
                    }
                    disclosureJsonWriter.write(disclosure, fingerprint, output);
                    return null;
                });
        } finally {
            releaseDisclosure(orgId);
        }
    }

    /**
     * Admission never waits. Single-capacity pools remember bounded, first-refusal timestamps
     * during the current hold; the oldest retained organization receives the turnover reservation.
     * Repeated refusals cannot refresh its position, and saturation evicts the oldest entry to
     * admit a newly refused organization. Pools with two slots retain their existing admission.
     */
    private synchronized boolean acquireDisclosure(int orgId) {
        int organizationCount = disclosureOrganizations.getOrDefault(orgId, 0);
        int capacity = Math.min(MAX_CONCURRENT_DISCLOSURES, maximumPoolSize / MIN_DISCLOSURE_POOL_SIZE);
        if (organizationCount >= MAX_CONCURRENT_DISCLOSURES_PER_ORGANIZATION) {
            return false;
        }
        long now = disclosureTicker.getAsLong();
        if (disclosureOrganizations.size() >= capacity) {
            if (capacity == 1 && !refusedDisclosureOrganizations.containsKey(orgId)) {
                if (refusedDisclosureOrganizations.size() == MAX_REFUSED_DISCLOSURE_ORGANIZATIONS) {
                    refusedDisclosureOrganizations.pollFirstEntry();
                }
                refusedDisclosureOrganizations.put(orgId, now);
            }
            return false;
        }
        if (capacity == 1 && reservedDisclosureOrganization != null) {
            if (now - disclosureReservationStartedAt < DISCLOSURE_TURNOVER_RESERVATION.toNanos()
                    && reservedDisclosureOrganization != orgId) {
                return false;
            }
            reservedDisclosureOrganization = null;
        }
        disclosureOrganizations.put(orgId, organizationCount + 1);
        return true;
    }

    /**
     * Reserves a freed single-capacity slot for one refused organization for thirty seconds.
     * Other refusals are forgotten at turnover, so unused reservations cannot chain indefinitely.
     */
    private synchronized void releaseDisclosure(int orgId) {
        disclosureOrganizations.remove(orgId);
        if (maximumPoolSize / MIN_DISCLOSURE_POOL_SIZE == 1 && disclosureOrganizations.isEmpty()) {
            var refused = refusedDisclosureOrganizations.pollFirstEntry();
            reservedDisclosureOrganization = refused == null ? null : refused.getKey();
            disclosureReservationStartedAt = disclosureTicker.getAsLong();
            refusedDisclosureOrganizations.clear();
        }
    }

    private void enrichDisclosure(DataSubjectDisclosureDto disclosure, DisclosureControlData control,
            long requestId, int workspaceId, int personId) {
        for (ThirdPartyProvisionDto provision : disclosure.getThirdPartyProvisions()) {
            String workspaceName = control.workspaces().names().get(provision.getTargetWorkspaceId());
            if (workspaceName == null) {
                throw new ResourceNotFoundException(
                    "Third-party provision workspace not found: " + provision.getTargetWorkspaceId());
            }
            provision.setTargetWorkspaceName(workspaceName);
        }
        disclosure.setRequestId(requestId);
        disclosure.setSubjectWorkspaceId(workspaceId);
        disclosure.setSubjectPersonId(personId);
        disclosure.setAuditTrail(control.auditTrail());
        disclosure.setAuditTrailTotal(control.auditTrailTotal());
        disclosure.setGeneratedAt(LocalDateTime.now());
    }


    /** Accepts a conservative purge through the existing linked-person proof and control roots. */
    public ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance acceptMissionPurge(
            int orgId, long requestId, int actorId,
            ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeRequest request) {
        var replay = tenantWorkScope.unroutedRead(
                () -> controlOperations.missionPurgeByKey(orgId, requestId, actorId, request.idempotencyKey()));
        if (replay != null) {
            replay.requireAcknowledgments(request.scopeAcknowledgement(), request.cutoffPolicyAcknowledgement());
            return replay;
        }
        if (!ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance.acknowledges(
                request.scopeAcknowledgement(), request.cutoffPolicyAcknowledgement())) {
            throw new BadRequestException("Mission holdings purge scope must be acknowledged");
        }
        DataSubjectRequest subject = tenantWorkScope.unrouted(
                () -> controlOperations.loadForMutation(orgId, requestId, actorId));
        if (subject.getIdentityVerifiedAt() == null || subject.getSubjectWorkspaceId() == null
                || subject.getSubjectPersonId() == null) {
            throw new BadRequestException("A verified linked subject is required");
        }
        List<Integer> workspaceIds = tenantWorkScope.unroutedRead(
                () -> controlOperations.missionPurgeWorkspaceIds(orgId, actorId));
        return withLockedSubjectPerson(orgId, actorId, new TreeSet<>(workspaceIds),
                subject.getSubjectWorkspaceId(), subject.getSubjectPersonId(),
                () -> tenantWorkScope.unrouted(
                    () -> controlOperations.acceptMissionPurge(orgId, requestId, actorId, request, workspaceIds,
                            subject.getSubjectWorkspaceId(), subject.getSubjectPersonId())));
    }

    private void apply(DataSubjectRequest target, int orgId, int actorId,
            DataSubjectRequestUpsertRequest request, boolean create) {
        validateRequestShape(request);
        LocalDateTime receivedAt = mysqlPrecision(request.getReceivedAt() == null
            ? create ? LocalDateTime.now() : target.getReceivedAt()
            : request.getReceivedAt());
        Integer subjectWorkspaceId = merge(create, request.getSubjectWorkspaceId(), target.getSubjectWorkspaceId());
        Integer subjectPersonId = merge(create, request.getSubjectPersonId(), target.getSubjectPersonId());
        LocalDateTime identityVerifiedAt =
            mysqlPrecision(merge(create, request.getIdentityVerifiedAt(), target.getIdentityVerifiedAt()));
        LocalDateTime dueAt = mysqlPrecision(merge(create, request.getDueAt(), target.getDueAt()));
        LocalDateTime respondedAt = mysqlPrecision(merge(create, request.getRespondedAt(), target.getRespondedAt()));
        LocalDateTime closedAt = mysqlPrecision(merge(create, request.getClosedAt(), target.getClosedAt()));
        validateChronology(receivedAt, identityVerifiedAt, respondedAt, closedAt);

        target.setOrgId(orgId);
        target.setRequestType(normalize(request.getRequestType(), create ? null : target.getRequestType(),
            REQUEST_TYPES, "requestType"));
        target.setStatus(normalize(request.getStatus(), create ? "received" : target.getStatus(), STATUSES, "status"));
        target.setChannel(mergeText(create, request.getChannel(), target.getChannel()));
        target.setRequesterName(required(request.getRequesterName(), "requesterName"));
        target.setSubjectName(required(request.getSubjectName(), "subjectName"));
        target.setSubjectEmail(mergeText(create, request.getSubjectEmail(), target.getSubjectEmail()));
        target.setSubjectWorkspaceId(subjectWorkspaceId);
        target.setSubjectPersonId(subjectPersonId);
        target.setReceivedAt(receivedAt);
        target.setIdentityVerifiedAt(identityVerifiedAt);
        target.setDueAt(dueAt);
        target.setRespondedAt(respondedAt);
        target.setClosedAt(closedAt);
        target.setSummary(mergeText(create, request.getSummary(), target.getSummary()));
        target.setResolution(mergeText(create, request.getResolution(), target.getResolution()));
        if (create) {
            target.setCreatedBy(actorId);
        }
        target.setUpdatedBy(actorId);
    }

    private static <T> T merge(boolean create, T supplied, T stored) {
        return create || supplied != null ? supplied : stored;
    }

    private static String mergeText(boolean create, String supplied, String stored) {
        return create || supplied != null ? blankToNull(supplied) : stored;
    }

    private static LocalDateTime mysqlPrecision(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        LocalDateTime truncated = value.truncatedTo(ChronoUnit.SECONDS);
        return value.getNano() >= 500_000_000 ? truncated.plusSeconds(1) : truncated;
    }

    private void validateRequestShape(DataSubjectRequestUpsertRequest request) {
        if (!request.isSubjectLinkComplete()) {
            throw new BadRequestException("subjectWorkspaceId and subjectPersonId must be supplied together");
        }
        if (!request.isMysqlDateTimeRangeValid()) {
            throw new BadRequestException("timestamps must use years from 1000 through 9999");
        }
    }

    private void validateSubjectLink(int orgId, Integer workspaceId, Integer personId) {
        if (workspaceId == null && personId == null) {
            return;
        }
        if (workspaceId == null || personId == null) {
            throw new BadRequestException("Subject person must exist in a workspace belonging to the organization");
        }
        boolean workspaceBelongsToOrg = tenantWorkScope.unrouted(
            () -> controlOperations.workspaceBelongsToOrg(orgId, workspaceId));
        if (!workspaceBelongsToOrg) {
            throw new BadRequestException("Subject person must exist in a workspace belonging to the organization");
        }
    }

    private <T> T withLockedSubjectPerson(
            int orgId,
            int actorId,
            Set<Integer> controlWorkspaceIds,
            Integer workspaceId,
            Integer personId,
            Supplier<T> work) {
        if (workspaceId == null || personId == null) {
            return work.get();
        }
        return disclosureAccess.withLockedSubjectPerson(
            orgId,
            actorId,
            workspaceId,
            personId,
            lockedPersonWork -> tenantWorkScope.unrouted(() ->
                controlOperations.withLockedSubjectRoots(
                    orgId,
                    actorId,
                    controlWorkspaceIds,
                    lockedPersonWork)),
            work);
    }

    private static Set<Integer> subjectWorkspaceIds(Integer previous, Integer requested) {
        Set<Integer> workspaceIds = new TreeSet<>();
        if (previous != null) {
            workspaceIds.add(previous);
        }
        if (requested != null) {
            workspaceIds.add(requested);
        }
        return Set.copyOf(workspaceIds);
    }

    private String normalize(String value, String fallback, Set<String> allowed, String field) {
        String normalized = value == null || value.isBlank()
            ? fallback
            : value.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || !allowed.contains(normalized)) {
            throw new BadRequestException("Unknown " + field + ": " + value);
        }
        return normalized;
    }

    private static void validateChronology(LocalDateTime receivedAt, LocalDateTime identityVerifiedAt,
            LocalDateTime respondedAt, LocalDateTime closedAt) {
        if (identityVerifiedAt != null && receivedAt.isAfter(identityVerifiedAt)) {
            throw new BadRequestException(
                "receivedAt (" + receivedAt + ") must be before or equal to identityVerifiedAt");
        }
        if (respondedAt != null && receivedAt.isAfter(respondedAt)) {
            throw new BadRequestException(
                "receivedAt (" + receivedAt + ") must be before or equal to respondedAt");
        }
        if (closedAt != null && receivedAt.isAfter(closedAt)) {
            throw new BadRequestException(
                "receivedAt (" + receivedAt + ") must be before or equal to closedAt");
        }
        if (respondedAt != null && closedAt != null && respondedAt.isAfter(closedAt)) {
            throw new BadRequestException("respondedAt must be before or equal to closedAt");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static DataSubjectRequest auditSnapshot(DataSubjectRequest source) {
        DataSubjectRequest snapshot = new DataSubjectRequest();
        snapshot.setId(source.getId());
        snapshot.setOrgId(source.getOrgId());
        snapshot.setRequestType(source.getRequestType());
        snapshot.setStatus(source.getStatus());
        snapshot.setChannel(source.getChannel());
        snapshot.setRequesterName(source.getRequesterName());
        snapshot.setSubjectName(source.getSubjectName());
        snapshot.setSubjectEmail(source.getSubjectEmail());
        snapshot.setSubjectWorkspaceId(source.getSubjectWorkspaceId());
        snapshot.setSubjectPersonId(source.getSubjectPersonId());
        snapshot.setReceivedAt(source.getReceivedAt());
        snapshot.setIdentityVerifiedAt(source.getIdentityVerifiedAt());
        snapshot.setDueAt(source.getDueAt());
        snapshot.setRespondedAt(source.getRespondedAt());
        snapshot.setClosedAt(source.getClosedAt());
        snapshot.setSummary(source.getSummary());
        snapshot.setResolution(source.getResolution());
        snapshot.setCreatedBy(source.getCreatedBy());
        snapshot.setUpdatedBy(source.getUpdatedBy());
        snapshot.setCreatedAt(source.getCreatedAt());
        snapshot.setUpdatedAt(source.getUpdatedAt());
        return snapshot;
    }
}
