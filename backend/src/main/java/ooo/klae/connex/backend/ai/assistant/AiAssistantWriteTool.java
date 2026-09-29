package ooo.klae.connex.backend.ai.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.beans.RecordTag;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
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

    /**
     * The record kinds this tool's target handle may name.
     *
     * <p>It is the only statement of them: the read-tool executor's handle check, the framework's
     * proposal and revalidation, and the card projection all read it from here.
     *
     * @return the non-empty set of record kinds a target handle may name
     */
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
     * <p>A confirm-tier tool's principals are also resolved when the proposal is prepared, and
     * their ids are pinned beside the stored request: the member reviews exactly those members, so
     * an approval whose own resolution names any other member is refused rather than written. A
     * {@code ResourceNotFoundException} at that point refuses the proposal before it is stored.
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
     * <p>A confirm-tier tool's resolution also runs when the proposal is prepared, and its field
     * and id are pinned beside the stored request: an approval whose own resolution differs from
     * the pin is refused rather than written, and the review labels only the pinned row. A {@code
     * ResourceNotFoundException} at that point refuses the proposal before it is stored.
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
     * The stored outcome fields that say only how the write went, which {@link #outcomeSummary}
     * may read even for a viewer who may not read the details.
     *
     * <p>Such a viewer still learns from the card that the write ran, so a summary may also say
     * whether it changed anything. The framework hands that viewer's review only these fields, and
     * only those that hold a boolean, so no workspace string or identifier can reach them through a
     * flag. A boolean can still be record state, so a shared flag must describe only how this write
     * went — whether it changed anything — and never a property of the record, such as whether it
     * is archived or restricted, which is exactly what withholding the details exists to keep from
     * that viewer. Each tool's flags are pinned by a reviewed ledger in
     * {@code AiAssistantWriteToolRegistryTest}.
     *
     * @return the flag fields, none by default
     */
    default Set<String> sharedOutcomeFlags() {
        return Set.of();
    }

    /**
     * What kind of write the stored request asked for, declared as named flags that {@link
     * #requestSummary} and {@link #outcomeSummary} may read even for a viewer who may not read the
     * details.
     *
     * <p>Such a viewer's review holds none of the request, so a summary that has always said which
     * kind of write was asked for — {@code assign_owner} says whether it removed or assigned an
     * owner — reads it from these flags instead. The declaration is static: the registry reads it
     * once at startup and refuses a malformed one, and the framework, never the tool, evaluates each
     * {@link SharedRequestFlag} against the stored request, so a flag can depend on nothing but
     * whether one request field names one fixed keyword. The framework hands that viewer's review
     * only these declared names, each holding a boolean, so no request value, workspace string,
     * identifier or property of the record can reach them through one. Each tool's flags are pinned
     * by a reviewed ledger in {@code AiAssistantWriteToolRegistryTest}.
     *
     * @return the flags by name, none by default
     */
    default Map<String, SharedRequestFlag> sharedRequestFlags() {
        return Map.of();
    }

    /**
     * The stored request fields a card requires to hold a non-blank string before it projects the
     * row at all.
     *
     * <p>A row stored under this tool's name is not always a proposal {@code prepare} wrote: a call
     * the loop refused keeps the model's raw arguments under the same name as a failed row, and
     * those arguments can be shaped like a proposal. The card projection hides such a row unless
     * every field named here holds a non-blank string, exactly as it hides a row whose target kind
     * the tool does not accept. The registry refuses a name that is not a string component of
     * {@link #requestType()}.
     *
     * @return the required request text fields, none by default
     */
    default Set<String> requiredRequestText() {
        return Set.of();
    }

    /**
     * Whether the framework screens this tool's detailed request summary for special-care text.
     *
     * <p>A screened summary the screen excludes is replaced by the one given to a viewer who may
     * not read the details. A tool may decline the screen only when its detailed summary names
     * nothing but a workspace member's label, resolved server-side against the workspace's own
     * member list rather than taken from the model or from record content, that the same viewer's
     * pending card already states unscreened as its {@link #diff} values. Screening such a summary
     * withholds nothing and only rewrites what the card has always said, so {@code assign_owner}
     * declines it. Each tool that declines is pinned by a reviewed ledger in
     * {@code AiAssistantWriteToolRegistryTest}.
     *
     * @return whether the detailed request summary is screened, {@code true} by default
     */
    default boolean screensDetailedRequestSummary() {
        return true;
    }

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
     * <p>Called only for a viewer who may read the proposal's details. The framework does not
     * screen these values for special-care text: a stage or a member is workspace vocabulary the
     * requester reviews by name, so a change keeps naming it even when a screened summary falls
     * back to the generic one. A tool whose values are free-text labels a member attaches to a
     * record, such as a tag name, screens them itself and returns {@code null} rather than name an
     * excluded one, so its card withholds the change, and with it the apply control, exactly where
     * its summary withholds the name.
     *
     * @param review the card's batched, viewer-authorized read state
     * @return the change, or {@code null} when the tool has no reviewable before and after or
     *     withholds it
     */
    Diff diff(Review review);

    /**
     * The member-visible request summary.
     *
     * <p>For a viewer who may not read the proposal's details the framework passes a review with
     * no target, members or stages, a request holding at most the boolean
     * {@link #sharedRequestFlags()}, and an outcome holding at most the boolean
     * {@link #sharedOutcomeFlags()}, so the summary can say no more than those flags. A detailed
     * summary is screened for special-care text, unless the tool declines under
     * {@link #screensDetailedRequestSummary()}, and replaced by the generic one when the screen
     * excludes it.
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

    /**
     * One request flag a viewer who may not read the details may learn: whether the stored
     * request's text field {@code field} names the fixed keyword {@code literal}, compared
     * ignoring case and surrounding whitespace.
     *
     * <p>The framework evaluates it; the tool only declares it, so the flag's value is a function
     * of that one request field and nothing else.
     *
     * @param field the request field compared
     * @param literal the fixed keyword it is compared with
     */
    record SharedRequestFlag(String field, String literal) {
        public SharedRequestFlag {
            if (field == null || field.isBlank() || literal == null || literal.isBlank()) {
                throw new IllegalStateException(
                        "An assistant shared request flag must name a field and a keyword");
            }
        }

        /**
         * @param request the stored request object, or {@code null}
         * @return whether the request's field is text naming the keyword
         */
        public boolean holds(JsonNode request) {
            JsonNode value = request == null ? null : request.get(field);
            return value != null
                    && value.isString()
                    && literal.equalsIgnoreCase(value.asString().trim());
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

    /**
     * The one read a write may make besides its own domain services: the point-in-time activities
     * already on the row's own person target's calendar inside one UTC window.
     *
     * <p>The framework binds it to the row's target before handing it over, so a tool names only a
     * window and can never read the calendar of a person it did not lock and gate; on a target that
     * is not a person it refuses. The framework answers it through the read-tool executor, which
     * refuses a person the workspace may no longer process and drops every activity linked to one,
     * so a tool reaches that calendar without holding the mapper or member lookup the executor
     * reads through. A tool receives it only in {@link Execution}, after every lock the write
     * depends on.
     */
    @FunctionalInterface
    interface ScheduleConflicts {

        /**
         * @param startUtc the window's start in UTC
         * @param endUtc the window's end in UTC
         * @return the executor's result, carrying its {@code conflicts} list and its
         *     {@code conflictsTruncated} flag
         * @throws IllegalStateException when the row's target is not a person
         */
        AiAssistantToolResult find(LocalDateTime startUtc, LocalDateTime endUtc);
    }

    /** What the framework holds locked for the target when it calls {@link #apply}. */
    record LockedTarget(String updatedAt, DealService.LockedStageChange stageChange) {
    }

    /**
     * One locked, authorized unit of work.
     *
     * <p>{@link #apply} may not re-resolve {@link #principals()} or {@link #resolution()} and may
     * not take a lock: every one of those was established before the locks it depends on. Its one
     * read beyond its own domain services is {@link #scheduleConflicts()}.
     */
    record Execution(
            Authority authority,
            Row row,
            List<PrincipalRequest> principals,
            Resolution resolution,
            LockedTarget lockedTarget,
            ScheduleConflicts scheduleConflicts) {
        public Execution {
            principals = List.copyOf(principals);
            Objects.requireNonNull(
                    scheduleConflicts, "An assistant write is handed the schedule read");
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

        /**
         * A read-back that verifies nothing, for a write whose domain service reports no
         * identifier to compare.
         *
         * <p>It compares the resolved identifier with itself, so it can never record a divergence
         * and is not verification. A tool that uses it states in its own Javadoc why nothing can be
         * read back, and {@code AiAssistantWriteToolSpiArchTest} pins which tools may.
         *
         * @param field the identifier's name
         * @param resolved the identifier the tool resolved before the write
         * @return a comparison of that identifier with itself
         */
        public static ReadBack structural(String field, Integer resolved) {
            return new ReadBack(field, resolved, resolved);
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
     *
     * <p>{@link AiAssistantWriteTool#undo} receives an {@code extra} equal to the one
     * {@link AiAssistantWriteTool#apply} recorded, rebuilt from the stored undo record. So that the
     * round trip is exact, an {@code extra} value must be a {@link String}, an {@link Integer}, a
     * {@link Boolean}, or a {@link Map} with string keys whose values are themselves one of these;
     * anything else — {@code null}, a list, or any other number type, which the stored JSON would
     * hand back as a different type or value — is refused when the inverse is built. Equality is
     * what survives, not key order.
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
            extra = durableCopy(extra, "");
            for (String key : extra.keySet()) {
                if (FRAMEWORK_KEYS.contains(key)) {
                    throw new IllegalStateException(
                            "An assistant inverse may not set the framework's undo key " + key);
                }
            }
        }

        private static Map<String, Object> durableCopy(Map<?, ?> values, String prefix) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalStateException("An assistant inverse key must be a string: "
                            + prefix + entry.getKey());
                }
                copy.put(key, durableValue(entry.getValue(), prefix + key));
            }
            return Collections.unmodifiableMap(copy);
        }

        private static Object durableValue(Object value, String path) {
            if (value instanceof String || value instanceof Integer || value instanceof Boolean) {
                return value;
            }
            if (value instanceof Map<?, ?> nested) {
                return durableCopy(nested, path + ".");
            }
            throw new IllegalStateException(
                    "An assistant inverse value must be a string, an integer, a boolean or a map"
                            + " of them: " + path);
        }
    }

    /**
     * The target values a card may compare against, as the viewer can currently see them.
     *
     * <p>{@code fields} holds the target's own reviewable column values, keyed by field name and
     * read off the same row the snapshot is built from; a column that holds no value has no key.
     * The framework hands a tool these values only when it declared {@link ReviewInput#FIELDS},
     * and an empty map otherwise. A person carries {@code firstResponseDueAt}, the UTC deadline of
     * a running first-response clock as an ISO local date-time.
     *
     * @param fields the target's reviewable field values, never {@code null}
     */
    record RecordSnapshot(
            String label,
            Integer pipelineId,
            Integer ownerId,
            Integer stageId,
            String updatedAt,
            Map<String, String> fields) {

        public RecordSnapshot {
            fields = fields == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /**
         * @param field a reviewable field name
         * @return the field's current value, or {@code null} when the target holds none or the
         *     tool did not declare {@link ReviewInput#FIELDS}
         */
        public String field(String field) {
            return fields.get(field);
        }

        /** @return this snapshot with no reviewable field values, for a tool that read none */
        public RecordSnapshot withoutFields() {
            return new RecordSnapshot(label, pipelineId, ownerId, stageId, updatedAt, Map.of());
        }
    }

    /**
     * The batched, viewer-authorized read state one card is projected from.
     *
     * <p>When the viewer may not read the details, the framework withholds every record value:
     * {@code target} is {@code null}, {@code request} holds at most the tool's boolean
     * {@link AiAssistantWriteTool#sharedRequestFlags()}, {@code outcome} holds at most the tool's
     * boolean {@link AiAssistantWriteTool#sharedOutcomeFlags()} of an executed call, and
     * {@code members}, {@code stages}, {@code tags} and {@code targetTags} are empty.
     *
     * <p>A proposal prepared since resolutions were pinned carries the id {@link
     * AiAssistantWriteTool#resolve} and the member ids {@link AiAssistantWriteTool#principals}
     * returned when it was proposed; a proposal stored before then carries neither and is reviewed
     * by name exactly as it always was. A proposal is pinned exactly when {@code
     * pinnedPrincipalIds} is non-null; {@code pinnedResolutionId} is {@code null} on a pinned
     * proposal whose tool resolved no value, and never on its own marks a proposal as unpinned. A
     * tool that reviews a pinned proposal names only the pinned rows, and reports the value
     * unresolved whenever its approval would refuse: when the request no longer resolves to the
     * pinned row, when a resolution the tool never makes is pinned, or when one it makes is not.
     * The card still resolves the stored name, as the approval does, so a pinned row renamed
     * since the proposal is unresolved rather than labelled by its id. Neither pin is handed to a
     * viewer who may not read the details.
     *
     * @param detailsReadable whether the viewer requested the proposal and can read its target
     * @param target the visible target, or {@code null} when the viewer may not read it
     * @param request the stored request object, only its shared request flags when the viewer may
     *     not read it, or {@code null} when it has none
     * @param outcome the stored outcome of an executed call, only its shared flags when the viewer
     *     may not read it, or {@code null}
     * @param members the workspace's members when the tool declared {@link ReviewInput#MEMBERS}
     * @param stages the workspace's pipeline stages when the tool declared {@link ReviewInput#STAGES}
     * @param tags the workspace's tags when the tool declared {@link ReviewInput#TAGS}
     * @param targetTags the tags the target currently holds when the tool declared {@link
     *     ReviewInput#TAGS}, read for the whole page of cards in one batch per record kind
     * @param pinnedResolutionId the resolved id pinned at proposal time, or {@code null} when none
     *     was pinned
     * @param pinnedPrincipalIds the principal ids pinned at proposal time in ascending order, or
     *     {@code null} for a proposal stored before principals were pinned
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
            List<Tag> tags,
            List<RecordTag> targetTags,
            Set<Permission> viewerPermissions,
            Integer pinnedResolutionId,
            List<Integer> pinnedPrincipalIds) {

        public Review {
            pinnedPrincipalIds = pinnedPrincipalIds == null ? null : List.copyOf(pinnedPrincipalIds);
        }

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
        STAGES,
        /**
         * The workspace's tags and the tags the target currently holds, for a tool that reviews a
         * tag association.
         */
        TAGS,
        /**
         * The target's own reviewable column values, carried on its {@link RecordSnapshot}, for a
         * tool whose card states what the record holds now as its before-value.
         */
        FIELDS
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
