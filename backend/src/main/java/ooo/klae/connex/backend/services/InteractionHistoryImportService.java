package ooo.klae.connex.backend.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.HistoryImportProvenance;
import ooo.klae.connex.backend.beans.HistoryImportWrite;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.DuplicateCandidateDto;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.HistoryImportColumnMapping;
import ooo.klae.connex.backend.dto.HistoryImportPreviewResult;
import ooo.klae.connex.backend.dto.HistoryImportRequest;
import ooo.klae.connex.backend.dto.HistoryImportResult;
import ooo.klae.connex.backend.dto.HistoryImportRowAnalysis;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.RowError;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Proof-bound, idempotent CSV import of historical activities, notes, and tasks.
 */
@Service
@RequiredArgsConstructor
public class InteractionHistoryImportService {

    private static final String VERSION = "connex-history-import-v1";
    private static final String SOURCE_SYSTEM = "csv";
    private static final String READY = "ready";
    private static final String ALREADY_IMPORTED = "already_imported";
    private static final String NEEDS_REVIEW = "needs_review";
    private static final String INVALID = "invalid";
    private static final String TODO = "todo";
    private static final String DONE = "done";
    private static final int BATCH_SIZE = 250;
    private static final DateTimeFormatter MYSQL_UTC =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> COMMON_FIELDS = Set.of(
        "occurredAt", "participantEmail", "participantPhone", "sourceId");

    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final DuplicatePreflightService duplicatePreflightService;
    private final DuplicateDecisionLockService duplicateDecisionLockService;
    private final MatchingService matchingService;
    private final PersonMapper personMapper;
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final NotificationReconciliationService notificationReconciliationService;
    private final AuditService auditService;
    private final Clock clock;

