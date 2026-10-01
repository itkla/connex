package ooo.klae.connex.backend.ai.assistant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.UpdateRecordFields;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/**
 * Proposes only the reviewed record fields and applies them through the ordinary audited services.
 *
 * <p>The framework holds the duplicate-decision mutex before locking a person or company. A
 * person's patch preserves its company reference because the mapper always writes that column;
 * a company's overlay preserves all unrequested columns because its mapper writes all five.
 * Deal updates use the single-field service methods, including their line-item value guard.
 * Neither contact channels nor provenance are accepted. Every proposed value is screened by the
 * framework's read projection, and model outcomes carry only static field keys.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantUpdateRecordFieldsWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "update_record_fields";
    private static final Pattern HANDLE = Pattern.compile(AiAssistantWriteToolRequest.HANDLE);
    private static final Pattern WEBSITE = Pattern.compile(
            "^(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?:/\\S*)?$");
    private static final Pattern VALUE = Pattern.compile("^\\d{1,13}(\\.\\d{1,2})?$");
    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    private static final Map<String, Integer> LIMITS = Map.of(
            "title", 128, "website", 255, "industry", 128, "address", 512,
            "value", 16, "expectedCloseDate", 10);
    private static final List<String> FIELDS = List.of(
            "title", "website", "industry", "address", "value", "expectedCloseDate");

    private final PersonService personService;
    private final CompanyService companyService;
    private final DealService dealService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolTier tier() {
        return ToolTier.CONFIRM;
    }

    @Override
    public Class<? extends AiAssistantWriteToolRequest> requestType() {
        return UpdateRecordFields.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return AiAssistantWriteFieldPolicy.EDITABLE_FIELDS.keySet();
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("person.title", "company.website", "company.industry", "company.address",
                "deal.value", "deal.expectedCloseDate");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(switch (targetKind) {
            case "person" -> Permission.PERSON_UPDATE;
            case "company" -> Permission.COMPANY_UPDATE;
            case "deal" -> Permission.DEAL_UPDATE;
            default -> throw new BadRequestException("Unsupported assistant record kind");
        });
    }

    @Override
    public boolean requiresOwnedTarget() {
        return true;
    }

    @Override
    public Lock lock(String targetKind) {
        return new Lock(false, switch (targetKind) {
            case "person", "company" -> TargetLock.DUPLICATE_DECISION_RECORD_UPDATE;
            case "deal" -> TargetLock.RECORD_UPDATE;
            default -> throw new BadRequestException("Unsupported assistant record kind");
        });
    }

    @Override
    public List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory) {
        return List.of();
    }

    @Override
    public Set<String> identifierValueFields() {
        return Set.of("website");
    }

    @Override
    public Set<String> modelAuthoredDiffFields() {
        return Set.copyOf(FIELDS);
    }

    @Override
    public void validateFor(String targetKind, JsonNode request) {
        if (request == null || !request.isObject()
                || !request.path("handle").isString()
                || !HANDLE.matcher(request.path("handle").asString()).matches()) {
            throw invalid();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : request.properties()) {
            if ("handle".equals(entry.getKey())) {
                continue;
            }
            String field = "expected_close_date".equals(entry.getKey())
                    ? "expectedCloseDate" : entry.getKey();
            if (!LIMITS.containsKey(field) || "expectedCloseDate".equals(entry.getKey())) {
                throw invalid();
            }
            if (!entry.getValue().isNull()) {
                if (!entry.getValue().isString()) {
                    throw invalid();
                }
                values.put(field, entry.getValue().asString());
            }
        }
        Set<String> allowed = AiAssistantWriteFieldPolicy.EDITABLE_FIELDS
                .getOrDefault(targetKind, Set.of());
        if (values.isEmpty() || !allowed.containsAll(values.keySet())) {
            throw invalid();
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue().isBlank() || entry.getValue().length() > LIMITS.get(entry.getKey())) {
                throw invalid();
            }
        }
        String website = values.get("website");
        String value = values.get("value");
        String date = values.get("expectedCloseDate");
        if (website != null && !WEBSITE.matcher(website).matches()
                || value != null && !VALUE.matcher(value).matches()) {
            throw invalid();
        }
        if (date != null) {
            if (!DATE.matcher(date).matches()) {
                throw invalid();
            }
            try {
                LocalDate.parse(date);
            } catch (DateTimeParseException exception) {
                throw invalid();
            }
        }
    }

    @Override
    public Outcome apply(Execution execution) {
        if (!(execution.row().request() instanceof UpdateRecordFields request)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        Target target = execution.row().target();
        Map<String, String> applied = new LinkedHashMap<>();
        int returnedId = switch (target.kind()) {
            case "person" -> {
                Person before = personService.getPersonById(target.id());
                Person patch = new Person();
                patch.setCompany(before.getCompany());
                patch.setTitle(request.title());
                Person after = personService.update(target.id(), patch);
                put(applied, "title", after.getTitle());
                yield after.getId();
            }
            case "company" -> {
                Company before = companyService.getCompanyById(target.id());
                Company overlay = new Company();
                overlay.setName(before.getName());
                overlay.setPhone(before.getPhone());
                overlay.setWebsite(request.website() == null ? before.getWebsite() : request.website());
                overlay.setIndustry(request.industry() == null ? before.getIndustry() : request.industry());
                overlay.setAddress(request.address() == null ? before.getAddress() : request.address());
                Company after = companyService.updateCompany(target.id(), overlay);
                put(applied, "website", after.getWebsite());
                put(applied, "industry", after.getIndustry());
                put(applied, "address", after.getAddress());
                yield after.getId();
            }
            case "deal" -> {
                Deal after = null;
                if (request.value() != null) {
                    after = dealService.updateValue(target.id(), new BigDecimal(request.value()));
                }
                if (request.expectedCloseDate() != null) {
                    after = dealService.reschedule(target.id(), request.expectedCloseDate());
                }
                if (after == null) {
                    throw new IllegalStateException("Assistant field edit has no deal fields");
                }
                put(applied, "value", after.getValue() == null ? null : after.getValue().toPlainString());
                put(applied, "expectedCloseDate", after.getExpectedCloseDate());
                yield after.getId();
            }
            default -> throw new BadRequestException("Unsupported assistant record kind");
        };
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", target.kind());
        for (String field : values(request).keySet()) {
            outcome.put(field, applied.get(field));
        }
        return new Outcome(outcome, null, new ReadBack("recordId", target.id(), returnedId));
    }

    @Override
    public boolean inverseAvailable() {
        return false;
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of(ReviewInput.FIELDS);
    }

    @Override
    public List<Diff> diffs(Review review) {
        List<Diff> diffs = new ArrayList<>();
        for (String field : FIELDS) {
            String proposed = review.requestText(
                    "expectedCloseDate".equals(field) ? "expected_close_date" : field);
            if (proposed == null) {
                continue;
            }
            RecordSnapshot target = review.target();
            String current = target == null ? null : target.field(field);
            boolean unresolved = target == null || target.sharedIn();
            boolean unchanged = Objects.equals(current, proposed)
                    || "value".equals(field) && sameValue(current, proposed);
            diffs.add(new Diff(field, current, unresolved, proposed,
                    unresolved ? DiffState.UNRESOLVED
                            : unchanged ? DiffState.UNCHANGED : DiffState.CHANGED));
        }
        return List.copyOf(diffs);
    }

    @Override
    public String requestSummary(Review review) {
        return "Update record fields";
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Record fields updated";
    }

    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        result.put("updated", FIELDS.stream().filter(storedOutcome::has).toList());
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return FIELDS;
    }

    private static Map<String, String> values(UpdateRecordFields request) {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "title", request.title());
        put(values, "website", request.website());
        put(values, "industry", request.industry());
        put(values, "address", request.address());
        put(values, "value", request.value());
        put(values, "expectedCloseDate", request.expectedCloseDate());
        return values;
    }

    private static void put(Map<String, String> values, String field, String value) {
        if (value != null) {
            values.put(field, value);
        }
    }

    private static boolean sameValue(String current, String proposed) {
        return current != null && VALUE.matcher(current).matches()
                && VALUE.matcher(proposed).matches()
                && new BigDecimal(current).compareTo(new BigDecimal(proposed)) == 0;
    }

    private static AiAssistantLoopException invalid() {
        return AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
    }
}
