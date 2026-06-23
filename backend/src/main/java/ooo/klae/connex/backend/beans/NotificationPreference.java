package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-user notification channel preference.
 */
@Data
@NoArgsConstructor
public class NotificationPreference {
    private int id;
    private int userId;
    private String type;
    private String channel;
    private boolean enabled;
    private String createdAt;
    private String updatedAt;
}