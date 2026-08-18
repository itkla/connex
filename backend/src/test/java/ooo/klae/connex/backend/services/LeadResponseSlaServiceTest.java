package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.beans.PersonFirstResponseState;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.PersonBreachRow;
import ooo.klae.connex.backend.dto.PersonLifecycleRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

/**
 * The first-response SLA clock, the breach sweep, and the browser state it feeds (#559,
 * increment 4b of {@code docs/LEAD_LIFECYCLE.md}). Verifies the idempotency guards each write
 * depends on, that a breach escalates through the rule engine exactly once, that a late answer
 * keeps its breach as evidence, and that the clock stays inside the owning workspace.
 */
class LeadResponseSlaServiceTest extends AbstractServiceTest {

    @Autowired LeadResponseSlaService slaService;
    @Autowired LeadResponseSlaScheduler slaScheduler;
    @Autowired PersonLifecycleService lifecycleService;
    @Autowired PersonService personService;
    @Autowired ActivityService activityService;
    @Autowired ShareMapper shareMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean RuleTriggerPublisher ruleTriggers;
    @MockitoBean NotificationChangePublisher notificationChanges;

    @Test
    void startingTheClockSetsADeadlineAndNeverExtendsARunningOne() {
        Person person = newPerson(newCompany());

        assertTrue(slaService.startFirstResponseClock(person.getId(), 4));
        Person under = personMapper.getPersonById(workspace.getId(), person.getId());
        assertNotNull(under.getFirstResponseDueAt());
        assertNull(under.getFirstRespondedAt());
        assertNull(under.getFirstResponseBreachedAt());

        assertFalse(slaService.startFirstResponseClock(person.getId(), 100));
        assertEquals(under.getFirstResponseDueAt(),
            personMapper.getPersonById(workspace.getId(), person.getId()).getFirstResponseDueAt());
    }

    @Test
    void aClockRequiresAPositiveBoundedDeadline() {
        Person person = newPerson(newCompany());

        assertThrows(BadRequestException.class,
            () -> slaService.startFirstResponseClock(person.getId(), null));
        assertThrows(BadRequestException.class,
            () -> slaService.startFirstResponseClock(person.getId(), 0));
        assertThrows(BadRequestException.class,
            () -> slaService.startFirstResponseClock(person.getId(), 24 * 365 + 1));

        assertNull(personMapper.getPersonById(workspace.getId(), person.getId())
            .getFirstResponseDueAt());
    }

    @Test
    void loggingAnActivityRecordsTheFirstResponseAndOnlyTheFirst() {
        Person person = newPerson(newCompany());
        slaService.startFirstResponseClock(person.getId(), 4);

        logActivity(person, "Called back");
        Person answered = personMapper.getPersonById(workspace.getId(), person.getId());
        assertNotNull(answered.getFirstRespondedAt());

        logActivity(person, "Called again");
        assertEquals(answered.getFirstRespondedAt(),
            personMapper.getPersonById(workspace.getId(), person.getId()).getFirstRespondedAt());
    }

    @Test
    void anActivityOnAContactUnderNoSlaLeavesTheClockColumnsAlone() {
        Person person = newPerson(newCompany());

        logActivity(person, "Coffee");

        Person after = personMapper.getPersonById(workspace.getId(), person.getId());
        assertNull(after.getFirstResponseDueAt());
        assertNull(after.getFirstRespondedAt());
    }

    @Test
    void thePassedDeadlineBreachesOnceAndEscalatesThroughTheRuleEngine() {
        Person person = newPerson(newCompany());
        slaService.startFirstResponseClock(person.getId(), 4);
        expireDeadline(person);

        assertEquals(List.of(person.getId()), breachingIds(workspace.getId()));
        assertTrue(slaService.recordBreach(workspace.getId(), breachRow(person)));

        assertNotNull(personMapper.getPersonById(workspace.getId(), person.getId())
            .getFirstResponseBreachedAt());
        verify(ruleTriggers).publish(
            workspace.getId(), "person", person.getId(), "person.first_response_overdue");

        assertTrue(breachingIds(workspace.getId()).isEmpty());
        assertFalse(slaService.recordBreach(workspace.getId(), breachRow(person)));
        verify(ruleTriggers).publish(
            workspace.getId(), "person", person.getId(), "person.first_response_overdue");
    }

