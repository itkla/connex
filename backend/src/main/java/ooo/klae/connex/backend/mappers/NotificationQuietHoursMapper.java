package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.NotificationQuietHours;

/**
 * Control-plane persistence for per-user notification quiet hours.
 */
public interface NotificationQuietHoursMapper {
    NotificationQuietHours findByUserId(@Param("userId") int userId);

    int upsert(NotificationQuietHours quietHours);
}
