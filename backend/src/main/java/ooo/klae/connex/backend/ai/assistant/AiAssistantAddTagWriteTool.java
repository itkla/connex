package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.AddTag;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.TagService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/**
 * Immediately associates one existing workspace tag with a person, company or deal.
 *
 * <p>The framework resolves the tag before locks, retains its definition {@code FOR SHARE}, then
 * the target {@code FOR UPDATE} and its existing associations before the owner-scope gate. Exactly
 * one workspace tag must match the requested name case-insensitively. The record's own tag
 * service call then reports whether this invocation created the association or found it already
 * present; the outcome carries that as {@code changed} and the resolved tag's own name.
 *
 * <p>The read-back is {@link ReadBack#structural structural} and verifies nothing: the record
 * services report only whether they created the association, never which tag they associated, and
 * the permitted-method allowlist grants this tool no read of the association, so there is no
 * identifier to compare. It cannot diverge and reads nothing back from the database.
 *
 * <p>The write has no inverse. An association this call found already present was not this call's
 * to remove, and one it created may since have been relied on, so the recorded inverse — the target,
 * a {@code present:} fingerprint of the tag id, and the tag id itself — is stored unavailable and
 * the card never offers undo.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantAddTagWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "add_tag";
    private static final String EXECUTED = "executed";
    private static final String CHANGED = "changed";
    private static final String REQUEST_COMPLETED = "Request completed";
    private static final Set<String> TARGET_KINDS = Set.of("person", "company", "deal");

    private final TagService tagService;
    private final PersonService personService;
    private final CompanyService companyService;
    private final DealService dealService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolTier tier() {
        return ToolTier.AUTO;
    }

    @Override
    public Class<? extends AiAssistantWriteToolRequest> requestType() {
        return AddTag.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return TARGET_KINDS;
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("person.tags", "company.tags", "deal.tags");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return switch (targetKind) {
            case "person" -> Set.of(Permission.PERSON_UPDATE);
            case "company" -> Set.of(Permission.COMPANY_UPDATE);
            case "deal" -> Set.of(Permission.DEAL_UPDATE);
            default -> throw new BadRequestException("Unsupported assistant record kind");
        };
    }

    @Override
    public Lock lock(String targetKind) {
        return new Lock(false, TargetLock.RECORD_UPDATE, true);
    }

    @Override
    public List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory) {
        return List.of();
    }

    @Override
    public Resolution resolve(Target target, AiAssistantWriteToolRequest request) {
        if (!(request instanceof AddTag addTag)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        Tag tag = uniqueTag(addTag.tag());
        return new Resolution("tag", tag.getId(), tag.getName());
    }

    @Override
    public Outcome apply(Execution execution) {
        if (!(execution.row().request() instanceof AddTag request)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        Target target = execution.row().target();
        Resolution resolution = execution.resolution();
        if (resolution == null) {
            throw new IllegalStateException("Assistant tag was not resolved");
        }
        boolean changed = switch (target.kind()) {
            case "person" -> personService.addTag(target.id(), resolution.id(), execution.admission());
            case "company" -> companyService.addTag(target.id(), resolution.id(), execution.admission());
            case "deal" -> dealService.addTag(target.id(), resolution.id(), execution.admission());
            default -> throw new BadRequestException("Unsupported tag target");
        };
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", EXECUTED);
        outcome.put("recordType", target.kind());
        outcome.put("tag", resolution.label());
        outcome.put(CHANGED, changed);
        return new Outcome(
                outcome,
                new Inverse(
                        "tag",
                        target.id(),
                        "present:" + resolution.id(),
                        false,
                        Map.of("tagId", resolution.id())),
                ReadBack.structural("tagId", resolution.id()));
    }

    @Override
    public boolean inverseAvailable() {
        return false;
    }

    @Override
    public Set<String> sharedOutcomeFlags() {
        return Set.of(CHANGED);
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of();
    }

    @Override
    public Diff diff(Review review) {
        return null;
    }

    @Override
    public String requestSummary(Review review) {
        return "Add an existing tag";
    }

    @Override
    public String outcomeSummary(Review review) {
        JsonNode changed = review.outcome() == null ? null : review.outcome().get(CHANGED);
        if (changed == null || !changed.isBoolean()) {
            return REQUEST_COMPLETED;
        }
        return changed.asBoolean() ? "Tag added" : "Tag was already present";
    }

    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        AiAssistantWriteTool.copyText(storedOutcome, result, "tag");
        result.put(CHANGED, storedOutcome.path(CHANGED).asBoolean());
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of("tag");
    }

    private Tag uniqueTag(String name) {
        List<Tag> matches = tagService.getAllTags().stream()
                .filter(tag -> tag.getName() != null && tag.getName().equalsIgnoreCase(name.trim()))
                .toList();
        if (matches.size() != 1) {
            throw new ResourceNotFoundException("Tag is unavailable or ambiguous");
        }
        return matches.getFirst();
    }
}