    @Test
    void aResponseThatLandsBeforeTheSweepPreventsTheBreach() {
        Person person = newPerson(newCompany());
        slaService.startFirstResponseClock(person.getId(), 4);
        expireDeadline(person);
        logActivity(person, "Answered just in time to matter");

        assertTrue(breachingIds(workspace.getId()).isEmpty());
        assertFalse(slaService.recordBreach(workspace.getId(), breachRow(person)));

        assertNull(personMapper.getPersonById(workspace.getId(), person.getId())
            .getFirstResponseBreachedAt());
        verify(ruleTriggers, never()).publish(
            workspace.getId(), "person", person.getId(), "person.first_response_overdue");
    }

    @Test
    void aLateAnswerIsRecordedWithoutErasingTheBreachItArrivedAfter() {
        Person person = newPerson(newCompany());
        slaService.startFirstResponseClock(person.getId(), 4);
        expireDeadline(person);
        slaService.recordBreach(workspace.getId(), breachRow(person));

        logActivity(person, "Finally called");

        Person late = personMapper.getPersonById(workspace.getId(), person.getId());
        assertNotNull(late.getFirstRespondedAt());
        assertNotNull(late.getFirstResponseBreachedAt());
        assertEquals(List.of(person.getId()), filteredIds(PersonFirstResponseState.RESPONDED, false));
        assertTrue(filteredIds(PersonFirstResponseState.OVERDUE, false).isEmpty());
    }

    @Test
    void theSweepDrainsTheBacklogAndStopsWhenNothingIsOverdue() {
        Person first = newPerson(newCompany());
        Person second = newPerson(newCompany());
        Person answered = newPerson(newCompany());
        List.of(first, second, answered).forEach(p -> slaService.startFirstResponseClock(p.getId(), 4));
        expireDeadline(first);
        expireDeadline(second);
        expireDeadline(answered);
        logActivity(answered, "Handled");

        LeadResponseSlaScheduler.BatchResult result = slaScheduler.sweepBatches(workspace.getId());

        assertEquals(2, result.attemptedCount());
        assertEquals(0, result.failedCount());
        assertNotNull(personMapper.getPersonById(workspace.getId(), first.getId())
            .getFirstResponseBreachedAt());
        assertNotNull(personMapper.getPersonById(workspace.getId(), second.getId())
            .getFirstResponseBreachedAt());
        assertNull(personMapper.getPersonById(workspace.getId(), answered.getId())
            .getFirstResponseBreachedAt());

        assertEquals(0, slaScheduler.sweepBatches(workspace.getId()).attemptedCount());
    }

    @Test
    void leavingTheLifecyclePassClearsTheClockItBelongedTo() {
        Person withdrawn = enterLifecycle(newPerson(newCompany()));
        slaService.startFirstResponseClock(withdrawn.getId(), 4);
        expireDeadline(withdrawn);
        slaService.recordBreach(workspace.getId(), breachRow(withdrawn));

        lifecycleService.withdrawFromLifecycle(withdrawn.getId(), "not a prospect after all");

        Person cleared = personMapper.getPersonById(workspace.getId(), withdrawn.getId());
        assertNull(cleared.getFirstResponseDueAt());
        assertNull(cleared.getFirstRespondedAt());
        assertNull(cleared.getFirstResponseBreachedAt());
    }

    @Test
    void recyclingAContactStartsItsNextPassWithoutTheOldDeadline() {
        Person person = enterLifecycle(newPerson(newCompany()));
        slaService.startFirstResponseClock(person.getId(), 4);
        lifecycleService.updateLifecycle(person.getId(), request(PersonLifecycleStage.DISQUALIFIED));
        lifecycleService.updateLifecycle(person.getId(), request(PersonLifecycleStage.RECYCLED));

        assertNull(personMapper.getPersonById(workspace.getId(), person.getId())
            .getFirstResponseDueAt());
        assertTrue(slaService.startFirstResponseClock(person.getId(), 8));
    }

