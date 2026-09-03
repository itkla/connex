package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.beans.PersonLifecycleHistory;
import ooo.klae.connex.backend.beans.PersonLifecyclePass;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;
import ooo.klae.connex.backend.dto.DisqualificationReasonDto;
import ooo.klae.connex.backend.dto.PersonLifecycleDto;
import ooo.klae.connex.backend.dto.PersonLifecycleHistoryDto;
import ooo.klae.connex.backend.dto.PersonLifecycleRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonLifecycleHistoryMapper;
import ooo.klae.connex.backend.mappers.PersonLifecyclePassMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Moves a contact through its lead lifecycle and reads the resulting timeline (#559).
 *
 * <p>Connex models the lifecycle as state on the contact rather than as a separate lead record; the
 * decision and the permitted-transition graph are documented in {@code docs/LEAD_LIFECYCLE.md}.
 * Every accepted move locks the owned contact, validates the transition against
 * {@link PersonLifecycleStage}, writes the whole lifecycle state, and appends one row to the
 * append-only history. Restricted, archived, and merely shared-in contacts are not transitionable:
 * the lifecycle is a statement about the owning workspace's own pipeline.
 */
@Service
@RequiredArgsConstructor
public class PersonLifecycleService {

    private static final int MAX_HISTORY_ROWS = 200;

    private final PersonMapper personMapper;
    private final PersonLifecycleHistoryMapper historyMapper;
    private final DealMapper dealMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final RuleTriggerPublisher ruleTriggers;
    private final LeadResponseSlaService leadResponseSla;
    private final PersonLifecyclePassMapper passMapper;
    private final PersonQualificationService qualificationService;
    private final DisqualificationReasonService disqualificationReasonService;
    private final NotificationChangePublisher notificationChanges;
    private final Clock clock;

    /**
     * Moves a contact and returns the resulting lifecycle state, with the advertised next moves
     * already filtered by the qualification gate.
     *
     * @param personId contact to move
     * @param request requested stage with its reason and note
     * @return lifecycle state after the move
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.PERSON_UPDATE)
    public PersonLifecycleDto updateLifecycleState(int personId, PersonLifecycleRequest request) {
        Person person = updateLifecycle(personId, request);
        return project(person.getWorkspaceId(), person);
    }

    /**
     * Withdraws a contact and returns the resulting lifecycle state.
     *
     * @param personId contact to withdraw
     * @param note optional explanation recorded with the withdrawal
     * @return lifecycle state after the withdrawal
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.PERSON_UPDATE)
    public PersonLifecycleDto withdrawLifecycleState(int personId, String note) {
        Person person = withdrawFromLifecycle(personId, note);
        return project(person.getWorkspaceId(), person);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.PERSON_UPDATE)
    public Person updateLifecycle(int personId, PersonLifecycleRequest request) {
        if (request == null || request.getStage() == null) {
            throw new BadRequestException("A lifecycle stage is required");
        }
        return applyStage(personId, request.getStage(), request.getReason(), request.getNote());
    }

    /**
     * Withdraws a contact from the lead lifecycle entirely, clearing its stage, reason, and notes.
     * The withdrawal and its note are recorded in the history, so the pipeline the contact left
     * remains auditable even though nothing about it is left on the contact itself.
     *
     * @param personId contact to withdraw
     * @param note optional explanation recorded with the withdrawal
     * @return the contact after the withdrawal
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.PERSON_UPDATE)
    public Person withdrawFromLifecycle(int personId, String note) {
        return applyStage(personId, null, null, note);
    }

    /**
     * The current lifecycle state of a contact the caller can see, including the moves it may make
     * next. Clients render those moves rather than reimplementing the transition rules.
     *
     * @param personId contact to read
     * @return current lifecycle state
     */
    public PersonLifecycleDto getLifecycle(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return project(workspaceId, requireOwnedPerson(workspaceId, personId));
    }

    /**
     * The lifecycle timeline of a contact the caller can see, most recent transition first.
     *
     * @param personId contact whose timeline is read
     * @return bounded transition history
     */
    public List<PersonLifecycleHistoryDto> getHistory(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireOwnedPerson(workspaceId, personId);
        Map<String, String> labels = new LinkedHashMap<>();
        for (DisqualificationReasonDto reason
                : disqualificationReasonService.resolved(workspaceId)) {
            if (PersonDisqualificationReason.isCanonicalCode(reason.code())) {
                labels.put(reason.code(), reason.label());
            }
        }
        return historyMapper.getByPersonId(workspaceId, personId, MAX_HISTORY_ROWS).stream()
            .map(history -> PersonLifecycleHistoryDto.from(
                history, resolvedLabel(labels, history.getReason())))
            .toList();
    }

    private Person applyStage(
            int personId,
            PersonLifecycleStage requested,
            String reason,
            String note) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        DisqualificationReasonDto lockedReason = requested == PersonLifecycleStage.DISQUALIFIED
            ? disqualificationReasonService.lockForLifecycle(workspaceId, reason)
            : null;
        Person before = personMapper.getOwnedPersonByIdForUpdate(workspaceId, personId);
        if (before == null
                || before.getArchivedAt() != null
                || before.getSuspendedAt() != null
                || before.getProvisionCeasedAt() != null) {
            throw new ResourceNotFoundException("Contact not found");
        }
        PersonLifecycleStage current = before.getLifecycleStage();
        boolean transitioning = current != requested;
        if (transitioning && !PersonLifecycleStage.isTransitionAllowed(current, requested)) {
            throw new BadRequestException(
                "A contact cannot move from " + stageLabel(current) + " to " + stageLabel(requested));
        }
        AcceptedReason accepted = requireReasonDisposition(
            requested, reason, current, before.getDisqualifiedReason(), lockedReason);
        String acceptedReason = accepted.code();
        if (transitioning && requested == PersonLifecycleStage.QUALIFIED) {
            requireQualificationCriteriaMet(workspaceId, personId);
        }
        if (transitioning && requested == PersonLifecycleStage.CONVERTED) {
            requireLinkedDeal(workspaceId, personId);
        }
        String acceptedNote = trimToNull(note);
        if (accepted.requiresNote() && acceptedNote == null) {
            throw new BadRequestException(
                "The selected disqualification reason requires a note explaining it");
        }
        String retainedNote = requested == null ? null : acceptedNote;
        LocalDateTime changedAt = transitioning ? now() : before.getLifecycleChangedAt();
        personMapper.updateLifecycle(
            workspaceId, personId, requested, changedAt, acceptedReason, retainedNote);
        if (transitioning) {
            recordTransition(workspaceId, personId, current, requested, acceptedReason, acceptedNote);
            recordPass(workspaceId, personId, current, requested, changedAt);
            if (endsLifecyclePass(requested)) {
                leadResponseSla.clearFirstResponseClock(workspaceId, personId);
            }
        }
        Person after = requireOwnedPerson(workspaceId, personId);
        recordAudit(before, after, transitioning, acceptedReason, retainedNote);
        if (transitioning) {
            notificationChanges.publish(workspaceId, "person", personId);
            ruleTriggers.publish(workspaceId, "person", personId, "person.lifecycle_changed");
        }
        return after;
    }

    /**
     * Keeps the pass ledger in step with the transition just accepted.
     *
     * <p>The pass is closed <em>before</em> the clock is cleared, so the response outcome is copied
     * onto the pass while it still exists. Reporting reads passes, and a pass that lost its response
     * data when it ended would make historical response time and breach rate disappear — and change
     * past reports retroactively.
     */
    private void recordPass(
            int workspaceId,
            int personId,
            PersonLifecycleStage from,
            PersonLifecycleStage to,
            LocalDateTime at) {
        if (endsLifecyclePass(to)) {
            passMapper.syncFirstResponse(workspaceId, personId);
            passMapper.closeOpenPass(workspaceId, personId, at);
            return;
        }
        if (entersLifecycle(from)) {
            PersonLifecyclePass pass = new PersonLifecyclePass();
            pass.setWorkspaceId(workspaceId);
            pass.setPersonId(personId);
            pass.setEnteredAt(at);
            passMapper.insert(pass);
        }
        if (to == PersonLifecycleStage.QUALIFIED
                || to == PersonLifecycleStage.CONVERTED
                || to == PersonLifecycleStage.DISQUALIFIED) {
            requirePassWrite(
                passMapper.stampMilestone(workspaceId, personId, to.name(), at), personId, to);
        }
    }

    /**
     * Whether this transition is an entry into the lifecycle, which opens a pass.
     *
     * <p>Entry is defined by where the contact came <em>from</em>, not where it is going: a recycled
     * contact may legally be worked straight to {@code WORKING}, {@code NURTURING}, {@code QUALIFIED}
     * or {@code DISQUALIFIED} without passing through {@code NEW}, and treating only {@code NEW} as
     * an entry left those passes unopened — their milestones then updated nothing at all.
     */
    private static boolean entersLifecycle(PersonLifecycleStage from) {
        return from == null || from == PersonLifecycleStage.RECYCLED;
    }

    /**
     * Fails the transition when a milestone found no open pass to record itself against.
     *
     * <p>The pass ledger is what reporting reads, so a silently skipped write is a figure that will
     * be wrong forever with nothing to indicate it. Better to refuse the transition and surface the
     * inconsistency than to accept it and publish an under-count.
     */
    private static void requirePassWrite(int rows, int personId, PersonLifecycleStage stage) {
        if (rows == 0) {
            throw new IllegalStateException(
                "No open lifecycle pass to record " + stage + " for contact " + personId);
        }
    }

    /**
     * Whether moving to this stage ends the lifecycle pass a first-response deadline belonged to.
     * Withdrawing leaves the lifecycle entirely and recycling returns the contact to the top of it,
     * so in both cases the old deadline no longer describes anything the workspace still owes; a
     * fresh pass gets a fresh clock from whichever rule puts it under an SLA again.
     */
    private static boolean endsLifecyclePass(PersonLifecycleStage requested) {
        return requested == null || requested == PersonLifecycleStage.RECYCLED;
    }

    /**
     * Projects a contact's lifecycle with the qualification gate applied, so every caller advertises
     * the same moves the transition will actually accept.
     *
     * @param workspaceId owning workspace
     * @param person contact to project
     * @return lifecycle state
     */
    public PersonLifecycleDto project(int workspaceId, Person person) {
        return PersonLifecycleDto.from(
            person,
            qualificationService.unmetRequiredCriteria(workspaceId, person.getId()).isEmpty(),
            resolvedLabel(workspaceId, person.getDisqualifiedReason()));
    }

    private AcceptedReason requireReasonDisposition(
            PersonLifecycleStage requested,
            String reason,
            PersonLifecycleStage current,
            String currentReason,
            DisqualificationReasonDto resolved) {
        if (requested == PersonLifecycleStage.DISQUALIFIED) {
            if (reason == null) {
                throw new BadRequestException("A disqualification reason is required");
            }
            if (resolved == null
                    || (resolved.archivedAt() != null
                        && (current != PersonLifecycleStage.DISQUALIFIED
                            || !Objects.equals(currentReason, resolved.code())))) {
                throw new BadRequestException("That disqualification reason is not available");
            }
            return new AcceptedReason(resolved.code(), resolved.requiresNote());
        }
        if (reason != null) {
            throw new BadRequestException(
                "A disqualification reason applies only when disqualifying a contact");
        }
        return new AcceptedReason(null, false);
    }

    /**
     * Holds a workspace to its own stated standard: every criterion it marked required must be met
     * before a contact can be called qualified. A workspace that configured no required criteria is
     * unaffected, so the gate never invents a standard nobody asked for — and, like the converted
     * gate below, it is enforced here rather than in the client so it cannot be skipped by calling
     * the API directly.
     */
    private void requireQualificationCriteriaMet(int workspaceId, int personId) {
        List<String> unmet = qualificationService.unmetRequiredCriteria(workspaceId, personId);
        if (!unmet.isEmpty()) {
            throw new BadRequestException(
                "This contact does not yet meet every required qualification criterion: "
                    + String.join(", ", unmet));
        }
    }

    private void requireLinkedDeal(int workspaceId, int personId) {
        if (dealMapper.getDealsByPersonId(workspaceId, personId).isEmpty()) {
            throw new BadRequestException(
                "A contact can only be marked converted once a deal is linked to it");
        }
    }

    private void recordTransition(
            int workspaceId,
            int personId,
            PersonLifecycleStage from,
            PersonLifecycleStage to,
            String reason,
            String note) {
        PersonLifecycleHistory history = new PersonLifecycleHistory();
        history.setWorkspaceId(workspaceId);
        history.setPersonId(personId);
        history.setFromStage(from);
        history.setToStage(to);
        history.setReason(reason);
        history.setNote(note);
        history.setChangedById(workspaceService.getCurrentUserId());
        historyMapper.insert(history);
    }

    private void recordAudit(
            Person before,
            Person after,
            boolean transitioning,
            String reason,
            String note) {
        Map<String, Object> changes = new LinkedHashMap<>();
        if (transitioning) {
            changes.put("lifecycleStage", Map.of(
                "from", stageLabel(before.getLifecycleStage()),
                "to", stageLabel(after.getLifecycleStage())));
        }
        if (!Objects.equals(before.getDisqualifiedReason(), reason)) {
            changes.put("disqualifiedReason", Map.of(
                "from", reasonLabel(before.getDisqualifiedReason()),
                "to", reasonLabel(reason)));
        }
        if (!Objects.equals(trimToNull(before.getQualificationNotes()), note)) {
            changes.put("qualificationNotesChanged", true);
        }
        if (changes.isEmpty()) {
            return;
        }
        auditService.record("person.lifecycle", "person", after.getId(), after.getName(),
            "Updated lead lifecycle for " + after.getName(), changes);
    }

    /**
     * The contact only when the active workspace owns it. A contact that is merely shared in stays a
     * 404 here: the lifecycle, its reasons, and its notes are the owning workspace's own pipeline
     * assessment, and a grantee must not be able to read or move them.
     */
    private Person requireOwnedPerson(int workspaceId, int personId) {
        Person person = personMapper.getPersonById(workspaceId, personId);
        if (person == null || person.getWorkspaceId() != workspaceId) {
            throw new ResourceNotFoundException("Contact not found");
        }
        return person;
    }

    private static String stageLabel(PersonLifecycleStage stage) {
        return stage == null ? "none" : stage.name();
    }

    private String resolvedLabel(int workspaceId, String code) {
        DisqualificationReasonDto reason =
            disqualificationReasonService.resolve(workspaceId, code);
        return reason == null ? code : reason.label();
    }

    private static String resolvedLabel(Map<String, String> labels, String code) {
        if (code == null) {
            return null;
        }
        if (!PersonDisqualificationReason.isCanonicalCode(code)) {
            return code;
        }
        return labels.containsKey(code) ? labels.get(code) : code;
    }

    private static String reasonLabel(String reason) {
        return reason == null ? "none" : reason;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    private record AcceptedReason(String code, boolean requiresNote) {
    }
}
