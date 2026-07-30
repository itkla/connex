package ooo.klae.connex.backend.dto;

/**
 * Canonical identity lookup tuple passed to tenant-scoped persistence.
 *
 * @param kind database identity kind
 * @param normalizedValue canonical matching value
 */
public record DuplicateIdentityKey(String kind, String normalizedValue) {
}