    @Test
    void aSharedInContactKeepsItsSlaInsideTheOwningWorkspace() {
        Person person = newPerson(newCompany());
        slaService.startFirstResponseClock(person.getId(), 4);
        expireDeadline(person);
        slaService.recordBreach(workspace.getId(), breachRow(person));

        Workspace grantee = siblingWorkspace();
        shareMapper.sharePerson(
            person.getId(), workspace.getId(), grantee.getId(), currentUser.getId(), false);
        User outsider = newUser();
        workspaceMapper.addMember(grantee.getId(), outsider.getId(), "owner");
        authenticateAs(outsider, grantee.getId());

        Person shared = personMapper.getPersonById(grantee.getId(), person.getId());
        assertNotNull(shared, "the share itself must still be visible");
        assertNull(shared.getFirstResponseDueAt());
        assertNull(shared.getFirstRespondedAt());
        assertNull(shared.getFirstResponseBreachedAt());

        assertThrows(ResourceNotFoundException.class,
            () -> slaService.startFirstResponseClock(person.getId(), 4));
        assertEquals(0L, personService.countPersons(null, null, null, false,
            MemberScope.allTeam(), null, false, null, false,
            List.of(PersonFirstResponseState.OVERDUE), false, false));
        assertTrue(breachingIds(grantee.getId()).isEmpty());
    }

    @Test
    void theBrowserFiltersEachSlaStateSeparately() {
        Person pending = newPerson(newCompany());
        Person overdue = newPerson(newCompany());
        Person responded = newPerson(newCompany());
        Person none = newPerson(newCompany());
        slaService.startFirstResponseClock(pending.getId(), 4);
        slaService.startFirstResponseClock(overdue.getId(), 4);
        slaService.startFirstResponseClock(responded.getId(), 4);
        expireDeadline(overdue);
        slaService.recordBreach(workspace.getId(), breachRow(overdue));
        logActivity(responded, "Replied");

        assertEquals(List.of(pending.getId()), filteredIds(PersonFirstResponseState.PENDING, false));
        assertEquals(List.of(overdue.getId()), filteredIds(PersonFirstResponseState.OVERDUE, false));
        assertEquals(List.of(responded.getId()), filteredIds(PersonFirstResponseState.RESPONDED, false));
        assertTrue(filteredIds(null, true).contains(none.getId()));
        assertFalse(filteredIds(null, true).contains(pending.getId()));

        Map<String, Long> facets = personService.countsByFirstResponseState().stream()
            .collect(Collectors.toMap(FacetCount::getKey, FacetCount::getCount));
        assertEquals(1L, facets.get(PersonFirstResponseState.PENDING.name()));
        assertEquals(1L, facets.get(PersonFirstResponseState.OVERDUE.name()));
        assertEquals(1L, facets.get(PersonFirstResponseState.RESPONDED.name()));
        assertTrue(facets.getOrDefault("__none__", 0L) >= 1L);
    }

    private List<Integer> filteredIds(PersonFirstResponseState state, boolean noFirstResponse) {
        return personService.getPersonsPage(null, null, null, null, null, false,
                MemberScope.allTeam(), null, false, null, false,
                state == null ? null : List.of(state), noFirstResponse, false, 100, 0)
            .stream().map(Person::getId).toList();
    }

    @Test
    void aRestrictedContactIsNeitherSweptNorBreached() {
        Person suspended = newPerson(newCompany());
        Person ceased = newPerson(newCompany());
        slaService.startFirstResponseClock(suspended.getId(), 4);
        slaService.startFirstResponseClock(ceased.getId(), 4);
        expireDeadline(suspended);
        expireDeadline(ceased);
        personService.updateProcessingRestrictions(suspended.getId(), true, false);
        personService.updateProcessingRestrictions(ceased.getId(), false, true);

        assertTrue(breachingIds(workspace.getId()).isEmpty());
        assertFalse(slaService.recordBreach(workspace.getId(), breachRow(suspended)));
        assertFalse(slaService.recordBreach(workspace.getId(), breachRow(ceased)));

        assertNull(personMapper.getPersonById(workspace.getId(), suspended.getId())
            .getFirstResponseBreachedAt());
        assertNull(personMapper.getPersonById(workspace.getId(), ceased.getId())
            .getFirstResponseBreachedAt());
        verify(ruleTriggers, never()).publish(
            workspace.getId(), "person", suspended.getId(), "person.first_response_overdue");
    }

