package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.SavedView;

/**
 * API representation of a {@code SavedView}. {@code config} is an opaque JSON object owned
 * by the client (filters, sort, search, display mode); it is stored and returned verbatim.
 */
@Data
@NoArgsConstructor
public class SavedViewDto {
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private int id;

    @NotBlank
    @Size(max = 16)
    private String recordType;

    @NotBlank
    @Size(max = 128)
    private String name;

    @NotNull
    private Object config;

    @PositiveOrZero
    private Integer position;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdAt;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String updatedAt;

    /**
     * Maps a bean to a DTO, leaving {@code config} unset (the controller resolves it from
     * the stored JSON).
     */
    public static SavedViewDto from(SavedView view) {
        SavedViewDto dto = new SavedViewDto();
        dto.setId(view.getId());
        dto.setRecordType(view.getRecordType());
        dto.setName(view.getName());
        dto.setPosition(view.getPosition());
        dto.setCreatedAt(view.getCreatedAt());
        dto.setUpdatedAt(view.getUpdatedAt());
        return dto;
    }
}
