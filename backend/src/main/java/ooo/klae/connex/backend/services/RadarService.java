package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.beans.RelationshipSignalFamilyState;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RadarContextDto;
import ooo.klae.connex.backend.dto.RadarResponseDto;
import ooo.klae.connex.backend.dto.RadarTaskRequestDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RelationshipSignalMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Request-facing canonical Radar reads and per-user lifecycle actions. */
@Service
@RequiredArgsConstructor
public class RadarService {
    private static final Duration STALE_AFTER = Duration.ofMinutes(15);
    private static final Duration MAX_SNOOZE = Duration.ofDays(365);
    private static final int PERSON_READ_BATCH = 500;
    private static final List<String> FAMILIES = List.of(
        RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
        RelationshipSignalDetectorService.DEAL_RISK,
        RelationshipSignalDetectorService.WARM_PATH);
    private static final Set<String> STATES = Set.of(
        "active", "followed", "snoozed", "dismissed");
    private static final Set<String> SUBJECT_TYPES = Set.of("person", "company", "deal");
    private static final TypeReference<List<RadarResponseDto.Evidence>> EVIDENCE_TYPE =
        new TypeReference<>() {
        };
    private static final TypeReference<RankPayload> RANK_TYPE = new TypeReference<>() {
    };

    private final RelationshipSignalMapper signalMapper;
    private final RelationshipSignalReconciliationService reconciliationService;
    private final WorkspaceService workspaceService;
    private final WorkspaceMapper workspaceMapper;
    private final UserMapper userMapper;
    private final PersonMapper personMapper;
    private final PersonEdgeReadService personEdgeReadService;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final TaskService taskService;
    private final WarmPathService warmPathService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Returns a bounded ranked Radar snapshot with explicit detector availability. */
    public RadarResponseDto get(
            List<String> familyFilters,
            List<String> stateFilters,
            @Nullable String query) {
        return get(familyFilters, stateFilters, query, null, null);
    }

