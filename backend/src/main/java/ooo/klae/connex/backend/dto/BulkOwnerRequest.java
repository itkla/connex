package ooo.klae.connex.backend.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to assign an owner across many deals. A {@code null} ownerId unassigns the owner.
 * Capped at 1000 ids per call.
 */
@Data
@NoArgsConstructor
public class BulkOwnerRequest {
    @NotEmpty
    @Size(max = 1000)
    private List<@NotNull @Positive Integer> ids = new ArrayList<>();

    @Positive
    private Integer ownerId;
}
