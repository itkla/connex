package ooo.klae.connex.backend.dto;

import java.time.LocalDate;

import ooo.klae.connex.backend.beans.Notification;

/** Validated active deal-close notification and its canonical source version. */
public record NotificationWorkItem(
    Notification notification,
    LocalDate expectedCloseDate,
    String currentVersion
) {
}
