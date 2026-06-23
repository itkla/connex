package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import ooo.klae.connex.backend.beans.NotificationPreference;

/**
 * Notification preference payload.
 */
@Data
@NoArgsConstructor
public class NotificationPreferenceDto {
    @NotBlank
    @Size(max = 64)
    private String type;

    @NotBlank
    @Size(max = 32)
    private String channel;

    private boolean enabled;

    public static NotificationPreferenceDto from(NotificationPreference preference) {
        NotificationPreferenceDto dto = new NotificationPreferenceDto();
        dto.setType(preference.getType());
        dto.setChannel(preference.getChannel());
        dto.setEnabled(preference.isEnabled());
        return dto;
    }
}