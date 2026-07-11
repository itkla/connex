package ooo.klae.connex.backend.ai.brief;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiOutputCacheStore;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.dto.DealBriefDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Generates presentation-only deal briefs through the audited AI invocation boundary, reusing a
 * persisted output while the deal's assembled context is unchanged.
 */
@Service
@RequiredArgsConstructor
public class DealBriefService {
    static final int MAX_TOKENS = 2048;
    static final int MAX_SECTIONS = 8;
    static final int MAX_TITLE_CHARS = 160;
    static final int MAX_BODY_CHARS = 2000;
    static final double TEMPERATURE = 0.2;

    private static final String FEATURE = "deal.brief";
    private static final String NOT_CONFIGURED = "not_configured";
    private static final String PROVIDER_ERROR = "provider_error";

    private final DealBriefAssembler dealBriefAssembler;
    private final AiInvocationService aiInvocationService;
    private final AiFeatureGate aiFeatureGate;
    private final AiOutputCacheStore aiOutputCacheStore;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    /**
     * Generates or reuses a fresh brief for a workspace-scoped deal.
     * @param dealId deal to summarize
     * @return available brief or a graceful unavailability response
     */
    public DealBriefDto generate(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!aiFeatureGate.isAiUsable()) {
            return DealBriefDto.unavailable(dealId, NOT_CONFIGURED);
        }

        BriefAssembly assembly = dealBriefAssembler.assemble(workspaceId, dealId);
        String contentHash = aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context());
        DealBriefDto cached = cached(workspaceId, dealId, contentHash);
        if (cached != null) {
            return cached;
        }

        try {
            AiStructuredOutcome<DealBriefContent> outcome = aiInvocationService.completeStructured(
                    new AiInvocation(FEATURE, assembly.context(), assembly.prompt(), MAX_TOKENS, TEMPERATURE),
                    DealBriefContent.class);
            if (!(outcome instanceof AiStructuredOutcome.Parsed<DealBriefContent> parsed)) {
                return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
            }
            List<DealBriefContent.Section> sections = sections(parsed.value());
            if (sections.isEmpty()) {
                return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
            }
            String generatedAt = Instant.now(clock).toString();
            aiOutputCacheStore.save(workspaceId, FEATURE, dealId, AiOutputCacheStore.NO_SUBJECT,
                    contentHash, new DealBriefContent(sections), parsed.demaskWarnings(), generatedAt);
            return DealBriefDto.of(dealId, toDtoSections(sections), generatedAt, parsed.demaskWarnings());
        } catch (MaskingLeakException | AiProviderException exception) {
            return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
        } catch (ForbiddenException exception) {
            return DealBriefDto.unavailable(dealId, NOT_CONFIGURED);
        }
    }

    private DealBriefDto cached(int workspaceId, int dealId, String contentHash) {
        Optional<AiOutputCache> row = aiOutputCacheStore.find(
                workspaceId, FEATURE, dealId, AiOutputCacheStore.NO_SUBJECT);
        if (row.isEmpty() || !contentHash.equals(row.get().getContentHash())) {
            return null;
        }
        Optional<DealBriefContent> content = aiOutputCacheStore.read(row.get().getPayload(), DealBriefContent.class);
        if (content.isEmpty()) {
            return null;
        }
        List<DealBriefContent.Section> sections = sections(content.get());
        if (sections.isEmpty()) {
            return null;
        }
        return DealBriefDto.of(
                dealId, toDtoSections(sections), row.get().getGeneratedAt(), row.get().getWarnings());
    }

    private static List<DealBriefContent.Section> sections(DealBriefContent content) {
        if (content == null || content.sections() == null) {
            return List.of();
        }
        List<DealBriefContent.Section> sections = new ArrayList<>();
        for (DealBriefContent.Section section : content.sections()) {
            if (section == null || isBlank(section.title()) || isBlank(section.body())) {
                continue;
            }
            sections.add(new DealBriefContent.Section(
                    truncate(section.title().strip(), MAX_TITLE_CHARS),
                    truncate(section.body().strip(), MAX_BODY_CHARS)));
            if (sections.size() == MAX_SECTIONS) {
                break;
            }
        }
        return List.copyOf(sections);
    }

    private static List<DealBriefDto.Section> toDtoSections(List<DealBriefContent.Section> sections) {
        return sections.stream()
                .map(section -> new DealBriefDto.Section(section.title(), section.body()))
                .toList();
    }

    private static String truncate(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints - 1);
        return value.substring(0, end) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
