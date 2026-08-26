package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Bounded coverage and freshness disclosure for one structured assistant answer.
 *
 * <p>Property inclusion is pinned to ALWAYS so the application-wide {@code non_null} inclusion
 * cannot drop the nullable instants. The browser declares them as required nullable fields and
 * compares them against null, so an omitted key would silently read as "present but unknown".
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatCoverageDto(
        String status,
        String asOf,
        String periodStart,
        String periodEnd,
        List<String> sources,
        List<String> exclusions,
        boolean truncated) {

    public AiChatCoverageDto {
        sources = sources == null ? List.of() : List.copyOf(sources);
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
    }
}
