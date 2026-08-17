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
import ooo.klae.connex.backend.beans.PersonLifecycleStage;
import ooo.klae.connex.backend.dto.PersonLifecycleDto;
import ooo.klae.connex.backend.dto.PersonLifecycleHistoryDto;
import ooo.klae.connex.backend.dto.PersonLifecycleRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonLifecycleHistoryMapper;
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
    private final PersonQualificationService qualificationService;
    private final NotificationChangePublisher notificationChanges;
    private final Clock clock;

    /**
     * Moves a contact to the requested lifecycle stage.
     *
     * <p>Requesting the stage the contact already holds is accepted as an update of the accompanying
     * reason and notes only: it records no transition, because nothing transitioned.
     *
     * @param personId contact to move
     * @param request requested stage with its reason and note
     * @return the contact after the move
     */
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
        return historyMapper.getByPersonId(workspaceId, personId, MAX_HISTORY_ROWS).stream()
            .map(PersonLifecycleHistoryDto::from)
            .toList();
    }

    private Person applyStage(
            int personId,
            PersonLifecycleStage requested,
            PersonDisqualificationReason reason,
            String note) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person before = personMapper.getOwnedPersonByIdForUpdate(workspaceId, personId);
        if (before == null
                || before.getArchivedAt() != null
                || before.getSuspendedAt() != null
                || before.getProvisionCeasedAt() != null) {
            throw new ResourceNotFoundException("Person not found with id: " + personId);
        }
        PersonLifecycleStage current = before.getLifecycleStage();
        boolean transitioning = current != requested;
        if (transitioning && !PersonLifecycleStage.isTransitionAllowed(current, requested)) {
            throw new BadRequestException(
                "A contact cannot move from " + stageLabel(current) + " to " + stageLabel(requested));
        }
        PersonDisqualificationReason acceptedReason =
            requireReasonDisposition(requested, reason);
        if (transitioning && requested == PersonLifecycleStage.QUALIFIED) {
            requireQualificationCriteriaMet(workspaceId, personId);
        }
        if (transitioning && requested == PersonLifecycleStage.CONVERTED) {
            requireLinkedDeal(workspaceId, personId);
        }
        String acceptedNote = trimToNull(note);
        if (acceptedReason == PersonDisqualificationReason.OTHER && acceptedNote == null) {
            throw new BadRequestException(
                "Disqualifying for another reason requires a note explaining it");
        }
        String retainedNote = requested == null ? null : acceptedNote;
        LocalDateTime changedAt = transitioning ? now() : before.getLifecycleChangedAt();
        personMapper.updateLifecycle(
            workspaceId, personId, requested, changedAt, acceptedReason, retainedNote);
        if (transitioning) {
            recordTransition(workspaceId, personId, current, requested, acceptedReason, acceptedNote);
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
            qualificationService.unmetRequiredCriteria(workspaceId, person.getId()).isEmpty());
    }

    private PersonDisqualificationReason requireReasonDisposition(
            PersonLifecycleStage requested, PersonDisqualificationReason reason) {
        if (requested == PersonLifecycleStage.DISQUALIFIED) {
            if (reason == null) {
                throw new BadRequestException("A disqualification reason is required");
            }
            return reason;
        }
        if (reason != null) {
            throw new BadRequestException(
                "A disqualification reason applies only when disqualifying a contact");
        }
        return null;
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
            PersonDisqualificationReason reason,
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
            PersonDisqualificationReason reason,
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
            throw new ResourceNotFoundException("Person not found with id: " + personId);
        }
        return person;
    }

    private static String stageLabel(PersonLifecycleStage stage) {
        return stage == null ? "none" : stage.name();
    }

    private static String reasonLabel(PersonDisqualificationReason reason) {
        return reason == null ? "none" : reason.name();
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
}
