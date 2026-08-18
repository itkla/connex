package ooo.klae.connex.backend.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.BandCounts;
import ooo.klae.connex.backend.dto.DecayCounts;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.RelationshipEvidenceDto;
import ooo.klae.connex.backend.dto.RelationshipEvidenceDto.AttributionRule;
import ooo.klae.connex.backend.dto.RelationshipEvidenceDto.Contributor;
import ooo.klae.connex.backend.dto.RelationshipEvidenceDto.Coverage;
import ooo.klae.connex.backend.dto.RelationshipEvidenceDto.PrivateNoteCountScope;
import ooo.klae.connex.backend.dto.RelationshipEvidenceDto.SourceCounts;
import ooo.klae.connex.backend.dto.RelationshipEvidenceDto.SourceType;
import ooo.klae.connex.backend.dto.RelationshipEvidenceDto.SubjectType;
import ooo.klae.connex.backend.dto.RelationshipEvidenceDto.Totals;
import ooo.klae.connex.backend.dto.RelationshipEvidenceRowDto;
import ooo.klae.connex.backend.dto.RelationshipEvidenceTotalsDto;
import ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.TrendCounts;
import ooo.klae.connex.backend.dto.WarmthSummaryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.util.DateTimes;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/**
 * Computes relationship "temperature" (warmth) for contacts and companies on read.
 *
 * <p>This is the single source of truth for warmth across the app: the relationship map
 * colours nodes by band and the dashboard surfaces cooling relationships from the scores produced here.
 *
 * <p>Warmth is a recency-decayed, intent-weighted sum of logged interactions. Each touch
 * starts at a base weight (meeting &gt; call &gt; email &gt; note &gt; task) and decays by
 * half over time. The decayed sum is squashed into 0–100 so the score saturates rather than
 * growing without bound for very active relationships. The versioned formula and all tuning
 * parameters live in {@link RelationshipWarmthModel}.
 *
 * <p>Every read is workspace-scoped; the caller resolves the active workspace and passes it in.
 */
@Service
@RequiredArgsConstructor
public class ScoringService {
    private static final RelationshipWarmthModel WARMTH_MODEL = RelationshipWarmthModel.current();

    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final Clock clock;

    private static final int MAX_BATCH_CONTACTS = 1_000;
    private static final int MAX_BATCH_COMPANIES = 2_000;
    private static final int MAX_EVIDENCE_CONTRIBUTORS = 20;
    private static final int MAX_EVIDENCE_SOURCES = 100_000;
    private static final int MINIMUM_CONFIDENT_CONTRIBUTORS = 3;

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final RelationshipEvidenceTotalsDto EMPTY_EVIDENCE_TOTALS =
        new RelationshipEvidenceTotalsDto(0, 0.0, 0, 0, 0, 0.0, 0.0, null, 0);

    /** A single timestamped, intent-weighted interaction. */
    private record Touch(long epochMillis, double weight) {}

    private record SourceTouch(
        String kind,
        int id,
        String timestamp,
        String weight
    ) {}

    private static final String EMPTY_CONTACT_SOURCE_STATE_HASH =
        contactSourceStateHash(List.of());

    /** Contact and company temperatures derived from one workspace aggregate snapshot. */
    public record WorkspaceScores(
        List<RelationshipTemperatureDto> contacts,
        List<RelationshipTemperatureDto> companies
    ) {}

    /**
     * Scores every contact in the workspace as of now, including those with no activity (band "cold").
     */
    public List<RelationshipTemperatureDto> scoreContacts(int workspaceId) {
        Instant reference = scoringInstant(Instant.now(clock));
        return computeContactScores(workspaceId, reference, reference);
    }

    /** Scores all contacts and companies from compact aggregates computed by the database. */
    public WorkspaceScores scoreWorkspace(int workspaceId) {
        Instant now = scoringInstant(Instant.now(clock));
        LocalDateTime reference = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        return new WorkspaceScores(
            temperatures(personMapper.getRelationshipScoreAggregates(
                workspaceId, reference, WARMTH_MODEL.sqlParameters()), now),
            temperatures(companyMapper.getRelationshipScoreAggregates(
                workspaceId, reference, WARMTH_MODEL.sqlParameters()), now)
        );
    }

    public List<RelationshipTemperatureDto> coolingContacts(int workspaceId, int limit) {
        return scoreContacts(workspaceId).stream()
            .filter(temperature -> "cooling".equals(temperature.getTrend()))
            .sorted((left, right) -> Integer.compare(
                right.getDaysSinceTouch() == null ? 0 : right.getDaysSinceTouch(),
                left.getDaysSinceTouch() == null ? 0 : left.getDaysSinceTouch()))
            .limit(limit)
            .toList();
    }

    /**
     * Scores every contact in the workspace as of {@code asOf}: warmth is decayed against that
     * instant and only interactions logged on or before it are counted. Used by the time-travel
     * replay to reconstruct historical warmth — touches dated after {@code asOf} are excluded so a
     * future-dated interaction can never leak into a past frame.
     */
    public List<RelationshipTemperatureDto> scoreContacts(int workspaceId, Instant asOf) {
        Instant reference = scoringInstant(asOf);
        return computeContactScores(workspaceId, reference, reference);
    }

    List<RelationshipTemperatureDto> scoreContactsExcludingHistoryImports(
            int workspaceId,
            Instant asOf,
            Set<Integer> excludedActivityIds,
            Set<Integer> excludedNoteIds,
            Set<Integer> excludedTaskIds) {
        Instant reference = scoringInstant(asOf);
        LocalDateTime referenceDateTime =
            LocalDateTime.ofInstant(reference, ZoneOffset.UTC);
        return temperatures(
            personMapper.getRelationshipScoreAggregatesExcludingHistoryImports(
                workspaceId,
                referenceDateTime,
                WARMTH_MODEL.sqlParameters(),
                List.copyOf(excludedActivityIds),
                List.copyOf(excludedNoteIds),
                List.copyOf(excludedTaskIds)),
            reference);
    }

