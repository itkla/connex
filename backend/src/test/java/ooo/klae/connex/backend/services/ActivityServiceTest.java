package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.ActivityMapper;

class ActivityServiceTest extends AbstractServiceTest {

    @Autowired ActivityService activityService;
    @Autowired ActivityMapper activityMapper;

    private Activity draft(String subject, User spoofedCreatedBy, String timestamp) {
        Activity activity = new Activity();
        activity.setType("call");
        activity.setSubject(subject);
        activity.setCreatedBy(spoofedCreatedBy);
        activity.setTimestamp(timestamp);
        return activity;
    }

    @Test
    void create_attributesCreatedByToSession_ignoringClient() {
        User other = newUser();
        Activity created = activityService.create(draft("intro", other, "2024-01-01 09:00:00"));
        Activity found = activityMapper.getActivityById(workspace.getId(), created.getId());
        assertEquals(currentUser.getId(), found.getCreatedBy().getId());
    }

    @Test
    void create_defaultsTimestampWhenBlank() {
        Activity created = activityService.create(draft("no-time", null, null));
        Activity found = activityMapper.getActivityById(workspace.getId(), created.getId());
        assertNotNull(found.getTimestamp());
        assertFalse(found.getTimestamp().isBlank());
    }

    @Test
    void update_preservesOriginalCreatedBy_ignoringClient() {
        Activity created = activityService.create(draft("first", null, "2024-01-01 09:00:00"));
        User other = newUser();
        activityService.update(created.getId(), draft("edited", other, "2024-02-02 10:00:00"));
        Activity found = activityMapper.getActivityById(workspace.getId(), created.getId());
        assertEquals(currentUser.getId(), found.getCreatedBy().getId());
        assertEquals("edited", found.getSubject());
    }

    @Test
    void getActivitiesPage_pagesInDatabaseOrder() {
        Activity oldest = activityService.create(draft("oldest", null, "2024-01-01 09:00:00"));
        Activity middle = activityService.create(draft("middle", null, "2024-02-01 09:00:00"));
        Activity newest = activityService.create(draft("newest", null, "2024-03-01 09:00:00"));

        List<Activity> page = activityService.getActivitiesPage(2, 1);

        assertEquals(List.of(middle.getId(), oldest.getId()), page.stream().map(Activity::getId).toList());
        assertFalse(page.stream().anyMatch(activity -> activity.getId() == newest.getId()));
    }

    @Test
    void getActivitiesPage_appliesPersonFilterInDatabase() {
        Company company = newCompany();
        Person person = newPerson(company);
        Person other = newPerson(company);
        Activity oldest = activityService.create(draftForPerson("oldest", person, "2024-01-01 09:00:00"));
        Activity middle = activityService.create(draftForPerson("middle", person, "2024-02-01 09:00:00"));
        Activity newest = activityService.create(draftForPerson("newest", person, "2024-03-01 09:00:00"));
        activityService.create(draftForPerson("other", other, "2024-04-01 09:00:00"));

        List<Activity> page = activityService.getActivitiesPage(person.getId(), null, null, 2, 1);

        assertEquals(List.of(middle.getId(), oldest.getId()), page.stream().map(Activity::getId).toList());
        assertFalse(page.stream().anyMatch(activity -> activity.getId() == newest.getId()));
    }

    private Activity draftForPerson(String subject, Person person, String timestamp) {
        Activity activity = draft(subject, null, timestamp);
        activity.setPerson(person);
        return activity;
    }
}
