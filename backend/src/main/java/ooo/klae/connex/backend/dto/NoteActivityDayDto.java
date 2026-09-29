package ooo.klae.connex.backend.dto;

import java.time.LocalDate;

/** Body-free count of visible authored notes created on one UTC calendar day. */
public record NoteActivityDayDto(LocalDate date, long count) {}
