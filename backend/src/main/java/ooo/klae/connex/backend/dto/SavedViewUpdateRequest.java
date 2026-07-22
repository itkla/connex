package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/** Request body for replacing a saved view while retaining omitted visibility or position values. */
@Data
@NoArgsConstructor
public class SavedViewUpdateRequest {
    @NotBlank
    @Size(max = 16)
    private String recordType;

    @NotBlank
    @Size(max = 128)
    private String name;

    @Pattern(regexp = "private|workspace")
    private String visibility;

    @NotNull
    private JsonNode config;

    @PositiveOrZero
    private Integer position;
}