    @Test
    void liftingARestrictionLetsTheStillOverdueDeadlineEscalate() {
        Person person = newPerson(newCompany());
        slaService.startFirstResponseClock(person.getId(), 4);
        expireDeadline(person);
        personService.updateProcessingRestrictions(person.getId(), true, false);
        assertTrue(breachingIds(workspace.getId()).isEmpty());

        personService.updateProcessingRestrictions(person.getId(), false, false);

        assertEquals(List.of(person.getId()), breachingIds(workspace.getId()));
        assertTrue(slaService.recordBreach(workspace.getId(), breachRow(person)));
        verify(ruleTriggers).publish(
            workspace.getId(), "person", person.getId(), "person.first_response_overdue");
    }

    @Test
    void theSweepEscalatesTheLongestWaitingLeadFirst() {
        Person older = newPerson(newCompany());
        Person newer = newPerson(newCompany());
        slaService.startFirstResponseClock(newer.getId(), 4);
        slaService.startFirstResponseClock(older.getId(), 4);
        expireDeadline(newer, "2021-01-01 00:00:00");
        expireDeadline(older, "2020-01-01 00:00:00");

        assertEquals(List.of(older.getId(), newer.getId()), breachingIds(workspace.getId()));
    }

    @Test
    void aClosedPassKeepsTheResponseOutcomeTheContactNoLongerHolds() {
        Person person = enterLifecycle(newPerson(newCompany()));
        slaService.startFirstResponseClock(person.getId(), 4);
        logActivity(person, "Called back");

        lifecycleService.withdrawFromLifecycle(person.getId(), "not a prospect after all");

        Person cleared = personMapper.getPersonById(workspace.getId(), person.getId());
        assertNull(cleared.getFirstRespondedAt(), "the contact's live clock is cleared as designed");

        Map<String, Object> pass = jdbcTemplate.queryForMap(
            "SELECT first_response_started_at, first_responded_at, ended_at "
                + "FROM person_lifecycle_pass WHERE workspace_id = ? AND person_id = ?",
            workspace.getId(), person.getId());
        assertNotNull(pass.get("first_responded_at"),
            "the pass keeps the response so historical reporting cannot change retroactively");
        assertNotNull(pass.get("first_response_started_at"));
        assertNotNull(pass.get("ended_at"));
    }

    @Test
    void recyclingOpensASecondPassSoLatencyIsMeasuredWithinIt() {
        Person person = enterLifecycle(newPerson(newCompany()));
        lifecycleService.updateLifecycle(person.getId(), request(PersonLifecycleStage.DISQUALIFIED));
        lifecycleService.updateLifecycle(person.getId(), request(PersonLifecycleStage.RECYCLED));
        lifecycleService.updateLifecycle(person.getId(), request(PersonLifecycleStage.NEW));

        Integer passes = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person_lifecycle_pass WHERE workspace_id = ? AND person_id = ?",
            Integer.class, workspace.getId(), person.getId());
        assertEquals(2, passes, "each entry into the lifecycle is its own cohort member");

