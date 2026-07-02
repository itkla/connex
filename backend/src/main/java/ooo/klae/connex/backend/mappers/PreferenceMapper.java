package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.NotificationPreference;

/**
 * Mapper for notification channel preferences.
 */
public interface PreferenceMapper {
    List<NotificationPreference> findByUser(@Param("userId") int userId);

    List<NotificationPreference> findByWorkspaceAndChannel(
        @Param("workspaceId") int workspaceId,
        @Param("channel") String channel
    );

    boolean isEnabled(
        @Param("userId") int userId,
        @Param("type") String type,
        @Param("channel") String channel
    );

    boolean isEnabledOptIn(
        @Param("userId") int userId,
        @Param("type") String type,
        @Param("channel") String channel
    );

    int upsert(NotificationPreference preference);
}