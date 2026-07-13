package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * One constant-offset UTC interval used to bucket report events in the user's local timezone.
 * @param startUtc inclusive UTC interval start
 * @param endUtc exclusive UTC interval end
 * @param offsetMinutes local offset from UTC within the interval
 */
public record ReportOffsetSegment(LocalDateTime startUtc, LocalDateTime endUtc, int offsetMinutes) {
}
