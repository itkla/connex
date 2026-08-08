package ooo.klae.connex.backend.ai;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.brief.DealBriefService;
import ooo.klae.connex.backend.ai.introrationale.IntroRationaleService;
import ooo.klae.connex.backend.ai.riskrationale.DealRiskRationaleService;
import ooo.klae.connex.backend.dto.AiGenerationStatusDto;
import ooo.klae.connex.backend.dto.DealBriefDto;
import ooo.klae.connex.backend.dto.DealRationaleDto;
import ooo.klae.connex.backend.dto.IntroRationaleDto;
import ooo.klae.connex.backend.tenant.Permission;

/** Feature adapters that classify existing domain results for the shared async contract. */
@Service
@RequiredArgsConstructor
public class AiGenerationAdapterService {
    private static final String NOT_CONFIGURED = "not_configured";
    private static final String PROVIDER_ERROR = "provider_error";
    private static final String RATE_LIMITED = "rate_limited";

    private final AiGenerationService aiGenerationService;
    private final DealBriefService dealBriefService;
    private final DealRiskRationaleService dealRiskRationaleService;
    private final IntroRationaleService introRationaleService;

    /** Starts or joins one deal-brief generation. */
    public AiGenerationStatusDto startDealBrief(int dealId, boolean refresh) {
        return aiGenerationService.start(
                AiFeature.DEAL_BRIEF,
                new SubjectGenerationIdentity(List.of(dealId), refresh),
                Set.of(Permission.AI_USE),
                DealBriefDto.unavailable(dealId, NOT_CONFIGURED),
                () -> classify(dealBriefService.generate(dealId, refresh)));
    }

    /** Starts or joins one deal-risk-rationale generation. */
    public AiGenerationStatusDto startDealRationale(int dealId, boolean refresh) {
        return aiGenerationService.start(
                AiFeature.DEAL_RISK_RATIONALE,
                new SubjectGenerationIdentity(List.of(dealId), refresh),
                Set.of(Permission.AI_USE),
                DealRationaleDto.unavailable(dealId, NOT_CONFIGURED),
                () -> classify(dealRiskRationaleService.generate(dealId, refresh)));
    }

    /** Starts or joins one introduction-rationale generation. */
    public AiGenerationStatusDto startIntroRationale(int personAId, int personBId) {
        int lo = Math.min(personAId, personBId);
        int hi = Math.max(personAId, personBId);
        return aiGenerationService.start(
                AiFeature.INTRO_RATIONALE,
                new SubjectGenerationIdentity(List.of(lo, hi), false),
                Set.of(Permission.AI_USE),
                IntroRationaleDto.unavailable(lo, hi, NOT_CONFIGURED),
                () -> classify(introRationaleService.generate(lo, hi)));
    }

    private static AiGenerationTaskResult<DealBriefDto> classify(DealBriefDto result) {
        if (result.isAvailable()) {
            return AiGenerationTaskResult.resolved(result);
        }
        return failed(result.getReason())
                ? AiGenerationTaskResult.failed(result.getReason())
                : AiGenerationTaskResult.unavailable(result);
    }

    private static AiGenerationTaskResult<DealRationaleDto> classify(DealRationaleDto result) {
        if (result.isAvailable()) {
            return AiGenerationTaskResult.resolved(result);
        }
        return failed(result.getReason())
                ? AiGenerationTaskResult.failed(result.getReason())
                : AiGenerationTaskResult.unavailable(result);
    }

    private static AiGenerationTaskResult<IntroRationaleDto> classify(IntroRationaleDto result) {
        if (result.isAvailable()) {
            return AiGenerationTaskResult.resolved(result);
        }
        return failed(result.getReason())
                ? AiGenerationTaskResult.failed(result.getReason())
                : AiGenerationTaskResult.unavailable(result);
    }

    private static boolean failed(String reason) {
        return PROVIDER_ERROR.equals(reason) || RATE_LIMITED.equals(reason);
    }

    private record SubjectGenerationIdentity(List<Integer> subjectIds, boolean refresh) {
        private SubjectGenerationIdentity {
            subjectIds = List.copyOf(subjectIds);
        }
    }
}
