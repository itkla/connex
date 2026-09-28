package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.AssignOwner;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/**
 * Hands one person, company or deal to a named workspace member, or removes its owner, only after
 * the member approves it.
 *
 * <p>It is the one tool that names a principal. {@link #principals} resolves the owner once, before
 * any lock, against the member directory the framework hands it: the literal {@code unassigned}
 * names nobody, and any other value must match exactly one member's display name or username
 * case-insensitively, or the approval is refused as unavailable or ambiguous. The framework locks
 * that member's authorization rows with the actor's — refusing one who is no longer an active
 * member or whose account deletion is reserved — and hands the same principal to {@link #apply},
 * which writes exactly that principal's id. {@link Execution} carries no member lookup, so the
 * member written can never be one the framework did not resolve and lock; a request whose
 * principals disagree with it is refused rather than resolved again.
 *
 * <p>The write is read back by owner id off the record the service returns, compared with the
 * principal's id — {@code null} on both sides for a removal — while the stored {@code owner} stays
 * the principal's label resolved before the lock, or {@code unassigned}. An owner change records
 * no inverse.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantAssignOwnerWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "assign_owner";
    private static final String OWNER_FIELD = "owner";
    private static final String UNASSIGNED = "unassigned";
    private static final String REMOVES_OWNER = "removesOwner";
    private static final String GENERIC_SUMMARY = "Assign an owner";
    private static final Set<String> TARGET_KINDS = Set.of("person", "company", "deal");

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
        return AssignOwner.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return TARGET_KINDS;
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("person.ownerId", "company.ownerId", "deal.ownerId");
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

    /**
     * The one member this assignment names, or none for a removal.
     *
     * <p>A removal reads no member list at all.
     */
    @Override
    public List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory) {
        AssignOwner assignment = assignment(request);
        if (removesOwner(assignment.owner())) {
            return List.of();
        }
        List<User> matches = matching(assignment.owner(), directory.members());
        if (matches.size() != 1) {
            throw new ResourceNotFoundException("Owner is unavailable or ambiguous");
        }
        User match = matches.getFirst();
        return List.of(new PrincipalRequest(match.getId(), principalLabel(match)));
    }

    @Override
    public Outcome apply(Execution execution) {
        AssignOwner assignment = assignment(execution.row().request());
        PrincipalRequest owner = lockedOwner(assignment, execution.principals());
        Integer ownerId = owner == null ? null : owner.userId();
        Target target = execution.row().target();
        Integer applied = switch (target.kind()) {
            case "person" -> appliedOwner(
                    personService.updateOwner(target.id(), ownerId), Person::getOwnerId);
            case "company" -> appliedOwner(
                    companyService.updateOwner(target.id(), ownerId), Company::getOwnerId);
            case "deal" -> appliedOwner(
                    dealService.updateOwner(target.id(), ownerId), Deal::getOwnerId);
            default -> throw new BadRequestException("Unsupported owner target");
        };
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", target.kind());
        outcome.put(OWNER_FIELD, owner == null ? UNASSIGNED : owner.label());
        return new Outcome(outcome, null, new ReadBack("ownerId", ownerId, applied));
    }

    @Override
    public boolean inverseAvailable() {
        return false;
    }

    /**
     * Whether the request removed the owner, the one fact about it a viewer who may not read the
     * details has always been told: a completed card says "Owner removed" or "Owner assigned".
     */
    @Override
    public Map<String, SharedRequestFlag> sharedRequestFlags() {
        return Map.of(REMOVES_OWNER, new SharedRequestFlag(OWNER_FIELD, UNASSIGNED));
    }

    /**
     * Declines the special-care screen: the detailed summary names only the member the owner
     * resolves to against the workspace's own member list, which the same requester's pending card
     * already states unscreened as the proposed owner, so the summary keeps saying which member.
     */
    @Override
    public boolean screensDetailedRequestSummary() {
        return false;
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of(ReviewInput.MEMBERS);
    }

    /**
     * The record's current and proposed owner, compared on owner ids rather than names.
     *
     * <p>A record owned by someone who has left cannot be named, and comparing that absent name
     * with a removal's absent name would call a real removal a no-op, so whether the change would
     * do anything is decided on the id the record stores. A proposed owner that no longer resolves
     * to exactly one nameable member is reported as unresolved rather than echoed back.
     */
    @Override
    public Diff diff(Review review) {
        RecordSnapshot target = review.target();
        String current = currentOwnerName(review.members(), target.ownerId());
        boolean currentUnresolved = target.ownerId() != null && current == null;
        String requested = review.requestText(OWNER_FIELD);
        if (requested != null && removesOwner(requested)) {
            return new Diff(
                    OWNER_FIELD, current, currentUnresolved, null,
                    target.ownerId() == null ? DiffState.UNCHANGED : DiffState.CHANGED);
        }
        User proposed = requestedOwner(requested, review.members());
        String proposedName = proposed == null ? null : memberName(proposed);
        if (proposedName == null) {
            return new Diff(OWNER_FIELD, current, currentUnresolved, null, DiffState.UNRESOLVED);
        }
        return new Diff(
                OWNER_FIELD,
                current,
                currentUnresolved,
                proposedName,
                target.ownerId() != null && target.ownerId() == proposed.getId()
                        ? DiffState.UNCHANGED
                        : DiffState.CHANGED);
    }

    @Override
    public String requestSummary(Review review) {
        if (review.detailsReadable()) {
            String requested = review.requestText(OWNER_FIELD);
            if (requested != null && removesOwner(requested)) {
                return "Remove the current owner";
            }
            User matched = requestedOwner(requested, review.members());
            String name = matched == null ? null : memberName(matched);
            if (name != null) {
                return "Assign owner: " + name;
            }
        }
        return GENERIC_SUMMARY;
    }

    @Override
    public String outcomeSummary(Review review) {
        return removedOwner(review) ? "Owner removed" : "Owner assigned";
    }

    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        AiAssistantWriteTool.copyText(storedOutcome, result, OWNER_FIELD);
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of(OWNER_FIELD);
    }

    private static AssignOwner assignment(AiAssistantWriteToolRequest request) {
        if (!(request instanceof AssignOwner assignment)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        return assignment;
    }

    /**
     * The principal the framework resolved and locked for this assignment, or {@code null} for a
     * removal.
     */
    private static PrincipalRequest lockedOwner(
            AssignOwner assignment, List<PrincipalRequest> principals) {
        if (removesOwner(assignment.owner())) {
            if (!principals.isEmpty()) {
                throw new IllegalStateException("Assistant owner removal names a principal");
            }
            return null;
        }
        if (principals.size() != 1) {
            throw new IllegalStateException("Assistant owner assignment was not resolved");
        }
        return principals.getFirst();
    }

    private static <T> Integer appliedOwner(T updated, Function<T, Integer> ownerId) {
        if (updated == null) {
            throw new IllegalStateException("Assistant owner assignment could not be read back");
        }
        return ownerId.apply(updated);
    }

    private static boolean removedOwner(Review review) {
        if (review.detailsReadable()) {
            String requested = review.requestText(OWNER_FIELD);
            return requested != null && removesOwner(requested);
        }
        JsonNode flag = review.request() == null ? null : review.request().get(REMOVES_OWNER);
        return flag != null && flag.isBoolean() && flag.booleanValue();
    }

    private static boolean removesOwner(String owner) {
        return UNASSIGNED.equalsIgnoreCase(owner.trim());
    }

    private static List<User> matching(String owner, List<User> members) {
        String normalized = owner.trim();
        return members.stream()
                .filter(user -> user.getDisplayName() != null
                        && user.getDisplayName().equalsIgnoreCase(normalized)
                        || user.getUsername() != null
                        && user.getUsername().equalsIgnoreCase(normalized))
                .toList();
    }

    private static User requestedOwner(String requested, List<User> members) {
        if (requested == null) {
            return null;
        }
        List<User> matches = matching(requested, members);
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static String principalLabel(User member) {
        return member.getDisplayName() == null || member.getDisplayName().isBlank()
                ? member.getUsername()
                : member.getDisplayName();
    }

    /**
     * The record's owner as this workspace can currently name them, or {@code null} for an unowned
     * record and for one owned by someone who is no longer an active member.
     */
    private static String currentOwnerName(List<User> members, Integer userId) {
        if (userId == null) {
            return null;
        }
        for (User member : members) {
            if (member.getId() == userId) {
                return memberName(member);
            }
        }
        return null;
    }

    private static String memberName(User member) {
        String label = principalLabel(member);
        return label == null || label.isBlank() ? null : label;
    }
}
