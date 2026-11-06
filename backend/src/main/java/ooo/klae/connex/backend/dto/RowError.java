package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A single row that could not be imported, with a zero-based index, legacy reason, and optional structured validation details. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RowError {
    private int rowIndex;
    private String reason;
    private List<String> errors;

    /** Preserves the reason-only contract for importers without structured row validation. */
    public RowError(int rowIndex, String reason) {
        this(rowIndex, reason, null);
    }
}