    /**
     * Returns a bounded ranked Radar snapshot, optionally narrowed in the database to the signals
     * whose subject is one record so a record page does not read the whole workspace feed.
     *
     * @param familyFilters requested detector families
     * @param stateFilters requested dispositions
     * @param query subject label substring
     * @param subjectType subject record type, required together with {@code subjectId}
     * @param subjectId subject record id, required together with {@code subjectType}
     * @return bounded ranked snapshot
     */
    public RadarResponseDto get(
            List<String> familyFilters,
            List<String> stateFilters,
            @Nullable String query,
            @Nullable String subjectType,
            @Nullable Integer subjectId) {
        String scopedSubjectType = validatedSubjectType(subjectType, subjectId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        List<RelationshipSignalFamilyState> familyStates = signalMapper.findFamilyStates(workspaceId);
        if (familyStates.isEmpty()) {
            reconciliationService.reconcileWorkspace(workspaceId);
            familyStates = signalMapper.findFamilyStates(workspaceId);
        }
        Set<String> requestedFamilies = validatedFamilies(familyFilters);
        Set<String> requestedStates = validatedStates(stateFilters);
        String normalizedQuery = normalizeQuery(query);
        Instant now = clock.instant();
        Map<String, Boolean> unavailable = new LinkedHashMap<>();
        familyStates.forEach(state -> unavailable.put(
            state.getFamily(), "unavailable".equals(state.getStatus())));

        List<RelationshipSignal> activeSignals = scopedSubjectType == null
            ? signalMapper.findActiveForActor(workspaceId, userId)
            : signalMapper.findActiveForActorBySubject(
                workspaceId, userId, scopedSubjectType, subjectId);
        Set<Integer> processablePersonIds = processablePersonIds(workspaceId, activeSignals);
        Map<Integer, String> personLabels = personLabels(workspaceId, processablePersonIds);
        Set<Integer> visibleEdgeIds = visibleEdgeIds(workspaceId, activeSignals);
        List<RadarResponseDto.Signal> items = new ArrayList<>();
        int position = 0;
        for (RelationshipSignal signal : activeSignals) {
            CurrentSubject subject = currentSubject(
                workspaceId, signal, processablePersonIds);
            if (subject == null) {
                continue;
            }
            String state = currentState(signal, now);
            if (!requestedFamilies.isEmpty() && !requestedFamilies.contains(signal.getFamily())) {
                continue;
            }
            if (!requestedStates.isEmpty() && !requestedStates.contains(state)) {
                continue;
            }
            if (normalizedQuery != null
                    && !subject.label().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                continue;
            }
            RadarResponseDto.Signal item = toDto(
                workspaceId, signal, subject, state, position + 1, now,
                unavailable.getOrDefault(signal.getFamily(), false),
                processablePersonIds, personLabels, visibleEdgeIds);
            if (item.evidence().isEmpty()) {
                continue;
            }
            position++;
            items.add(item);
        }
        List<RadarResponseDto.FamilyStatus> statuses = familyStatuses(familyStates);
        Map<String, Integer> counts = counts(items);
        boolean partialFailure = statuses.stream()
            .anyMatch(status -> "unavailable".equals(status.status()));
        return new RadarResponseDto(
            List.copyOf(items), statuses, counts, now, partialFailure);
    }

    /** Returns only summary metadata and counts. */
    public RadarResponseDto summary() {
        RadarResponseDto full = get(List.of(), List.of(), null);
        return new RadarResponseDto(
            List.of(), full.families(), full.counts(), full.asOf(), full.partialFailure());
    }

    /** Follows one signal for the current user. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RadarResponseDto.Signal follow(long signalId, String expectedVersion) {
        return disposition(signalId, expectedVersion, "followed", null, false);
    }

    /** Snoozes one signal until a validated future instant. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RadarResponseDto.Signal snooze(
            long signalId, String expectedVersion, Instant until) {
        Instant now = clock.instant();
        if (!until.isAfter(now) || until.isAfter(now.plus(MAX_SNOOZE))) {
            throw new BadRequestException("Snooze time must be within the next 365 days");
        }
        return disposition(
            signalId,
            expectedVersion,
            "snoozed",
            LocalDateTime.ofInstant(until, ZoneOffset.UTC),
            false);
    }

    /** Dismisses the current source fingerprint for the current user. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RadarResponseDto.Signal dismiss(long signalId, String expectedVersion) {
        return disposition(signalId, expectedVersion, "dismissed", null, true);
    }

    /** Creates one canonical task and binds it idempotently to the actor's signal state. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.TASK_CREATE)
    public RadarResponseDto.Signal createTask(
            long signalId,
            String expectedVersion,
            RadarTaskRequestDto request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        lockTaskMutation(workspaceId, userId, request.assignedToId());
        RelationshipSignal signal = requireLocked(
            workspaceId, signalId, userId, expectedVersion);
        requireCurrentSubject(workspaceId, signal);
        if (isStale(signal, clock.instant())) {
            throw new ConflictException("Radar evidence is stale; refresh before creating a task");
        }
        if (signal.getTaskId() != null) {
            return requireDto(signalId);
        }
        long stateVersion = ensureState(workspaceId, signal, userId);
        Task created;
        boolean warmPath = RelationshipSignalDetectorService.WARM_PATH.equals(signal.getFamily());
        if (warmPath) {
            int bridgePersonId = resolveBridge(workspaceId, signal, request);
            if (request.dueDate() != null
                    || (request.assignedToId() != null
                        && request.assignedToId() != userId)) {
                throw new BadRequestException(
                    "Warm-path tasks use the current user and do not accept a due date");
            }
            validateRequestedLinks(request, signal);
            created = warmPathService.acceptPath(
                signal.getSubjectId(), bridgePersonId, request.description());
        } else {
            Task task = task(request, signal, userId, workspaceId);
            created = taskService.create(task);
        }
        if (signalMapper.attachTask(
                workspaceId,
                signalId,
                userId,
                created.getId(),
                signal.getSourceStateHash(),
                stateVersion) != 1) {
            throw new ConflictException("Radar signal changed; refresh and try again");
        }
        RadarResponseDto.Signal updated = requireDto(signalId);
        if (warmPath) {
            signalMapper.resolveByIds(
                workspaceId, List.of(signalId), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        }
        return updated;
    }

    /** Returns current authorized navigation context without trusting persisted labels. */
    public RadarContextDto context(long signalId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        RelationshipSignal signal = signalMapper.getActiveForActor(
            workspaceId, signalId, userId);
        if (signal == null) {
            throw new ResourceNotFoundException("Radar signal not found");
        }
        CurrentSubject subject = requireCurrentSubject(workspaceId, signal);
        return new RadarContextDto(
            subject.type(), subject.id(), subject.label(), href(subject.type(), subject.id()));
    }

    private RadarResponseDto.Signal disposition(
            long signalId,
            String expectedVersion,
            String disposition,
            @Nullable LocalDateTime snoozeUntil,
            boolean dismissed) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        lockActorMutation(workspaceId, userId);
        RelationshipSignal signal = requireLocked(
            workspaceId, signalId, userId, expectedVersion);
        requireCurrentSubject(workspaceId, signal);
        String dismissedHash = dismissed ? signal.getSourceStateHash() : null;
        if (signal.getStateVersion() == null) {
            signalMapper.insertState(
                workspaceId, signalId, userId, disposition, snoozeUntil, dismissedHash);
        } else if (signalMapper.updateState(
                workspaceId,
                signalId,
                userId,
                disposition,
                snoozeUntil,
                dismissedHash,
                versionToken(expectedVersion).stateVersion()) != 1) {
            throw new ConflictException("Radar signal changed; refresh and try again");
        }
        return requireDto(signalId);
    }

