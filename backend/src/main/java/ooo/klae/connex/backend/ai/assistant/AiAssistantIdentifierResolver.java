package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
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
        mentions(message).forEach(mention ->
                MaskingEngine.maskField(mention.entityKind(), mention.name(), context));
    }

    /** Returns durable identities for every bounded visible record name in supplied text. */
    public List<AiChatPageContextDto> mentionedResources(String message) {
        return mentions(message).stream()
                .map(mention -> new AiChatPageContextDto(mention.kind(), mention.id()))
                .toList();
    }

    private List<Mention> mentions(String message) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Person> people = personMapper.findMentionedRecords(
                workspaceId, message, OVERFLOW_LIMIT);
        List<Company> companies = companyMapper.findMentionedRecords(
                workspaceId, message, OVERFLOW_LIMIT);
        List<Deal> deals = dealMapper.findMentionedRecords(
                workspaceId, message, OVERFLOW_LIMIT);
        requireBounded(people);
        requireBounded(companies);
        requireBounded(deals);
        List<Mention> mentions = new ArrayList<>(
                people.size() + companies.size() + deals.size());
        people.forEach(person -> mentions.add(new Mention(
                "person", person.getId(), person.getName(), EntityKind.PERSON)));
        companies.forEach(company -> mentions.add(new Mention(
                "company", company.getId(), company.getName(), EntityKind.COMPANY)));
        deals.forEach(deal -> mentions.add(new Mention(
                "deal", deal.getId(), deal.getName(), EntityKind.DEAL)));
        return List.copyOf(mentions);
    }

    private static void requireBounded(List<?> records) {
        if (records.size() > MAX_IDENTIFIERS_PER_KIND) {
            throw AiAssistantLoopException.malformed("identifier_limit_exceeded");
        }
    }

    private record Mention(String kind, int id, String name, EntityKind entityKind) {}
}
