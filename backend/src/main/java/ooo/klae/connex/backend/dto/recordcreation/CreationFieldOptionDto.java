package ooo.klae.connex.backend.dto.recordcreation;

public record CreationFieldOptionDto(
    String value,
    LocalizedTextDto label,
    boolean disabled
) {
}