    private RelationshipSignal requireLocked(
            int workspaceId, long signalId, int userId, String expectedVersion) {
        RelationshipSignal signal = signalMapper.getActiveForActorForUpdate(
            workspaceId, signalId, userId);
        if (signal == null) {
            throw new ResourceNotFoundException("Radar signal not found");
        }
        VersionToken expected = versionToken(expectedVersion);
        long currentVersion = signal.getStateVersion() == null ? 0 : signal.getStateVersion();
        if (signal.getVersion() != expected.signalVersion()
                || currentVersion != expected.stateVersion()) {
            throw new ConflictException("Radar signal changed; refresh and try again");
        }
        return signal;
    }

    private void lockActorMutation(int workspaceId, int userId) {
        lockActorIdentity(userId);
        if (!activeMembership(workspaceId, userId)) {
            throw new ResourceNotFoundException("Radar signal not found");
        }
    }

    private void lockTaskMutation(
            int workspaceId, int userId, @Nullable Integer requestedAssigneeId) {
        lockActorIdentity(userId);
        int assigneeId = requestedAssigneeId == null ? userId : requestedAssigneeId;
        Set<Integer> membershipIds = new TreeSet<>();
        membershipIds.add(userId);
        membershipIds.add(assigneeId);
        for (Integer membershipId : membershipIds) {
            if (activeMembership(workspaceId, membershipId)) {
                continue;
            }
            if (membershipId == userId) {
                throw new ResourceNotFoundException("Radar signal not found");
            }
            throw new ForbiddenException(
                "User " + membershipId + " is not a member of this workspace");
        }
    }

    private void lockActorIdentity(int userId) {
        if (userMapper.lockByIdForShare(userId) == null
                || userMapper.isAccountDeletionReserved(userId)) {
            throw new ResourceNotFoundException("Radar signal not found");
        }
    }

    private boolean activeMembership(int workspaceId, int userId) {
        var membership = workspaceMapper.lockAuthorizationMembership(workspaceId, userId);
        return membership != null && "active".equals(membership.getStatus());
    }

    private long ensureState(int workspaceId, RelationshipSignal signal, int userId) {
        if (signal.getStateVersion() != null) {
            return signal.getStateVersion();
        }
        signalMapper.insertState(
            workspaceId, signal.getId(), userId, "active", null, null);
        return 1;
    }

