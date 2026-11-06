package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A single row that could not be imported, with the zero-based row index and a reason. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RowError {
    private int rowIndex;
    private String reason;
    private List<ImportProblem> problems;

    public RowError(int rowIndex, String reason) {
        this(rowIndex, reason, null);
    }
}
