package ooo.klae.connex.backend.dto;

/**
 * Availability of the report-definition composer for the current actor.
 * @param available whether the composer may be used
 * @param reason stable unavailability reason
 */
public record ReportComposerAvailabilityDto(
        boolean available,
        String reason) {
}
