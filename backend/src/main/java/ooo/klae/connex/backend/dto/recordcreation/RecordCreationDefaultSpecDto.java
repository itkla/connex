package ooo.klae.connex.backend.dto.recordcreation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;

public record RecordCreationDefaultSpecDto(
    @NotNull RecordCreationDefaultKind kind,
    String stringValue,
    BigDecimal numberValue,
    Boolean booleanValue,
    LocalDate dateValue,
    Integer referenceId,
    @Size(max = 20) List<Integer> referenceIds
) {
}
