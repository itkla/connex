package ooo.klae.connex.backend.businesscard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.dto.BusinessCardScanResponse;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.CompanyCandidate;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.FieldCandidate;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.Fields;

/**
 * Deterministically extracts English and Japanese contact fields from OCR lines.
 */
@Component
public class BusinessCardExtractor {
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![a-z0-9._%+\\-])[a-z0-9._%+\\-]{1,64}@[a-z0-9](?:[a-z0-9.\\-]{0,251}[a-z0-9])?\\.[a-z]{2,63}(?![a-z0-9._%+\\-])");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?\\d[\\d() .\\-]{6,28}\\d)(?!\\d)");
    private static final Pattern NAME_LABEL = Pattern.compile(
            "(?iu)^(?:name|full name|氏名|お名前)\\s*[:：]\\s*(.+)$");
    private static final Pattern COMPANY_LABEL = Pattern.compile(
            "(?iu)^(?:company|organization|organisation|会社|所属)\\s*[:：]\\s*(.+)$");
    private static final Pattern TITLE_LABEL = Pattern.compile(
            "(?iu)^(?:title|position|role|役職|肩書)\\s*[:：]\\s*(.+)$");
    private static final Pattern COMPANY_SUFFIX = Pattern.compile(
            "(?iu)(?:\\b(?:incorporated|inc|llc|ltd|limited|corp|corporation|company|co|gmbh|plc)\\.?$|株式会社|有限会社|合同会社|合資会社|一般社団法人|公益社団法人|財団法人)");
    private static final Pattern COMPANY_WORD = Pattern.compile(
            "(?iu)(?:\\b(?:labs?|studio|solutions?|systems?|group|holdings?|technologies|technology|partners?|bank|university)\\b|銀行|大学|研究所|事務所|病院)");
    private static final Pattern TITLE_WORD = Pattern.compile(
            "(?iu)(?:\\b(?:chief|ceo|cto|cfo|coo|founder|president|vice president|vp|director|manager|engineer|consultant|sales|marketing|designer|developer|partner|attorney|admiral|mathematician|professor|architect|scientist|fellow|chair)\\b|代表取締役|取締役|社長|副社長|専務|常務|部長|次長|課長|係長|マネージャー|エンジニア|デザイナー|コンサルタント|営業|顧問|代表)");
    private static final Pattern ENGLISH_NAME = Pattern.compile(
            "(?iu)^[\\p{L}][\\p{L}'’\\-]+(?:\\s+[\\p{L}][\\p{L}'’\\-]+){1,4}$");
    private static final Pattern JAPANESE_NAME = Pattern.compile(
            "^[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}々・ー]{1,12}(?:\\s+[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}々・ー]{1,12}){1,2}$");
    private static final Pattern WEB = Pattern.compile("(?iu)(?:https?://|www\\.|\\.(?:com|net|org|jp)\\b)");
    private static final Pattern CONTACT_LABEL = Pattern.compile(
            "(?iu)^(?:e-?mail|mail|tel|telephone|phone|mobile|mob|fax|〒|address|web|url)\\s*[:：]");
    private static final double LOW_CONFIDENCE = 0.70;

    /**
     * Extracts editable candidates and warnings without mutating or persisting OCR output.
     *
     * @param rawLines validated sidecar response
     * @return typed scan draft
     */
    public BusinessCardScanResponse extract(List<OcrLine> rawLines) {
        List<Line> lines = normalizedLines(rawLines);
        Candidate email = email(lines);
        Candidate phone = phone(lines);
        Candidate title = title(lines);
        Candidate company = company(lines);
        Candidate name = name(lines, title, company);

        Set<String> warnings = new LinkedHashSet<>();
        int recognizedContactFields = count(name, email, phone, title);
        if (recognizedContactFields == 0 && company == null) {
            warnings.add("no_recognizable_fields");
        } else if (name == null || (email == null && phone == null)) {
            warnings.add("partial_result");
        }
        addConfidenceWarning(warnings, "name", name);
        addConfidenceWarning(warnings, "email", email);
        addConfidenceWarning(warnings, "phone", phone);
        addConfidenceWarning(warnings, "title", title);
        addConfidenceWarning(warnings, "company", company);

        return new BusinessCardScanResponse(
                new Fields(field(name), field(email), field(phone), field(title)),
                new CompanyCandidate(value(company), confidence(company), null),
                List.copyOf(warnings));
    }

    private static List<Line> normalizedLines(List<OcrLine> rawLines) {
        if (rawLines == null || rawLines.isEmpty()) {
            return List.of();
        }
        List<OcrLine> sorted = rawLines.stream()
                .filter(line -> line != null && line.text() != null && !line.text().isBlank())
                .sorted(Comparator.comparingInt(OcrLine::verticalCenter).thenComparingInt(OcrLine::xMin))
                .toList();
        int maxHeight = sorted.stream().mapToInt(OcrLine::height).max().orElse(1);
        List<Line> normalized = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            OcrLine source = sorted.get(i);
            String text = BusinessCardTextNormalizer.text(source.text());
            if (!text.isBlank()) {
                normalized.add(new Line(i, text, clamp(source.confidence()),
                        source.height() / (double) Math.max(1, maxHeight)));
            }
        }
        return List.copyOf(normalized);
    }

    private static Candidate email(List<Line> lines) {
        Candidate best = null;
        for (Line line : lines) {
            String compact = line.text().replace(" ", "");
            Matcher matcher = EMAIL.matcher(compact);
            while (matcher.find()) {
                String value = matcher.group();
                int at = value.indexOf('@');
                value = value.substring(0, at) + value.substring(at).toLowerCase(Locale.ROOT);
                Candidate candidate = new Candidate(value, line.confidence(), line.index(), 1);
                best = better(best, candidate);
            }
        }
        return best;
    }

    private static Candidate phone(List<Line> lines) {
        Candidate best = null;
        for (Line line : lines) {
            if (EMAIL.matcher(line.text().replace(" ", "")).find()) {
                continue;
            }
            Matcher matcher = PHONE.matcher(line.text());
            while (matcher.find()) {
                String raw = matcher.group().trim();
                String digits = raw.replaceAll("\\D", "");
                if (digits.length() < 8 || digits.length() > 15) {
                    continue;
                }
                boolean phoneLabel = Pattern.compile("(?iu)(?:tel|telephone|phone|mobile|mob|携帯|電話)")
                        .matcher(line.text()).find();
                boolean fax = Pattern.compile("(?iu)fax").matcher(line.text()).find();
                boolean postal = line.text().contains("〒") && !phoneLabel;
                if (postal || fax) {
                    continue;
                }
                String value = raw.startsWith("+") ? "+" + digits : digits;
                double evidence = phoneLabel ? 1 : 0.85;
                Candidate candidate = new Candidate(value, line.confidence(), line.index(), evidence);
                best = better(best, candidate);
            }
        }
        return best;
    }

    private static Candidate title(List<Line> lines) {
        Candidate best = null;
        for (Line line : lines) {
            Matcher label = TITLE_LABEL.matcher(line.text());
            if (label.matches()) {
                best = better(best, candidate(label.group(1), line, 1));
                continue;
            }
            if (TITLE_WORD.matcher(line.text()).find()
                    && !EMAIL.matcher(line.text().replace(" ", "")).find()
                    && !PHONE.matcher(line.text()).find()) {
                best = better(best, candidate(line.text(), line, 0.9));
            }
        }
        return best;
    }

    private static Candidate company(List<Line> lines) {
        Candidate best = null;
        Candidate uppercaseFallback = null;
        for (Line line : lines) {
            Matcher label = COMPANY_LABEL.matcher(line.text());
            if (label.matches()) {
                best = better(best, candidate(label.group(1), line, 1));
                continue;
            }
            boolean suffix = COMPANY_SUFFIX.matcher(line.text()).find();
            boolean companyWord = COMPANY_WORD.matcher(line.text()).find();
            boolean uppercase = uppercaseWordmark(line.text());
            if (!TITLE_WORD.matcher(line.text()).find()
                    && !CONTACT_LABEL.matcher(line.text()).find()
                    && !EMAIL.matcher(line.text().replace(" ", "")).find()
                    && !PHONE.matcher(line.text()).find()
                    && !WEB.matcher(line.text()).find()) {
                if (suffix || companyWord) {
                    double evidence = (suffix ? 0.98 : 0.88)
                            + Math.min(0.08, line.heightRatio() * 0.08);
                    best = better(best, candidate(line.text(), line, evidence));
                } else if (uppercase && (!personShape(line.text())
                        || hasAlternatePersonName(lines, line.index()))) {
                    double evidence = 0.76 + Math.min(0.08, line.heightRatio() * 0.08);
                    uppercaseFallback = better(
                            uppercaseFallback, candidate(line.text(), line, evidence));
                }
            }
        }
        return best == null ? uppercaseFallback : best;
    }

    private static boolean hasAlternatePersonName(List<Line> lines, int excludedIndex) {
        return lines.stream()
                .anyMatch(line -> line.index() != excludedIndex
                        && personShape(line.text())
                        && !TITLE_WORD.matcher(line.text()).find()
                        && !COMPANY_SUFFIX.matcher(line.text()).find()
                        && !COMPANY_WORD.matcher(line.text()).find());
    }

    private static Candidate name(List<Line> lines, Candidate title, Candidate company) {
        Candidate best = null;
        for (Line line : lines) {
            Matcher label = NAME_LABEL.matcher(line.text());
            if (label.matches()) {
                best = better(best, candidate(label.group(1), line, 1));
                continue;
            }
            if (sameLine(line, title) || sameLine(line, company)
                    || CONTACT_LABEL.matcher(line.text()).find()
                    || TITLE_WORD.matcher(line.text()).find()
                    || COMPANY_SUFFIX.matcher(line.text()).find()
                    || COMPANY_WORD.matcher(line.text()).find()
                    || EMAIL.matcher(line.text().replace(" ", "")).find()
                    || PHONE.matcher(line.text()).find()
                    || WEB.matcher(line.text()).find()) {
                continue;
            }
            if (!personShape(line.text())) {
                continue;
            }
            double evidence = 0.72 + Math.min(0.18, line.heightRatio() * 0.18);
            if (uppercaseWordmark(line.text())) {
                evidence -= 0.18;
            }
            best = better(best, candidate(line.text(), line, evidence));
        }
        return best;
    }

    private static boolean personShape(String text) {
        return ENGLISH_NAME.matcher(text).matches() || JAPANESE_NAME.matcher(text).matches();
    }

    private static boolean uppercaseWordmark(String text) {
        int letters = 0;
        boolean lower = false;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (Character.isLetter(codePoint)) {
                letters++;
                lower |= Character.isLowerCase(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return letters >= 3 && !lower && text.codePoints().anyMatch(Character::isUpperCase);
    }

    private static Candidate candidate(String value, Line line, double evidence) {
        String normalized = BusinessCardTextNormalizer.text(value);
        if (normalized.isBlank()) {
            return null;
        }
        double boundedEvidence = clamp(evidence);
        double confidence = clamp(line.confidence() * (0.75 + boundedEvidence * 0.25));
        return new Candidate(normalized, confidence, line.index(), boundedEvidence);
    }

    private static Candidate better(Candidate current, Candidate candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        double candidateRank = candidate.confidence() * candidate.evidence();
        double currentRank = current.confidence() * current.evidence();
        if (candidateRank > currentRank) {
            return candidate;
        }
        if (candidateRank == currentRank && candidate.lineIndex() < current.lineIndex()) {
            return candidate;
        }
        return current;
    }

    private static boolean sameLine(Line line, Candidate candidate) {
        return candidate != null && candidate.lineIndex() == line.index();
    }

    private static int count(Candidate... candidates) {
        int result = 0;
        for (Candidate candidate : candidates) {
            if (candidate != null) {
                result++;
            }
        }
        return result;
    }

    private static void addConfidenceWarning(Set<String> warnings, String field, Candidate candidate) {
        if (candidate != null && candidate.confidence() < LOW_CONFIDENCE) {
            warnings.add("low_confidence_" + field);
        }
    }

    private static FieldCandidate field(Candidate candidate) {
        return candidate == null
                ? FieldCandidate.empty()
                : new FieldCandidate(candidate.value(), candidate.confidence());
    }

    private static String value(Candidate candidate) {
        return candidate == null ? null : candidate.value();
    }

    private static Double confidence(Candidate candidate) {
        return candidate == null ? null : candidate.confidence();
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record Line(int index, String text, double confidence, double heightRatio) {
    }

    private record Candidate(
            String value,
            double confidence,
            int lineIndex,
            double evidence) {
    }
}
