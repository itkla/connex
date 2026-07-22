package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One avenue to a warm-path target: a contact the team is warm with who can make the
 * introduction, with the evidence tier connecting bridge and target. {@code evidenceType} is one
 * of {@code connection} (an explicit person edge — verified), {@code colleagues} (same current
 * employer — inferred), or {@code former_colleagues} (dated tenure overlap at a past employer —
 * inferred, weakest). {@code evidenceCompany} and the overlap years label the colleague tiers.
 */
@Data
@NoArgsConstructor
public class WarmPathBridgeDto {
    private int personId;
    private String name;
    private String title;
    private String company;
    private String imageUrl;
    private String warmth;
    private String evidenceType;
    private String evidenceCompany;
    private Integer overlapStartYear;
    private Integer overlapEndYear;
    private int score;
}
