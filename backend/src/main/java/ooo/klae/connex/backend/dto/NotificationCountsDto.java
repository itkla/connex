package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Recipient-global notification counts and quiet-hours snapshot.
 */
@Data
@NoArgsConstructor
public class NotificationCountsDto {
    private long unread;
    private long snoozed;
    private long stateVersion;
    private Long cutoffId;
    private String readAt;
    private String asOf;
    private String nextSnoozeExpiry;
    private boolean quietHoursActive;
    private String nextQuietHoursTransition;
}
