package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.NotificationQuietHours;
import ooo.klae.connex.backend.beans.User;

class NotificationQuietHoursMapperTest extends AbstractMapperTest {
    @Autowired private NotificationQuietHoursMapper quietHoursMapper;

    @Test
    void settingsRoundTripAndRemainUserScoped() {
        User first = newUser();
        User second = newUser();
        NotificationQuietHours quietHours = settings(first.getId(), true, "America/New_York", 31);

        assertEquals(1, quietHoursMapper.upsert(quietHours));

        NotificationQuietHours stored = quietHoursMapper.findByUserId(first.getId());
        assertTrue(stored.isEnabled());
        assertEquals("America/New_York", stored.getTimezone());
        assertEquals("22:00", stored.getStartLocal());
        assertEquals("07:00", stored.getEndLocal());
        assertEquals(31, stored.getDaysMask());
        assertNull(quietHoursMapper.findByUserId(second.getId()));

        quietHours.setEnabled(false);
        quietHours.setTimezone("Asia/Tokyo");
        quietHours.setDaysMask(127);
        assertEquals(2, quietHoursMapper.upsert(quietHours));
        stored = quietHoursMapper.findByUserId(first.getId());
        assertFalse(stored.isEnabled());
        assertEquals("Asia/Tokyo", stored.getTimezone());
        assertEquals(127, stored.getDaysMask());
    }

    private static NotificationQuietHours settings(
        int userId,
        boolean enabled,
        String timezone,
        int daysMask
    ) {
        NotificationQuietHours quietHours = new NotificationQuietHours();
        quietHours.setUserId(userId);
        quietHours.setEnabled(enabled);
        quietHours.setTimezone(timezone);
        quietHours.setStartLocal("22:00");
        quietHours.setEndLocal("07:00");
        quietHours.setDaysMask(daysMask);
        return quietHours;
    }
}
