package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Sequence;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.sequence.SequenceMergeFieldDto;
import ooo.klae.connex.backend.exceptions.SequenceException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;

/** Allowlist, resolution, and safe rendering for sequence merge fields. */
@Service
@RequiredArgsConstructor
public class SequenceMergeFieldResolver {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([a-z][a-z0-9_.]*)}}", Pattern.CASE_INSENSITIVE);
    private static final List<SequenceMergeFieldDto> CATALOG = List.of(
        new SequenceMergeFieldDto("person.name", "person", "Contact name"),
        new SequenceMergeFieldDto("person.email", "person", "Contact email"),
        new SequenceMergeFieldDto("person.phone", "person", "Contact phone"),
        new SequenceMergeFieldDto("person.title", "person", "Contact title"),
        new SequenceMergeFieldDto("company.name", "company", "Primary company name"),
        new SequenceMergeFieldDto("owner.name", "owner", "Sequence owner name"),
        new SequenceMergeFieldDto("owner.email", "owner", "Sequence owner email"),
        new SequenceMergeFieldDto("deal.name", "deal", "Primary deal name"),
        new SequenceMergeFieldDto("deal.value", "deal", "Primary deal value"),
        new SequenceMergeFieldDto("deal.currency", "deal", "Primary deal currency"));
    private static final Set<String> KEYS = CATALOG.stream()
        .map(SequenceMergeFieldDto::key)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final PersonMapper personMapper;
    private final DealMapper dealMapper;
    private final UserService userService;

    /** Returns the fixed merge-field catalog. */
    public List<SequenceMergeFieldDto> catalog() {
        return CATALOG;
    }

    ResolvedFields resolve(Sequence sequence, int workspaceId, int actorId, int personId) {
        MemberScope memberScope = new MemberScope(MemberScope.Mode.ME, actorId, List.of());
        Person person = personMapper.getSequencePreviewPerson(workspaceId, personId, memberScope);
        if (person == null) {
            throw SequenceException.notFound("Contact not found");
        }

        Company company = person.getCompany();
        Deal deal = dealMapper.getSequencePreviewDeals(workspaceId, personId, memberScope).stream()
            .findFirst()
            .orElse(null);
        User owner = sequence.getOwnerId() == null
            ? null
            : userService.getActiveWorkspaceUser(workspaceId, sequence.getOwnerId());

        Map<String, String> values = new LinkedHashMap<>();
        values.put("person.name", person.getName());
        values.put("person.email", person.getEmail());
        values.put("person.phone", person.getPhone());
        values.put("person.title", person.getTitle());
        values.put("company.name", company == null ? null : company.getName());
        values.put("owner.name", owner == null ? null : ownerName(owner));
        values.put("owner.email", owner == null ? null : owner.getEmail());
        values.put("deal.name", deal == null ? null : deal.getName());
        values.put("deal.value", deal == null ? null : decimal(deal.getValue()));
        values.put("deal.currency", deal == null ? null : deal.getCurrency());
        return new ResolvedFields(Map.copyOf(nonNullValues(values)));
    }

    void validateTemplate(String template) {
        if (template == null) {
            return;
        }
        Matcher matcher = TOKEN.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            if (!KEYS.contains(key)) {
                throw SequenceException.badRequest(
                    "SEQUENCE_MERGE_FIELD_INVALID", "Sequence content contains an unsupported merge field");
            }
        }
        String withoutValidTokens = matcher.replaceAll("");
        if (withoutValidTokens.contains("{{") || withoutValidTokens.contains("}}")) {
            throw SequenceException.badRequest(
                "SEQUENCE_MERGE_FIELD_INVALID", "Sequence content contains an invalid merge field");
        }
    }

    Rendered render(String template, ResolvedFields fields, boolean html) {
        if (template == null) {
            return new Rendered(null, Set.of());
        }
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        Set<String> unresolved = new TreeSet<>();
        int offset = 0;
        while (matcher.find()) {
            rendered.append(template, offset, matcher.start());
            String key = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            String value = fields.values().get(key);
            if (value == null || value.isBlank()) {
                unresolved.add(key);
                rendered.append(matcher.group());
            } else {
                rendered.append(html ? escapeHtml(value) : value);
            }
            offset = matcher.end();
        }
        rendered.append(template, offset, template.length());
        return new Rendered(rendered.toString(), Set.copyOf(unresolved));
    }

    private static Map<String, String> nonNullValues(Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null) {
                result.put(key, value);
            }
        });
        return result;
    }

    private static String ownerName(User owner) {
        return owner.getDisplayName() == null || owner.getDisplayName().isBlank()
            ? owner.getUsername()
            : owner.getDisplayName();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    record ResolvedFields(Map<String, String> values) {
    }

    record Rendered(String value, Set<String> unresolved) {
    }
}