    private Task task(
            RadarTaskRequestDto request,
            RelationshipSignal signal,
            int currentUserId,
            int workspaceId) {
        validateRequestedLinks(request, signal);
        if (request.bridgePersonId() != null) {
            validateBridge(workspaceId, signal, request.bridgePersonId());
        }
        Task task = new Task();
        task.setDescription(request.description().trim());
        task.setDueDate(request.dueDate());
        User assignedTo = new User();
        assignedTo.setId(request.assignedToId() == null
            ? currentUserId
            : request.assignedToId());
        task.setAssignedTo(assignedTo);
        if ("person".equals(signal.getSubjectType())) {
            Person person = new Person();
            person.setId(signal.getSubjectId());
            task.setPerson(person);
        } else if ("deal".equals(signal.getSubjectType())) {
            Deal deal = new Deal();
            deal.setId(signal.getSubjectId());
            task.setDeal(deal);
        }
        return task;
    }

    private int resolveBridge(
            int workspaceId,
            RelationshipSignal signal,
            RadarTaskRequestDto request) {
        Integer requestedBridge = request.bridgePersonId();
        if (requestedBridge != null) {
            validateBridge(workspaceId, signal, requestedBridge);
            return requestedBridge;
        }
        for (RadarResponseDto.Evidence evidence : parseEvidence(signal.getEvidenceJson())) {
            Object value = evidence.parameters().get("bridgePersonId");
            if (value instanceof Number number) {
                int bridgePersonId = number.intValue();
                validateBridge(workspaceId, signal, bridgePersonId);
                return bridgePersonId;
            }
        }
        throw new ConflictException("Warm-path evidence is no longer available");
    }

    private static void validateRequestedLinks(
            RadarTaskRequestDto request, RelationshipSignal signal) {
        if ("person".equals(signal.getSubjectType())) {
            if ((request.personId() != null && request.personId() != signal.getSubjectId())
                    || request.dealId() != null) {
                throw new BadRequestException("Task links must match the Radar subject");
            }
        } else if ("deal".equals(signal.getSubjectType())) {
            if ((request.dealId() != null && request.dealId() != signal.getSubjectId())
                    || request.personId() != null) {
                throw new BadRequestException("Task links must match the Radar subject");
            }
        } else if (request.personId() != null || request.dealId() != null) {
            throw new BadRequestException("Company Radar tasks cannot link an unrelated record");
        }
    }

    private void validateBridge(
            int workspaceId, RelationshipSignal signal, int bridgePersonId) {
        if (!RelationshipSignalDetectorService.WARM_PATH.equals(signal.getFamily())
                || bridgePersonId == signal.getSubjectId()
                || !isProcessablePerson(workspaceId, bridgePersonId)
                || !evidenceContainsBridge(signal, bridgePersonId)) {
            throw new BadRequestException("Warm-path bridge is not current for this signal");
        }
    }

    private boolean evidenceContainsBridge(RelationshipSignal signal, int bridgePersonId) {
        for (RadarResponseDto.Evidence evidence : parseEvidence(signal.getEvidenceJson())) {
            Object value = evidence.parameters().get("bridgePersonId");
            if (value instanceof Number number && number.intValue() == bridgePersonId) {
                return true;
            }
        }
        return false;
    }

    private RadarResponseDto.Signal requireDto(long signalId) {
        return get(List.of(), List.of(), null).items().stream()
            .filter(item -> item.id() == signalId)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Radar signal not found"));
    }

    private RadarResponseDto.Signal toDto(
            int workspaceId,
            RelationshipSignal signal,
            CurrentSubject subject,
            String state,
            int position,
            Instant now,
            boolean familyUnavailable,
            Set<Integer> processablePersonIds,
            Map<Integer, String> personLabels,
            Set<Integer> visibleEdgeIds) {
        RankPayload rank = objectMapper.readValue(
            signal.getRankExplanationJson(), RANK_TYPE);
        return new RadarResponseDto.Signal(
            signal.getId(),
            signal.getFamily(),
            new RadarResponseDto.Subject(subject.type(), subject.id(), subject.label()),
            signal.getPriority(),
            state,
            instant(signal.getSnoozeUntil()),
            signal.getTaskId(),
            signal.getVersion() + ":"
                + (signal.getStateVersion() == null ? 0 : signal.getStateVersion()),
            instant(signal.getEvidenceAsOf()),
            familyUnavailable || isStale(signal, now),
            currentEvidence(
                workspaceId,
                parseEvidence(signal.getEvidenceJson()),
                processablePersonIds,
                personLabels,
                visibleEdgeIds),
            new RadarResponseDto.Rank(position, rank.rule(), rank.factors()));
    }

