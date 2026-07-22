package ooo.klae.connex.backend.ai.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

/**
 * Grounds a model-authored report narrative. The model writes prose but never types a figure: it
 * cites deterministic sources and references figures only through {@code {{num:...}}} placeholders.
 * The pre-demask guard rejects any literal digit the model typed; the resolver fills each placeholder
 * with its exact locale-formatted value, drops claims that are not fully grounded, and fails closed
 * when the surviving structure is not viable.
 */
final class AiReportProseResolver {
    static final int MAX_SECTIONS = 3;
    static final int MAX_CLAIMS_PER_SECTION = 12;
    static final int MAX_FINDINGS = 12;
    static final int MAX_TITLE_CHARS = 160;
    static final int MAX_CLAIM_CHARS = 1600;

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^}]*?)\\s*}}");
    private static final Pattern DIGIT = Pattern.compile("\\p{Nd}");
    private static final String UNKNOWN_REFERENCE = "[unknown reference]";
    private static final List<String> INCREASE_WORDS = List.of(
            "increased", "rose", "grew", "climbed", "gained", "expanded", "improved",
            "増加", "上昇", "上回", "拡大");
    private static final List<String> DECREASE_WORDS = List.of(
            "decreased", "fell", "dropped", "declined", "shrank", "slipped", "contracted",
            "減少", "下落", "下回", "縮小", "低下");

    private AiReportProseResolver() {
    }

    /**
     * A pre-demask guard that rejects any narrative whose prose contains a literal figure. Every
     * {@code text} field must express figures through placeholders only; this runs before demasking
     * so a demasked entity name that legitimately contains digits cannot trip it.
     * @return guard over the raw masked output
     */
    static AiRawOutputGuard noLiteralFigures() {
        return AiReportProseResolver::proseHasNoLiteralDigit;
    }

    private static boolean proseHasNoLiteralDigit(JsonNode maskedOutput) {
        for (JsonNode text : maskedOutput.findValues("text")) {
            if (text.isString() && DIGIT.matcher(stripTokens(text.asString())).find()) {
                return false;
            }
        }
        return true;
    }

    static Optional<AiReportNarrativeContent> resolve(
            AiReportNarrativeContent content, AiReportContext context, AiReportFigures figures) {
        if (content == null || content.sections() == null || content.findings() == null) {
            return Optional.empty();
        }
        Map<String, ReportAppendixRowDto> sources = new LinkedHashMap<>();
        for (ReportAppendixRowDto source : context.sources()) {
            sources.put(source.sourceId(), source);
        }
        Map<String, String> titles = titleLookup();
        int[] tally = {0, 0};

        List<AiReportNarrativeContent.Section> sections = new ArrayList<>();
        for (AiReportNarrativeContent.Section section : bounded(content.sections(), MAX_SECTIONS)) {
            resolveSection(section, sources, titles, figures, tally).ifPresent(sections::add);
        }
        List<AiReportNarrativeContent.Claim> findings = resolveClaims(
                bounded(content.findings(), MAX_FINDINGS), sources, figures, tally);

        if (sections.isEmpty() || findings.isEmpty()) {
            return Optional.empty();
        }
        int emitted = tally[0];
        int dropped = tally[1];
        if (emitted == 0 || dropped * 2 > emitted) {
            return Optional.empty();
        }
        return Optional.of(new AiReportNarrativeContent(List.copyOf(sections), List.copyOf(findings)));
    }

    private static Optional<AiReportNarrativeContent.Section> resolveSection(
            AiReportNarrativeContent.Section section,
            Map<String, ReportAppendixRowDto> sources,
            Map<String, String> titles,
            AiReportFigures figures,
            int[] tally) {
        if (section == null || section.title() == null) {
            return Optional.empty();
        }
        String canonicalTitle = titles.get(normalize(section.title()));
        if (canonicalTitle == null) {
            return Optional.empty();
        }
        List<AiReportNarrativeContent.Claim> claims = resolveClaims(
                bounded(section.claims(), MAX_CLAIMS_PER_SECTION), sources, figures, tally);
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AiReportNarrativeContent.Section(
                truncate(canonicalTitle, MAX_TITLE_CHARS), claims));
    }

    private static List<AiReportNarrativeContent.Claim> resolveClaims(
            List<AiReportNarrativeContent.Claim> claims,
            Map<String, ReportAppendixRowDto> sources,
            AiReportFigures figures,
            int[] tally) {
        if (claims == null) {
            return List.of();
        }
        List<AiReportNarrativeContent.Claim> resolved = new ArrayList<>();
        for (AiReportNarrativeContent.Claim claim : claims) {
            tally[0]++;
            AiReportNarrativeContent.Claim grounded = resolveClaim(claim, sources, figures);
            if (grounded == null) {
                tally[1]++;
                continue;
            }
            resolved.add(grounded);
        }
        return resolved;
    }

    private static AiReportNarrativeContent.Claim resolveClaim(
            AiReportNarrativeContent.Claim claim, Map<String, ReportAppendixRowDto> sources,
            AiReportFigures figures) {
        if (claim == null || claim.text() == null || claim.text().isBlank()
                || claim.sourceIds() == null || claim.sourceIds().isEmpty()) {
            return null;
        }
        List<String> citations = claim.sourceIds().stream()
                .filter(sources::containsKey)
                .distinct()
                .toList();
        if (citations.isEmpty() || claim.text().contains(UNKNOWN_REFERENCE)
                || contradictsKnownDirection(claim.text(), citations, sources)) {
            return null;
        }
        String text = fillTokens(claim.text().strip(), figures, Set.copyOf(citations));
        if (text == null || text.contains("{{")) {
            return null;
        }
        return new AiReportNarrativeContent.Claim(truncate(text, MAX_CLAIM_CHARS), citations);
    }

    /**
     * Best-effort guard against a single-source claim whose directional verb contradicts the
     * deterministic change. It only fires when the claim cites exactly one source with a known,
     * non-zero direction and the prose asserts the opposite direction without also asserting the
     * true one; ambiguous or multi-source claims pass.
     */
    private static boolean contradictsKnownDirection(
            String text, List<String> citations, Map<String, ReportAppendixRowDto> sources) {
        if (citations.size() != 1) {
            return false;
        }
        ReportAppendixRowDto source = sources.get(citations.getFirst());
        if (source == null || source.priorValue() == null) {
            return false;
        }
        int direction = source.value().compareTo(source.priorValue());
        if (direction == 0) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean saysUp = containsAny(lower, INCREASE_WORDS);
        boolean saysDown = containsAny(lower, DECREASE_WORDS);
        return direction > 0 ? saysDown && !saysUp : saysUp && !saysDown;
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String fillTokens(String text, AiReportFigures figures, Set<String> citations) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!figures.has(token) || !citations.contains(figureSource(token))) {
                return null;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(figures.resolve(token)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String figureSource(String token) {
        int prefix = AiReportFigures.PREFIX.length();
        int lastDot = token.lastIndexOf('.');
        return lastDot <= prefix ? "" : token.substring(prefix, lastDot);
    }

    private static String stripTokens(String text) {
        return TOKEN.matcher(text).replaceAll(" ");
    }

    private static Map<String, String> titleLookup() {
        Map<String, String> lookup = new LinkedHashMap<>();
        for (String title : AiReportFacts.titles()) {
            lookup.put(normalize(title), title);
        }
        return lookup;
    }

    private static <T> List<T> bounded(List<T> values, int max) {
        if (values == null) {
            return List.of();
        }
        return values.size() <= max ? values : values.subList(0, max);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints - 1);
        return value.substring(0, end) + "…";
    }
}
