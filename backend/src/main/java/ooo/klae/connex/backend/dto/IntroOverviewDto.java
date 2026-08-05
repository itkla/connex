package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The introductions page's combined feed: the give-side suggestions (pairs the team can
 * introduce to each other, #43) and the receive-side warm paths (targets the team can reach via
 * a bridge, #614), computed from a single workspace warmth pass so one page view no longer
 * rescores every contact twice.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntroOverviewDto {
    private List<IntroSuggestionDto> suggestions;
    private List<WarmPathDto> paths;
    private String asOf;
    private String suggestionsEmptyReason;
    private String pathsEmptyReason;
}
