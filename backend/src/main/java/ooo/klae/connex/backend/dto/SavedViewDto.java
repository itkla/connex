package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.NoArgsConstructor;
import ooo.klae.connex.backend.beans.SavedView;
import tools.jackson.databind.JsonNode;

/** API representation of a saved view with caller-relative ownership and preference state. */
@Data
@NoArgsConstructor
public class SavedViewDto {
    private int id;
    private int workspaceId;
    private String recordType;
    private String name;
    private String visibility;
    private int ownerUserId;
    private boolean ownedByCurrentUser;
    private JsonNode config;
    private int position;
    private boolean pinned;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Integer pinPosition;

    @JsonProperty("default")
    private boolean defaultView;

    private String createdAt;
    private String updatedAt;

    /** Maps a saved-view projection to its canonical API representation. */
    public static SavedViewDto from(SavedView view) {
        SavedViewDto dto = new SavedViewDto();
        dto.setId(view.getId());
        dto.setWorkspaceId(view.getWorkspaceId());
        dto.setRecordType(view.getRecordType());
        dto.setName(view.getName());
        dto.setVisibility(view.getVisibility());
        dto.setOwnerUserId(view.getUserId());
        dto.setOwnedByCurrentUser(view.isOwnedByCurrentUser());
        dto.setConfig(view.getConfig());
        dto.setPosition(view.getPosition());
        dto.setPinned(view.isPinned());
        dto.setPinPosition(view.getPinPosition());
        dto.setDefaultView(view.isDefaultView());
        dto.setCreatedAt(view.getCreatedAt());
        dto.setUpdatedAt(view.getUpdatedAt());
        return dto;
    }
}
