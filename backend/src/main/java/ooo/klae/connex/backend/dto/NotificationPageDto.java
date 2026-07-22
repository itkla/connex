package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * A recipient-scoped notification page and the monotonic state version of its database snapshot.
 */
public record NotificationPageDto(
    List<NotificationDto> items,
    long total,
    long stateVersion,
    String asOf
) {
}
