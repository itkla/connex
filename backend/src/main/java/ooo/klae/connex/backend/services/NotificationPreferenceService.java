package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.NotificationPreference;
import ooo.klae.connex.backend.dto.NotificationPreferenceDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.PreferenceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

/**
 * Authenticated preference operations and exact/wildcard fallback.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {
    private static final String IN_APP = "in_app";
    private static final String EMAIL = "email";

    private final PreferenceMapper preferenceMapper;
    private final AuthService authService;
    private final WorkspaceService workspaceService;
    private final NotificationChangePublisher notificationChanges;

    public List<NotificationPreferenceDto> getCurrentPreferences() {
        int userId = authService.getCurrentUser().getId();
        return preferenceMapper.findByUser(userId).stream()
            .map(NotificationPreferenceDto::from)
            .toList();
    }

    @Transactional
    public List<NotificationPreferenceDto> updateCurrentPreferences(
        List<NotificationPreferenceDto> preferences
    ) {
        int userId = authService.getCurrentUser().getId();
        if (preferences == null) {
            throw new BadRequestException("Preferences are required");
        }
        for (NotificationPreferenceDto dto : preferences) {
            validate(dto);
            NotificationPreference preference = new NotificationPreference();
            preference.setUserId(userId);
            preference.setType(dto.getType().trim());
            preference.setChannel(dto.getChannel().trim());
            preference.setEnabled(dto.isEnabled());
            preferenceMapper.upsert(preference);
        }
        notificationChanges.publish(workspaceService.getCurrentWorkspaceId(), "preference", userId);
        return getCurrentPreferences();
    }

    public boolean isEnabled(int userId, String type, String channel) {
        return preferenceMapper.isEnabled(userId, type, channel);
    }

    private static void validate(NotificationPreferenceDto dto) {
        if (dto == null || dto.getType() == null || dto.getType().isBlank()) {
            throw new BadRequestException("Preference type is required");
        }
        if (!IN_APP.equals(dto.getChannel()) && !EMAIL.equals(dto.getChannel())) {
            throw new BadRequestException("Only the in_app and email notification channels are supported");
        }
    }
}