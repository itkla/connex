package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-user global quiet-hours configuration.
 */
@Data
@NoArgsConstructor
public class NotificationQuietHours {
    private int userId;
    private boolean enabled;
    private String timezone;
    private String startLocal;
    private String endLocal;
    private int daysMask;
    private String createdAt;
    private String updatedAt;
}
