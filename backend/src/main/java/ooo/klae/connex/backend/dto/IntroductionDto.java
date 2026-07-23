package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A recorded introduction for the lineage feed ("intros you've made"): the two contacts that were
 * introduced, the member who made the intro, and when. Populated directly by a MyBatis projection
 * (columns map to these fields via underscore-to-camel-case); {@code introducerName} and
 * {@code references} are hydrated separately by {@code IntroductionService}.
 */
@Data
@NoArgsConstructor
public class IntroductionDto {
    private int id;
    private int personAId;
    private String personAName;
    private String personACompany;
    private String personAImageUrl;
    private int personBId;
    private String personBName;
    private String personBCompany;
    private String personBImageUrl;
    private Integer introducerId;
    private String introducerName;
    private String note;
    private String introducedAt;
    private List<ReferenceDto> references;
}
