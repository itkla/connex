package ooo.klae.connex.backend.ai.riskrationale;

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
import ooo.klae.connex.backend.dto.DealRationaleDto;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Generates presentation-only deal-risk rationales through the audited AI invocation boundary,
 * reusing a persisted output while the deal's assessed context is unchanged.
 */
@Service
@RequiredArgsConstructor
public class DealRiskRationaleService {
    static final int MAX_TOKENS = 1536;
    static final int MAX_ACTIONS = 6;
    static final int MAX_NARRATIVE_CHARS = 1200;
    static final int MAX_ACTION_CHARS = 280;
    static final double TEMPERATURE = 0.2;

    private static final String FEATURE = "deal.risk_rationale";
    private static final String NOT_CONFIGURED = "not_configured";
    private static final String PROVIDER_ERROR = "provider_error";
    private static final String NOT_AT_RISK = "not_at_risk";

    private final DealRiskRationaleAssembler dealRiskRationaleAssembler;
    private final AiInvocationService aiInvocationService;
    private final AiFeatureGate aiFeatureGate;
    private final DealRiskService dealRiskService;
    private final AiOutputCacheStore aiOutputCacheStore;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    /**
     * Generates or reuses a fresh rationale for a workspace-scoped at-risk deal.
     * @param dealId deal whose deterministic risk signals should be explained
     * @return available rationale or a graceful unavailability response
     */
    public DealRationaleDto generate(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!aiFeatureGate.isAiUsable()) {
            return DealRationaleDto.unavailable(dealId, NOT_CONFIGURED);
        }

        DealRiskDto risk = dealRiskService.assessDeal(workspaceId, dealId);
        if (risk == null || "none".equals(risk.getLevel())
                || risk.getFactors() == null || risk.getFactors().isEmpty()) {
            return DealRationaleDto.unavailable(dealId, NOT_AT_RISK);
        }

        RationaleAssembly assembly = dealRiskRationaleAssembler.assemble(workspaceId, dealId, risk);
        String contentHash = aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context());
        DealRationaleDto cached = cached(workspaceId, dealId, contentHash);
        if (cached != null) {
            return cached;
        }

        try {
            AiStructuredOutcome<DealRiskRationaleContent> outcome = aiInvocationService.completeStructured(
                    new AiInvocation(FEATURE, assembly.context(), assembly.prompt(), MAX_TOKENS, TEMPERATURE),
                    DealRiskRationaleContent.class);
            if (!(outcome instanceof AiStructuredOutcome.Parsed<DealRiskRationaleContent> parsed)) {
                return DealRationaleDto.unavailable(dealId, PROVIDER_ERROR);
            }
            DealRiskRationaleContent content = parsed.value();
            if (content == null || isBlank(content.narrative())) {
                return DealRationaleDto.unavailable(dealId, PROVIDER_ERROR);
            }
            String narrative = truncate(content.narrative().strip(), MAX_NARRATIVE_CHARS);
            List<String> actions = actions(content.actions());
            String generatedAt = Instant.now(clock).toString();
            aiOutputCacheStore.save(workspaceId, FEATURE, dealId, AiOutputCacheStore.NO_SUBJECT,
                    contentHash, new DealRiskRationaleContent(narrative, actions), parsed.demaskWarnings(), generatedAt);
            return DealRationaleDto.of(dealId, narrative, actions, generatedAt, parsed.demaskWarnings());
        } catch (MaskingLeakException | AiProviderException exception) {
            return DealRationaleDto.unavailable(dealId, PROVIDER_ERROR);
        } catch (ForbiddenException exception) {
            return DealRationaleDto.unavailable(dealId, NOT_CONFIGURED);
        }
    }

    private DealRationaleDto cached(int workspaceId, int dealId, String contentHash) {
        Optional<AiOutputCache> row = aiOutputCacheStore.find(
                workspaceId, FEATURE, dealId, AiOutputCacheStore.NO_SUBJECT);
        if (row.isEmpty() || !contentHash.equals(row.get().getContentHash())) {
            return null;
        }
        Optional<DealRiskRationaleContent> content = aiOutputCacheStore.read(
                row.get().getPayload(), DealRiskRationaleContent.class);
        if (content.isEmpty() || isBlank(content.get().narrative())) {
            return null;
        }
        return DealRationaleDto.of(
                dealId,
                truncate(content.get().narrative().strip(), MAX_NARRATIVE_CHARS),
                actions(content.get().actions()),
                row.get().getGeneratedAt(),
                row.get().getWarnings());
    }

    private static List<String> actions(List<String> actions) {
        if (actions == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String action : actions) {
            if (isBlank(action)) {
                continue;
            }
            cleaned.add(truncate(action.strip(), MAX_ACTION_CHARS));
            if (cleaned.size() == MAX_ACTIONS) {
                break;
            }
        }
        return List.copyOf(cleaned);
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
