package ooo.klae.connex.backend.ai.masking;

import java.util.regex.Pattern;

/** Conservative fail-closed screen for generated summary and reasoning content. */
public final class AiGeneratedContentScreen {
    private static final Pattern EMAIL_ADDRESS =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE_LIKE_RUN =
            Pattern.compile("(?<![\\p{L}\\p{N}])(?:[+() .-]*[0-9]){7,}(?![\\p{L}\\p{N}])");
    private static final Pattern RAW_RECORD_ID = Pattern.compile(
            "(?:\\\"(?:record|person|company|deal)?id\\\"\\s*:\\s*[1-9][0-9]*"
                    + "|(?<![\\p{L}\\p{N}_])(?:record|person|company|deal)[ _-]?id"
                    + "\\s*(?::|=|#)\\s*[1-9][0-9]*(?![\\p{L}\\p{N}_]))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BRACED_PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*[PCDEH][1-9][0-9]*\\s*}}");
    private static final Pattern BARE_PLACEHOLDER = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])[PCDEH][1-9][0-9]*(?![\\p{L}\\p{N}_])");

    private AiGeneratedContentScreen() {
    }

    /**
     * Returns a stable content-free rejection rule for generated text.
     * @param content generated text before or after demasking
     * @return a stable rejection rule, or {@code null} when the content is safe to persist or show
     */
    public static String rejectionReason(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        if (SpecialCareTextScreen.screen(content).excluded()) {
            return "special_care_content";
        }
        if (EMAIL_ADDRESS.matcher(content).find()) {
            return "email_address";
        }
        if (PHONE_LIKE_RUN.matcher(content).find()) {
            return "phone_number";
        }
        if (RAW_RECORD_ID.matcher(content).find()) {
            return "raw_record_id";
        }
        return null;
    }

    /** Returns whether generated text contains a placeholder body outside its required braces. */
    public static boolean containsBarePlaceholder(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String withoutBraced = BRACED_PLACEHOLDER.matcher(content).replaceAll("");
        return BARE_PLACEHOLDER.matcher(withoutBraced).find();
    }

    /** Returns whether generated display text still contains any placeholder body. */
    public static boolean containsPlaceholder(String content) {
        return content != null
                && !content.isBlank()
                && BARE_PLACEHOLDER.matcher(content).find();
    }
}