    private List<RadarResponseDto.Evidence> currentEvidence(
            int workspaceId,
            List<RadarResponseDto.Evidence> evidence,
            Set<Integer> processablePersonIds,
            Map<Integer, String> personLabels,
            Set<Integer> visibleEdgeIds) {
        List<RadarResponseDto.Evidence> current = new ArrayList<>();
        for (RadarResponseDto.Evidence item : evidence) {
            Integer requiredPersonId = requiredPersonId(item);
            if (requiredPersonId != null
                    && !processablePersonIds.contains(requiredPersonId)) {
                continue;
            }
            List<Integer> edgeReferences = item.references().stream()
                .filter(reference -> "person_edge".equals(reference.type()))
                .map(RadarResponseDto.Reference::id)
                .toList();
            if (!edgeReferences.isEmpty()
                    && edgeReferences.stream().noneMatch(visibleEdgeIds::contains)) {
                continue;
            }
            List<RadarResponseDto.Reference> references = item.references().stream()
                .map(reference -> resolveReference(
                    workspaceId, reference, personLabels, visibleEdgeIds))
                .filter(Objects::nonNull)
                .toList();
            current.add(new RadarResponseDto.Evidence(
                item.type(), item.parameters(), references));
        }
        return List.copyOf(current);
    }

    private static Integer requiredPersonId(RadarResponseDto.Evidence evidence) {
        Object personId = evidence.parameters().get("personId");
        if (personId instanceof Number number) {
            return number.intValue();
        }
        Object bridgePersonId = evidence.parameters().get("bridgePersonId");
        return bridgePersonId instanceof Number number ? number.intValue() : null;
    }

    /**
     * Returns the reference carrying the referenced record's current label, or null when the caller
     * may not see it. Labels are read from the live record for exactly the workspace in context, so
     * a reference can never disclose a name from another tenant or a name that no longer exists.
     */
    private RadarResponseDto.Reference resolveReference(
            int workspaceId,
            RadarResponseDto.Reference reference,
            Map<Integer, String> personLabels,
            Set<Integer> visibleEdgeIds) {
        return switch (reference.type()) {
            case "person" -> personLabels.containsKey(reference.id())
                ? reference.withLabel(personLabels.get(reference.id()))
                : null;
            case "company" -> {
                Company company = companyMapper.getCompanyById(workspaceId, reference.id());
                yield company == null
                    ? null
                    : reference.withLabel(label(company.getName(), company.getId()));
            }
            case "deal" -> {
                Deal deal = dealMapper.getDealById(workspaceId, reference.id());
                yield deal == null ? null : reference.withLabel(label(deal.getName(), deal.getId()));
            }
            case "person_edge" -> visibleEdgeIds.contains(reference.id()) ? reference : null;
            default -> null;
        };
    }

    /**
     * Current names of every processable person referenced by the supplied signals, read once per
     * request so evidence labelling never becomes a per-reference query. The map is built from the
     * already-authorized processable ids, so its key set is the person visibility set that decides
     * whether a person reference is disclosed at all.
     */
    private Map<Integer, String> personLabels(int workspaceId, Set<Integer> processablePersonIds) {
        if (processablePersonIds.isEmpty()) {
            return Map.of();
        }
        List<Integer> ordered = processablePersonIds.stream().sorted().toList();
        Map<Integer, String> labels = new LinkedHashMap<>();
        for (int offset = 0; offset < ordered.size(); offset += PERSON_READ_BATCH) {
            for (Person person : personMapper.getByIds(
                    workspaceId,
                    ordered.subList(offset, Math.min(offset + PERSON_READ_BATCH, ordered.size())))) {
                labels.put(person.getId(), label(person.getName(), person.getId()));
            }
        }
        return Map.copyOf(labels);
    }

