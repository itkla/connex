package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * A versioned records-list configuration owned by one workspace member and optionally visible to
 * every active member of that workspace. Caller-relative pin/default projections are populated by
 * saved-view mapper reads.
 */
@Data
@NoArgsConstructor
public class SavedView {
    private int id;
    private int workspaceId;
    private int userId;
    private String recordType;
    private String name;
    private JsonNode config;
    private String visibility;
    private int position;
    private boolean ownedByCurrentUser;
    private boolean pinned;
    private Integer pinPosition;
    private boolean defaultView;
    private String createdAt;
    private String updatedAt;
}