    /**
     * Returns clock-independent fingerprints of every persisted touch that affects each contact's
     * warmth score.
     */
    Map<Integer, String> contactSourceStateHashes(
            int workspaceId,
            Set<Integer> excludedActivityIds,
            Set<Integer> excludedNoteIds,
            Set<Integer> excludedTaskIds) {
        Map<Integer, List<SourceTouch>> touches = new HashMap<>();
        for (Activity activity : activityMapper.getAllActivities(workspaceId)) {
            Integer personId = personId(activity.getPerson());
            if (personId != null
                    && !excludedActivityIds.contains(activity.getId())
                    && epoch(activity.getTimestamp()) != null) {
                touches.computeIfAbsent(personId, key -> new ArrayList<>()).add(
                    new SourceTouch(
                        "activity",
                        activity.getId(),
                        activity.getTimestamp(),
                        Double.toString(activityWeight(activity.getType()))));
            }
        }
        for (Note note : noteMapper.getAllNotes(workspaceId)) {
            Integer personId = personId(note.getPerson());
            if (personId != null
                    && !excludedNoteIds.contains(note.getId())
                    && isSharedNote(note)
                    && epoch(note.getCreatedAt()) != null) {
                touches.computeIfAbsent(personId, key -> new ArrayList<>()).add(
                    new SourceTouch(
                        "note",
                        note.getId(),
                        note.getCreatedAt(),
                        Double.toString(WARMTH_MODEL.noteWeight())));
            }
        }
        for (Task task : taskMapper.getAllTasks(workspaceId)) {
            Integer personId = personId(task.getPerson());
            if (personId != null
                    && !excludedTaskIds.contains(task.getId())
                    && epoch(task.getCreatedAt()) != null) {
                touches.computeIfAbsent(personId, key -> new ArrayList<>()).add(
                    new SourceTouch(
                        "task",
                        task.getId(),
                        task.getCreatedAt(),
                        Double.toString(WARMTH_MODEL.taskWeight())));
            }
        }
        return sourceStateHashes(touches);
    }

    /** Returns clock-independent fingerprints of persisted touches attributed to each company. */
    Map<Integer, String> companySourceStateHashes(int workspaceId) {
        List<Person> persons = personMapper.getProcessablePersons(workspaceId);
        Map<Integer, Integer> personCompany = personCompanyMap(persons);
        Set<Integer> processablePersonIds = personIds(persons);
        Map<Integer, Integer> dealCompany = dealCompanyMap(dealMapper.getAllDeals(workspaceId));
        Map<Integer, List<SourceTouch>> touches = new HashMap<>();
        for (Activity activity : activityMapper.getAllActivities(workspaceId)) {
            if (epoch(activity.getTimestamp()) != null) {
                addCompanySourceTouch(
                    activity.getPerson(),
                    activity.getDeal(),
                    new SourceTouch(
                        "activity",
                        activity.getId(),
                        activity.getTimestamp(),
                        Double.toString(activityWeight(activity.getType()))),
                    personCompany,
                    processablePersonIds,
                    dealCompany,
                    touches);
            }
        }
        for (Note note : noteMapper.getAllNotes(workspaceId)) {
            if (isSharedNote(note) && epoch(note.getCreatedAt()) != null) {
                addCompanySourceTouch(
                    note.getPerson(),
                    note.getDeal(),
                    new SourceTouch(
                        "note",
                        note.getId(),
                        note.getCreatedAt(),
                        Double.toString(WARMTH_MODEL.noteWeight())),
                    personCompany,
                    processablePersonIds,
                    dealCompany,
                    touches);
            }
        }
        for (Task task : taskMapper.getAllTasks(workspaceId)) {
            if (epoch(task.getCreatedAt()) != null) {
                addCompanySourceTouch(
                    task.getPerson(),
                    task.getDeal(),
                    new SourceTouch(
                        "task",
                        task.getId(),
                        task.getCreatedAt(),
                        Double.toString(WARMTH_MODEL.taskWeight())),
                    personCompany,
                    processablePersonIds,
                    dealCompany,
                    touches);
            }
        }
        return sourceStateHashes(touches);
    }

    static String emptyContactSourceStateHash() {
        return EMPTY_CONTACT_SOURCE_STATE_HASH;
    }

    private static String contactSourceStateHash(
            List<SourceTouch> sourceTouches) {
        List<String> values = new ArrayList<>();
        values.add(WARMTH_MODEL.version());
        sourceTouches.stream()
            .sorted(Comparator
                .comparing(SourceTouch::kind)
                .thenComparingInt(SourceTouch::id)
                .thenComparing(SourceTouch::timestamp)
                .thenComparing(SourceTouch::weight))
            .forEach(touch -> {
                values.add(touch.kind());
                values.add(Integer.toString(touch.id()));
                values.add(touch.timestamp());
                values.add(touch.weight());
            });
        return hashValues(values);
    }

