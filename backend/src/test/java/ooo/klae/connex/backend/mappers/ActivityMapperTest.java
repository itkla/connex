package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;

class ActivityMapperTest extends AbstractMapperTest {

    @Autowired ActivityMapper activityMapper;

    private Activity build(String type, String subject, Person person, Deal deal, User user) {
        Activity activity = new Activity();
        activity.setType(type);
        activity.setSubject(subject);
        activity.setNotes("notes-" + unique());
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(user);
        activity.setTimestamp("2024-06-01 10:00:00");
        return activity;
    }

    /**
     * Inserts a new activity and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        User user = newUser();
        Activity activity = build("call", "Intro call", null, null, user);

        activityMapper.insert(activity);

        assertNotEquals(0, activity.getId());
    }

    /**
     * Gets an activity by ID and checks if the returned activity is not null.
     */
    @Test
    void getActivityById_returnsInsertedRow() {
        User user = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);

        Activity activity = build("meeting", "Kickoff", person, deal, user);
        activityMapper.insert(activity);

        Activity found = activityMapper.getActivityById(activity.getId());

        assertNotNull(found);
        assertEquals("meeting", found.getType());
        assertEquals("Kickoff", found.getSubject());
        assertEquals(activity.getNotes(), found.getNotes());
        assertEquals(person.getId(), found.getPerson().getId());
        assertEquals(deal.getId(), found.getDeal().getId());
        assertEquals(user.getId(), found.getCreatedBy().getId());
        assertNotNull(found.getTimestamp());
    }

    /**
     * Gets an activity by ID and checks if the returned activity is null when the ID is negative.
     */
    @Test
    void getActivityById_returnsNullWhenMissing() {
        assertNull(activityMapper.getActivityById(-1));
    }

    /**
     * Inserts a new activity and checks if the person and deal are null when they are not provided.
     */
    @Test
    void insert_acceptsNullPersonAndDeal() {
        User user = newUser();
        Activity activity = build("email", "FYI", null, null, user);

        activityMapper.insert(activity);

        Activity found = activityMapper.getActivityById(activity.getId());
        assertNotNull(found);
        // Result map always materializes the association; with null FK the association id is 0.
        assertTrue(found.getPerson() == null || found.getPerson().getId() == 0);
        assertTrue(found.getDeal() == null || found.getDeal().getId() == 0);
    }

    /**
     * Gets all activities and checks if the returned list includes the inserted activity.
     */
    @Test
    void getAllActivities_includesInsertedRow() {
        User user = newUser();
        Activity activity = build("call", "Hello", null, null, user);
        activityMapper.insert(activity);

        List<Activity> allActivities = activityMapper.getAllActivities();

        assertTrue(allActivities.stream().anyMatch(x -> x.getId() == activity.getId()));
    }

    /**
     * Updates an activity and checks if the new values are persisted.
     */
    @Test
    void update_persistsNewValues() {
        User user = newUser();
        Activity activity = build("call", "Initial", null, null, user);
        activityMapper.insert(activity);

        activity.setType("email");
        activity.setSubject("Updated");
        activity.setNotes("changed");
        activity.setTimestamp("2024-12-01 12:30:00");

        activityMapper.update(activity);

        Activity found = activityMapper.getActivityById(activity.getId());
        assertEquals("email", found.getType());
        assertEquals("Updated", found.getSubject());
        assertEquals("changed", found.getNotes());
    }

    /**
     * Deletes an activity and checks if the activity is removed.
     */
    @Test
    void delete_removesRow() {
        User user = newUser();
        Activity activity = build("call", "Bye", null, null, user);
        activityMapper.insert(activity);

        activityMapper.delete(activity.getId());

        assertNull(activityMapper.getActivityById(activity.getId()));
    }

    /**
     * Gets activities by person ID and checks if the returned list includes the inserted activity.
     */
    @Test
    void getActivitiesByPersonId_filtersByPerson() {
        User user = newUser();
        Person person1 = newPerson(newCompany());
        Person person2 = newPerson(newCompany());

        Activity activity1 = build("call", "for p1", person1, null, user);
        Activity activity2 = build("call", "for p2", person2, null, user);
        activityMapper.insert(activity1);
        activityMapper.insert(activity2);

        List<Activity> matched = activityMapper.getActivitiesByPersonId(person1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == activity1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == activity2.getId()));
    }

    /**
     * Gets activities by deal ID and checks if the returned list includes the inserted activity.
     */
    @Test
    void getActivitiesByDealId_filtersByDeal() {
        User user = newUser();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal1 = newDeal(pipeline, stage, newCompany());
        Deal deal2 = newDeal(pipeline, stage, newCompany());

        Activity activity1 = build("call", "d1", null, deal1, user);
        Activity activity2 = build("call", "d2", null, deal2, user);
        activityMapper.insert(activity1);
        activityMapper.insert(activity2);

        List<Activity> matched = activityMapper.getActivitiesByDealId(deal1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == activity1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == activity2.getId()));
    }

    /**
     * Gets activities by created by ID and checks if the returned list includes the inserted activity.
     */
    @Test
    void getActivitiesByCreatedById_filtersByUser() {
        User user1 = newUser();
        User user2 = newUser();

        Activity activity1 = build("call", "by u1", null, null, user1);
        Activity activity2 = build("call", "by u2", null, null, user2);
        activityMapper.insert(activity1);
        activityMapper.insert(activity2);

        List<Activity> matched = activityMapper.getActivitiesByCreatedById(user1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == activity1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == activity2.getId()));
    }
}