    private Set<Integer> processablePersonIds(
            int workspaceId, List<RelationshipSignal> signals) {
        Set<Integer> requested = new LinkedHashSet<>();
        for (RelationshipSignal signal : signals) {
            if ("person".equals(signal.getSubjectType())) {
                requested.add(signal.getSubjectId());
            }
            for (RadarResponseDto.Evidence evidence : parseEvidence(signal.getEvidenceJson())) {
                Integer requiredPersonId = requiredPersonId(evidence);
                if (requiredPersonId != null) {
                    requested.add(requiredPersonId);
                }
                evidence.references().stream()
                    .filter(reference -> "person".equals(reference.type()))
                    .map(RadarResponseDto.Reference::id)
                    .forEach(requested::add);
            }
        }
        if (requested.isEmpty()) {
            return Set.of();
        }
        List<Integer> ordered = requested.stream().sorted().toList();
        Set<Integer> processable = new LinkedHashSet<>();
        for (int offset = 0; offset < ordered.size(); offset += PERSON_READ_BATCH) {
            processable.addAll(personMapper.getProcessablePersonIds(
                workspaceId,
                ordered.subList(
                    offset, Math.min(offset + PERSON_READ_BATCH, ordered.size()))));
        }
        return Set.copyOf(processable);
    }

    private Set<Integer> visibleEdgeIds(
            int workspaceId, List<RelationshipSignal> signals) {
        Set<Integer> requested = new LinkedHashSet<>();
        for (RelationshipSignal signal : signals) {
            for (RadarResponseDto.Evidence evidence : parseEvidence(signal.getEvidenceJson())) {
                evidence.references().stream()
                    .filter(reference -> "person_edge".equals(reference.type()))
                    .map(RadarResponseDto.Reference::id)
                    .forEach(requested::add);
            }
        }
        return requested.isEmpty()
            ? Set.of()
            : personEdgeReadService.getVisibleEdgeIds(
                workspaceId, requested.stream().sorted().toList());
    }

    private boolean isProcessablePerson(int workspaceId, int personId) {
        return personMapper.getProcessablePersonIds(workspaceId, List.of(personId))
            .contains(personId);
    }

    private List<RadarResponseDto.Evidence> parseEvidence(String json) {
        return objectMapper.readValue(json, EVIDENCE_TYPE);
    }

    private CurrentSubject requireCurrentSubject(
            int workspaceId, RelationshipSignal signal) {
        CurrentSubject subject = currentSubject(
            workspaceId,
            signal,
            processablePersonIds(workspaceId, List.of(signal)));
        if (subject == null) {
            throw new ResourceNotFoundException("Radar signal not found");
        }
        return subject;
    }

    private CurrentSubject currentSubject(
            int workspaceId,
            RelationshipSignal signal,
            Set<Integer> processablePersonIds) {
        return switch (signal.getSubjectType()) {
            case "person" -> {
                Person person = personMapper.getPersonById(workspaceId, signal.getSubjectId());
                yield person == null || !processablePersonIds.contains(person.getId())
                    ? null
                    : new CurrentSubject(
                        "person", person.getId(), label(person.getName(), person.getId()));
            }
            case "company" -> {
                Company company = companyMapper.getCompanyById(workspaceId, signal.getSubjectId());
                yield company == null ? null : new CurrentSubject(
                    "company", company.getId(), label(company.getName(), company.getId()));
            }
            case "deal" -> {
                Deal deal = dealMapper.getDealById(workspaceId, signal.getSubjectId());
                yield deal == null ? null : new CurrentSubject(
                    "deal", deal.getId(), label(deal.getName(), deal.getId()));
            }
            default -> null;
        };
    }

    private static String currentState(RelationshipSignal signal, Instant now) {
        if (signal.getDisposition() == null) {
            return "active";
        }
        if ("dismissed".equals(signal.getDisposition())
                && !signal.getSourceStateHash().equals(signal.getDismissedSourceHash())) {
            return "active";
        }
        if ("snoozed".equals(signal.getDisposition())
                && (signal.getSnoozeUntil() == null
                    || !instant(signal.getSnoozeUntil()).isAfter(now))) {
            return "active";
        }
        return signal.getDisposition();
    }

    private static boolean isStale(RelationshipSignal signal, Instant now) {
        return instant(signal.getEvidenceAsOf()).isBefore(now.minus(STALE_AFTER));
    }

