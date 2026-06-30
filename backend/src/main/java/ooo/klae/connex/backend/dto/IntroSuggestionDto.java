package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A suggested reverse introduction: a pair of contacts the team is positioned to introduce because
 * it knows both but they are not yet connected to each other. Carries each contact's display
 * attributes and warmth band, the ranking score, and the human-readable reason codes
 * ({@code mutual_connections}, {@code shared_company}) behind the suggestion. Assembled in
 * {@code IntroductionService}; ordered by descending {@code score}.
 */
@Data
@NoArgsConstructor
public class IntroSuggestionDto {
    private int personAId;
    private String personAName;
    private String personATitle;
    private String personACompany;
    private String personAImageUrl;
    private String personAWarmth;
    private int personBId;
    private String personBName;
    private String personBTitle;
    private String personBCompany;
    private String personBImageUrl;
    private String personBWarmth;
    private int score;
    private List<String> reasons;
    private int mutualConnections;
    private String sharedCompany;
}
