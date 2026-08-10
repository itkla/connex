package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A warm introduction path surfaced to the user: a target contact worth reaching, plus the best
 * bridges — warm contacts positioned to make the introduction. {@code reachType} is
 * {@code rewarm} (a previously engaged relationship gone dormant) or {@code reach} (a contact the
 * team has never engaged). The row {@code score} is the best bridge's score; bridges are ordered
 * by descending score. Assembled in {@code WarmPathService}.
 */
@Data
@NoArgsConstructor
public class WarmPathDto {
    private int targetId;
    private String targetName;
    private String targetTitle;
    private String targetCompany;
    private String targetImageUrl;
    private String targetWarmth;
    private Integer targetDaysSinceTouch;
    private String reachType;
    private int score;
    private List<WarmPathBridgeDto> bridges;
    private String asOf;
    @JsonIgnore
    private List<String> sourceState;
}
