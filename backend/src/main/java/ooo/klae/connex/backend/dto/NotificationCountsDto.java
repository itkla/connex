package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unread active notification counts.
 */
@Data
@NoArgsConstructor
public class NotificationCountsDto {
    private long unread;
}