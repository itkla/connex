package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.ai.assistant.AiAssistantScopeActivity;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

/**
 * Executes the bounded assistant cohort statements against a real database.
 *
 * <p>These two statements carry the whole honesty contract of a scoped read — the attribution of an
 * activity to exactly one cohort record, the per-record window function, the tenant predicate, and
 * the switchable processing-restriction clauses — and none of it is observable from a mocked mapper.
 */
class AiAssistantScopeActivityMapperTest extends AbstractMapperTest {

    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 12, 31, 23, 59, 59);

    @Autowired private ActivityMapper activityMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * A company cohort attributes a contact's activity through the contact's employer and a
     * deal-only activity through the deal's company, so one activity belongs to exactly one record
     * and the per-record cap means what it says.
     */
    @Test
    void aCompanyCohortAttributesThroughTheContactFirstAndTheDealOtherwise() {
        User actor = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        activity(actor, person, null, "meeting", "2026-03-01 09:00:00");
        activity(actor, null, deal, "call", "2026-03-02 09:00:00");

        List<AiAssistantScopeActivity> rows = activityMapper.getAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "company",
                List.of(company.getId()), WINDOW_START, WINDOW_END, List.of(), true, 10, 50);

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.scopeRecordId() == company.getId()));
        assertEquals("call", rows.getFirst().type());
        assertEquals(2L, activityMapper.countAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "company",
                List.of(company.getId()), WINDOW_START, WINDOW_END, List.of(), true));
    }

    @Test
    void thePerRecordWindowBoundsEachCohortRecordInsideTheQuery() {
        User actor = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        activity(actor, person, null, "meeting", "2026-03-01 09:00:00");
        activity(actor, person, null, "meeting", "2026-03-02 09:00:00");
        activity(actor, person, null, "meeting", "2026-03-03 09:00:00");

        List<AiAssistantScopeActivity> rows = activityMapper.getAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "company",
                List.of(company.getId()), WINDOW_START, WINDOW_END, List.of(), true, 2, 50);

        assertEquals(2, rows.size());
        assertEquals(3L, activityMapper.countAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "company",
                List.of(company.getId()), WINDOW_START, WINDOW_END, List.of(), true));
    }

    @Test
    void aCohortIdFromAnotherWorkspaceReadsNothing() {
        User actor = newUser();
        Workspace other = newWorkspace();
        Company elsewhere = companyIn(other);
        Person person = personIn(other, elsewhere);
        Activity foreign = new Activity();
        foreign.setWorkspaceId(other.getId());
        foreign.setType("meeting");
        foreign.setSubject("Elsewhere");
        foreign.setPerson(person);
        foreign.setCreatedBy(actor);
        foreign.setTimestamp("2026-03-01 09:00:00");
        activityMapper.insert(foreign);

        assertTrue(activityMapper.getAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "company",
                List.of(elsewhere.getId()), WINDOW_START, WINDOW_END, List.of(), true, 10, 50)
                .isEmpty());
        assertEquals(0L, activityMapper.countAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "company",
                List.of(elsewhere.getId()), WINDOW_START, WINDOW_END, List.of(), true));
    }

    /**
     * The two counts are the evidence behind the {@code restricted_records} disclosure: honouring
     * restrictions must drop the row, and the unrestricted count must still see it, or a bounded
     * answer cannot tell a member that a subject was excluded.
     */
    @Test
    void aProcessingRestrictedSubjectIsExcludedOnlyWhenRestrictionsAreHonoured() {
        User actor = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        activity(actor, person, null, "meeting", "2026-03-01 09:00:00");
        jdbcTemplate.update(
                "UPDATE person SET provision_ceased_at = ? WHERE workspace_id = ? AND id = ?",
                LocalDateTime.of(2026, 2, 1, 0, 0), workspace.getId(), person.getId());

        assertEquals(0L, activityMapper.countAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "company",
                List.of(company.getId()), WINDOW_START, WINDOW_END, List.of(), true));
        assertEquals(1L, activityMapper.countAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "company",
                List.of(company.getId()), WINDOW_START, WINDOW_END, List.of(), false));
        assertTrue(activityMapper.getAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "company",
                List.of(company.getId()), WINDOW_START, WINDOW_END, List.of(), true, 10, 50)
                .isEmpty());
    }

    /**
     * A workspace in no organization contributes no sibling workspace ids. The statement must still
     * render, rather than emitting {@code IN ()} and failing every scoped read for that tenant.
     */
    @Test
    void anEmptyOrganizationScopeStillRendersAValidStatement() {
        User actor = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        activity(actor, person, null, "meeting", "2026-03-01 09:00:00");

        assertEquals(1L, activityMapper.countAiAssistantScopeActivities(
                workspace.getId(), List.of(), "company",
                List.of(company.getId()), WINDOW_START, WINDOW_END, List.of(), true));
        assertEquals(1, activityMapper.getAiAssistantScopeActivities(
                workspace.getId(), List.of(), "company",
                List.of(company.getId()), WINDOW_START, WINDOW_END, List.of(), true, 10, 50)
                .size());
    }

    @Test
    void personAndDealCohortsReadTheirOwnAttributionAndTheTypeFilterApplies() {
        User actor = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        activity(actor, person, null, "Meeting", "2026-03-01 09:00:00");
        activity(actor, null, deal, "call", "2026-03-02 09:00:00");

        List<AiAssistantScopeActivity> people = activityMapper.getAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "person",
                List.of(person.getId()), WINDOW_START, WINDOW_END, List.of("meeting"),
                true, 10, 50);
        List<AiAssistantScopeActivity> deals = activityMapper.getAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "deal",
                List.of(deal.getId()), WINDOW_START, WINDOW_END, List.of("call"), true, 10, 50);

        assertEquals(1, people.size());
        assertEquals(person.getId(), people.getFirst().scopeRecordId());
        assertEquals(1, deals.size());
        assertEquals(deal.getId(), deals.getFirst().scopeRecordId());
        assertFalse(activityMapper.getAiAssistantScopeActivities(
                workspace.getId(), List.of(workspace.getId()), "person",
                List.of(person.getId()), WINDOW_START, WINDOW_END, List.of("call"),
                true, 10, 50).stream().findAny().isPresent());
    }

    private void activity(
            User actor, Person person, Deal deal, String type, String timestamp) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType(type);
        activity.setSubject("Subject " + unique());
        activity.setNotes("Notes " + unique());
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(actor);
        activity.setTimestamp(timestamp);
        activityMapper.insert(activity);
    }

    private Company companyIn(Workspace target) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(target.getId());
        companyMapper.insert(company);
        return company;
    }

    private Person personIn(Workspace target, Company company) {
        Person person = new Person();
        person.setName("Person " + unique());
        person.setCompany(company);
        person.setWorkspaceId(target.getId());
        personMapper.insert(person);
        return person;
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setName("Scope " + unique());
        created.setSlug("scope-" + unique());
        workspaceMapper.insert(created);
        return created;
    }
}
