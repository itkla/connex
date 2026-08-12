package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Optimistic-concurrency request for resolving or reopening a comment thread. */
@Data
@NoArgsConstructor
public class RecordCommentThreadStateRequest {
    @NotNull
    @Min(0)
    private Integer expectedVersion;
}
