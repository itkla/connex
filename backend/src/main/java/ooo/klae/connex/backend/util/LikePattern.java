package ooo.klae.connex.backend.util;

/**
 * Helpers for building safe SQL {@code LIKE} patterns from user input. Escapes the
 * LIKE metacharacters ({@code \ % _}) so they are matched literally rather than as
 * wildcards; the mappers rely on MySQL's default backslash escape character.
 */
public final class LikePattern {

    private LikePattern() {
    }

    /**
     * Escapes {@code \}, {@code %}, and {@code _} so they match literally inside a LIKE.
     */
    public static String escape(String input) {
        return input
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }

    /**
     * Builds a {@code %...%} "contains" pattern with the input safely escaped.
     */
    public static String containing(String input) {
        return "%" + escape(input) + "%";
    }

    /**
     * Builds a {@code ...%} "starts with" prefix pattern with the input safely escaped.
     */
    public static String starting(String input) {
        return escape(input) + "%";
    }
}