    /** Previews an activity-history import using {@code ACTIVITY_CREATE}. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.ACTIVITY_CREATE)
    public HistoryImportPreviewResult previewActivities(HistoryImportRequest request) {
        return preview(Kind.ACTIVITY, request);
    }

    /** Commits an activity-history import using {@code ACTIVITY_CREATE}. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.ACTIVITY_CREATE)
    public HistoryImportResult commitActivities(HistoryImportRequest request) {
        return commit(Kind.ACTIVITY, request);
    }

    /** Previews a note-history import using {@code NOTE_CREATE}. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.NOTE_CREATE)
    public HistoryImportPreviewResult previewNotes(HistoryImportRequest request) {
        return preview(Kind.NOTE, request);
    }

    /** Commits a note-history import using {@code NOTE_CREATE}. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.NOTE_CREATE)
    public HistoryImportResult commitNotes(HistoryImportRequest request) {
        return commit(Kind.NOTE, request);
    }

    /** Previews a task-history import using {@code TASK_CREATE}. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.TASK_CREATE)
    public HistoryImportPreviewResult previewTasks(HistoryImportRequest request) {
        return preview(Kind.TASK, request);
    }

    /** Commits a task-history import using {@code TASK_CREATE}. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.TASK_CREATE)
    public HistoryImportResult commitTasks(HistoryImportRequest request) {
        return commit(Kind.TASK, request);
    }

    private HistoryImportPreviewResult preview(
            Kind kind,
            HistoryImportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Mapping mapping = validateRequest(kind, request);
        String reviewContext = reviewContext(kind, workspaceId, request);
        List<PlanRow> plan = parseRows(kind, request, mapping);
        List<PlanRow> matchable = matchableRows(plan);
        DuplicatePreflightService.ImportPreviewSession session =
            duplicatePreflightService.beginImportPreview(
                preflightRequests(matchable), List.of(), reviewContext);
        try {
            resolveParticipants(
                workspaceId,
                request.getLinks(),
                matchable,
                session.responses());
            applyProvenance(kind, workspaceId, plan);
            duplicatePreflightService.completeImportPreview(
                session, decisionFingerprint(plan));
            return previewResult(plan, session.reviewProof());
        } catch (RuntimeException exception) {
            duplicatePreflightService.cancelImportPreview(session.reviewProof());
            throw exception;
        }
    }

    private HistoryImportResult commit(
            Kind kind,
            HistoryImportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String reviewContext = reviewContext(kind, workspaceId, request);
        DuplicatePreflightService.ImportCommitAdmission admission =
            duplicatePreflightService.claimImportCommit(
                request.getDuplicateReviewProof(), reviewContext);
        duplicateDecisionLockService.lockCurrentOrganization();
        Mapping mapping = validateRequest(kind, request);
        List<PlanRow> plan = parseRows(kind, request, mapping);
        List<PlanRow> matchable = matchableRows(plan);
        DuplicatePreflightService.ImportCommitSession session =
            duplicatePreflightService.beginImportCommit(
                preflightRequests(matchable), List.of(), reviewContext, admission);
        resolveParticipants(
            workspaceId,
            request.getLinks(),
            matchable,
            session.responses());
        applyProvenance(kind, workspaceId, plan);
        duplicatePreflightService.completeImportCommit(
            session, decisionFingerprint(plan));
        lockResolvedPeople(workspaceId, plan);

        List<PlanRow> creates = plan.stream()
            .filter(row -> READY.equals(row.status))
            .toList();
        int actorId = authService.getCurrentUser().getId();
        if (!creates.isEmpty() && kind == Kind.TASK) {
            lockTaskBoard(workspaceId);
        }
        Instant evaluationInstant = clock.instant();
        NotificationReconciliationService.HistoricalExpectationSnapshot before = null;
        if (!creates.isEmpty()) {
            before =
                notificationReconciliationService.historicalExpectationSnapshot(
                    workspaceId, evaluationInstant);
        }
        List<HistoryImportWrite> writes =
            writes(kind, workspaceId, actorId, creates);
        insertBatches(kind, workspaceId, writes);
        if (!writes.isEmpty()) {
            NotificationReconciliationService.HistoricalBaselineScope scope =
                baselineScope(kind, workspaceId, writes);
            NotificationReconciliationService.HistoricalExpectationSnapshot after =
                notificationReconciliationService.historicalExpectationSnapshot(
                    workspaceId, evaluationInstant);
            NotificationReconciliationService.HistoricalExpectationSnapshot counterfactual =
                notificationReconciliationService.historicalExpectationSnapshot(
                    workspaceId, evaluationInstant, scope);
            if (!scope.sameRelevantExpectations(
                    Objects.requireNonNull(before), counterfactual)) {
                throw new ConflictException(
                    "Notification inputs changed; preview the import again");
            }
            notificationReconciliationService.persistHistoricalBaselines(
                workspaceId,
                before,
                after,
                scope,
                importRunId(request.getDuplicateReviewProof()));
            audit(kind, writes.size(), alreadyImportedCount(plan), failedRows(plan).size());
        }
        return new HistoryImportResult(
            writes.size(),
            alreadyImportedCount(plan),
            failedRows(plan));
    }

    private Mapping validateRequest(
            Kind kind,
            HistoryImportRequest request) {
        if (request == null || request.getRows() == null || request.getMapping() == null) {
            throw new BadRequestException("Import rows and mapping are required");
        }
        if (request.getRows().size() > 5000) {
            throw new BadRequestException("At most 5000 interaction-history rows may be imported");
        }
        if (request.getMapping().isEmpty() || request.getMapping().size() > 64) {
            throw new BadRequestException("Between 1 and 64 column mappings are required");
        }
        Set<String> allowed = new LinkedHashSet<>(COMMON_FIELDS);
        allowed.addAll(kind.fields);
        Map<String, String> byColumn = new LinkedHashMap<>();
        Set<String> mappedFields = new LinkedHashSet<>();
        for (HistoryImportColumnMapping entry : request.getMapping()) {
            if (entry == null
                    || entry.column() == null
                    || entry.column().isBlank()
                    || entry.field() == null
                    || entry.field().isBlank()) {
                throw new BadRequestException("Every history column mapping is required");
            }
            if (!allowed.contains(entry.field())) {
                throw new BadRequestException(
                    "Unsupported " + kind.entityType + " history field: " + entry.field());
            }
            if (byColumn.put(entry.column(), entry.field()) != null) {
                throw new BadRequestException(
                    "CSV column is mapped more than once: " + entry.column());
            }
            if (!mappedFields.add(entry.field())) {
                throw new BadRequestException(
                    "History field is mapped more than once: " + entry.field());
            }
        }
        if (!mappedFields.contains("occurredAt")
                || !mappedFields.contains(kind.requiredField)) {
            throw new BadRequestException(
                "occurredAt and " + kind.requiredField + " mappings are required");
        }
        if (!mappedFields.contains("participantEmail")
                && !mappedFields.contains("participantPhone")) {
            throw new BadRequestException(
                "participantEmail or participantPhone must be mapped");
        }
        Map<Integer, Integer> links =
            request.getLinks() == null ? Map.of() : request.getLinks();
        if (links.size() > request.getRows().size()) {
            throw new BadRequestException("Manual links exceed the imported row count");
        }
        for (Map.Entry<Integer, Integer> link : links.entrySet()) {
            if (link.getKey() == null
                    || link.getKey() < 0
                    || link.getKey() >= request.getRows().size()
                    || link.getValue() == null
                    || link.getValue() <= 0) {
                throw new BadRequestException("Manual history links contain an invalid row or person");
            }
        }
        return new Mapping(Map.copyOf(byColumn));
    }

    private List<PlanRow> parseRows(
            Kind kind,
            HistoryImportRequest request,
            Mapping mapping) {
        List<PlanRow> plan = new ArrayList<>(request.getRows().size());
        for (int index = 0; index < request.getRows().size(); index++) {
            Map<String, String> source = request.getRows().get(index);
            if (source == null) {
                throw new BadRequestException("History import rows cannot be null");
            }
            PlanRow row = new PlanRow(index);
            row.occurredAt = parseOccurredAt(
                value(source, mapping, "occurredAt"), row.errors);
            parseParticipant(source, mapping, row);
            parseSourceId(source, mapping, row);
            switch (kind) {
                case ACTIVITY -> parseActivity(source, mapping, row);
                case NOTE -> parseNote(source, mapping, row);
                case TASK -> parseTask(source, mapping, row);
            }
            if (row.errors.isEmpty()) {
                row.semanticValues = semanticValues(kind, row);
            } else {
                row.status = INVALID;
            }
            plan.add(row);
        }
        return plan;
    }

    private String parseOccurredAt(
            String raw,
            List<String> errors) {
        String candidate = trimmed(raw);
        if (candidate == null) {
            errors.add("occurredAt is required");
            return null;
        }
        try {
            LocalDateTime parsed;
            if (hasOffset(candidate)) {
                OffsetDateTime offset = OffsetDateTime.parse(
                    candidate, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                if (!ZoneOffset.UTC.equals(offset.getOffset())) {
                    errors.add("occurredAt offset must be UTC");
                    return null;
                }
                parsed = offset.toLocalDateTime();
            } else {
                String iso = candidate.length() > 10 && candidate.charAt(10) == ' '
                    ? candidate.substring(0, 10) + "T" + candidate.substring(11)
                    : candidate;
                parsed = LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            if (parsed.getYear() < 1000 || parsed.getYear() > 9999) {
                errors.add("occurredAt is outside the MySQL datetime range");
                return null;
            }
            LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant(), ZoneOffset.UTC);
            if (parsed.isAfter(now)) {
                errors.add("occurredAt cannot be in the future");
                return null;
            }
            LocalDateTime canonical = parsed.truncatedTo(ChronoUnit.SECONDS);
            return canonical.format(MYSQL_UTC);
        } catch (DateTimeException exception) {
            errors.add("occurredAt must be a UTC ISO or local datetime");
            return null;
        }
    }

    private void parseParticipant(
            Map<String, String> source,
            Mapping mapping,
            PlanRow row) {
        String email = trimmed(value(source, mapping, "participantEmail"));
        String phone = trimmed(value(source, mapping, "participantPhone"));
        if (email == null && phone == null) {
            row.errors.add("participantEmail or participantPhone is required");
            return;
        }
        if (email != null) {
            row.normalizedEmail = matchingService
                .normalizeIdentifier(IdentityKind.EMAIL, email)
                .orElse(null);
            if (row.normalizedEmail == null) {
                row.errors.add("participantEmail is invalid");
            }
        }
        if (phone != null) {
            row.normalizedPhone = matchingService
                .normalizeIdentifier(IdentityKind.PHONE, phone)
                .orElse(null);
            if (row.normalizedPhone == null) {
                row.errors.add("participantPhone is invalid");
            }
        }
    }

    private static void parseSourceId(
            Map<String, String> source,
            Mapping mapping,
            PlanRow row) {
        String raw = value(source, mapping, "sourceId");
        if (raw == null || raw.isBlank()) {
            return;
        }
        if (raw.codePointCount(0, raw.length()) > 512) {
            row.errors.add("sourceId must be at most 512 characters");
            return;
        }
        row.sourceId = raw;
    }

    private static void parseActivity(
            Map<String, String> source,
            Mapping mapping,
            PlanRow row) {
        row.subject = required(
            value(source, mapping, "subject"), "subject", 255, row.errors);
        row.type = trimmed(value(source, mapping, "type"));
        if (row.type == null) {
            row.type = "other";
        } else if (row.type.codePointCount(0, row.type.length()) > 32) {
            row.errors.add("type must be at most 32 characters");
        }
        row.notes = optional(value(source, mapping, "notes"), 16_000, row.errors);
    }

    private static void parseNote(
            Map<String, String> source,
            Mapping mapping,
            PlanRow row) {
        row.content = required(
            value(source, mapping, "content"), "content", 50_000, row.errors);
        row.title = optional(value(source, mapping, "title"), 255, row.errors);
    }

    private static void parseTask(
            Map<String, String> source,
            Mapping mapping,
            PlanRow row) {
        row.description = required(
            value(source, mapping, "description"), "description", 1_000, row.errors);
        String dueDate = trimmed(value(source, mapping, "dueDate"));
        if (dueDate != null) {
            try {
                if (!dueDate.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                    throw new DateTimeException("Invalid date shape");
                }
                LocalDate parsed = LocalDate.parse(
                    dueDate, DateTimeFormatter.ISO_LOCAL_DATE);
                if (parsed.getYear() < 1000 || parsed.getYear() > 9999) {
                    throw new DateTimeException("Date is outside the MySQL range");
                }
                row.dueDate = parsed.toString();
            } catch (DateTimeException exception) {
                row.errors.add("dueDate must use YYYY-MM-DD");
            }
        }
        String completed = trimmed(value(source, mapping, "completed"));
        if (completed == null) {
            row.completed = false;
        } else if ("true".equalsIgnoreCase(completed)) {
            row.completed = true;
        } else if ("false".equalsIgnoreCase(completed)) {
            row.completed = false;
        } else {
            row.errors.add("completed must be true or false");
        }
    }

    private static String required(
            String raw,
            String field,
            int maxCodePoints,
            List<String> errors) {
        String value = trimmed(raw);
        if (value == null) {
            errors.add(field + " is required");
            return null;
        }
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            errors.add(field + " must be at most " + maxCodePoints + " characters");
        }
        return value;
    }

    private static String optional(
            String raw,
            Integer maxCodePoints,
            List<String> errors) {
        String value = trimmed(raw);
        if (value != null
                && maxCodePoints != null
                && value.codePointCount(0, value.length()) > maxCodePoints) {
            errors.add("Optional field must be at most " + maxCodePoints + " characters");
        }
        return value;
    }

    private static List<String> semanticValues(
            Kind kind,
            PlanRow row) {
        List<String> values = new ArrayList<>();
        values.add(row.occurredAt);
        values.add(row.normalizedEmail);
        values.add(row.normalizedPhone);
        switch (kind) {
            case ACTIVITY -> {
                values.add(row.subject);
                values.add(row.type);
                values.add(row.notes);
            }
            case NOTE -> {
                values.add(row.content);
                values.add(row.title);
                values.add("workspace");
            }
            case TASK -> {
                values.add(row.description);
                values.add(row.dueDate);
                values.add(Boolean.toString(row.completed));
                values.add(row.completed ? DONE : TODO);
            }
        }
        return Collections.unmodifiableList(values);
    }

    private static List<PlanRow> matchableRows(List<PlanRow> plan) {
        return plan.stream()
            .filter(row -> row.errors.isEmpty())
            .toList();
    }

    private static List<PersonDuplicatePreflightRequest> preflightRequests(
            List<PlanRow> plan) {
        return plan.stream()
            .map(row -> new PersonDuplicatePreflightRequest(
                null,
                row.normalizedEmail == null ? List.of() : List.of(row.normalizedEmail),
                row.normalizedPhone == null ? List.of() : List.of(row.normalizedPhone)))
            .toList();
    }

    private void resolveParticipants(
            int workspaceId,
            Map<Integer, Integer> requestedLinks,
            List<PlanRow> plan,
            List<DuplicatePreflightResponse> responses) {
        if (plan.size() != responses.size()) {
            throw new IllegalStateException("Duplicate preflight response count changed");
        }
        Map<Integer, Integer> links =
            requestedLinks == null ? Map.of() : requestedLinks;
        Map<Integer, Person> manualTargets =
            manualTargets(workspaceId, links.values());
        for (int index = 0; index < plan.size(); index++) {
            PlanRow row = plan.get(index);
            DuplicatePreflightResponse response = responses.get(index);
            row.candidates = response.candidates();
            Integer manualId = links.get(row.rowIndex);
            if (manualId != null) {
                applyManualDecision(row, response, manualId, manualTargets.get(manualId));
            } else {
                applyAutomaticDecision(row, response);
            }
        }
    }

    private Map<Integer, Person> manualTargets(
            int workspaceId,
            java.util.Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Integer> distinct = ids.stream().distinct().sorted().toList();
        Map<Integer, Person> targets = new HashMap<>();
        for (Person person : personMapper.getByIds(workspaceId, distinct)) {
            if (person.getWorkspaceId() == workspaceId && processable(person)) {
                targets.put(person.getId(), person);
            }
        }
        return targets;
    }

    private static void applyManualDecision(
            PlanRow row,
            DuplicatePreflightResponse response,
            int manualId,
            Person target) {
        if (target == null) {
            needsReview(row, "Selected participant is unavailable in this workspace");
            return;
        }
        boolean conflictingStrongCandidate = response.candidates().stream()
            .anyMatch(candidate ->
                candidate.strength() == DuplicateMatchStrength.STRONG
                    && candidate.recordId() != manualId);
        if (response.truncated() || conflictingStrongCandidate) {
            needsReview(row, "Selected participant conflicts with supplied identity evidence");
            return;
        }
        ready(row, target.getId(), target.getName());
    }

    private static void applyAutomaticDecision(
            PlanRow row,
            DuplicatePreflightResponse response) {
        List<DuplicateCandidateDto> strongCandidates = response.candidates().stream()
            .filter(candidate -> candidate.strength() == DuplicateMatchStrength.STRONG)
            .toList();
        if (!response.truncated()
                && strongCandidates.size() == 1
                && strongCandidates.getFirst().ownedByActiveWorkspace()) {
            DuplicateCandidateDto candidate = strongCandidates.getFirst();
            ready(row, candidate.recordId(), candidate.name());
            return;
        }
        needsReview(
            row,
            response.candidates().isEmpty()
                ? "No owned participant matched the supplied identities"
                : "Participant identities require explicit review");
    }

    private void applyProvenance(
            Kind kind,
            int workspaceId,
            List<PlanRow> plan) {
        Map<String, List<PlanRow>> byKey = new LinkedHashMap<>();
        Map<List<String>, Integer> semanticOccurrences = new HashMap<>();
        for (PlanRow row : plan) {
            if (row.sourceId == null && !row.semanticValues.isEmpty()) {
                row.semanticOccurrence = semanticOccurrences.merge(
                    row.semanticValues, 1, Integer::sum) - 1;
            }
        }
        for (PlanRow row : plan) {
            if (!READY.equals(row.status)) {
                continue;
            }
            row.historyImportKey = historyImportKey(kind, row);
            row.historyPayloadHash = historyPayloadHash(kind, row);
            byKey.computeIfAbsent(
                row.historyImportKey, ignored -> new ArrayList<>()).add(row);
        }
        List<PlanRow> representatives = new ArrayList<>();
        for (List<PlanRow> group : byKey.values()) {
            long payloads = group.stream()
                .map(row -> row.historyPayloadHash)
                .distinct()
                .count();
            if (payloads > 1) {
                group.forEach(row ->
                    invalid(row, "History import key maps to conflicting payloads"));
                continue;
            }
            representatives.add(group.getFirst());
            for (int index = 1; index < group.size(); index++) {
                alreadyImported(group.get(index));
            }
        }
        Map<String, String> existing = existingProvenance(
            kind,
            workspaceId,
            representatives.stream()
                .filter(row -> READY.equals(row.status))
                .map(row -> row.historyImportKey)
                .toList());
        for (PlanRow representative : representatives) {
            if (!READY.equals(representative.status)) {
                continue;
            }
            String payloadHash = existing.get(representative.historyImportKey);
            if (payloadHash == null) {
                continue;
            }
            List<PlanRow> group = byKey.get(representative.historyImportKey);
            if (payloadHash.equals(representative.historyPayloadHash)) {
                group.forEach(InteractionHistoryImportService::alreadyImported);
            } else {
                group.forEach(row ->
                    invalid(row, "History import key already exists with different data"));
            }
        }
    }

    private Map<String, String> existingProvenance(
            Kind kind,
            int workspaceId,
            List<String> keys) {
        Map<String, String> existing = new HashMap<>();
        List<String> distinct = keys.stream().distinct().toList();
        for (int offset = 0; offset < distinct.size(); offset += BATCH_SIZE) {
            List<String> batch = distinct.subList(
                offset, Math.min(offset + BATCH_SIZE, distinct.size()));
            List<HistoryImportProvenance> rows =
                findHistoryImports(kind, workspaceId, batch);
            for (HistoryImportProvenance row : rows) {
                existing.put(
                    row.getHistoryImportKey(),
                    row.getHistoryPayloadHash());
            }
        }
        return existing;
    }

    private List<HistoryImportProvenance> findHistoryImports(
            Kind kind,
            int workspaceId,
            List<String> keys) {
        return switch (kind) {
            case ACTIVITY -> activityMapper.findHistoryImports(workspaceId, keys);
            case NOTE -> noteMapper.findHistoryImports(workspaceId, keys);
            case TASK -> taskMapper.findHistoryImports(workspaceId, keys);
        };
    }

    private void lockResolvedPeople(
            int workspaceId,
            List<PlanRow> plan) {
        TreeSet<Integer> ids = new TreeSet<>();
        for (PlanRow row : plan) {
            if ((READY.equals(row.status) || ALREADY_IMPORTED.equals(row.status))
                    && row.participantId != null) {
                ids.add(row.participantId);
            }
        }
        for (int personId : ids) {
            Person person =
                personMapper.getOwnedPersonByIdForUpdate(workspaceId, personId);
            if (!processable(person) || person.getArchivedAt() != null) {
                throw new ConflictException(
                    "Participant decisions changed; preview the import again");
            }
        }
    }

    private void lockTaskBoard(int workspaceId) {
        Integer isolation =
            TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || isolation == null
                || isolation != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Interaction-history task imports require READ_COMMITTED isolation");
        }
        taskMapper.lockTaskBoard(workspaceId);
    }

    private List<HistoryImportWrite> writes(
            Kind kind,
            int workspaceId,
            int actorId,
            List<PlanRow> creates) {
        int todoPosition = 0;
        int donePosition = 0;
        if (kind == Kind.TASK && !creates.isEmpty()) {
            todoPosition = taskMapper.nextTaskPosition(workspaceId, TODO);
            donePosition = taskMapper.nextTaskPosition(workspaceId, DONE);
        }
        List<HistoryImportWrite> writes = new ArrayList<>(creates.size());
        for (PlanRow row : creates) {
            HistoryImportWrite write = new HistoryImportWrite();
            write.setWorkspaceId(workspaceId);
            write.setPersonId(Objects.requireNonNull(row.participantId));
            write.setActorId(actorId);
            write.setOccurredAt(row.occurredAt);
            write.setType(row.type);
            write.setSubject(row.subject);
            write.setNotes(row.notes);
            write.setContent(row.content);
            write.setTitle(row.title);
            write.setDescription(row.description);
            write.setDueDate(row.dueDate);
            write.setCompleted(row.completed);
            write.setStatus(row.completed ? DONE : TODO);
            if (kind == Kind.TASK) {
                write.setPosition(row.completed ? donePosition++ : todoPosition++);
            }
            write.setHistoryImportKey(row.historyImportKey);
            write.setHistoryPayloadHash(row.historyPayloadHash);
            write.setHistorySourceId(row.sourceId);
            write.setHistorySourceRowRef("csv-row:" + (row.rowIndex + 1));
            writes.add(write);
        }
        return writes;
    }

    private void insertBatches(
            Kind kind,
            int workspaceId,
            List<HistoryImportWrite> writes) {
        for (int offset = 0; offset < writes.size(); offset += BATCH_SIZE) {
            List<HistoryImportWrite> batch = writes.subList(
                offset, Math.min(offset + BATCH_SIZE, writes.size()));
            switch (kind) {
                case ACTIVITY -> activityMapper.insertHistoryBatch(workspaceId, batch);
                case NOTE -> noteMapper.insertHistoryBatch(workspaceId, batch);
                case TASK -> taskMapper.insertHistoryBatch(workspaceId, batch);
            }
        }
    }

    private NotificationReconciliationService.HistoricalBaselineScope baselineScope(
            Kind kind,
            int workspaceId,
            List<HistoryImportWrite> writes) {
        Set<Integer> personIds = writes.stream()
            .map(HistoryImportWrite::getPersonId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> entityIds = new LinkedHashSet<>();
        List<String> keys = writes.stream()
            .map(HistoryImportWrite::getHistoryImportKey)
            .toList();
        for (int offset = 0; offset < keys.size(); offset += BATCH_SIZE) {
            List<HistoryImportProvenance> rows = findHistoryImports(
                kind,
                workspaceId,
                keys.subList(offset, Math.min(offset + BATCH_SIZE, keys.size())));
            rows.stream()
                .map(HistoryImportProvenance::getEntityId)
                .forEach(entityIds::add);
        }
        if (entityIds.size() != writes.size()) {
            throw new IllegalStateException(
                "Imported interaction provenance could not be resolved");
        }
        return switch (kind) {
            case ACTIVITY ->
                new NotificationReconciliationService.HistoricalBaselineScope(
                    personIds, entityIds, Set.of(), Set.of());
            case NOTE ->
                new NotificationReconciliationService.HistoricalBaselineScope(
                    personIds, Set.of(), entityIds, Set.of());
            case TASK ->
                new NotificationReconciliationService.HistoricalBaselineScope(
                    personIds, Set.of(), Set.of(), entityIds);
        };
    }

    private void audit(
            Kind kind,
            int created,
            int skipped,
            int failed) {
        auditService.record(
            "import.history." + kind.entityType,
            kind.entityType,
            null,
            "CSV history import",
            "Imported historical " + kind.entityType + " rows",
            Map.of(
                "created", created,
                "skipped", skipped,
                "failed", failed,
                "sourceSystem", SOURCE_SYSTEM));
    }

    private static HistoryImportPreviewResult previewResult(
            List<PlanRow> plan,
            String proof) {
        List<HistoryImportRowAnalysis> rows = plan.stream()
            .map(row -> new HistoryImportRowAnalysis(
                row.rowIndex,
                row.status,
                row.participantId,
                row.participantLabel,
                row.candidates,
                row.errors))
            .toList();
        return new HistoryImportPreviewResult(
            plan.size(),
            count(plan, READY),
            count(plan, ALREADY_IMPORTED),
            count(plan, NEEDS_REVIEW),
            count(plan, INVALID),
            rows,
            proof);
    }

    private static int count(
            List<PlanRow> plan,
            String status) {
        return Math.toIntExact(plan.stream()
            .filter(row -> status.equals(row.status))
            .count());
    }

    private static int alreadyImportedCount(List<PlanRow> plan) {
        return count(plan, ALREADY_IMPORTED);
    }

    private static List<RowError> failedRows(List<PlanRow> plan) {
        return plan.stream()
            .filter(row -> INVALID.equals(row.status)
                || NEEDS_REVIEW.equals(row.status))
            .map(row -> new RowError(
                row.rowIndex,
                String.join("; ", row.errors)))
            .toList();
    }

    private static String reviewContext(
            Kind kind,
            int workspaceId,
            HistoryImportRequest request) {
        MessageDigest digest = sha256();
        updateDigest(digest, VERSION);
        updateDigest(digest, kind.entityType);
        updateDigest(digest, Integer.toString(workspaceId));
        List<Map<String, String>> rows =
            request == null || request.getRows() == null ? List.of() : request.getRows();
        updateDigest(digest, Integer.toString(rows.size()));
        for (Map<String, String> row : rows) {
            updateDigest(digest, "row");
            if (row == null) {
                updateDigest(digest, null);
                continue;
            }
            updateDigest(digest, Integer.toString(row.size()));
            for (Map.Entry<String, String> entry : new TreeMap<>(row).entrySet()) {
                updateDigest(digest, entry.getKey());
                updateDigest(digest, entry.getValue());
            }
        }
        List<HistoryImportColumnMapping> mapping =
            request == null || request.getMapping() == null
                ? List.of()
                : request.getMapping();
        updateDigest(digest, Integer.toString(mapping.size()));
        for (HistoryImportColumnMapping entry : mapping) {
            updateDigest(digest, entry == null ? null : entry.column());
            updateDigest(digest, entry == null ? null : entry.field());
        }
        Map<Integer, Integer> links =
            request == null || request.getLinks() == null
                ? Map.of()
                : request.getLinks();
        updateDigest(digest, Integer.toString(links.size()));
        for (Map.Entry<Integer, Integer> entry : new TreeMap<>(links).entrySet()) {
            updateDigest(digest, Objects.toString(entry.getKey(), null));
            updateDigest(digest, Objects.toString(entry.getValue(), null));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String decisionFingerprint(List<PlanRow> plan) {
        MessageDigest digest = sha256();
        updateDigest(digest, VERSION);
        updateDigest(digest, "decision");
        for (PlanRow row : plan) {
            updateDigest(digest, Integer.toString(row.rowIndex));
            updateDigest(digest, row.status);
            updateDigest(
                digest,
                row.participantId == null
                    ? null
                    : Integer.toString(row.participantId));
            updateDigest(digest, row.historyImportKey);
            updateDigest(digest, row.historyPayloadHash);
            for (String error : row.errors) {
                updateDigest(digest, error);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String historyImportKey(
            Kind kind,
            PlanRow row) {
        List<String> values = new ArrayList<>();
        values.add(VERSION);
        values.add(kind.entityType);
        values.add(SOURCE_SYSTEM);
        if (row.sourceId != null) {
            values.add("source-id");
            values.add(row.sourceId);
        } else {
            values.add("semantic");
            values.addAll(row.semanticValues);
            values.add(Integer.toString(row.semanticOccurrence));
        }
        return hash(values);
    }

    private static String historyPayloadHash(
            Kind kind,
            PlanRow row) {
        List<String> values = new ArrayList<>();
        values.add(VERSION);
        values.add(kind.entityType);
        values.add("payload");
        values.addAll(row.semanticValues);
        values.add(Integer.toString(Objects.requireNonNull(row.participantId)));
        return hash(values);
    }

    private static String importRunId(String proof) {
        return hash(List.of(VERSION, "import-run", proof));
    }

    private static String hash(List<String> values) {
        MessageDigest digest = sha256();
        for (String value : values) {
            updateDigest(digest, value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateDigest(
            MessageDigest digest,
            String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String value(
            Map<String, String> source,
            Mapping mapping,
            String field) {
        for (Map.Entry<String, String> entry : mapping.byColumn.entrySet()) {
            if (field.equals(entry.getValue())) {
                return source.get(entry.getKey());
            }
        }
        return null;
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasOffset(String value) {
        return value.endsWith("Z")
            || value.matches(".*[+-]\\d{2}:\\d{2}$");
    }

    private static boolean processable(Person person) {
        return person != null
            && person.getArchivedAt() == null
            && person.getSuspendedAt() == null
            && person.getProvisionCeasedAt() == null;
    }

    private static void ready(
            PlanRow row,
            int participantId,
            String participantLabel) {
        row.status = READY;
        row.participantId = participantId;
        row.participantLabel = participantLabel;
        row.errors.clear();
    }

    private static void needsReview(
            PlanRow row,
            String reason) {
        row.status = NEEDS_REVIEW;
        row.participantId = null;
        row.participantLabel = null;
        row.errors.clear();
        row.errors.add(reason);
    }

    private static void alreadyImported(PlanRow row) {
        row.status = ALREADY_IMPORTED;
        row.errors.clear();
    }

    private static void invalid(
            PlanRow row,
            String reason) {
        row.status = INVALID;
        row.errors.clear();
        row.errors.add(reason);
    }

    private enum Kind {
        ACTIVITY(
            "activity",
            "subject",
            Set.of("subject", "type", "notes")),
        NOTE(
            "note",
            "content",
            Set.of("content", "title")),
        TASK(
            "task",
            "description",
            Set.of("description", "dueDate", "completed"));

        private final String entityType;
        private final String requiredField;
        private final Set<String> fields;

        Kind(
                String entityType,
                String requiredField,
                Set<String> fields) {
            this.entityType = entityType;
            this.requiredField = requiredField;
            this.fields = fields;
        }
    }

    private record Mapping(
            Map<String, String> byColumn) {
    }

    private static final class PlanRow {
        private final int rowIndex;
        private final List<String> errors = new ArrayList<>();
        private String status;
        private String occurredAt;
        private String normalizedEmail;
        private String normalizedPhone;
        private String sourceId;
        private String type;
        private String subject;
        private String notes;
        private String content;
        private String title;
        private String description;
        private String dueDate;
        private boolean completed;
        private List<String> semanticValues = List.of();
        private Integer participantId;
        private String participantLabel;
        private List<DuplicateCandidateDto> candidates = List.of();
        private String historyImportKey;
        private String historyPayloadHash;
        private int semanticOccurrence;

        private PlanRow(int rowIndex) {
            this.rowIndex = rowIndex;
        }
    }
}
