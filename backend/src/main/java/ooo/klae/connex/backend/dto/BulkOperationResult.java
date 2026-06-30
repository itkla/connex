package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outcome of a bulk record mutation: how many records succeeded, how many failed, and a
 * per-record reason for each failure. Each {@link RowError} carries the zero-based index of the
 * failed id within the request's id list, so a partially-applied batch is reported rather than
 * failing the whole operation silently.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkOperationResult {
    private int succeeded;
    private int failed;
    private List<RowError> errors;
}
