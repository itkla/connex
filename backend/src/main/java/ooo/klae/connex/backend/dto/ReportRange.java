package ooo.klae.connex.backend.dto;

import java.time.LocalDate;

/**
 * Optional inclusive calendar range used by custom-cadence reports.
 * @param start first included date
 * @param end last included date
 */
public record ReportRange(LocalDate start, LocalDate end) {
}
