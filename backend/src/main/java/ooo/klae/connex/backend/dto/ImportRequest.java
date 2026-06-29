package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for a CSV import (preview or commit). {@code rows} are the parsed CSV rows keyed by
 * header; {@code mapping} aligns CSV columns to Connex fields; {@code onDuplicate} selects the
 * merge behaviour for matched records ("fill_empty", "skip", or "overwrite"); {@code links}
 * carries manual row-to-record overrides ({@code rowIndex -> recordId}) chosen in the review step.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportRequest {

    @NotNull
    @Size(max = 5000)
    private List<Map<String, String>> rows;

    @NotEmpty
    @Valid
    @Size(max = 256)
    private List<ColumnMapping> mapping;

    private String onDuplicate;

    private Map<Integer, Integer> links;
}