    private static VersionToken versionToken(String value) {
        if (value == null || !value.matches("\\d+:\\d+")) {
            throw new BadRequestException("Radar version is invalid");
        }
        int separator = value.indexOf(':');
        try {
            return new VersionToken(
                Long.parseLong(value.substring(0, separator)),
                Long.parseLong(value.substring(separator + 1)));
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Radar version is invalid");
        }
    }

    /**
     * Validates the optional record scope, returning null when the caller wants the whole feed.
     * Both parameters are required together so a half-supplied scope cannot silently widen the read.
     */
    private static String validatedSubjectType(
            @Nullable String subjectType, @Nullable Integer subjectId) {
        String normalized = subjectType == null ? "" : subjectType.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() && subjectId == null) {
            return null;
        }
        if (normalized.isEmpty() || subjectId == null) {
            throw new BadRequestException("subjectType and subjectId must be supplied together");
        }
        if (!SUBJECT_TYPES.contains(normalized)) {
            throw new BadRequestException("subjectType must be one of: person, company, deal");
        }
        if (subjectId < 1) {
            throw new BadRequestException("subjectId must be a positive integer");
        }
        return normalized;
    }

    private static Set<String> validatedFamilies(List<String> filters) {
        Set<String> values = new LinkedHashSet<>(filters == null ? List.of() : filters);
        if (!FAMILIES.containsAll(values)) {
            throw new BadRequestException("Unknown Radar family");
        }
        return values;
    }

    private static Set<String> validatedStates(List<String> filters) {
        Set<String> values = new LinkedHashSet<>(filters == null ? List.of() : filters);
        if (!STATES.containsAll(values)) {
            throw new BadRequestException("Unknown Radar state");
        }
        return values;
    }

    private static String normalizeQuery(@Nullable String query) {
        return query == null || query.isBlank()
            ? null
            : query.trim().toLowerCase(Locale.ROOT);
    }

    private static List<RadarResponseDto.FamilyStatus> familyStatuses(
            List<RelationshipSignalFamilyState> states) {
        Map<String, RelationshipSignalFamilyState> byFamily = new LinkedHashMap<>();
        states.forEach(state -> byFamily.put(state.getFamily(), state));
        List<RadarResponseDto.FamilyStatus> result = new ArrayList<>();
        for (String family : FAMILIES) {
            RelationshipSignalFamilyState state = byFamily.get(family);
            result.add(state == null
                ? new RadarResponseDto.FamilyStatus(
                    family, "unavailable", null, null, null, "not_generated")
                : new RadarResponseDto.FamilyStatus(
                    family,
                    state.getStatus(),
                    instant(state.getLastAttemptAt()),
                    instant(state.getLastSuccessAt()),
                    instant(state.getEvidenceAsOf()),
                    state.getErrorCode()));
        }
        return List.copyOf(result);
    }

    private static Map<String, Integer> counts(
            List<RadarResponseDto.Signal> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("total", items.size());
        for (String family : FAMILIES) {
            counts.put(family, 0);
        }
        for (String state : STATES) {
            counts.put(state, 0);
        }
        for (RadarResponseDto.Signal item : items) {
            counts.computeIfPresent(item.family(), (key, count) -> count + 1);
            counts.computeIfPresent(item.state(), (key, count) -> count + 1);
        }
        return Map.copyOf(counts);
    }

    private static String href(String type, int id) {
        return switch (type) {
            case "person" -> "/records/contacts/" + id;
            case "company" -> "/records/companies/" + id;
            case "deal" -> "/records/deals/" + id;
            default -> throw new IllegalArgumentException("Unknown Radar subject type");
        };
    }

    private static String label(String value, int id) {
        return value == null || value.isBlank() ? "#" + id : value.trim();
    }

    private static Instant instant(@Nullable LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private record CurrentSubject(String type, int id, String label) {
    }

    private record VersionToken(long signalVersion, long stateVersion) {
    }

    private record RankPayload(
            String rule, List<RadarResponseDto.RankFactor> factors) {
        private RankPayload {
            factors = List.copyOf(factors);
        }
    }
}
