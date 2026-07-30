package ooo.klae.connex.backend.dto;

/**
 * Bounded name-candidate lookup tuple.
 *
 * @param normalizedName canonical name used for exact Java comparison
 * @param pattern escaped broad SQL candidate pattern
 */
public record DuplicateNameKey(String normalizedName, String pattern) {
}
