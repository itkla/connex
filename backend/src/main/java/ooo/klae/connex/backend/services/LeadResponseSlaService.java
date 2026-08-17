package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Runs the first-response SLA clock a contact carries while it sits in a lead lifecycle (#559).
 *
 * <p>The clock is started by the rule engine's {@code set_response_due} action rather than by a
 * workspace-wide setting, so the existing condition language decides which leads get which deadline
 * — the epic requires routing and SLA to reuse the rule engine instead of introducing a second
 * configuration language. It stops when any activity is logged against the contact, which is what a
 * first response is in this product; the sweep records a breach when the deadline passes first.
 *
 * <p>Every write is a guarded single statement rather than a read-then-write, because rules re-fire,
 * activities land concurrently, and the sweep may overlap itself. The guards make each operation
 * idempotent on its own: a running clock is never extended, a first response is never overwritten
 * by a later one, and a breach is never recorded twice or against an answered contact.
 *
 * <p>Like the rest of the lead lifecycle, the clock is strictly the owning workspace's own record
 * of how fast it answered. A contact that is merely shared in is not addressable here.
 */
@Service
@RequiredArgsConstructor
public class LeadResponseSlaService {

    private static final int MAX_DUE_IN_HOURS = 24 * 365;

    private final PersonMapper personMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final RuleTriggerPublisher ruleTriggers;
    private final NotificationChangePublisher notificationChanges;
    private final Clock clock;

    /**
     * Starts a first-response deadline the given number of hours from now on a contact that has no
     * clock. A contact whose clock is already running, already answered, or already breached is left
     * exactly as it is: the deadline describes the <em>first</em> response, and a re-firing rule must
     * not quietly buy the workspace more time.
     *
     * @param personId contact to put under an SLA
     * @param dueInHours hours until the first response is due
     * @return {@code true} when a new clock was started
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.PERSON_UPDATE)
    public boolean startFirstResponseClock(int personId, Integer dueInHours) {
        int hours = requireDueInHours(dueInHours);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person person = requireOwnedPerson(workspaceId, personId);
        LocalDateTime dueAt = now().plusHours(hours);
        if (personMapper.startFirstResponseClock(workspaceId, personId, dueAt) == 0) {
            return false;
        }
        auditService.record("person.first_response_sla", "person", personId, person.getName(),
            "Started the first-response SLA for " + person.getName(),
            Map.of("firstResponseDueAt", Map.of("from", "none", "to", dueAt.toString())));
        notificationChanges.publish(workspaceId, "person", personId);
        return true;
    }

    /**
     * Records the workspace's first response to a contact under a running SLA clock. Called on every
     * activity logged against a contact; contacts with no clock, and contacts already responded to,
     * are silently unaffected, so the caller never has to ask whether an SLA applies.
     *
     * <p>An activity logged after the deadline still stops the clock, and the breach that was
     * already recorded stays on the contact: a late answer is a late answer, and erasing the breach
     * to record it would destroy the evidence the SLA exists to produce.
     *
     * @param workspaceId owning workspace
     * @param personId contact the activity was logged against
     */
    public void recordFirstResponse(int workspaceId, int personId) {
        if (personMapper.recordFirstResponse(workspaceId, personId, now()) > 0) {
            notificationChanges.publish(workspaceId, "person", personId);
        }
    }

    /**
     * Clears the whole clock, used when a contact leaves the lifecycle pass the clock belonged to.
     * The deadline described that pass; carrying it into the next one would report a stale breach
     * forever. The transition that cleared it remains in {@code person_lifecycle_history}.
     *
     * @param workspaceId owning workspace
     * @param personId contact leaving its lifecycle pass
     */
    public void clearFirstResponseClock(int workspaceId, int personId) {
        personMapper.clearFirstResponseClock(workspaceId, personId);
    }

    /**
     * Contacts in the workspace whose first-response deadline has passed unanswered and whose breach
     * has not been recorded yet, paged by an exclusive id cursor.
     *
     * @param workspaceId owning workspace
     * @param afterId exclusive id cursor; {@code 0} starts at the beginning
     * @param limit maximum ids to return
     * @return breaching contact ids, ascending
     */
    public List<Integer> findBreaches(int workspaceId, int afterId, int limit) {
        return personMapper.findFirstResponseBreaches(workspaceId, now(), afterId, Math.max(1, limit));
    }

    /**
     * Records one breach and announces it to the rule engine so a workspace can escalate with the
     * actions it already has — notify, create a task, or route the lead to someone else.
     *
     * <p>The update re-asserts every condition the sweep selected on, so a response that lands
     * between the select and this call wins and no breach is written. Nothing is published unless
     * the row actually changed, which keeps two overlapping sweeps from escalating twice.
     *
     * @param workspaceId owning workspace
     * @param personId breaching contact
     * @return {@code true} when this call recorded the breach
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean recordBreach(int workspaceId, int personId) {
        LocalDateTime breachedAt = now();
        if (personMapper.recordFirstResponseBreach(workspaceId, personId, breachedAt) == 0) {
            return false;
        }
        Person person = personMapper.getPersonById(workspaceId, personId);
        String name = person == null ? "contact " + personId : person.getName();
        auditService.record("person.first_response_breached", "person", personId, name,
            "First-response SLA breached for " + name,
            Map.of("firstResponseBreachedAt", Map.of("from", "none", "to", breachedAt.toString())));
        notificationChanges.publish(workspaceId, "person", personId);
        ruleTriggers.publish(workspaceId, "person", personId, "person.first_response_overdue");
        return true;
    }

    private static int requireDueInHours(Integer dueInHours) {
        if (dueInHours == null || dueInHours < 1 || dueInHours > MAX_DUE_IN_HOURS) {
            throw new BadRequestException(
                "A first-response SLA must be between 1 and " + MAX_DUE_IN_HOURS + " hours");
        }
        return dueInHours;
    }

    private Person requireOwnedPerson(int workspaceId, int personId) {
        Person person = personMapper.getPersonById(workspaceId, personId);
        if (person == null || person.getWorkspaceId() != workspaceId
                || person.getArchivedAt() != null
                || person.getSuspendedAt() != null
                || person.getProvisionCeasedAt() != null) {
            throw new ResourceNotFoundException("Person not found with id: " + personId);
        }
        return person;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }
}
