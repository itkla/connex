package ooo.klae.connex.backend.ai.assistant;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.WorkspaceService;

/** Seeds bounded, locally authorized display names found in the initiating user message. */
@Service
@RequiredArgsConstructor
public class AiAssistantIdentifierResolver {
    private static final int MAX_IDENTIFIERS_PER_KIND = 20;
    private static final int OVERFLOW_LIMIT = MAX_IDENTIFIERS_PER_KIND + 1;

    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final WorkspaceService workspaceService;

    /** Tokenizes every bounded visible record name occurring in the supplied message. */
    public void seed(String message, MaskingContext context) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        seed(
                personMapper.findMentionedNames(workspaceId, message, OVERFLOW_LIMIT),
                EntityKind.PERSON,
                context);
        seed(
                companyMapper.findMentionedNames(workspaceId, message, OVERFLOW_LIMIT),
                EntityKind.COMPANY,
                context);
        seed(
                dealMapper.findMentionedNames(workspaceId, message, OVERFLOW_LIMIT),
                EntityKind.DEAL,
                context);
    }

    private static void seed(
            List<String> identifiers, EntityKind kind, MaskingContext context) {
        if (identifiers.size() > MAX_IDENTIFIERS_PER_KIND) {
            throw AiAssistantLoopException.malformed("identifier_limit_exceeded");
        }
        identifiers.forEach(identifier -> MaskingEngine.maskField(kind, identifier, context));
    }
}
