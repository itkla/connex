package ooo.klae.connex.backend.dto;

import java.time.LocalDate;

/**
 * Optional live-generation overrides.
 * @param start first included date
 * @param end last included date
 */
public record ReportGenerateRequest(LocalDate start, LocalDate end) {
}