    private static Map<Integer, String> sourceStateHashes(
            Map<Integer, List<SourceTouch>> touches) {
        Map<Integer, String> hashes = new LinkedHashMap<>();
        touches.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> hashes.put(
                entry.getKey(), contactSourceStateHash(entry.getValue())));
        return hashes;
    }

    private static String hashValues(List<String> values) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        for (String value : values) {
            if (value == null) {
                digest.update(
                    ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
                continue;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(
                ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Returns bounded, source-level evidence for one visible, processable contact.
     *
     * <p>Only active-workspace activities, workspace-visible notes, and tasks are eligible.
     * Private-note disclosure is restricted to the current caller's own excluded notes.
     *
     * <p>Totals and the ranked contributors are two reads of the same capped source set inside one
     * read-only transaction, so they share a snapshot and the returned rows always reconcile with
     * the totals the score is derived from.
     */
    @Transactional(readOnly = true)
    public RelationshipEvidenceDto contactEvidence(int workspaceId, int personId, int currentUserId) {
        if (!personMapper.getProcessablePersonIds(workspaceId, List.of(personId)).contains(personId)) {
            throw new ResourceNotFoundException("Contact not found");
        }
        Instant asOf = scoringInstant(Instant.now(clock));
        LocalDateTime reference = LocalDateTime.ofInstant(asOf, ZoneOffset.UTC);
        RelationshipEvidenceTotalsDto totals = boundedTotals(personMapper.getRelationshipEvidenceTotals(
            workspaceId,
            personId,
            reference,
            WARMTH_MODEL.sqlParameters(),
            MAX_EVIDENCE_SOURCES + 1
        ));
        List<RelationshipEvidenceRowDto> rows = totals.contributorCount() == 0
            ? List.of()
            : personMapper.getRelationshipEvidenceContributors(
                workspaceId,
                personId,
                reference,
                WARMTH_MODEL.sqlParameters(),
                MAX_EVIDENCE_SOURCES + 1,
                MAX_EVIDENCE_CONTRIBUTORS
            );
        int privateNotes = boundedPrivateNoteCount(noteMapper.countOwnPrivateNotesForPersonEvidence(
            workspaceId, personId, currentUserId, reference, MAX_EVIDENCE_SOURCES + 1));
        return evidence(
            SubjectType.PERSON,
            personId,
            asOf,
            AttributionRule.DIRECT_PERSON_TOUCHES,
            totals,
            rows,
            privateNotes
        );
    }

    /**
     * Scores only the given contacts as of now, gathering each one's touches directly rather than
     * scanning the whole workspace. Used by callers that need warmth for a handful of known people
     * (e.g. a single deal's stakeholders) without paying the cost of scoring every contact.
     * @param workspaceId the workspace
     * @param personIds the contact ids to score
     * @return warmth for the requested contacts (unknown/quiet ids yield a cold score)
     */
    public List<RelationshipTemperatureDto> scoreContacts(int workspaceId, Set<Integer> personIds) {
        List<Integer> requested = personIds.stream()
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        if (requested.size() > MAX_BATCH_CONTACTS) {
            throw new BadRequestException(
                "At most " + MAX_BATCH_CONTACTS + " contacts may be scored in one request");
        }
        if (requested.isEmpty()) {
            return List.of();
        }
        Instant reference = scoringInstant(Instant.now(clock));
        Set<Integer> existing = new HashSet<>(personMapper.getProcessablePersonIds(workspaceId, requested));
        List<Integer> processable = requested.stream().filter(existing::contains).toList();
        if (processable.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<Touch>> touches = new HashMap<>();
        for (Activity activity : activityMapper.getActivitiesByPersonIds(workspaceId, processable)) {
            Integer personId = personId(activity.getPerson());
            Long timestamp = epoch(activity.getTimestamp());
            if (personId != null && timestamp != null) {
                add(touches, personId, new Touch(timestamp, activityWeight(activity.getType())));
            }
        }
        for (Note note : noteMapper.getNotesByPersonIds(workspaceId, processable)) {
            if (!isSharedNote(note)) continue;
            Integer personId = personId(note.getPerson());
            Long timestamp = epoch(note.getCreatedAt());
            if (personId != null && timestamp != null) {
                add(touches, personId, new Touch(timestamp, WARMTH_MODEL.noteWeight()));
            }
        }
        for (Task task : taskMapper.getTasksByPersonIds(workspaceId, processable)) {
            Integer personId = personId(task.getPerson());
            Long timestamp = epoch(task.getCreatedAt());
            if (personId != null && timestamp != null) {
                add(touches, personId, new Touch(timestamp, WARMTH_MODEL.taskWeight()));
            }
        }
        List<RelationshipTemperatureDto> out = new ArrayList<>(existing.size());
        for (Integer personId : processable) {
            out.add(temperature(personId, touches.getOrDefault(personId, List.of()), reference, reference));
        }
        return out;
    }

    private List<RelationshipTemperatureDto> computeContactScores(
            int workspaceId, Instant reference, Instant cutoff) {
        List<Person> persons = personMapper.getProcessablePersons(workspaceId);
        Map<Integer, List<Touch>> byPerson = collectContactTouches(workspaceId, personIds(persons));
        List<RelationshipTemperatureDto> out = new ArrayList<>(persons.size());
        for (Person p : persons) {
            out.add(temperature(p.getId(), byPerson.getOrDefault(p.getId(), List.of()), reference, cutoff));
        }
        return out;
    }

    /** Buckets every contact-linked touch (activities, notes, tasks) by contact id. */
    private Map<Integer, List<Touch>> collectContactTouches(int workspaceId, Set<Integer> processablePersonIds) {
        Map<Integer, List<Touch>> byPerson = new HashMap<>();
        for (Activity a : activityMapper.getAllActivities(workspaceId)) {
            Integer pid = personId(a.getPerson());
            Long ts = epoch(a.getTimestamp());
            if (pid != null && processablePersonIds.contains(pid) && ts != null) {
                add(byPerson, pid, new Touch(ts, activityWeight(a.getType())));
            }
        }
        for (Note n : noteMapper.getAllNotes(workspaceId)) {
            if (!isSharedNote(n)) continue;
            Integer pid = personId(n.getPerson());
            Long ts = epoch(n.getCreatedAt());
            if (pid != null && processablePersonIds.contains(pid) && ts != null) {
                add(byPerson, pid, new Touch(ts, WARMTH_MODEL.noteWeight()));
            }
        }
        for (Task t : taskMapper.getAllTasks(workspaceId)) {
            Integer pid = personId(t.getPerson());
            Long ts = epoch(t.getCreatedAt());
            if (pid != null && processablePersonIds.contains(pid) && ts != null) {
                add(byPerson, pid, new Touch(ts, WARMTH_MODEL.taskWeight()));
            }
        }
        return byPerson;
    }

    /**
     * Scores every company in the workspace as of now. A touch counts toward a company when it is
     * linked to one of the company's present-day contacts or one of its current deals. When both
     * links target the same company, the touch is counted once.
     */
    public List<RelationshipTemperatureDto> scoreCompanies(int workspaceId) {
        Instant reference = scoringInstant(Instant.now(clock));
        return computeCompanyScores(workspaceId, reference, reference);
    }

    /**
     * Returns cooling companies using
     * {@link AttributionRule#PRESENT_DAY_PERSON_COMPANY_OR_DEAL_COMPANY}.
     */
    public List<RelationshipTemperatureDto> coolingCompanies(int workspaceId, int limit) {
        return scoreCompanies(workspaceId).stream()
            .filter(temperature -> "cooling".equals(temperature.getTrend()))
            .sorted((left, right) -> Integer.compare(
                right.getDaysSinceTouch() == null ? 0 : right.getDaysSinceTouch(),
                left.getDaysSinceTouch() == null ? 0 : left.getDaysSinceTouch()))
            .limit(limit)
            .toList();
    }

    /**
     * Scores only the given visible companies as of now, gathering each company's touches directly
     * instead of scanning every company in the workspace. Attribution uses
     * {@link AttributionRule#PRESENT_DAY_PERSON_COMPANY_OR_DEAL_COMPANY}.
     */
    public List<RelationshipTemperatureDto> scoreCompanies(int workspaceId, Set<Integer> companyIds) {
        List<Integer> requested = companyIds.stream()
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        if (requested.size() > MAX_BATCH_COMPANIES) {
            throw new BadRequestException(
                "At most " + MAX_BATCH_COMPANIES + " companies may be scored in one request");
        }
        if (requested.isEmpty()) return List.of();
        Instant reference = scoringInstant(Instant.now(clock));
        List<Company> companies = companyMapper.getByIds(workspaceId, requested);
        if (companies.isEmpty()) return List.of();
        List<Integer> visibleIds = companies.stream().map(Company::getId).toList();
        List<Person> persons = personMapper.getPersonsByCompanyIds(workspaceId, visibleIds);
        List<Deal> deals = dealMapper.getDealsByCompanyIds(workspaceId, visibleIds);
        List<Activity> activities = companyActivities(workspaceId, persons, visibleIds);
        List<Note> notes = noteMapper.getWorkspaceNotesByCompanyIds(workspaceId, visibleIds).stream()
            .filter(ScoringService::isSharedNote)
            .toList();
        List<Task> tasks = companyTasks(workspaceId, persons, visibleIds);
        Map<Integer, Integer> personCompany = personCompanyMap(persons);
        Map<Integer, Integer> dealCompany = dealCompanyMap(deals);
        Map<Integer, List<Touch>> byCompany = new HashMap<>();
        collectCompanyTouches(
            activities,
            notes,
            tasks,
            personCompany,
            processablePersonIds(workspaceId, activities, notes, tasks),
            dealCompany,
            byCompany);
        Map<Integer, RelationshipTemperatureDto> scores = new HashMap<>();
        for (Company company : companies) {
            scores.put(company.getId(), temperature(
                company.getId(), byCompany.getOrDefault(company.getId(), List.of()),
                reference, reference));
        }
        return requested.stream().map(scores::get).filter(java.util.Objects::nonNull).toList();
    }

    private List<Task> companyTasks(
            int workspaceId, List<Person> persons, List<Integer> companyIds) {
        Map<Integer, Task> tasks = new LinkedHashMap<>();
        List<Integer> authorizedPersonIds = persons.stream().map(Person::getId).distinct().toList();
        for (int from = 0; from < authorizedPersonIds.size(); from += MAX_BATCH_CONTACTS) {
            int to = Math.min(authorizedPersonIds.size(), from + MAX_BATCH_CONTACTS);
            for (Task task : taskMapper.getTasksByPersonCompanyIds(
                    workspaceId, authorizedPersonIds.subList(from, to), companyIds)) {
                tasks.putIfAbsent(task.getId(), task);
            }
        }
        for (Task task : taskMapper.getTasksByDealCompanyIds(workspaceId, companyIds)) {
            tasks.putIfAbsent(task.getId(), task);
        }
        return List.copyOf(tasks.values());
    }

    private List<Activity> companyActivities(
            int workspaceId, List<Person> persons, List<Integer> companyIds) {
        Map<Integer, Activity> activities = new LinkedHashMap<>();
        List<Integer> authorizedPersonIds = persons.stream().map(Person::getId).distinct().toList();
        for (int from = 0; from < authorizedPersonIds.size(); from += MAX_BATCH_CONTACTS) {
            int to = Math.min(authorizedPersonIds.size(), from + MAX_BATCH_CONTACTS);
            for (Activity activity : activityMapper.getActivitiesByPersonIds(
                    workspaceId, authorizedPersonIds.subList(from, to))) {
                activities.putIfAbsent(activity.getId(), activity);
            }
        }
        for (Activity activity : activityMapper.getActivitiesByDealCompanyIds(workspaceId, companyIds)) {
            activities.putIfAbsent(activity.getId(), activity);
        }
        return List.copyOf(activities.values());
    }

    /**
     * Scores the complete relationship-map company set with
     * {@link AttributionRule#PRESENT_DAY_PERSON_COMPANY_OR_DEAL_COMPANY} after enforcing its fixed
     * workspace cap.
     */
    public List<RelationshipTemperatureDto> scoreCompaniesForMap(int workspaceId) {
        long companyCount = companyMapper.countCompanies(
            workspaceId, null, null, false, null, MemberScope.allTeam(), false);
        if (companyCount > MAX_BATCH_COMPANIES) {
            throw new BadRequestException(
                "Relationship map supports at most " + MAX_BATCH_COMPANIES + " companies");
        }
        Instant now = scoringInstant(Instant.now(clock));
        LocalDateTime reference = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        return temperatures(
            companyMapper.getRelationshipScoreAggregates(
                workspaceId, reference, WARMTH_MODEL.sqlParameters()), now);
    }

    /**
     * Scores every company in the workspace as of {@code asOf}: warmth is decayed against that
     * instant and only interactions logged on or before it are counted. Company attribution uses
     * present-day contact/deal parentage; the time-travel replay refines attribution to the
     * as-of-{@code asOf} employment when it assembles frames.
     */
    public List<RelationshipTemperatureDto> scoreCompanies(int workspaceId, Instant asOf) {
        Instant reference = scoringInstant(asOf);
        return computeCompanyScores(workspaceId, reference, reference);
    }

    /**
     * Returns bounded, source-level evidence for one visible company.
     *
     * <p>Live company attribution uses each contact's present-day company plus the current company
     * of a linked deal, deduplicating a source when both links resolve to this company.
     *
     * <p>Totals and the ranked contributors are two reads of the same capped source set inside one
     * read-only transaction, so they share a snapshot and the returned rows always reconcile with
     * the totals the score is derived from.
     */
    @Transactional(readOnly = true)
    public RelationshipEvidenceDto companyEvidence(int workspaceId, int companyId, int currentUserId) {
        if (companyMapper.getByIds(workspaceId, List.of(companyId)).stream()
                .noneMatch(company -> company.getId() == companyId)) {
            throw new ResourceNotFoundException("Company not found");
        }
        Instant asOf = scoringInstant(Instant.now(clock));
        LocalDateTime reference = LocalDateTime.ofInstant(asOf, ZoneOffset.UTC);
        RelationshipEvidenceTotalsDto totals = boundedTotals(companyMapper.getRelationshipEvidenceTotals(
            workspaceId,
            companyId,
            reference,
            WARMTH_MODEL.sqlParameters(),
            MAX_EVIDENCE_SOURCES + 1
        ));
        List<RelationshipEvidenceRowDto> rows = totals.contributorCount() == 0
            ? List.of()
            : companyMapper.getRelationshipEvidenceContributors(
                workspaceId,
                companyId,
                reference,
                WARMTH_MODEL.sqlParameters(),
                MAX_EVIDENCE_SOURCES + 1,
                MAX_EVIDENCE_CONTRIBUTORS
            );
        int privateNotes = boundedPrivateNoteCount(noteMapper.countOwnPrivateNotesForCompanyEvidence(
            workspaceId, companyId, currentUserId, reference, MAX_EVIDENCE_SOURCES + 1));
        return evidence(
            SubjectType.COMPANY,
            companyId,
            asOf,
            AttributionRule.PRESENT_DAY_PERSON_COMPANY_OR_DEAL_COMPANY,
            totals,
            rows,
            privateNotes
        );
    }

    public WarmthSummaryDto summarize(int workspaceId) {
        List<RelationshipTemperatureDto> contacts = scoreContacts(workspaceId);
        List<RelationshipTemperatureDto> companies = scoreCompanies(workspaceId);
        return summarizeScores(contacts, companies);
    }

    /** Reduces an already-computed score snapshot without repeating workspace scans. */
    public WarmthSummaryDto summarizeScores(
            List<RelationshipTemperatureDto> contacts,
            List<RelationshipTemperatureDto> companies) {
        return new WarmthSummaryDto(
            bandCounts(contacts),
            bandCounts(companies),
            trendCounts(contacts),
            decayCounts(contacts)
        );
    }

    private static BandCounts bandCounts(List<RelationshipTemperatureDto> scores) {
        return new BandCounts(
            scores.stream().filter(score -> "hot".equals(score.getBand())).count(),
            scores.stream().filter(score -> "warm".equals(score.getBand())).count(),
            scores.stream().filter(score -> "cool".equals(score.getBand())).count(),
            scores.stream().filter(score -> "cold".equals(score.getBand())).count()
        );
    }

    private static TrendCounts trendCounts(List<RelationshipTemperatureDto> scores) {
        return new TrendCounts(
            scores.stream().filter(score -> "rising".equals(score.getTrend())).count(),
            scores.stream().filter(score -> "steady".equals(score.getTrend())).count(),
            scores.stream().filter(score -> "cooling".equals(score.getTrend())).count()
        );
    }

    private static DecayCounts decayCounts(List<RelationshipTemperatureDto> scores) {
        return new DecayCounts(
            scores.stream().filter(score -> inDecayRange(score, 0, 30)).count(),
            scores.stream().filter(score -> inDecayRange(score, 31, 60)).count(),
            scores.stream().filter(score -> inDecayRange(score, 61, 90)).count()
        );
    }

    private static boolean inDecayRange(RelationshipTemperatureDto score, int minimum, int maximum) {
        Integer days = score.getDaysUntilCold();
        return days != null && days >= minimum && days <= maximum;
    }

    private List<RelationshipTemperatureDto> computeCompanyScores(
            int workspaceId, Instant reference, Instant cutoff) {
        Map<Integer, List<Touch>> byCompany = collectCompanyTouches(workspaceId);
        List<Company> companies = companyMapper.getAllCompanies(workspaceId);
        List<RelationshipTemperatureDto> out = new ArrayList<>(companies.size());
        for (Company c : companies) {
            out.add(temperature(c.getId(), byCompany.getOrDefault(c.getId(), List.of()), reference, cutoff));
        }
        return out;
    }

    /** Buckets every touch by the company of its linked contact and/or deal (present-day parentage). */
    private Map<Integer, List<Touch>> collectCompanyTouches(int workspaceId) {
        List<Person> persons = personMapper.getProcessablePersons(workspaceId);
        Map<Integer, Integer> personCompany = personCompanyMap(persons);
        Map<Integer, Integer> dealCompany = dealCompanyMap(dealMapper.getAllDeals(workspaceId));
        Map<Integer, List<Touch>> byCompany = new HashMap<>();
        collectCompanyTouches(
            activityMapper.getAllActivities(workspaceId),
            noteMapper.getAllNotes(workspaceId).stream().filter(ScoringService::isSharedNote).toList(),
            taskMapper.getAllTasks(workspaceId),
            personCompany,
            personIds(persons),
            dealCompany,
            byCompany);
        return byCompany;
    }

    private void collectCompanyTouches(
            List<Activity> activities,
            List<Note> notes,
            List<Task> tasks,
            Map<Integer, Integer> personCompany,
            Set<Integer> processablePersonIds,
            Map<Integer, Integer> dealCompany,
            Map<Integer, List<Touch>> byCompany) {
        for (Activity activity : activities) {
            Long timestamp = epoch(activity.getTimestamp());
            if (timestamp != null) {
                attribute(activity.getPerson(), activity.getDeal(),
                    new Touch(timestamp, activityWeight(activity.getType())),
                    personCompany, processablePersonIds, dealCompany, byCompany);
            }
        }
        for (Note note : notes) {
            Long timestamp = epoch(note.getCreatedAt());
            if (timestamp != null) {
                attribute(note.getPerson(), note.getDeal(), new Touch(timestamp, WARMTH_MODEL.noteWeight()),
                    personCompany, processablePersonIds, dealCompany, byCompany);
            }
        }
        for (Task task : tasks) {
            Long timestamp = epoch(task.getCreatedAt());
            if (timestamp != null) {
                attribute(task.getPerson(), task.getDeal(), new Touch(timestamp, WARMTH_MODEL.taskWeight()),
                    personCompany, processablePersonIds, dealCompany, byCompany);
            }
        }
    }

    private static Map<Integer, Integer> personCompanyMap(List<Person> persons) {
        Map<Integer, Integer> result = new HashMap<>();
        for (Person person : persons) {
            Integer companyId = person.getCompany() == null ? null : person.getCompany().getId();
            if (companyId != null && companyId != 0) result.put(person.getId(), companyId);
        }
        return result;
    }

    private static Set<Integer> personIds(List<Person> persons) {
        return persons.stream().map(Person::getId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Set<Integer> processablePersonIds(
            int workspaceId, List<Activity> activities, List<Note> notes, List<Task> tasks) {
        Set<Integer> requested = new HashSet<>();
        activities.stream().map(Activity::getPerson).map(ScoringService::personId)
            .filter(java.util.Objects::nonNull).forEach(requested::add);
        notes.stream().map(Note::getPerson).map(ScoringService::personId)
            .filter(java.util.Objects::nonNull).forEach(requested::add);
        tasks.stream().map(Task::getPerson).map(ScoringService::personId)
            .filter(java.util.Objects::nonNull).forEach(requested::add);
        if (requested.isEmpty()) return Set.of();
        List<Integer> ids = List.copyOf(requested);
        Set<Integer> processable = new HashSet<>();
        for (int from = 0; from < ids.size(); from += MAX_BATCH_CONTACTS) {
            int to = Math.min(ids.size(), from + MAX_BATCH_CONTACTS);
            processable.addAll(personMapper.getProcessablePersonIds(workspaceId, ids.subList(from, to)));
        }
        return Set.copyOf(processable);
    }

    private static Map<Integer, Integer> dealCompanyMap(List<Deal> deals) {
        Map<Integer, Integer> result = new HashMap<>();
        for (Deal deal : deals) {
            if (deal.getCompanyId() != null) result.put(deal.getId(), deal.getCompanyId());
        }
        return result;
    }

    /** Per-frame contact and company warmth bands, produced from a single touch load. */
    public record ReplayBands(List<Map<Integer, String>> contactFrames, List<Map<Integer, String>> companyFrames) {}

    /**
     * Warmth bands for every contact and company across {@code frameMillis}, for the time-travel
     * replay. Activities, notes, and tasks are loaded <em>once</em> and bucketed for both contacts
     * (their own touches) and companies (each touch attributed to the company that contact worked at
     * <em>when the touch occurred</em> via {@code employerAt}, so a contact moving employers cools the
     * old account and heats the new one rather than retroactively re-crediting their whole history; a
     * deal's touch uses {@code dealCompany}, the present-day parentage). Each frame's map omits nodes
     * with no qualifying touch (the caller defaults them to "cold"). This is
     * {@link AttributionRule#TOUCH_TIME_EMPLOYER_OR_PRESENT_DAY_DEAL_COMPANY}.
     */
    public ReplayBands replayBands(int workspaceId, long[] frameMillis, EmployerResolver employerAt,
            Set<Integer> processablePersonIds, Map<Integer, Integer> dealCompany) {
        Map<Integer, List<Touch>> byPerson = new HashMap<>();
        List<CompanyTouch> companyTouches = new ArrayList<>();
        for (Activity a : activityMapper.getAllActivities(workspaceId)) {
            collectTouch(epoch(a.getTimestamp()), activityWeight(a.getType()), a.getPerson(), a.getDeal(),
                processablePersonIds, dealCompany, byPerson, companyTouches);
        }
        for (Note n : noteMapper.getAllNotes(workspaceId)) {
            if (!isSharedNote(n)) continue;
            collectTouch(epoch(n.getCreatedAt()), WARMTH_MODEL.noteWeight(), n.getPerson(), n.getDeal(),
                processablePersonIds, dealCompany, byPerson, companyTouches);
        }
        for (Task t : taskMapper.getAllTasks(workspaceId)) {
            collectTouch(epoch(t.getCreatedAt()), WARMTH_MODEL.taskWeight(), t.getPerson(), t.getDeal(),
                processablePersonIds, dealCompany, byPerson, companyTouches);
        }

        List<AttributedTouch> attributed = attributeCompanyTouches(companyTouches, employerAt);

        List<Map<Integer, String>> contactFrames = new ArrayList<>(frameMillis.length);
        List<Map<Integer, String>> companyFrames = new ArrayList<>(frameMillis.length);
        for (long t : frameMillis) {
            Instant asOf = Instant.ofEpochMilli(t);
            Map<Integer, String> contactBands = new HashMap<>();
            for (Map.Entry<Integer, List<Touch>> e : byPerson.entrySet()) {
                contactBands.put(e.getKey(), temperature(e.getKey(), e.getValue(), asOf, asOf).getBand());
            }
            contactFrames.add(contactBands);

            Map<Integer, List<Touch>> byCompany = new HashMap<>();
            for (AttributedTouch at : attributed) {
                if (at.epochMillis() > t) continue;
                for (Integer cid : at.companies()) add(byCompany, cid, new Touch(at.epochMillis(), at.weight()));
            }
            Map<Integer, String> companyBands = new HashMap<>();
            for (Map.Entry<Integer, List<Touch>> e : byCompany.entrySet()) {
                companyBands.put(e.getKey(), temperature(e.getKey(), e.getValue(), asOf, asOf).getBand());
            }
            companyFrames.add(companyBands);
        }
        return new ReplayBands(contactFrames, companyFrames);
    }

    /** Buckets one touch into the per-contact map and the company-attributable list in a single pass. */
    private static void collectTouch(Long ts, double weight, Person person, Deal deal,
            Set<Integer> processablePersonIds, Map<Integer, Integer> dealCompany,
            Map<Integer, List<Touch>> byPerson,
            List<CompanyTouch> companyTouches) {
        if (ts == null) return;
        Integer pid = personId(person);
        if (pid != null && !processablePersonIds.contains(pid)) return;
        if (pid != null) add(byPerson, pid, new Touch(ts, weight));
        companyTouches.add(new CompanyTouch(ts, weight, pid, dealCompanyFor(deal, dealCompany)));
    }

    /** Resolves which company a contact worked at at a given instant, for touch-time attribution. */
    @FunctionalInterface
    public interface EmployerResolver {
        /** The company the contact worked at at {@code epochMillis}, or {@code null} if none/unknown. */
        Integer employerAt(int personId, long epochMillis);
    }

    /** A touch carrying the linkage needed to attribute it to a company as of any instant. */
    private record CompanyTouch(long epochMillis, double weight, Integer personId, Integer dealCompanyId) {}

    /** A touch with its target companies fixed by the contact's employer at the touch's own time. */
    private record AttributedTouch(long epochMillis, double weight, Set<Integer> companies) {}

    /** Fixes each touch's target companies once, by the contact's employer at the touch's own time. */
    private static List<AttributedTouch> attributeCompanyTouches(List<CompanyTouch> touches,
            EmployerResolver employerAt) {
        List<AttributedTouch> out = new ArrayList<>(touches.size());
        for (CompanyTouch ct : touches) {
            Set<Integer> companies = new HashSet<>();
            if (ct.personId() != null) {
                Integer cid = employerAt.employerAt(ct.personId(), ct.epochMillis());
                if (cid != null) companies.add(cid);
            }
            if (ct.dealCompanyId() != null) companies.add(ct.dealCompanyId());
            if (!companies.isEmpty()) out.add(new AttributedTouch(ct.epochMillis(), ct.weight(), companies));
        }
        return out;
    }

    private static Integer dealCompanyFor(Deal deal, Map<Integer, Integer> dealCompany) {
        Integer did = dealId(deal);
        return did == null ? null : dealCompany.get(did);
    }

    /**
     * Rejects a record whose eligible source set exceeds {@link #MAX_EVIDENCE_SOURCES}, so one
     * pathological history degrades into a fast, predictable refusal instead of an unbounded scan
     * that holds a pooled connection. The statements bind the same ceiling, so a set at the ceiling
     * is reported as one row over the limit and never as a silently truncated total.
     */
    private static RelationshipEvidenceTotalsDto boundedTotals(RelationshipEvidenceTotalsDto totals) {
        if (totals == null) {
            return EMPTY_EVIDENCE_TOTALS;
        }
        if (totals.contributorCount() > MAX_EVIDENCE_SOURCES) {
            throw new BadRequestException(
                "Relationship evidence supports at most " + MAX_EVIDENCE_SOURCES
                    + " attributed sources for one record");
        }
        return totals;
    }

    private static int boundedPrivateNoteCount(int count) {
        if (count > MAX_EVIDENCE_SOURCES) {
            throw new BadRequestException(
                "Relationship evidence supports at most " + MAX_EVIDENCE_SOURCES
                    + " excluded private notes for one caller and record");
        }
        return count;
    }

    private RelationshipEvidenceDto evidence(
            SubjectType subjectType,
            int subjectId,
            Instant asOf,
            AttributionRule attributionRule,
            RelationshipEvidenceTotalsDto total,
            List<RelationshipEvidenceRowDto> rows,
            int callerPrivateNotesExcluded) {
        if (total.contributorCount() == 0) {
            return new RelationshipEvidenceDto(
                subjectType,
                subjectId,
                emptyTemperature(subjectId, asOf),
                asOf,
                attributionRule,
                List.of(),
                new Totals(0, 0, 0, 0.0, 0.0, 0.0, new SourceCounts(0, 0, 0)),
                new Coverage(
                    true,
                    MINIMUM_CONFIDENT_CONTRIBUTORS,
                    callerPrivateNotesExcluded,
                    PrivateNoteCountScope.CURRENT_CALLER_ONLY
                )
            );
        }

        RelationshipScoreAggregateDto aggregate = new RelationshipScoreAggregateDto(
            subjectId,
            total.totalDecayedContribution(),
            total.recentWeight(),
            total.priorWeight(),
            total.lastTouchAt(),
            total.recentTouchCount()
        );
        RelationshipTemperatureDto temperature = temperatures(List.of(aggregate), asOf).getFirst();
        List<Contributor> contributors = rows.stream()
            .map(row -> new Contributor(
                sourceType(row.sourceType()),
                row.sourceId(),
                row.interactionType(),
                requiredInstant(row.occurredAt()),
                row.baseWeight(),
                row.decayedContribution(),
                row.captureEvidence()
            ))
            .toList();
        double returnedContribution = contributors.stream()
            .mapToDouble(Contributor::decayedContribution)
            .sum();
        int omittedCount = Math.max(0, total.contributorCount() - contributors.size());
        double omittedContribution = Math.max(
            0.0, total.totalDecayedContribution() - returnedContribution);
        Totals totals = new Totals(
            total.contributorCount(),
            contributors.size(),
            omittedCount,
            total.totalDecayedContribution(),
            returnedContribution,
            omittedContribution,
            new SourceCounts(total.activityCount(), total.noteCount(), total.taskCount())
        );
        Coverage coverage = new Coverage(
            total.contributorCount() < MINIMUM_CONFIDENT_CONTRIBUTORS,
            MINIMUM_CONFIDENT_CONTRIBUTORS,
            callerPrivateNotesExcluded,
            PrivateNoteCountScope.CURRENT_CALLER_ONLY
        );
        return new RelationshipEvidenceDto(
            subjectType,
            subjectId,
            temperature,
            asOf,
            attributionRule,
            contributors,
            totals,
            coverage
        );
    }

    private static SourceType sourceType(String value) {
        return switch (value) {
            case "activity" -> SourceType.ACTIVITY;
            case "note" -> SourceType.NOTE;
            case "task" -> SourceType.TASK;
            default -> throw new IllegalStateException("Unexpected relationship evidence source");
        };
    }

    private static Instant requiredInstant(String value) {
        Long timestamp = epoch(value);
        if (timestamp == null) {
            throw new IllegalStateException("Relationship evidence timestamp is missing");
        }
        return Instant.ofEpochMilli(timestamp);
    }

    /**
     * Collapses a contact's or company's touches into a single temperature reading, decayed against
     * {@code reference} and counting only touches timestamped at or before {@code cutoff}. Live and
     * replay readings both use their reference instant as the cutoff, so future-dated touches are
     * skipped before the age calculation.
     */
    private RelationshipTemperatureDto temperature(
            int id, List<Touch> touches, Instant reference, Instant cutoff) {
        long referenceEpochMillis = reference.toEpochMilli();
        long cutoffEpochMillis = cutoff.toEpochMilli();
        double raw = 0, recent = 0, prior = 0;
        long lastTs = Long.MIN_VALUE;
        int recentCount = 0;
        boolean any = false;
        for (Touch t : touches) {
            if (t.epochMillis() > cutoffEpochMillis) continue;
            any = true;
            double ageDays = WARMTH_MODEL.ageDays(referenceEpochMillis, t.epochMillis());
            raw += WARMTH_MODEL.decayedContribution(t.weight(), ageDays);
            if (WARMTH_MODEL.isRecent(ageDays)) {
                recent += t.weight();
                recentCount++;
            } else if (WARMTH_MODEL.isPrior(ageDays)) {
                prior += t.weight();
            }
            if (t.epochMillis() > lastTs) lastTs = t.epochMillis();
        }
        if (!any) {
            return emptyTemperature(id, reference);
        }

        return temperature(id, raw, recent, prior, lastTs, recentCount, reference);
    }

    List<RelationshipTemperatureDto> temperatures(
            List<RelationshipScoreAggregateDto> aggregates,
            Instant reference) {
        List<RelationshipTemperatureDto> scores = new ArrayList<>(aggregates.size());
        for (RelationshipScoreAggregateDto aggregate : aggregates) {
            Long lastTouch = epoch(aggregate.lastTouchAt());
            if (lastTouch == null) {
                scores.add(emptyTemperature(aggregate.id(), reference));
            } else {
                scores.add(temperature(
                    aggregate.id(),
                    aggregate.rawWeight(),
                    aggregate.recentWeight(),
                    aggregate.priorWeight(),
                    lastTouch,
                    aggregate.recentTouchCount(),
                    reference));
            }
        }
        return scores;
    }

    private RelationshipTemperatureDto temperature(
            int id,
            double raw,
            double recent,
            double prior,
            long lastTs,
            int recentCount,
            Instant reference) {
        long referenceEpochMillis = reference.toEpochMilli();
        int score = WARMTH_MODEL.score(raw);
        String band = WARMTH_MODEL.band(score);
        long daysSince = WARMTH_MODEL.wholeDaysSince(referenceEpochMillis, lastTs);
        String trend = WARMTH_MODEL.trend(recent, prior, daysSince);

        String lastTouchAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastTs), ZoneOffset.UTC)
            .format(MYSQL_DATETIME);

        Integer daysUntilCold = null;
        String goesColdAt = null;
        OptionalDouble predictedDays = WARMTH_MODEL.daysToCold(raw);
        if (predictedDays.isPresent()) {
            double daysToCold = predictedDays.getAsDouble();
            daysUntilCold = (int) Math.round(daysToCold);
            goesColdAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(WARMTH_MODEL.plusDays(referenceEpochMillis, daysToCold)), ZoneOffset.UTC)
                .format(MYSQL_DATETIME);
        }

        return new RelationshipTemperatureDto(id, score, band, trend, lastTouchAt, (int) daysSince, recentCount,
            goesColdAt, daysUntilCold, WARMTH_MODEL.version(), reference);
    }

    private static RelationshipTemperatureDto emptyTemperature(int id, Instant reference) {
        return new RelationshipTemperatureDto(
            id,
            0,
            "cold",
            "steady",
            null,
            null,
            0,
            null,
            null,
            WARMTH_MODEL.version(),
            reference
        );
    }

    /** Attributes a touch to its contact's company and/or its deal's company (deduplicated). */
    private void attribute(Person person, Deal deal, Touch touch,
            Map<Integer, Integer> personCompany, Set<Integer> processablePersonIds,
            Map<Integer, Integer> dealCompany,
            Map<Integer, List<Touch>> byCompany) {
        for (Integer companyId : attributedCompanyIds(
                person, deal, personCompany, processablePersonIds, dealCompany)) {
            add(byCompany, companyId, touch);
        }
    }

    private void addCompanySourceTouch(
            Person person,
            Deal deal,
            SourceTouch touch,
            Map<Integer, Integer> personCompany,
            Set<Integer> processablePersonIds,
            Map<Integer, Integer> dealCompany,
            Map<Integer, List<SourceTouch>> byCompany) {
        for (Integer companyId : attributedCompanyIds(
                person, deal, personCompany, processablePersonIds, dealCompany)) {
            byCompany.computeIfAbsent(companyId, key -> new ArrayList<>()).add(touch);
        }
    }

    private Set<Integer> attributedCompanyIds(
            Person person,
            Deal deal,
            Map<Integer, Integer> personCompany,
            Set<Integer> processablePersonIds,
            Map<Integer, Integer> dealCompany) {
        Set<Integer> companies = new HashSet<>();
        Integer pid = personId(person);
        if (pid != null && !processablePersonIds.contains(pid)) return Set.of();
        if (pid != null) {
            Integer cid = personCompany.get(pid);
            if (cid != null) companies.add(cid);
        }
        Integer did = dealId(deal);
        if (did != null) {
            Integer cid = dealCompany.get(did);
            if (cid != null) companies.add(cid);
        }
        return companies;
    }

    private static void add(Map<Integer, List<Touch>> map, int key, Touch touch) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(touch);
    }

    private static double activityWeight(String type) {
        return WARMTH_MODEL.activityWeight(type);
    }

    private static Integer personId(Person p) {
        return (p == null || p.getId() == 0) ? null : p.getId();
    }

    private static Integer dealId(Deal d) {
        return (d == null || d.getId() == 0) ? null : d.getId();
    }

    private static Long epoch(String s) {
        return DateTimes.epochMillis(s);
    }

    private static Instant scoringInstant(Instant instant) {
        return Instant.ofEpochMilli(instant.toEpochMilli());
    }

    private static boolean isSharedNote(Note note) {
        return "workspace".equals(note.getVisibility());
    }
}
