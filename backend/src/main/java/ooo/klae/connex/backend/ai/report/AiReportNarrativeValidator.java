package ooo.klae.connex.backend.ai.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

/**
 * Fail-closed validator for model-authored report claims and their deterministic citations.
 */
final class AiReportNarrativeValidator {
    static final int MAX_SECTIONS = 12;
    static final int MAX_CLAIMS_PER_SECTION = 12;
    static final int MAX_FINDINGS = 12;
    static final int MAX_SOURCES_PER_CLAIM = 12;
    static final int MAX_TITLE_CHARS = 160;
    static final int MAX_CLAIM_CHARS = 1600;

    private AiReportNarrativeValidator() {
    }

    /**
     * Validates, bounds, and normalizes structured model content.
     * @param content parsed model output or cached content
     * @param context deterministic source registry used to ground the output
     * @return sanitized content, or empty when any claim is not fully grounded
     */
    static Optional<AiReportNarrativeContent> validate(
            AiReportNarrativeContent content, AiReportContext context) {
        if (content == null || content.sections() == null || content.findings() == null
                || content.sections().isEmpty() || content.findings().isEmpty()
                || content.sections().size() > MAX_SECTIONS
                || content.findings().size() > MAX_FINDINGS) {
            return Optional.empty();
        }
        Map<String, Set<String>> supportedClaims = supportedClaims(context.sources());
        List<AiReportNarrativeContent.Section> sections = new ArrayList<>();
        for (AiReportNarrativeContent.Section section : content.sections()) {
            Optional<AiReportNarrativeContent.Section> validated = validateSection(section, supportedClaims);
            if (validated.isEmpty()) {
                return Optional.empty();
            }
            sections.add(validated.get());
        }
        Optional<List<AiReportNarrativeContent.Claim>> findings = validateClaims(
                content.findings(), MAX_FINDINGS, supportedClaims);
        if (findings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AiReportNarrativeContent(List.copyOf(sections), findings.get()));
    }

    private static Optional<AiReportNarrativeContent.Section> validateSection(
            AiReportNarrativeContent.Section section,
            Map<String, Set<String>> supportedClaims) {
        if (section == null || section.title() == null || section.title().isBlank()) {
            return Optional.empty();
        }
        String title = section.title().strip();
        if (!AiReportFacts.titleSet().contains(title)) {
            return Optional.empty();
        }
        Optional<List<AiReportNarrativeContent.Claim>> claims = validateClaims(
                section.claims(), MAX_CLAIMS_PER_SECTION, supportedClaims);
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AiReportNarrativeContent.Section(truncate(title, MAX_TITLE_CHARS), claims.get()));
    }

    private static Optional<List<AiReportNarrativeContent.Claim>> validateClaims(
            List<AiReportNarrativeContent.Claim> claims,
            int maxClaims,
            Map<String, Set<String>> supportedClaims) {
        if (claims == null || claims.isEmpty() || claims.size() > maxClaims) {
            return Optional.empty();
        }
        List<AiReportNarrativeContent.Claim> validated = new ArrayList<>();
        for (AiReportNarrativeContent.Claim claim : claims) {
            Optional<AiReportNarrativeContent.Claim> normalized = validateClaim(claim, supportedClaims);
            if (normalized.isEmpty()) {
                return Optional.empty();
            }
            validated.add(normalized.get());
        }
        return Optional.of(List.copyOf(validated));
    }

    private static Optional<AiReportNarrativeContent.Claim> validateClaim(
            AiReportNarrativeContent.Claim claim,
            Map<String, Set<String>> supportedClaims) {
        if (claim == null || claim.text() == null || claim.text().isBlank()
                || claim.sourceIds() == null || claim.sourceIds().isEmpty()
                || claim.sourceIds().size() != 1) {
            return Optional.empty();
        }
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        for (String sourceId : claim.sourceIds()) {
            if (sourceId == null || sourceId.isBlank() || !supportedClaims.containsKey(sourceId)) {
                return Optional.empty();
            }
            sourceIds.add(sourceId);
        }
        if (sourceIds.isEmpty()) {
            return Optional.empty();
        }
        String text = claim.text().strip();
        String sourceId = sourceIds.getFirst();
        if (!supportedClaims.get(sourceId).contains(text)) {
            return Optional.empty();
        }
        return Optional.of(new AiReportNarrativeContent.Claim(
                truncate(text, MAX_CLAIM_CHARS), List.copyOf(sourceIds)));
    }

    private static Map<String, Set<String>> supportedClaims(List<ReportAppendixRowDto> sources) {
        Map<String, Set<String>> claims = new LinkedHashMap<>();
        for (ReportAppendixRowDto source : sources) {
            claims.put(source.sourceId(), Set.copyOf(AiReportFacts.claims(source)));
        }
        return Map.copyOf(claims);
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
