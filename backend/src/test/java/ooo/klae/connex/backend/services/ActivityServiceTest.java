package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
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
}
