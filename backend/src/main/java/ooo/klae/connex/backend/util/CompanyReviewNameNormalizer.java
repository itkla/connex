package ooo.klae.connex.backend.util;

import java.util.Optional;
import java.util.regex.Pattern;

/** Company legal-form comparison for weak review evidence; never a canonical identity key. */
public final class CompanyReviewNameNormalizer {

    private static final String JAPANESE_FORM =
        "(?:株式会社|有限会社|合同会社|\\(株\\)|\\(有\\)|\\(同\\))";
    private static final Pattern PREFIX = Pattern.compile("^" + JAPANESE_FORM + "\\s*");
    private static final Pattern SUFFIX = Pattern.compile(
        "\\s*" + JAPANESE_FORM + "$|(?:^|[\\s,]+)"
            + "(?:inc\\.?|incorporated|co\\.?\\s*,?\\s*ltd\\.?|ltd\\.?|limited|k\\.?\\s*k\\.?|gmbh|l\\.?l\\.?c\\.?)$");

    private CompanyReviewNameNormalizer() {
    }

    /**
     * Removes supported boundary legal forms after canonical Unicode normalization.
     * @param raw authored company name
     * @return review name, or empty for invalid or legal-form-only input
     */
    public static Optional<String> normalize(String raw) {
        return CanonicalNameNormalizer.normalize(raw).flatMap(normalized -> {
            String current = normalized;
            while (true) {
                String stripped = SUFFIX.matcher(PREFIX.matcher(current).replaceFirst(""))
                    .replaceFirst("").strip();
                if (stripped.equals(current)) {
                    return stripped.isEmpty() ? Optional.empty() : Optional.of(stripped);
                }
                current = stripped;
            }
        });
    }
}
