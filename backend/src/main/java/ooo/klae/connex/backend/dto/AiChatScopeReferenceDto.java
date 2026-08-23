package ooo.klae.connex.backend.dto;

/**
 * One authorized record referenced by an interpreted assistant query scope.
 *
 * @param id tenant-local identifier the caller already supplied and may use again
 * @param label projected display name, empty when the record has no name
 */
public record AiChatScopeReferenceDto(int id, String label) {
}
