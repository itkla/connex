package ooo.klae.connex.backend.ai.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One assistant write tool, declared once.
 *
 * <p>Everything a write tool could forget lives in its one implementation or fails the build: its
 * tier, request type, accepted target kinds, required permissions, the locks it needs, how it
 * applies and inverts itself, how it reads on a card and what the model is told. The wire
 * declaration — name, toolset, tier, argument specs, description — stays in
 * {@link AiAssistantToolCatalog}, whose static order is the vocabulary the provider sees.
 *
 * <p>An implementation is a Spring bean discovered by {@link AiAssistantWriteToolRegistry}, which
 * refuses to start on a declaration that disagrees with the catalog. {@link
 * AiAssistantWriteToolService} is the framework that drives it: it resolves the principals and the
 * target value once, locks the authorization rows, the session, the tool call and the declared
 * aggregates in its own fixed order, asserts the declared permissions from the locked snapshot,
 * checks proposal freshness for every confirm-tier tool, runs the owner-scope target gate, and only
 * then calls {@link #apply}. An implementation therefore takes no lock, performs no permission
 * check of its own beyond what its domain services already do, reaches no mapper, and never
 * re-resolves a principal the framework handed it.
 */
public interface AiAssistantWriteTool {

    /** @return the catalog key this tool implements; must name a declared non-read tool */
    String name();

    /** @return the tier, which must equal the catalog's declaration for {@link #name()} */
    ToolTier tier();

    /** @return the record of the sealed request vocabulary this tool's arguments bind to */
    Class<? extends AiAssistantWriteToolRequest> requestType();

    /** @return the non-empty set of record kinds a target handle may name */
    Set<String> acceptedTargetKinds();

    /**
     * The {@code kind.field} keys this tool writes.
     *
     * <p>It constrains what a tool declares, not what its {@link #apply} actually does; see
     * {@link AiAssistantWriteFieldPolicy}.
     *
     * @return declared writable fields, disjoint from {@link AiAssistantWriteFieldPolicy#NEVER_WRITABLE}
     */
    Set<String> declaredWritableFields();

    /**
     * @param targetKind an accepted target kind
     * @return the non-empty permissions the actor must hold, asserted from the locked snapshot
     */
    Set<Permission> requiredPermissions(String targetKind);

    /**
     * @param targetKind an accepted target kind
     * @return the aggregate locks the framework takes, in its own fixed order, before apply
     */
    Lock lock(String targetKind);

    /**
     * The members this write will name, resolved once before any lock.
     *
     * <p>The framework locks each returned user's authorization rows with the actor's, ascending by
     * user id, and passes these same objects to {@link #apply} through {@link Execution}. Only an
     * approval resolves principals; an immediate-tier tool that names one is refused.
     *
     * <p>Resolution runs before any lock, so it must be a pure function of the request and the
     * directory the framework supplies: a permission read here would fill the transaction's
     * first-level cache with a pre-lock answer every later permission check would be served, and a
     * domain getter guarded by {@code @RequirePermission} performs exactly such a read. The
     * directory's own read is a plain member list, not a permission read.
     *
     * @param request the validated typed request
     * @param directory the workspace's members, read by the framework only when asked for
     * @return the principals, empty for a tool that names none
     */
    List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory);

    /**
     * Resolves, once and before any record lock, the server-side value this write moves its target
     * to.
     *
     * <p>A tool whose lock needs that value — a stage change locks the board rows of the stage it
     * moves the deal to — resolves it here; the framework locks with it and passes the same object
     * to {@link #apply}. The same resolution labels the pending-proposal review.
     *
     * @param target the resolved target
     * @param request the validated typed request
     * @return the resolved value, or {@code null} when the tool moves the target to no named value
     */
    default Resolution resolve(Target target, AiAssistantWriteToolRequest request) {
        return null;
    }

    /**
     * Performs the write through domain services only.
     *
     * @param execution the locked, authorized unit of work
     * @return the member- and model-visible outcome, its inverse, and the identifier read back
     */
    Outcome apply(Execution execution);

    /**
     * Applies the recorded inverse while the created record still matches its fingerprint.
     *
     * <p>An implementation guards on the fingerprint and lets the domain service's
     * {@code ConflictException} roll the transaction back.
     *
     * @param authority who is undoing the write, never why they may
     * @param inverse the durable inverse {@link #apply} recorded
     */
    default void undo(Authority authority, Inverse inverse) {
        throw new ConflictException("Assistant tool has no owned inverse");
    }

    /** @return whether this tool can ever be undone; part of the card's undo-offer rule */
    boolean inverseAvailable();

    /**
     * The workspace data this tool's card projection reads besides its own target and request.
     *
     * <p>The read service batches each input once per page of cards, only for cards whose viewer
     * may read their details; a tool that reads an input it did not declare finds it empty.
     *
     * @return the batched inputs {@link #diff} and the summaries read from a {@link Review}
     */
    Set<ReviewInput> reviewInputs();

    /**
     * The before and after values one pending proposal would write, never shown to the model.
     *
     * <p>Called only for a viewer who may read the proposal's details.
     *
     * @param review the card's batched, viewer-authorized read state
     * @return the change, or {@code null} when the tool has no reviewable before and after
     */
    Diff diff(Review review);

    /**
     * The member-visible request summary.
     *
     * <p>For a viewer who may not read the proposal's details the framework passes a review with
     * no target, request, outcome, members or stages, so the summary can only be generic. A
     * detailed summary is screened for special-care text and replaced by the generic one when the
     * screen excludes it.
     *
     * @param review the card's batched, viewer-authorized read state
     * @return the member-visible request summary
     */
    String requestSummary(Review review);

    /**
     * The member-visible summary of an executed write, projected under the same rule as
     * {@link #requestSummary}.
     *
     * @param review the card's batched read state for an executed call
     * @return the member-visible summary of the completed write
     */
    String outcomeSummary(Review review);

    /**
     * Projects a stored outcome into the model's view of it.
     *
     * <p>No identifier, no undo or verification metadata, and no string class beyond what the tool
     * already reports: server-resolved scalars, workspace-authored labels such as a stage name, and
     * echoes of the model's own arguments such as a task description or a note title. A tool may
     * copy a field only by naming it, so a key the stored outcome gains later never reaches the
     * provider by accident.
     *
     * @param storedOutcome the stored outcome object
     * @return the model-visible outcome in stable key order
     */
    Map<String, Object> modelOutcome(JsonNode storedOutcome);

    /** @return the ordered outcome fields a card may render, before screening and capping */
    List<String> memberOutcomeFields();

    /**
     * Fingerprints a created record's state so an inverse can refuse a record someone else changed.
     *
     * @param objectMapper mapper serializing the state
     * @param state the record state in stable key order
     * @return the lowercase hexadecimal SHA-256 of the serialized state
     */
    static String fingerprint(ObjectMapper objectMapper, Map<String, Object> state) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    objectMapper.writeValueAsString(state).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Assistant tool metadata could not be serialized", exception);
        }
    }

    /**
     * Copies one non-blank string field of a stored outcome into a model projection.
     *
     * @param source the stored outcome
     * @param target the projection being built
     * @param field the field to copy when it holds a non-blank string
     */
    static void copyText(JsonNode source, Map<String, Object> target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isString() && !value.asString().isBlank()) {
            target.put(field, value.asString());
        }
    }

    /** The record one write targets. */
    record Target(String kind, int id) {
    }

    /**
     * The framework's unit of work: one target and the request applied to it.
     *
     * <p>Every tool in this phase writes exactly one row per call.
     */
    record Row(Target target, AiAssistantWriteToolRequest request) {
    }

    /** A member the write names, resolved once before the lock and never re-resolved. */
    record PrincipalRequest(int userId, String label) {
    }

    /**
     * The workspace's members as the framework reads them for principal resolution.
     *
     * <p>It is the only member lookup a tool is handed, and only {@link #principals} receives it.
     */
    @FunctionalInterface
    interface MemberDirectory {

        /** @return the workspace's active members */
        List<User> members();
    }

    /**
     * A server-side value resolved once before the lock.
     *
     * @param field the pending-proposal argument key the label is reviewed under
     * @param id the resolved row's identifier
     * @param label the resolved row's workspace-authored name
     */
    record Resolution(String field, int id, String label) {
    }

    /**
     * The aggregate locks a tool needs, taken by the framework in its own fixed order: the task
     * board root first when {@link #taskBoard()} is set, then the target.
     */
    record Lock(boolean taskBoard, TargetLock target) {
        public Lock {
            Objects.requireNonNull(target, "An assistant write lock names its target lock");
        }
    }

    /** How the framework locks the target row. */
    enum TargetLock {
        /** The processable person {@code FOR SHARE}, for a write that links to it without changing it. */
        PERSON_SHARE,
        /** The target person, company or deal {@code FOR UPDATE}. */
        RECORD_UPDATE,
        /** The ordered board rows of a deal stage change toward the resolved stage. */
        DEAL_STAGE_CHANGE
    }

    /**
     * Who a write is performed for, where and when.
     *
     * <p>Why the write is allowed — a running turn, a member's approval, a member's undo — is the
     * framework's decision alone and is deliberately absent, so no tool can branch on it and a new
     * kind of authority is added inside the framework without editing a tool.
     */
    record Authority(int workspaceId, int userId, int toolCallId, Instant at) {
    }

    /** What the framework holds locked for the target when it calls {@link #apply}. */
    record LockedTarget(String updatedAt, DealService.LockedStageChange stageChange) {
    }

    /**
     * One locked, authorized unit of work.
     *
     * <p>{@link #apply} may not re-resolve {@link #principals()} or {@link #resolution()} and may
     * not take a lock: every one of those was established before the locks it depends on.
     */
    record Execution(
            Authority authority,
            Row row,
            List<PrincipalRequest> principals,
            Resolution resolution,
            LockedTarget lockedTarget) {
        public Execution {
            principals = List.copyOf(principals);
        }
    }

    /**
     * The identifier a write produced, compared by the framework with the one it resolved.
     *
     * <p>Every write declares one comparison. A {@code null} is a value like any other: a write
     * that requested no value and applied one diverges, as does one that requested a value and
     * applied none.
     *
     * @param field the identifier's name in a divergence record
     * @param requested the identifier resolved before the write, possibly {@code null}
     * @param applied the identifier the write call itself returned, possibly {@code null}
     */
    record ReadBack(String field, Integer requested, Integer applied) {
        public ReadBack {
            Objects.requireNonNull(field, "An assistant read-back names the identifier it compares");
        }
    }

    /**
     * What one write produced.
     *
     * @param data the outcome in stable key order, stored and returned verbatim
     * @param inverse the undo metadata, or {@code null} when the tool records none
     * @param readBack the identifier read back off the write call's own return value
     */
    record Outcome(Map<String, Object> data, Inverse inverse, ReadBack readBack) {
        public Outcome {
            data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
            Objects.requireNonNull(readBack, "An assistant write reads back what it wrote");
        }
    }

    /**
     * The durable inverse of one write.
     *
     * <p>The framework serializes it after its own {@code status} and {@code expiresAt}, then
     * {@code entityKind}, {@code entityId}, {@code fingerprint} and any {@code extra} keys in order.
     * An {@code extra} key may not name one of those framework keys, nor {@code undoneAt}, so a
     * tool can neither move the undo window nor retarget the inverse.
     */
    record Inverse(
            String entityKind,
            int entityId,
            String fingerprint,
            boolean available,
            Map<String, Object> extra) {

        /** The undo keys the framework owns. */
        public static final Set<String> FRAMEWORK_KEYS = Set.of(
                "status", "expiresAt", "entityKind", "entityId", "fingerprint", "undoneAt");

        public Inverse {
            extra = Collections.unmodifiableMap(new LinkedHashMap<>(extra));
            for (String key : extra.keySet()) {
                if (FRAMEWORK_KEYS.contains(key)) {
                    throw new IllegalStateException(
                            "An assistant inverse may not set the framework's undo key " + key);
                }
            }
        }
    }

    /** The target values a card may compare against, as the viewer can currently see them. */
    record RecordSnapshot(
            String label,
            Integer pipelineId,
            Integer ownerId,
            Integer stageId,
            String updatedAt) {
    }

    /**
     * The batched, viewer-authorized read state one card is projected from.
     *
     * <p>When the viewer may not read the details, the framework withholds every record value:
     * {@code target}, {@code request} and {@code outcome} are {@code null} and {@code members} and
     * {@code stages} are empty.
     *
     * @param detailsReadable whether the viewer requested the proposal and can read its target
     * @param target the visible target, or {@code null} when the viewer may not read it
     * @param request the stored request object, or {@code null} when the viewer may not read it
     * @param outcome the stored outcome of an executed call, or {@code null}
     * @param members the workspace's members when the tool declared {@link ReviewInput#MEMBERS}
     * @param stages the workspace's pipeline stages when the tool declared {@link ReviewInput#STAGES}
     */
    record Review(
            String targetKind,
            int targetId,
            boolean detailsReadable,
            RecordSnapshot target,
            JsonNode request,
            JsonNode outcome,
            List<User> members,
            List<Stage> stages,
            Set<Permission> viewerPermissions) {

        /**
         * @param field a stored request field
         * @return the field's string value, or {@code null} when it holds none
         */
        public String requestText(String field) {
            JsonNode value = request == null ? null : request.get(field);
            return value != null && value.isString() ? value.asString() : null;
        }
    }

    /** Workspace data a card projection may batch-read for a tool. */
    enum ReviewInput {
        /** The workspace's members, for a tool that reviews an owner. */
        MEMBERS,
        /** The workspace's pipeline stages, for a tool that reviews a deal stage. */
        STAGES
    }

    /** Whether a reviewed value resolved and whether the record already holds it. */
    enum DiffState { UNRESOLVED, UNCHANGED, CHANGED }

    /**
     * One pending proposal's before and after values.
     *
     * <p>The card's public state is derived from {@link #state()} by the framework, which alone
     * applies the permission and freshness rules the approval itself enforces.
     */
    record Diff(
            String field,
            String currentValue,
            boolean currentValueUnresolved,
            String proposedValue,
            DiffState state) {
    }
}
