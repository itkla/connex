package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body to connect a contact to another contact in the warm-intro graph.
 */
@Data
@NoArgsConstructor
public class ConnectionRequestDto {
    @NotNull
    private Integer targetPersonId;
    @Size(max = 32)
    private String type;
    private Integer strength;
    @Size(max = 255)
    private String note;
}
