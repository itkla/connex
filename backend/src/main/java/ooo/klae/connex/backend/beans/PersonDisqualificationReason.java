package ooo.klae.connex.backend.beans;

import java.util.List;
import java.util.Locale;

/** The built-in reason codes offered until a workspace customizes its vocabulary (#559). */
public final class PersonDisqualificationReason {
    public static final String CODE_PATTERN = "^[A-Z][A-Z0-9_]{1,31}$";
    public static final String NO_BUDGET = "NO_BUDGET";
    public static final String NO_FIT = "NO_FIT";
    public static final String NO_AUTHORITY = "NO_AUTHORITY";
    public static final String BAD_TIMING = "BAD_TIMING";
    public static final String COMPETITOR = "COMPETITOR";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String UNRESPONSIVE = "UNRESPONSIVE";
    public static final String SPAM = "SPAM";
    public static final String OTHER = "OTHER";

    public static final List<BuiltIn> BUILT_INS = List.of(
        new BuiltIn(NO_BUDGET, false),
        new BuiltIn(NO_FIT, false),
        new BuiltIn(NO_AUTHORITY, false),
        new BuiltIn(BAD_TIMING, false),
        new BuiltIn(COMPETITOR, false),
        new BuiltIn(DUPLICATE, false),
        new BuiltIn(UNRESPONSIVE, false),
        new BuiltIn(SPAM, false),
        new BuiltIn(OTHER, true)
    );

    private PersonDisqualificationReason() {
    }

    /** Whether a code is already in the canonical uppercase ASCII form stored by Connex. */
    public static boolean isCanonicalCode(String code) {
        return code != null && code.matches(CODE_PATTERN);
    }

    /** Localized built-in label, or the stable code when the code is not built in. */
    public static String localizedLabel(String code, Locale locale) {
        if (code == null) {
            return null;
        }
        boolean japanese = locale != null && "ja".equals(locale.getLanguage());
        return switch (code) {
            case NO_BUDGET -> japanese ? "予算なし" : "No budget";
            case NO_FIT -> japanese ? "適合しない" : "Not a fit";
            case NO_AUTHORITY -> japanese ? "決裁者に到達できない" : "No route to a decision maker";
            case BAD_TIMING -> japanese ? "時期が合わない" : "Wrong timing";
            case COMPETITOR -> japanese ? "競合他社に決定" : "Went with a competitor";
            case DUPLICATE -> japanese ? "重複" : "Duplicate";
            case UNRESPONSIVE -> japanese ? "反応なし" : "Unresponsive";
            case SPAM -> japanese ? "正規の問い合わせではない" : "Not a genuine inquiry";
            case OTHER -> japanese ? "その他" : "Other";
            default -> code;
        };
    }

    /** One immutable built-in code and its default note requirement. */
    public record BuiltIn(String code, boolean requiresNote) {
    }
}
