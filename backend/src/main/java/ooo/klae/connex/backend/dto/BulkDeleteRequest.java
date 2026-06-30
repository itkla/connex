package ooo.klae.connex.backend.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to delete many records by id. Capped at 1000 ids per call. */
@Data
@NoArgsConstructor
public class BulkDeleteRequest {
    @NotEmpty
    @Size(max = 1000)
    private List<@Positive Integer> ids = new ArrayList<>();
}
