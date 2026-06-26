package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A mutual connection between two contacts in the warm-introduction graph. Stored once per pair
 * with {@code sourcePersonId < targetPersonId}; traversed in both directions. Mapped via
 * {@code PersonEdgeMapper}.
 */
@Data
@NoArgsConstructor
public class PersonEdge {
    private int id;
    private int workspaceId;
    private int sourcePersonId;
    private int targetPersonId;
    private String type;
    private int strength;
    private String note;
    private String createdAt;
}
