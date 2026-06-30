package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An introduction the team makes between two of its contacts — the "give side" of the
 * relationship graph (issue #43). A {@code made} row is intro lineage; a {@code dismissed}
 * row suppresses a suggested pair. Pairs are unordered and stored with
 * {@code personAId < personBId}. Mapped via {@code IntroductionMapper} / {@code IntroductionMapper.xml}.
 */
@Data
@NoArgsConstructor
public class Introduction {
    private int id;
    private int workspaceId;
    private int introducerUserId;
    private int personAId;
    private int personBId;
    private String status;
    private String note;
    private String introducedAt;
    private String createdAt;
    private String updatedAt;
}