        Integer open = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person_lifecycle_pass "
                + "WHERE workspace_id = ? AND person_id = ? AND ended_at IS NULL",
            Integer.class, workspace.getId(), person.getId());
        assertEquals(1, open, "exactly one pass is open at a time");
    }

    @Test
    void aRecycledContactWorkedStraightToQualifiedStillGetsAPass() {
        Person person = enterLifecycle(newPerson(newCompany()));
        lifecycleService.updateLifecycle(person.getId(), request(PersonLifecycleStage.DISQUALIFIED));
        lifecycleService.updateLifecycle(person.getId(), request(PersonLifecycleStage.RECYCLED));

        lifecycleService.updateLifecycle(person.getId(), request(PersonLifecycleStage.WORKING));
        lifecycleService.updateLifecycle(person.getId(), request(PersonLifecycleStage.QUALIFIED));

        Integer qualifiedPasses = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person_lifecycle_pass "
                + "WHERE workspace_id = ? AND person_id = ? AND qualified_at IS NOT NULL",
            Integer.class, workspace.getId(), person.getId());
        assertEquals(1, qualifiedPasses,
            "RECYCLED may go straight to WORKING, so entry is defined by where the contact came "
                + "from; treating only NEW as an entry left the milestone with no pass to land on");
    }

    @Test
    void thePassCreditsTheOwnerWhoQualifiedItNotAlaterAssignee() {
        User first = newUser();
        User second = newUser();
        Person person = enterLifecycle(newPerson(newCompany()));
        personService.updateOwner(person.getId(), first.getId());
        lifecycleService.updateLifecycle(person.getId(), request(PersonLifecycleStage.QUALIFIED));

        personService.updateOwner(person.getId(), second.getId());

        Integer owner = jdbcTemplate.queryForObject(
            "SELECT owner_id FROM person_lifecycle_pass WHERE workspace_id = ? AND person_id = ?",
            Integer.class, workspace.getId(), person.getId());
        assertEquals(first.getId(), owner,
            "reassignment must not move credit for work already done");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aClockStartingWhileThePassClosesNeverAttachesToTheWrongPass() throws Exception {
        Person person = enterLifecycle(newPerson(newCompany()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> clock = executor.submit(() -> afterStart(ready, start, () ->
                slaService.startFirstResponseClock(person.getId(), 4)));
            Future<?> withdrawal = executor.submit(() -> afterStart(ready, start, () ->
                lifecycleService.withdrawFromLifecycle(person.getId(), "closing")));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            settle(clock);
            settle(withdrawal);
        } finally {
            start.countDown();
        }

        List<Map<String, Object>> passes = jdbcTemplate.queryForList(
            "SELECT entered_at, ended_at, first_response_started_at, first_responded_at "
                + "FROM person_lifecycle_pass WHERE workspace_id = ? AND person_id = ?",
            workspace.getId(), person.getId());
        assertEquals(1, passes.size(), "the race must not fork the ledger into two passes");
        for (Map<String, Object> pass : passes) {
            Object started = pass.get("first_response_started_at");
            if (started != null) {
                assertTrue(((java.sql.Timestamp) started).toLocalDateTime()
                        .isBefore(((java.sql.Timestamp) pass.get("entered_at")).toLocalDateTime())
                        == false,
                    "a clock may only be attached to a pass it started within");
            }
        }
    }

    private static void afterStart(CountDownLatch ready, CountDownLatch start, Runnable work) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent lifecycle work did not start");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        work.run();
    }

    /** Either outcome is legal; only a corrupt ledger is not, so failures are absorbed here. */
    private static void settle(Future<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException expected) {
            // one side losing the race is a legitimate outcome
        }
    }

    private List<Integer> breachingIds(int workspaceId) {
        return slaService.findBreaches(workspaceId, 50).stream().map(PersonBreachRow::id).toList();
    }

    private PersonBreachRow breachRow(Person person) {
        return new PersonBreachRow(person.getId(), person.getName());
    }

    private Workspace siblingWorkspace() {
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        return sibling;
    }

    private void logActivity(Person person, String subject) {
        Activity activity = new Activity();
        activity.setType("call");
        activity.setSubject(subject);
        activity.setPerson(person);
        activityService.create(activity);
    }

    private void expireDeadline(Person person) {
        expireDeadline(person, "2020-01-01 00:00:00");
    }

    private void expireDeadline(Person person, String dueAt) {
        jdbcTemplate.update(
            "UPDATE person SET first_response_due_at = ? WHERE workspace_id = ? AND id = ?",
            java.sql.Timestamp.valueOf(dueAt), workspace.getId(), person.getId());
    }

    private Person enterLifecycle(Person person) {
        return lifecycleService.updateLifecycle(person.getId(), request(PersonLifecycleStage.NEW));
    }

    private static PersonLifecycleRequest request(PersonLifecycleStage stage) {
        PersonLifecycleRequest request = new PersonLifecycleRequest();
        request.setStage(stage);
        if (stage == PersonLifecycleStage.DISQUALIFIED) {
            request.setReason(PersonDisqualificationReason.NO_FIT);
        }
        return request;
    }
}
