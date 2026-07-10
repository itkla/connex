package ooo.klae.connex.backend.ai.masking;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Advisory, conservative JP/EN free-text screen for suspected special-care data. It returns only
 * categories, never matched substrings or offsets, never persists matched text, and never rewrites
 * customer records. False positives are acceptable because suspected special-care free text is
 * excluded from AI payloads rather than masked and sent.
 */
public final class SpecialCareTextScreen {
    private static final Map<SpecialCareCategory, List<Pattern>> CATEGORY_PATTERNS = buildPatterns();

    private SpecialCareTextScreen() {
    }

    /**
     * Verdict for a free-text value. The shape intentionally cannot carry matched text.
     * @param excluded whether the text must be excluded from provider payloads
     * @param categories suspected special-care categories
     */
    public record ScreenVerdict(boolean excluded, Set<SpecialCareCategory> categories) {

        public ScreenVerdict {
            categories = Set.copyOf(Objects.requireNonNull(categories, "categories"));
        }
    }

    /**
     * Screens free text for suspected special-care content.
     * @param freeText uncontrolled free-text CRM value
     * @return exclusion verdict with categories only
     */
    public static ScreenVerdict screen(String freeText) {
        if (freeText == null || freeText.isBlank()) {
            return new ScreenVerdict(false, Set.of());
        }
        EnumSet<SpecialCareCategory> categories = EnumSet.noneOf(SpecialCareCategory.class);
        for (Map.Entry<SpecialCareCategory, List<Pattern>> entry : CATEGORY_PATTERNS.entrySet()) {
            if (matchesAny(entry.getValue(), freeText)) {
                categories.add(entry.getKey());
            }
        }
        return new ScreenVerdict(!categories.isEmpty(), categories);
    }

    private static boolean matchesAny(List<Pattern> patterns, String freeText) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(freeText).find()) {
                return true;
            }
        }
        return false;
    }

    private static Map<SpecialCareCategory, List<Pattern>> buildPatterns() {
        EnumMap<SpecialCareCategory, List<Pattern>> patterns = new EnumMap<>(SpecialCareCategory.class);
        patterns.put(SpecialCareCategory.MEDICAL, compileTerms(List.of(
                "medical history", "diagnosis", "hospitalized", "treatment", "chronic illness",
                "病歴", "診断", "通院", "入院", "治療", "疾患", "がん")));
        patterns.put(SpecialCareCategory.CRIMINAL_RECORD, compileTerms(List.of(
                "criminal record", "conviction", "arrested", "probation", "felony",
                "前科", "逮捕", "有罪", "犯罪歴", "刑事事件")));
        patterns.put(SpecialCareCategory.SOCIAL_STATUS, compileTerms(List.of(
                "public assistance", "welfare recipient", "homeless", "caste", "social status",
                "生活保護", "被差別部落", "ホームレス", "社会的身分")));
        patterns.put(SpecialCareCategory.DISABILITY, compileTerms(List.of(
                "disability", "disabled", "wheelchair", "autism", "mobility impairment",
                "障害", "障がい", "車椅子", "自閉症", "身体障害")));
        patterns.put(SpecialCareCategory.UNION, compileTerms(List.of(
                "labor union", "trade union", "union member", "collective bargaining",
                "労働組合", "組合員", "団体交渉")));
        patterns.put(SpecialCareCategory.RELIGION, compileTerms(List.of(
                "religion", "religious belief", "christian", "muslim", "buddhist",
                "信仰", "宗教", "キリスト教", "イスラム教", "仏教")));
        patterns.put(SpecialCareCategory.RACE_ETHNICITY, compileTerms(List.of(
                "ethnicity", "ethnic origin", "racial origin", "indigenous identity",
                "民族", "人種", "出身民族", "在日", "先住民")));
        return Map.copyOf(patterns);
    }

    private static List<Pattern> compileTerms(List<String> terms) {
        List<Pattern> patterns = new ArrayList<>();
        for (String term : terms) {
            patterns.add(Pattern.compile(regexFor(term), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return List.copyOf(patterns);
    }

    private static String regexFor(String term) {
        String quoted = Pattern.quote(term);
        if (isAsciiPhrase(term)) {
            return "(?<![\\p{L}\\p{N}_])" + quoted + "(?![\\p{L}\\p{N}_])";
        }
        return quoted;
    }

    private static boolean isAsciiPhrase(String term) {
        for (int index = 0; index < term.length(); index++) {
            if (term.charAt(index) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
