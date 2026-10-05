package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.RemoveTag;
import ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen;
import ooo.klae.connex.backend.beans.RecordTag;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.TagService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/**
 * Removes one existing workspace tag from a person, company or deal, only after the member
 * approves it.
 *
 * <p>Removing a tag changes an existing record, and {@code docs/PRODUCT.md} enumerates only the
 * immediate writes an assistant may make; a removal is not one of them, so this tool is
 * confirm-tier. The tag is resolved by exactly one case-insensitive name match over the
 * workspace's tags — a name as stored first, surrounding whitespace included, and only when no tag
 * has that name, a name matched with the whitespace around both sides stripped — and the tag id it
 * resolved to when the proposal was prepared is pinned: a tag deleted and re-created under the
 * same name between the proposal and the approval is another tag, and the approval is refused
 * rather than removing an association the member never reviewed. The framework holds the target
 * {@code FOR UPDATE}, refuses the approval if the record was written after the proposal, and runs
 * the owner-scope gate before this tool removes the association through the record's own service,
 * which records the audit row when it removed one.
 *
 * <p>The tag row itself is never locked. The pin compares ids, so a rename of the reviewed tag
 * that commits after the approval resolved it still removes exactly the association the member
 * reviewed. Its label is not trusted from that resolution: the outcome names the tag as it reads
 * after the record lock, just before the record service reads it for its audit row, so the stored
 * outcome never carries a name the tag had already lost when it was removed.
 *
 * <p>The read-back is {@link ReadBack#structural structural} and verifies nothing: the record
 * services report only whether they removed the association, never which tag they removed, and
 * the permitted-method allowlist grants this tool no read of the association, so there is no
 * identifier to compare. It cannot diverge and reads nothing back from the database.
 *
 * <p>The write records no inverse. An association this call found already absent was never this
 * call's to restore, and restoring one it removed would re-add a label the member approved
 * removing, so the card never offers undo.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantRemoveTagWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "remove_tag";
    private static final String TAG_FIELD = "tag";
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
        return ToolTier.CONFIRM;
    }

    @Override
    public Class<? extends AiAssistantWriteToolRequest> requestType() {
        return RemoveTag.class;
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
        return new Lock(false, TargetLock.RECORD_UPDATE);
    }

    @Override
    public List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory) {
        return List.of();
    }

    /**
     * The one workspace tag the requested name matches case-insensitively.
     *
     * <p>A tag whose stored name equals the request is matched first, so a name stored with
     * surrounding whitespace resolves when the request carries it; only when none does are names
     * compared with that whitespace stripped from both sides. A name matching no tag, or more than
     * one at the tier that matched, refuses: when the proposal is prepared the framework turns
     * that into a recoverable {@code unresolved_reference}, so no card is ever shown for a tag its
     * approval could only refuse.
     */
    @Override
    public Resolution resolve(Target target, AiAssistantWriteToolRequest request) {
        if (!(request instanceof RemoveTag tagRequest)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        Tag tag = requestedTag(tagRequest.tag(), tagService.getAllTags());
        if (tag == null) {
            throw new ResourceNotFoundException("Tag is unavailable or ambiguous");
        }
        return new Resolution(TAG_FIELD, tag.getId(), tag.getName());
    }

    @Override
    public Outcome apply(Execution execution) {
        Resolution resolution = execution.resolution();
        if (resolution == null) {
            throw new ConflictException("Prepared tag removal is unavailable");
        }
        Target target = execution.row().target();
        String label = currentName(resolution);
        boolean changed = switch (target.kind()) {
            case "person" -> personService.removeTag(target.id(), resolution.id());
            case "company" -> companyService.removeTag(target.id(), resolution.id());
            case "deal" -> dealService.removeTag(target.id(), resolution.id());
            default -> throw new BadRequestException("Unsupported tag target");
        };
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", target.kind());
        outcome.put(TAG_FIELD, label);
        outcome.put(CHANGED, changed);
        return new Outcome(outcome, null, ReadBack.structural("tagId", resolution.id()));
    }

    /**
     * The resolved tag's name as it reads now, after the framework's record lock, or the name it
     * was resolved under when it no longer exists.
     *
     * <p>It reads the one id already resolved and never picks another. The record lock statement
     * clears the transaction's first-level cache, so this reads committed state rather than the
     * resolution's own pre-lock read.
     */
    private String currentName(Resolution resolution) {
        return tagService.getAllTags().stream()
                .filter(tag -> tag.getId() == resolution.id() && tag.getName() != null)
                .map(Tag::getName)
                .findFirst()
                .orElse(resolution.label());
    }

    @Override
    public boolean inverseAvailable() {
        return false;
    }

    @Override
    public Set<String> sharedOutcomeFlags() {
        return Set.of(CHANGED);
    }

    /** A card names the tag it proposes to remove, so a stored row without one is never projected. */
    @Override
    public Set<String> requiredRequestText() {
        return Set.of(TAG_FIELD);
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of(ReviewInput.TAGS);
    }

    /**
     * The pinned tag while the record holds it, and nothing after the removal, withheld when the
     * special-care screen excludes the name it would show.
     *
     * <p>A tag name is a free-text label a member attached to the record, so it is screened here
     * on the same rule as this card's request summary and its outcome values: a card that falls
     * back to the generic summary never names the tag in its change either, and offers no apply.
     *
     * <p>A record that no longer holds the tag would be left exactly as it is, so the change is
     * unchanged. A requested name that no longer resolves to the pinned tag — the tag was deleted,
     * renamed, or deleted and re-created under the same name — is unresolved, which is exactly
     * when the approval refuses; the pinned tag under its current name, or else the record's own
     * tag under the requested name, is shown as the current value when the record holds one, so
     * the card never claims the record holds nothing.
     */
    @Override
    public Diff diff(Review review) {
        Diff diff = reviewedDiff(review);
        return SpecialCareTextScreen.screen(diff.currentValue()).excluded() ? null : diff;
    }

    private static Diff reviewedDiff(Review review) {
        Tag reviewed = reviewedTag(review);
        if (reviewed == null) {
            return new Diff(
                    TAG_FIELD, heldName(review), false, null, DiffState.UNRESOLVED);
        }
        boolean held = review.targetTags().stream()
                .anyMatch(tag -> tag.tagId() == reviewed.getId());
        return held
                ? new Diff(TAG_FIELD, reviewed.getName(), false, null, DiffState.CHANGED)
                : new Diff(TAG_FIELD, null, false, null, DiffState.UNCHANGED);
    }

    @Override
    public String requestSummary(Review review) {
        if (review.detailsReadable()) {
            Tag reviewed = reviewedTag(review);
            if (reviewed != null && reviewed.getName() != null) {
                return "Remove tag: " + reviewed.getName();
            }
        }
        return "Remove a tag";
    }

    @Override
    public String outcomeSummary(Review review) {
        JsonNode changed = review.outcome() == null ? null : review.outcome().get(CHANGED);
        if (changed == null || !changed.isBoolean()) {
            return REQUEST_COMPLETED;
        }
        return changed.asBoolean() ? "Tag removed" : "Tag was not on the record";
    }

    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        AiAssistantWriteTool.copyText(storedOutcome, result, TAG_FIELD);
        result.put(CHANGED, storedOutcome.path(CHANGED).asBoolean());
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of(TAG_FIELD);
    }

    /**
     * The tag the requested name resolves to, and for a pinned proposal only while that is the
     * pinned tag and no member is pinned, which is exactly when its approval can pass.
     */
    private static Tag reviewedTag(Review review) {
        Tag matched = requestedTag(review.requestText(TAG_FIELD), review.tags());
        if (matched == null || review.pinnedPrincipalIds() == null) {
            return matched;
        }
        return review.pinnedPrincipalIds().isEmpty()
                && Integer.valueOf(matched.getId()).equals(review.pinnedResolutionId())
                ? matched
                : null;
    }

    private static Tag requestedTag(String requested, List<Tag> tags) {
        List<Tag> matches = nameMatches(requested, tags, Tag::getName);
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /**
     * The candidates whose name equals the requested one case-insensitively as stored, or, only
     * when none does, those equal once the whitespace around both is stripped.
     *
     * <p>Tag names are stored as members typed them, surrounding whitespace included, so the
     * request is never trimmed on its own: a stored {@code " Priority "} is reachable by its exact
     * name, and a request that differs from a stored name only in that whitespace still matches
     * it, but only while no other tag also matches that way.
     */
    private static <T> List<T> nameMatches(
            String requested, List<T> candidates, Function<T, String> name) {
        if (requested == null) {
            return List.of();
        }
        List<T> exact = candidates.stream()
                .filter(candidate -> name.apply(candidate) != null
                        && name.apply(candidate).equalsIgnoreCase(requested))
                .toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        String stripped = requested.strip();
        return candidates.stream()
                .filter(candidate -> name.apply(candidate) != null
                        && name.apply(candidate).strip().equalsIgnoreCase(stripped))
                .toList();
    }

    /**
     * What the record holds in place of an unresolved tag: the pinned tag under whatever name it
     * carries now, so a renamed tag is still shown as held, and otherwise the record's own tag
     * under the requested name, matched as the request itself is.
     */
    private static String heldName(Review review) {
        Integer pinned = review.pinnedResolutionId();
        if (pinned != null) {
            String pinnedName = review.targetTags().stream()
                    .filter(tag -> tag.tagId() == pinned)
                    .map(RecordTag::name)
                    .findFirst()
                    .orElse(null);
            if (pinnedName != null) {
                return pinnedName;
            }
        }
        return nameMatches(review.requestText(TAG_FIELD), review.targetTags(), RecordTag::name)
                .stream()
                .map(RecordTag::name)
                .findFirst()
                .orElse(null);
    }
}
