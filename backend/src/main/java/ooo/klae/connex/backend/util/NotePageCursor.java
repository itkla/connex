package ooo.klae.connex.backend.util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** The descending update-time/id boundary of a timeline note page. */
public record NotePageCursor(LocalDateTime updatedAt, int id) {
    /** Validates a complete cursor, accepting the timestamp returned by the note DTO. */
    public static NotePageCursor parse(String beforeAt, Integer beforeId) {
        if (beforeAt == null && beforeId == null) return null;
        if (beforeAt == null || beforeId == null || beforeId <= 0 || beforeAt.length() > 40) {
            throw new BadRequestException("A note cursor requires a timestamp and positive note id");
        }
        String value = beforeAt.replace(' ', 'T');
        try {
            LocalDateTime timestamp;
            try {
                timestamp = LocalDateTime.parse(value);
            } catch (DateTimeParseException exception) {
                timestamp = OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            }
            return new NotePageCursor(timestamp, beforeId);
        } catch (DateTimeParseException exception) {
            throw new BadRequestException("Invalid note cursor timestamp");
        }
    }
}
