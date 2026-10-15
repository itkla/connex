package ooo.klae.connex.backend.services;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.EntityReferenceMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

import lombok.RequiredArgsConstructor;

/**
 * Derives the structured @/# references for an entity's prose field from its
 * content tokens ({@code [Label](type:id)}) and persists them, replacing the
 * previous set. Every token is validated against the active workspace before it
 * is stored, so a client can never inject a reference to an entity outside the
 * current tenant. Members ({@code user}) drive mention notifications; contacts
 * ({@code person}), deals, and companies are stored as inline record references
 * (no notification). The source entity is polymorphic ({@code sourceType} /
 * {@code sourceId}): notes, tasks, activities, comments, and introductions share this machinery.
 */
@Service
@RequiredArgsConstructor
public class ReferenceService {

    private final EntityReferenceMapper entityReferenceMapper;
    private final WorkspaceService workspaceService;
    private final PersonMapper personMapper;
    private final DealMapper dealMapper;
    private final CompanyMapper companyMapper;
    private final NoteMapper noteMapper;
    private final AttachmentMapper attachmentMapper;
    private final TaskMapper taskMapper;
    private final ActivityMapper activityMapper;
    private final WorkspaceMapper workspaceMapper;

    public static final String SOURCE_NOTE = "note";
    public static final String SOURCE_TASK = "task";
    public static final String SOURCE_ACTIVITY = "activity";
    public static final String SOURCE_COMMENT = "comment";
    public static final String SOURCE_INTRODUCTION = "introduction";
    public static final String SOURCE_DEAL = "deal";

    static final String TYPE_USER = "user";
    static final String TYPE_PERSON = "person";
    static final String TYPE_DEAL = "deal";
    static final String TYPE_COMPANY = "company";
    static final String TYPE_NOTE = "note";
    static final String TYPE_FILE = "file";
    static final String TYPE_TASK = "task";
    static final String TYPE_ACTIVITY = "activity";
    private static final int MAX_REFERENCES = 100;
    private static final int MAX_LABEL_LENGTH = 255;
    private static final int MAX_MEMBER_LABEL_CHARACTERS = 999;
    private static final int BATCH_SIZE = 500;
    private static final String REFERENCE_SEPARATOR = "(?::|&colon;|\\\\:)";
    private static final String REFERENCE_TYPE_PATTERN =
        "(user|person|deal|company|note|file|task|activity)";
    private static final Pattern TOKEN = Pattern.compile(
        "\\[([^\\]]+)\\]\\(" + REFERENCE_TYPE_PATTERN
            + REFERENCE_SEPARATOR + "(\\d+)\\)");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile(
        "(?im)^[ \\t]{0,3}+\\[((?:\\\\.|[^\\]\\\\])++)\\]:[ \\t]*+"
            + "(?:\\r?\\n[ \\t]++)?<?" + REFERENCE_TYPE_PATTERN
            + ":(\\d+)>?(?:[ \\t].*)?$");
    private static final Pattern MEMBER_CANONICAL_DESTINATION = Pattern.compile(
        "\\]\\(" + REFERENCE_TYPE_PATTERN + REFERENCE_SEPARATOR + "(\\d++)\\)");
    private static final Pattern MEMBER_REFERENCE_DEFINITION = Pattern.compile(
        "\\[((?:\\\\.|[^\\[\\]\\\\]){1,999}+)\\]:");
    private static final Pattern MEMBER_REFERENCE_DESTINATION = Pattern.compile(
        "(?i)<?user:(\\d+)>?(?:[ \\t].*)?");
    private static final Pattern MEMBER_DEFINITION_DESTINATION = Pattern.compile("(?i)<?user:(\\d+)>?");
    private static final String MEMBER_LINE_PREFIX = "[ ]{0,3}+(?:(?:>[ \t]?|[-+*][ \t]++|[0-9]{1,9}[.)][ \t]++)[ ]{0,3}+)*+";
    private static final Pattern MEMBER_LINE_CONTENT = Pattern.compile(MEMBER_LINE_PREFIX);
    private static final Pattern MEMBER_CONTINUATION_PREFIX = Pattern.compile("(?m)^(?!\\A)" + MEMBER_LINE_PREFIX);
    private static final Pattern MEMBER_CODE_BLOCK_START = Pattern.compile(
        "(?:#{1,6}(?:[ \t]|$)|`{3,}+[^`]*+$|~{3,}+"
            + "|<!--|<\\?|<![A-Za-z]|<!\\[CDATA\\["
            + "|<(?i:pre|script|style|textarea)(?:[ \t>]|$)"
            + "|</?(?i:address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details"
            + "|dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html"
            + "|iframe|legend|li|link|main|menu|menuitem|nav|noframes|ol|optgroup|option|p|param|search|section"
            + "|summary|table|tbody|td|tfoot|th|thead|title|tr|track|ul)(?:[ \t>]|/>|$))");
    private static final String MEMBER_INLINE_SPACE = "[ \\t]*(?:(?:\\r\\n|[\\r\\n])[ \\t]*)?";
    private static final String MEMBER_ATTRIBUTE_SPACE = "(?:[ \\t]++|[ \\t]*(?:\\r\\n|[\\r\\n])[ \\t]*)";
    private static final String MEMBER_ATTRIBUTE_VALUE = "(?:[^ \\t\\r\\n\\\"'=<>`]++|'[^']*+'|\\\"[^\\\"]*+\\\")";
    private static final Pattern MEMBER_OPAQUE_INLINE = Pattern.compile(
        "(?:<[A-Za-z][A-Za-z0-9-]*+(?:" + MEMBER_ATTRIBUTE_SPACE
            + "[A-Za-z_:][A-Za-z0-9_.:-]*+(?:" + MEMBER_INLINE_SPACE + "=" + MEMBER_INLINE_SPACE
            + MEMBER_ATTRIBUTE_VALUE + ")?+)*+" + MEMBER_INLINE_SPACE + "/?>"
            + "|</[A-Za-z][A-Za-z0-9-]*+" + MEMBER_INLINE_SPACE + ">"
            + "|<[A-Za-z][A-Za-z0-9+.-]{1,31}:[^<>\\x00-\\x20]*+>"
            + "|<[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]++@"
            + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*+>)");

    /**
     * Reader-scoped prose and structured references after invisible targets are removed.
     * @param content reader-safe prose
     * @param references reader-visible structured references
     */
    public record ReaderVisibleContent(String content, List<EntityReference> references) {
        public ReaderVisibleContent {
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

    /** One authored or decoded member label and the user target of that reading, without authorization. */
    public record MemberReference(String label, int memberId) {
    }

    private record ReferenceTarget(String type, int id) {
    }

    private record MarkdownTargets(List<ReferenceTarget> targets, Set<String> unparseableTypes) {
        private MarkdownTargets {
            targets = List.copyOf(targets);
            unparseableTypes = Set.copyOf(unparseableTypes);
        }

        private boolean isEmpty() {
            return targets.isEmpty() && unparseableTypes.isEmpty();
        }

        private boolean hasUnparseableTarget() {
            return !unparseableTypes.isEmpty();
        }
    }

    /**
     * Re-derives and persists a source entity's @/# references from its content,
     * replacing any previous set. Every valid member reference is stored (so the
     * mention chip renders regardless of who edits the source); returns the IDs of
     * members referenced for the first time (present now but not before this
     * call), so the caller can notify only newly-added mentions. Excluding the
     * acting author from notification is the caller's responsibility. Scoped to
     * {@code workspaceId}. A first-time sync skips the empty range delete so
     * concurrent creates do not contend on the index's empty terminal gap.
     *
     * @param workspaceId the owning workspace
     * @param sourceType  the entity type the references belong to ({@code note}, {@code task}, {@code comment})
     * @param sourceId    the entity whose references are being synced
     * @param content     the entity's current prose content
     * @return the user IDs newly referenced by this sync
     */
    @Transactional
    public List<Integer> syncReferences(int workspaceId, String sourceType, int sourceId, String content) {
        List<EntityReference> previous = entityReferenceMapper.findBySource(workspaceId, sourceType, sourceId);
        Set<Integer> before = mentionedMemberIds(previous);
        int currentUserId = workspaceService.getCurrentUserId();
        List<EntityReference> resolved = resolve(workspaceId, sourceType, sourceId, content, currentUserId);

        if (!previous.isEmpty()) {
            entityReferenceMapper.deleteBySource(workspaceId, sourceType, sourceId);
        }
        for (EntityReference reference : resolved) {
            entityReferenceMapper.insert(reference);
        }

        Set<Integer> added = mentionedMemberIds(resolved);
        added.removeAll(before);
        return new ArrayList<>(added);
    }

    /**
     * Note-scoped overload of {@link #syncReferences(int, String, int, String)},
     * preserved so existing note callers are unaffected by the generalization.
     *
     * @param workspaceId the owning workspace
     * @param noteId      the note whose references are being synced
     * @param content     the note's current content
     * @return the user IDs newly referenced by this sync
     */
    @Transactional
    public List<Integer> syncReferences(int workspaceId, int noteId, String content) {
        return syncReferences(workspaceId, SOURCE_NOTE, noteId, content);
    }

    /**
     * The resolved references for a single source entity, in stored order.
     *
     * @param workspaceId the owning workspace
     * @param sourceType  the source entity type
     * @param sourceId    the source entity
     * @return the entity's references (never null)
     */
    public List<EntityReference> referencesFor(int workspaceId, String sourceType, int sourceId) {
        return entityReferenceMapper.findBySource(workspaceId, sourceType, sourceId);
    }

    /**
     * Removes every reference belonging to a source entity. Callers invoke this
     * when the source is deleted — {@code entity_reference} is polymorphic and so
     * carries no FK cascade to rely on.
     *
     * @param workspaceId the owning workspace
     * @param sourceType  the source entity type
     * @param sourceId    the source entity
     */
    public void deleteReferences(int workspaceId, String sourceType, int sourceId) {
        entityReferenceMapper.deleteBySource(workspaceId, sourceType, sourceId);
    }

    /** Removes every stored reference for a batch of same-type sources in one statement. */
    public void deleteReferencesForSources(int workspaceId, String sourceType, List<Integer> sourceIds) {
        if (sourceIds.isEmpty()) {
            return;
        }
        entityReferenceMapper.deleteBySourceIds(workspaceId, sourceType, sourceIds);
    }

    /**
     * Removes every reference pointing AT a target entity, invoked when the
     * target itself is deleted so no dangling inbound chip is left behind.
     *
     * @param workspaceId the owning workspace
     * @param refType     the deleted target's type (e.g. {@code note}, {@code file})
     * @param refId       the deleted target's id
     */
    public void deleteReferencesTo(int workspaceId, String refType, int refId) {
        entityReferenceMapper.deleteByTarget(workspaceId, refType, refId);
    }

    /**
     * Attaches each note's resolved references in a single batch query, so any
     * read path (including MyBatis collections that bypass {@code NoteService})
     * returns notes the frontend can render as chips. Mutates the notes in place
     * and returns them. Scoped to {@code workspaceId}.
     *
     * @param workspaceId the owning workspace
     * @param notes the notes to hydrate
     * @return the same notes, each with its references populated
     */
    public List<Note> hydrate(int workspaceId, List<Note> notes) {
        if (notes == null || notes.isEmpty()) {
            return notes;
        }
        Map<Integer, List<EntityReference>> bySource =
            referencesBySource(workspaceId, SOURCE_NOTE, notes.stream().map(Note::getId).toList());
        for (Note note : notes) {
            note.setReferences(bySource.getOrDefault(note.getId(), List.of()));
        }
        redactInvisibleReferences(workspaceId, notes);
        return notes;
    }

    /** Hydrates a SQL-bounded note page and truncates content only after reader-specific redaction. */
    public List<Note> hydrateNotePreviews(int workspaceId, List<Note> notes) {
        List<Note> hydrated = hydrate(workspaceId, notes);
        if (hydrated == null) {
            return List.of();
        }
        for (Note note : hydrated) {
            if (note.getContent() != null && note.getContent().length() > 500) {
                note.setContent(note.getContent().substring(0, 500));
            }
        }
        return hydrated;
    }

    /**
     * Attaches each task's resolved references in a single batch query, so any
     * read path returns tasks the frontend can render as chips. Mutates the tasks
     * in place and returns them. Scoped to {@code workspaceId}.
     *
     * @param workspaceId the owning workspace
     * @param tasks the tasks to hydrate
     * @return the same tasks, each with its references populated
     */
    public List<Task> hydrateTasks(int workspaceId, List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return tasks;
        }
        Map<Integer, List<EntityReference>> bySource =
            referencesBySource(workspaceId, SOURCE_TASK, tasks.stream().map(Task::getId).toList());
        for (Task task : tasks) {
            task.setReferences(bySource.getOrDefault(task.getId(), List.of()));
        }
        redactInvisibleTargets(workspaceId, tasks,
            Task::getReferences, Task::setReferences, Task::getDescription, Task::setDescription);
        return tasks;
    }

    /**
     * Attaches reader-visible references to deals and masks invisible targets in their close reasons.
     * The references and note visibility are resolved in batches, and the input order is preserved.
     * @param workspaceId the owning workspace
     * @param deals the deals to hydrate
     * @return the same deals with reader-scoped close reasons and references
     */
    public List<Deal> hydrateDeals(int workspaceId, List<Deal> deals) {
        if (deals == null || deals.isEmpty()) {
            return deals;
        }
        Map<Integer, List<EntityReference>> bySource = referencesBySource(
            workspaceId, SOURCE_DEAL, deals.stream().map(Deal::getId).toList());
        List<ReaderVisibleContent> visible = redactInvisibleTargets(
            workspaceId,
            deals.stream()
                .map(deal -> new ReaderVisibleContent(
                    deal.getClosedReason(), bySource.getOrDefault(deal.getId(), List.of())))
                .toList());
        for (int index = 0; index < deals.size(); index++) {
            Deal deal = deals.get(index);
            ReaderVisibleContent content = visible.get(index);
            deal.setClosedReason(content.content());
            deal.setReferences(content.references());
        }
        return deals;
    }

    /**
     * Attaches each activity's resolved references in a single batch query, so any
     * read path returns activities the frontend can render as chips. Mutates the
     * activities in place and returns them. Scoped to {@code workspaceId}.
     *
     * @param workspaceId the owning workspace
     * @param activities the activities to hydrate
     * @return the same activities, each with its references populated
     */
    public List<Activity> hydrateActivities(int workspaceId, List<Activity> activities) {
        if (activities == null || activities.isEmpty()) {
            return activities;
        }
        Map<Integer, List<EntityReference>> bySource =
            referencesBySource(workspaceId, SOURCE_ACTIVITY, activities.stream().map(Activity::getId).toList());
        for (Activity activity : activities) {
            activity.setReferences(bySource.getOrDefault(activity.getId(), List.of()));
        }
        redactInvisibleTargets(workspaceId, activities,
            Activity::getReferences, Activity::setReferences, Activity::getNotes, Activity::setNotes);
        return activities;
    }

    /**
     * Groups a set of source entities' references by {@code sourceId} in a single
     * batch query, for callers that hydrate a projection DTO (which has no bean to
     * mutate). Returns an empty map for an empty id list. Scoped to {@code workspaceId}.
     *
     * @param workspaceId the owning workspace
     * @param sourceType the source entity type
     * @param sourceIds the source entity ids
     * @return references grouped by source id
     */
    public Map<Integer, List<EntityReference>> referencesBySource(
            int workspaceId, String sourceType, List<Integer> sourceIds) {
        Map<Integer, List<EntityReference>> bySource = new LinkedHashMap<>();
        if (sourceIds == null || sourceIds.isEmpty()) {
            return bySource;
        }
        for (int start = 0; start < sourceIds.size(); start += BATCH_SIZE) {
            List<Integer> batch = sourceIds.subList(start, Math.min(start + BATCH_SIZE, sourceIds.size()));
            for (EntityReference reference : entityReferenceMapper.findBySources(
                    workspaceId, sourceType, batch)) {
                bySource.computeIfAbsent(reference.getSourceId(), key -> new ArrayList<>()).add(reference);
            }
        }
        return bySource;
    }

    /**
     * Removes every reference target the current reader cannot see from prose and structured references.
     * The input list is resolved in one visibility query and the returned list preserves its order.
     * @param workspaceId the owning workspace
     * @param items prose/reference pairs to scope
     * @return reader-visible pairs in input order
     */
    public List<ReaderVisibleContent> redactInvisibleTargets(
            int workspaceId, List<ReaderVisibleContent> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Set<ReferenceTarget> targets = new HashSet<>();
        boolean containsTarget = false;
        for (ReaderVisibleContent item : items) {
            for (EntityReference reference : item.references()) {
                containsTarget = true;
                targets.add(new ReferenceTarget(reference.getRefType(), reference.getRefId()));
            }
            MarkdownTargets markdownTargets = markdownTargets(item.content());
            if (!markdownTargets.isEmpty()) {
                containsTarget = true;
                targets.addAll(markdownTargets.targets());
            }
        }
        if (!containsTarget) {
            return List.copyOf(items);
        }
        int currentUserId = workspaceService.getCurrentUserId();
        Set<ReferenceTarget> visible = visibleTargets(workspaceId, targets, currentUserId);
        return items.stream()
            .map(item -> new ReaderVisibleContent(
                redactTokens(item.content(), visible),
                item.references().stream()
                    .filter(reference -> visible.contains(
                        new ReferenceTarget(reference.getRefType(), reference.getRefId())))
                    .toList()))
            .toList();
    }

    private List<EntityReference> resolve(
            int workspaceId, String sourceType, int sourceId, String content, int currentUserId) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Map<String, EntityReference> unique = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(normalizeReferenceSyntax(content));
        while (matcher.find() && unique.size() < MAX_REFERENCES) {
            String type = matcher.group(2);
            int refId;
            try {
                refId = Integer.parseInt(matcher.group(3));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (!isVisible(workspaceId, type, refId, currentUserId)) {
                continue;
            }
            String key = type + ":" + refId;
            if (unique.containsKey(key)) {
                continue;
            }
            unique.put(key, build(workspaceId, sourceType, sourceId, type, refId, matcher.group(1)));
        }
        return new ArrayList<>(unique.values());
    }

    private boolean isVisible(int workspaceId, String type, int refId, int currentUserId) {
        return switch (type) {
            case TYPE_USER -> isMemberTargetVisible(workspaceId, refId);
            case TYPE_PERSON -> personMapper.exists(workspaceId, refId);
            case TYPE_DEAL -> dealMapper.exists(workspaceId, refId);
            case TYPE_COMPANY -> companyMapper.exists(workspaceId, refId);
            case TYPE_NOTE -> noteMapper.getVisibleNoteById(workspaceId, refId, currentUserId) != null;
            case TYPE_FILE -> !attachmentMapper.getVisibleIdsIn(
                workspaceId, List.of(refId), currentUserId).isEmpty();
            case TYPE_TASK -> taskMapper.exists(workspaceId, refId);
            case TYPE_ACTIVITY -> activityMapper.exists(workspaceId, refId);
            default -> false;
        };
    }

    /** Applies the user-reference visibility rule, including active and pending workspace members. */
    public boolean isMemberTargetVisible(int workspaceId, int memberId) {
        return workspaceService.isMemberIncludingPending(workspaceId, memberId);
    }

    /**
     * Requires a supported target to be visible to the current reader before a relational lookup.
     * @param workspaceId the active workspace
     * @param type the closed internal reference type
     * @param refId the target id
     * @param currentUserId the current reader
     * @return the canonical reference type
     */
    public String requireVisibleTarget(int workspaceId, String type, int refId, int currentUserId) {
        String canonicalType = canonicalReferenceType(type);
        if (canonicalType == null || refId <= 0) {
            throw new ResourceNotFoundException("Reference target not found");
        }
        ReferenceTarget target = new ReferenceTarget(canonicalType, refId);
        if (!visibleTargets(workspaceId, Set.of(target), currentUserId).contains(target)) {
            throw new ResourceNotFoundException("Reference target not found");
        }
        return canonicalType;
    }

    /**
     * Removes reference targets and content tokens that the current reader cannot see.
     */
    private void redactInvisibleReferences(int workspaceId, List<Note> notes) {
        redactInvisibleTargets(workspaceId, notes,
            Note::getReferences, Note::setReferences, Note::getContent, Note::setContent);
    }

    /**
     * Generic target redaction shared by every prose-bearing source type. A no-op when no internal
     * target is referenced. Accessors cover each bean's differing reference and content fields.
     *
     * @param workspaceId   the owning workspace
     * @param items         the source items to redact in place
     * @param getReferences reads an item's resolved references
     * @param setReferences writes an item's filtered references
     * @param getContent    reads an item's stored content
     * @param setContent    writes an item's masked content
     * @param <T>           the source bean type
     */
    private <T> void redactInvisibleTargets(
            int workspaceId,
            List<T> items,
            Function<T, List<EntityReference>> getReferences,
            BiConsumer<T, List<EntityReference>> setReferences,
            Function<T, String> getContent,
            BiConsumer<T, String> setContent) {
        List<ReaderVisibleContent> redacted = redactInvisibleTargets(workspaceId, items.stream()
            .map(item -> new ReaderVisibleContent(getContent.apply(item), getReferences.apply(item)))
            .toList());
        for (int index = 0; index < items.size(); index++) {
            T item = items.get(index);
            ReaderVisibleContent visible = redacted.get(index);
            setReferences.accept(item, visible.references());
            setContent.accept(item, visible.content());
        }
    }

    private static String redactTokens(String content, Set<ReferenceTarget> visibleTargets) {
        if (content == null) {
            return null;
        }
        String redacted = TOKEN.matcher(content).replaceAll(match ->
            parseReferenceTarget(match.group(2), match.group(3))
                .filter(visibleTargets::contains)
                .map(ignored -> Matcher.quoteReplacement(match.group()))
                .orElseGet(() -> redactionPlaceholder(match.group(2))));
        MarkdownTargets targets = markdownTargets(redacted);
        if (targets.hasUnparseableTarget()) {
            return "(unavailable reference)";
        }
        Optional<ReferenceTarget> invisible = targets.targets().stream()
            .filter(target -> !visibleTargets.contains(target))
            .findFirst();
        if (invisible.isPresent()) {
            return redactionPlaceholder(invisible.get().type());
        }
        return redacted;
    }

    private static String redactionPlaceholder(String type) {
        return TYPE_NOTE.equals(type) ? "(private note)" : "(unavailable reference)";
    }

    /**
     * Collects every internal target an untrusted body reaches through inline destinations and
     * link-reference definitions.
     *
     * <p>Every repetition in that pattern is possessive so a hostile body cannot drive backtracking,
     * and each is unambiguous in context, so refusing to give characters back does not change what
     * it matches: no label alternative can consume a bare {@code ]}, and no indent run can be
     * followed by a space or tab that the next required token would accept. Its trailing
     * description is {@code [ \t].*} rather than {@code [ \t]+.*} because {@code .} already covers
     * further spaces and tabs.
     *
     * @param content the raw reader-visible body
     * @return the referenced targets and whether any target was unparseable
     */
    private static MarkdownTargets markdownTargets(String content) {
        if (content == null || content.isBlank()) {
            return new MarkdownTargets(List.of(), Set.of());
        }
        String normalized = normalizeReferenceSyntax(content);
        List<ReferenceTarget> targets = new ArrayList<>();
        Set<String> unparseableTypes = new HashSet<>();
        collectInlineTargets(normalized, targets, unparseableTypes);
        collectReferenceTargets(REFERENCE_DEFINITION.matcher(normalized), targets, unparseableTypes);
        return new MarkdownTargets(targets, unparseableTypes);
    }

    private static String normalizeReferenceSyntax(String content) {
        return HtmlUtils.htmlUnescape(content.replace("&colon;", ":")).replace("\\:", ":");
    }

    private static void collectReferenceTargets(
            Matcher matcher, List<ReferenceTarget> targets, Set<String> unparseableTypes) {
        while (matcher.find()) {
            Optional<ReferenceTarget> target = parseReferenceTarget(matcher.group(2), matcher.group(3));
            if (target.isPresent()) {
                targets.add(target.get());
            } else {
                String type = canonicalReferenceType(matcher.group(2));
                if (type != null) {
                    unparseableTypes.add(type);
                }
            }
        }
    }

    private static void collectInlineTargets(
            String content, List<ReferenceTarget> targets, Set<String> unparseableTypes) {
        collectInlineTargets(content, targets, unparseableTypes, new ArrayList<>());
    }

    private static void collectInlineTargets(
            String content, List<ReferenceTarget> targets, Set<String> unparseableTypes,
            List<String> memberLabels) {
        int[] labelEnds = matchingLabelEnds(content);
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) != '[' || isEscaped(content, index)) {
                continue;
            }
            int labelEnd = labelEnds[index];
            if (labelEnd < 0) {
                continue;
            }
            int destinationStart = skipWhitespace(content, labelEnd + 1);
            if (destinationStart >= content.length() || content.charAt(destinationStart) != '(') {
                index = labelEnd;
                continue;
            }
            destinationStart = skipWhitespace(content, destinationStart + 1);
            if (destinationStart < content.length() && content.charAt(destinationStart) == '<') {
                destinationStart++;
            }
            int typeEnd = destinationStart;
            while (typeEnd < content.length() && Character.isLetter(content.charAt(typeEnd))) {
                typeEnd++;
            }
            if (typeEnd == destinationStart || typeEnd >= content.length()
                    || content.charAt(typeEnd) != ':') {
                index = labelEnd;
                continue;
            }
            String type = canonicalReferenceType(content.substring(destinationStart, typeEnd));
            if (type == null) {
                index = labelEnd;
                continue;
            }
            int idStart = typeEnd + 1;
            int idEnd = idStart;
            while (idEnd < content.length() && Character.isDigit(content.charAt(idEnd))) {
                idEnd++;
            }
            if (idEnd == idStart) {
                unparseableTypes.add(type);
            } else {
                Optional<ReferenceTarget> target = parseReferenceTarget(
                    type, content.substring(idStart, idEnd));
                if (target.isPresent()) {
                    targets.add(target.get());
                    if (TYPE_USER.equals(target.get().type())) {
                        memberLabels.add(content.substring(index + 1, labelEnd));
                    }
                } else {
                    unparseableTypes.add(type);
                }
            }
            index = labelEnd;
        }
    }

    /**
     * Extracts every authored member-reference label from prose already scoped by
     * {@link #redactInvisibleTargets}, including repeated targets and reference-style Markdown.
     * Original delimiters establish label spans before either labels or destinations are decoded.
     * This pure parser grants no visibility and must receive only reader-authorized content.
     */
    public static List<String> memberLabelsFromReaderVisibleContent(String readerVisibleContent) {
        List<MemberLabelSpan> labels = memberLabelSpans(readerVisibleContent);
        var candidates = new java.util.LinkedHashSet<String>();
        labels.forEach(span -> candidates.add(normalizeReferenceSyntax(
                readerVisibleContent.substring(span.start(), span.end()))));
        labels.forEach(span -> candidates.add(readerVisibleContent.substring(span.start(), span.end())));
        return candidates.stream().filter(label -> !label.isBlank()).toList();
    }

    /**
     * Extracts member labels and target ids from original prose using every member-label reading.
     * Authored and decoded spellings retain each possible user target, including ambiguous definitions.
     * This pure parser grants no visibility; callers must authorize each target before using its label.
     */
    public static List<MemberReference> memberReferencesInContent(String content) {
        List<MemberLabelSpan> labels = memberLabelSpans(content);
        var candidates = new java.util.LinkedHashSet<MemberReference>();
        Map<MemberTargets, Set<String>> collected = new HashMap<>();
        labels.forEach(span -> collectMemberReferences(candidates, collected, span.targets(),
                normalizeReferenceSyntax(content.substring(span.start(), span.end()))));
        labels.forEach(span -> collectMemberReferences(candidates, collected, span.targets(),
                content.substring(span.start(), span.end())));
        return List.copyOf(candidates);
    }

    /** Expands shared definition targets only once per distinct label, after source-span collection. */
    private static void collectMemberReferences(
            Set<MemberReference> references, Map<MemberTargets, Set<String>> collected,
            MemberTargets targets, String label) {
        if (!label.isBlank() && collected.computeIfAbsent(targets, ignored -> new HashSet<>()).add(label)) {
            for (int id : targets.ids) {
                references.add(new MemberReference(label, id));
            }
        }
    }

    private static List<MemberLabelSpan> memberLabelSpans(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Map<String, MemberTargets> memberDefinitions = new HashMap<>();
        ReferenceDelimiters delimiters = new ReferenceDelimiters(content, true);
        Set<Integer> definitionLineStarts = memberDefinitionLineStarts(content);
        Map<Integer, Integer> definitionEnds = new HashMap<>();
        Matcher definitions = MEMBER_REFERENCE_DEFINITION.matcher(content);
        while (definitions.find()) {
            if (delimiters.escaped()[definitions.start()]
                    || !memberLabelWithinLimit(content, definitions.start(1), definitions.end(1))) {
                continue;
            }
            if (definitionLineStarts.contains(definitions.start())) {
                int end = referenceDefinitionEnd(content, definitions.end());
                if (end >= 0) {
                    definitionEnds.put(definitions.start(), end);
                }
            }
            memberReferenceId(memberDefinitionDestination(content, definitions.end()), true).ifPresent(id ->
                    memberReferenceKeys(definitions.group(1)).forEach(key ->
                            memberDefinitions.computeIfAbsent(key, ignored -> new MemberTargets()).ids.add(id)));
        }
        Set<Integer> validDefinitionStarts = memberDefinitionStartsOutsideParagraphs(content, definitionEnds);
        List<List<MemberLabelSpan>> readings = new ArrayList<>();
        readings.add(canonicalMemberLabels(content, delimiters, false));
        readings.add(canonicalMemberLabels(content, delimiters, true));
        for (int mode = 0; mode < 3; mode++) {
            List<MemberLabelSpan> spans = new ArrayList<>();
            collectMemberLabels(content, memberDefinitions, validDefinitionStarts, spans,
                    matchingLabelEnds(content, mode != 0, mode == 2, true, delimiters));
            readings.add(spans);
        }
        return mergeMemberLabelReadings(readings);
    }

    /**
     * Resolves each candidate's sequential delimiter steps through a backward suffix table. Code
     * spans and escapes use the same transition as structural readings, including literal slashes
     * inside code. The additional no-code reading remains conservative around ambiguous blocks.
     * Destinations are cached and the 999-code-point bound precedes every source-label extraction.
     */
    private static List<MemberLabelSpan> canonicalMemberLabels(
            String content, ReferenceDelimiters delimiters, boolean codeSpans) {
        int[] nextClose = new int[content.length() + 1];
        nextClose[content.length()] = -1;
        for (int index = content.length() - 1; index >= 0; index--) {
            int end = delimiters.next(index, codeSpans, false);
            nextClose[index] = end > index + 1 ? nextClose[end]
                    : content.charAt(index) == ']' ? index : nextClose[index + 1];
        }
        int[] destinations = new int[content.length()];
        Matcher destination = MEMBER_CANONICAL_DESTINATION.matcher(content);
        List<MemberLabelSpan> labels = new ArrayList<>();
        for (int index = 0; index < content.length(); index = delimiters.next(index, false, false)) {
            if (content.charAt(index) != '[') {
                continue;
            }
            int start = index + 1;
            int end = nextClose[start];
            if (end <= start || !memberLabelWithinLimit(content, start, end)) {
                continue;
            }
            if (destinations[end] == 0) {
                destinations[end] = destination.region(end, content.length()).lookingAt()
                        && TYPE_USER.equals(destination.group(1))
                        ? parseReferenceTarget(TYPE_USER, destination.group(2)).map(ReferenceTarget::id).orElse(-1)
                        : -1;
            }
            if (destinations[end] > 0) {
                labels.add(new MemberLabelSpan(start, end, new MemberTargets(destinations[end])));
            }
        }
        return labels;
    }

    /** Tests the shared 999-code-point bound for raw member provenance without copying the label. */
    public static boolean memberLabelWithinLimit(String label) {
        return label != null && memberLabelWithinLimit(label, 0, label.length());
    }

    /** Rejects oversized spans before allocation; the UTF-16 gate bounds code-point counting too. */
    private static boolean memberLabelWithinLimit(String content, int start, int end) {
        return end - start <= MAX_MEMBER_LABEL_CHARACTERS * 2
                && content.codePointCount(start, end) <= MAX_MEMBER_LABEL_CHARACTERS;
    }

    /** Merges the fixed number of source-ordered readings in linear time. */
    private static List<MemberLabelSpan> mergeMemberLabelReadings(List<List<MemberLabelSpan>> readings) {
        int[] positions = new int[readings.size()];
        var merged = new java.util.LinkedHashSet<MemberLabelSpan>();
        while (true) {
            MemberLabelSpan first = null;
            int selected = -1;
            for (int index = 0; index < readings.size(); index++) {
                if (positions[index] < readings.get(index).size()) {
                    MemberLabelSpan next = readings.get(index).get(positions[index]);
                    if (first == null || next.start() < first.start()
                            || next.start() == first.start() && next.end() < first.end()) {
                        first = next;
                        selected = index;
                    }
                }
            }
            if (selected < 0) {
                return List.copyOf(merged);
            }
            merged.add(first);
            positions[selected]++;
        }
    }

    /**
     * Unions independent readings without consuming brackets. Only an adjacent plain label gives
     * its following bracket an unambiguous key role; nested, opaque and block readings keep shortcuts.
     */
    private static void collectMemberLabels(
            String content, Map<String, MemberTargets> memberDefinitions, Set<Integer> validDefinitionStarts,
            List<MemberLabelSpan> labels, int[] labelEnds) {
        Set<Integer> referenceKeys = new HashSet<>();
        for (int index = 0; index < content.length(); index++) {
            int end = labelEnds[index];
            if (end < 0 || !memberLabelWithinLimit(content, index + 1, end)) {
                continue;
            }
            String label = content.substring(index + 1, end);
            int next = skipWhitespace(content, end + 1);
            if (next < content.length() && content.charAt(next) == '(') {
                int destinationStart = skipWhitespace(content, next + 1);
                int destinationEnd = destinationStart;
                while (destinationEnd < content.length()
                        && !Character.isWhitespace(content.charAt(destinationEnd))
                        && "()[]".indexOf(content.charAt(destinationEnd)) < 0) {
                    destinationEnd++;
                }
                int start = index + 1;
                memberReferenceId(content.substring(destinationStart, destinationEnd), false)
                        .ifPresent(id -> labels.add(new MemberLabelSpan(start, end, new MemberTargets(id))));
            }
            if (next == end + 1 && next < content.length() && content.charAt(next) == ':'
                    && validDefinitionStarts.contains(index)) {
                continue;
            }
            if (!referenceKeys.contains(index)) {
                for (MemberTargets targets : memberDefinitionTargets(memberDefinitions, label)) {
                    labels.add(new MemberLabelSpan(index + 1, end, targets));
                }
            }
            if (next == end + 1 && next < content.length()
                    && content.charAt(next) == '[' && labelEnds[next] >= 0
                    && memberLabelWithinLimit(content, next + 1, labelEnds[next])) {
                String explicit = content.substring(next + 1, labelEnds[next]);
                String key = explicit.isBlank() ? label : explicit;
                Set<MemberTargets> targets = memberDefinitionTargets(memberDefinitions, key);
                if (!targets.isEmpty()) {
                    for (MemberTargets target : targets) {
                        labels.add(new MemberLabelSpan(index + 1, end, target));
                    }
                    if (next == end + 1 && label.chars().noneMatch(value -> "[<`\r\n".indexOf(value) >= 0)) {
                        referenceKeys.add(next);
                    }
                }
            }
        }
    }

    /** Validates source syntax only for key suppression; member discovery never depends on title content. */
    private static int referenceDefinitionEnd(String content, int offset) {
        int start = skipDefinitionSpaces(content, offset);
        if (isDefinitionLineEnd(content, start) && start < content.length()) {
            start = skipDefinitionSpaces(content, memberLineContentStart(content, afterDefinitionLineEnd(content, start)));
        }
        int end = referenceDefinitionDestinationEnd(content, start);
        if (end < 0) {
            return -1;
        }
        int title = skipDefinitionSpaces(content, end);
        if (isDefinitionLineEnd(content, title)) {
            return title;
        }
        if (title == end) {
            return -1;
        }
        char opening = content.charAt(title);
        char closing = switch (opening) {
            case '\'', '"' -> opening;
            case '(' -> ')';
            default -> 0;
        };
        if (closing == 0) {
            return -1;
        }
        for (int index = title + 1; index < content.length(); index++) {
            char value = content.charAt(index);
            if (isReferenceEscape(content, index)) {
                index++;
            } else if (value == closing) {
                int lineEnd = skipDefinitionSpaces(content, index + 1);
                return isDefinitionLineEnd(content, lineEnd) ? lineEnd : -1;
            } else if (opening == '(' && value == '(') {
                return -1;
            } else if (isDefinitionLineEnd(content, index)) {
                int next = skipDefinitionSpaces(content, memberLineContentStart(content, afterDefinitionLineEnd(content, index)));
                if (isDefinitionLineEnd(content, next)) {
                    return -1;
                }
                index = next - 1;
            }
        }
        return -1;
    }

    private static int referenceDefinitionDestinationEnd(String content, int start) {
        if (isDefinitionLineEnd(content, start)) {
            return -1;
        }
        boolean angle = content.charAt(start) == '<';
        int depth = 0;
        int index = start + (angle ? 1 : 0);
        for (; index < content.length(); index++) {
            char value = content.charAt(index);
            if (isReferenceEscape(content, index)) {
                index++;
            } else if (angle) {
                if (value == '>') {
                    return index + 1;
                }
                if (value == '<' || isDefinitionLineEnd(content, index)) {
                    return -1;
                }
            } else if (value <= ' ' || value == 127) {
                break;
            } else if (value == '(') {
                depth++;
            } else if (value == ')' && --depth < 0) {
                return -1;
            }
        }
        return angle || depth != 0 || index == start ? -1 : index;
    }

    private static boolean isReferenceEscape(String content, int index) {
        if (content.charAt(index) != '\\' || index + 1 >= content.length()) {
            return false;
        }
        char value = content.charAt(index + 1);
        return value >= '!' && value <= '/' || value >= ':' && value <= '@'
                || value >= '[' && value <= '`' || value >= '{' && value <= '~';
    }

    private static int skipDefinitionSpaces(String content, int index) {
        while (index < content.length() && (content.charAt(index) == ' ' || content.charAt(index) == '\t')) {
            index++;
        }
        return index;
    }

    private static boolean isDefinitionLineEnd(String content, int index) {
        return index == content.length() || content.charAt(index) == '\r' || content.charAt(index) == '\n';
    }

    private static int afterDefinitionLineEnd(String content, int index) {
        if (content.charAt(index++) == '\r' && index < content.length() && content.charAt(index) == '\n') {
            index++;
        }
        return index;
    }

    private static Optional<Integer> memberReferenceId(String source, boolean complete) {
        String value = complete ? normalizeReferenceSyntax(memberDefinitionDestination(source)).strip()
                : normalizeReferenceSyntax(source).stripLeading();
        Matcher destination = (complete ? MEMBER_DEFINITION_DESTINATION : MEMBER_REFERENCE_DESTINATION).matcher(value);
        return (complete ? destination.matches() : destination.lookingAt())
                ? parseReferenceTarget(TYPE_USER, destination.group(1)).map(ReferenceTarget::id)
                : Optional.empty();
    }

    /** Extracts the original destination so authored title content cannot suppress member detection. */
    private static String memberDefinitionDestination(String source) {
        return memberDefinitionDestination(source, 0);
    }

    /** Accepts container and continuation layouts conservatively without interpreting title text. */
    private static String memberDefinitionDestination(String source, int offset) {
        int start = offset;
        while (start < source.length()) {
            start = skipWhitespace(source, start);
            if (start == source.length()) {
                return "";
            }
            if (source.charAt(start) == '>') {
                start++;
                continue;
            }
            int markerEnd = start;
            while (markerEnd < source.length() && Character.isDigit(source.charAt(markerEnd))) {
                markerEnd++;
            }
            boolean bullet = "-+*".indexOf(source.charAt(start)) >= 0;
            boolean ordered = markerEnd > start && markerEnd < source.length()
                    && ".)".indexOf(source.charAt(markerEnd)) >= 0;
            int end = bullet ? start + 1 : ordered ? markerEnd + 1 : start;
            if (end > start && end < source.length() && Character.isWhitespace(source.charAt(end))) {
                start = end;
                continue;
            }
            break;
        }
        if (start == source.length()) {
            return "";
        }
        int end = start;
        if (source.charAt(start) == '<') {
            int closing = source.indexOf('>', start + 1);
            if (closing < 0) {
                return "";
            }
            end = closing + 1;
        } else {
            while (end < source.length() && !Character.isWhitespace(source.charAt(end))) {
                end++;
            }
        }
        return source.substring(start, end);
    }

    private static Set<MemberTargets> memberDefinitionTargets(Map<String, MemberTargets> definitions, String label) {
        var targets = new java.util.LinkedHashSet<MemberTargets>();
        memberReferenceKeys(label).forEach(key -> {
            MemberTargets target = definitions.get(key);
            if (target != null) {
                targets.add(target);
            }
        });
        return targets;
    }

    /** Container-stripped keys supplement literal readings rather than discarding authored text. */
    private static Set<String> memberReferenceKeys(String label) {
        Set<String> keys = new HashSet<>();
        for (String reading : List.of(label, normalizeReferenceSyntax(label))) {
            keys.add(referenceLabelKey(reading));
            keys.add(referenceLabelKey(MEMBER_CONTINUATION_PREFIX.matcher(reading).replaceAll("")));
        }
        return keys;
    }

    /** Shares targets by definition key without multiplying spans or comparing whole id sets. */
    private static final class MemberTargets {
        private final Set<Integer> ids = new java.util.LinkedHashSet<>();

        private MemberTargets() {
        }

        private MemberTargets(int memberId) {
            ids.add(memberId);
        }
    }

    private record MemberLabelSpan(int start, int end, MemberTargets targets) {
    }

    private static String referenceLabelKey(String label) {
        return label.replaceAll("(?U)\\s+", " ").strip()
                .toLowerCase(java.util.Locale.ROOT).toUpperCase(java.util.Locale.ROOT);
    }

    /** Only a logical line opening makes a colon a definition delimiter rather than prose. */
    private static Set<Integer> memberDefinitionLineStarts(String content) {
        Set<Integer> starts = new HashSet<>();
        for (int index = 0; index < content.length(); index++) {
            if (index == 0 || content.charAt(index - 1) == '\n' || content.charAt(index - 1) == '\r') {
                starts.add(memberLineContentStart(content, index));
            }
        }
        return starts;
    }

    /** Definitions and code-span pairing share the same paragraph interruption decisions. */
    private static Set<Integer> memberDefinitionStartsOutsideParagraphs(
            String content, Map<Integer, Integer> definitionEnds) {
        Set<Integer> starts = new HashSet<>();
        var paragraphs = new MemberParagraphState();
        int definitionEnd = -1;
        for (int lineStart = 0; lineStart < content.length();) {
            int lineEnd = memberLineEnd(content, lineStart);
            if (lineStart > definitionEnd) {
                MemberParagraphLine line = paragraphs.readLine(content, lineStart, lineEnd);
                Integer end = definitionEnds.get(line.bodyStart());
                if (end != null && line.definitionAllowed()) {
                    starts.add(line.bodyStart());
                    definitionEnd = end;
                    paragraphs.paragraphOpen = false;
                }
            }
            lineStart = lineEnd == content.length() ? lineEnd : afterDefinitionLineEnd(content, lineEnd);
        }
        return starts;
    }

    private static int memberLineEnd(String content, int start) {
        int end = start;
        while (end < content.length() && !isDefinitionLineEnd(content, end)) {
            end++;
        }
        return end;
    }

    private record MemberParagraphLine(int bodyStart, boolean definitionAllowed, boolean blockStart) {
    }

    private record MemberListContainer(int quoteDepth, char delimiter, int contentIndent) {
    }

    private record MemberListMarker(int end, int number, char delimiter) {
    }

    /**
     * Classifies paragraph interruption using CommonMark 0.31.2
     * <a href="https://spec.commonmark.org/0.31.2/#thematic-breaks">§§4.1–4.9</a> and
     * <a href="https://spec.commonmark.org/0.31.2/#block-quotes">§§5.1–5.2</a>.
     * Blank lines, ATX headings, fences, thematic breaks, block quotes, setext underlines and
     * HTML blocks of types 1–6 end a paragraph. A new list item must be nonempty and an ordered
     * item must start at 1; existing list siblings may start another item. Indented code and
     * HTML type 7 cannot interrupt. Unrecognized and lazy container continuations retain their
     * paragraph reading, so ambiguity cannot suppress a member shortcut. Container bodies can
     * immediately open another paragraph rather than leaving subsequent lazy text outside one.
     * <a href="https://spec.commonmark.org/0.31.2/#tabs">§2.2 tab expansion</a> retains indentation
     * columns not consumed by a container's single optional whitespace column.
     */
    private static final class MemberParagraphState {
        private final Map<Integer, MemberListContainer> lists = new HashMap<>();
        private final Deque<Integer> listIndents = new ArrayDeque<>();
        private boolean paragraphOpen;
        private int activeQuoteDepth;

        private MemberParagraphLine readLine(String content, int start, int end) {
            int body = skipMemberLineSpaces(content, start, end);
            int indent = memberColumn(content, start, body);
            int quoteDepth = 0;
            int parentIndent = listContentIndent(indent, quoteDepth);
            boolean newContainer = false;
            boolean boundary = false;
            MemberLineRule rule = memberLineRule(content, start, end);
            while (body < end && indent - parentIndent <= 3) {
                if (content.charAt(body) == '>') {
                    quoteDepth++;
                    if (quoteDepth > activeQuoteDepth) {
                        paragraphOpen = false;
                        newContainer = true;
                        discardListsFrom(indent);
                    }
                    int afterQuote = body + 1;
                    if (afterQuote < end && (content.charAt(afterQuote) == ' ' || content.charAt(afterQuote) == '\t')) {
                        afterQuote++;
                    }
                    int quoteIndent = indent + afterQuote - body;
                    int afterQuoteColumn = memberColumn(content, body + 1, afterQuote, indent + 1);
                    body = skipMemberLineSpaces(content, afterQuote, end);
                    indent = memberColumn(content, afterQuote, body, afterQuoteColumn);
                    parentIndent = Math.max(quoteIndent, listContentIndent(indent, quoteDepth));
                    continue;
                }
                if (rule.interruptsAt(body, paragraphOpen)
                        || MEMBER_CODE_BLOCK_START.matcher(content).region(body, end).lookingAt()) {
                    boundary = true;
                    break;
                }
                MemberListMarker marker = memberListMarker(content, body, end);
                if (marker == null) {
                    break;
                }
                int nonspace = skipMemberLineSpaces(content, marker.end(), end);
                var sibling = lists.get(indent);
                boolean sameList = sibling != null && sibling.quoteDepth() == quoteDepth
                        && sibling.delimiter() == marker.delimiter();
                if (paragraphOpen && !sameList && (nonspace == end
                        || ".)".indexOf(marker.delimiter()) >= 0 && marker.number() != 1)) {
                    break;
                }
                int markerColumn = indent + marker.end() - body;
                int nonspaceColumn = memberColumn(content, marker.end(), nonspace, markerColumn);
                int contentIndent = nonspaceColumn - markerColumn > 4
                        ? memberColumn(content, marker.end(), marker.end() + 1, markerColumn) : nonspaceColumn;
                discardListsFrom(indent);
                lists.put(indent, new MemberListContainer(quoteDepth, marker.delimiter(), contentIndent));
                listIndents.addLast(indent);
                parentIndent = contentIndent;
                paragraphOpen = false;
                newContainer = true;
                body = nonspace;
                indent = nonspaceColumn;
            }
            boolean definitionAllowed = !paragraphOpen && indent - parentIndent <= 3;
            boolean blank = body == end;
            boolean indentedCode = !paragraphOpen && indent - parentIndent >= 4;
            if (blank || boundary || !paragraphOpen) {
                while (!listIndents.isEmpty()) {
                    var container = lists.get(listIndents.getLast());
                    if (container.quoteDepth() <= quoteDepth && (blank || container.contentIndent() <= indent)) {
                        break;
                    }
                    lists.remove(listIndents.removeLast());
                }
            }
            paragraphOpen = !blank && !boundary && !indentedCode;
            activeQuoteDepth = blank || boundary || newContainer ? quoteDepth : Math.max(activeQuoteDepth, quoteDepth);
            return new MemberParagraphLine(body, definitionAllowed, blank || boundary || newContainer || indentedCode);
        }

        private int listContentIndent(int indent, int quoteDepth) {
            if (listIndents.isEmpty()) {
                return 0;
            }
            for (int candidate = Math.min(indent, listIndents.getLast()); candidate >= 0; candidate--) {
                var container = lists.get(candidate);
                if (container != null && container.contentIndent() <= indent
                        && (paragraphOpen || container.quoteDepth() <= quoteDepth)) {
                    return container.contentIndent();
                }
            }
            return 0;
        }

        private void discardListsFrom(int indent) {
            while (!listIndents.isEmpty() && listIndents.getLast() >= indent) {
                lists.remove(listIndents.removeLast());
            }
        }
    }

    private static int skipMemberLineSpaces(String content, int start, int end) {
        int cursor = start;
        while (cursor < end && (content.charAt(cursor) == ' ' || content.charAt(cursor) == '\t')) {
            cursor++;
        }
        return cursor;
    }

    /** Parses only the marker; paragraph state decides whether it can open an item. */
    private static MemberListMarker memberListMarker(String content, int start, int end) {
        if (start == end) {
            return null;
        }
        char first = content.charAt(start);
        int cursor = start;
        int number = 0;
        if ("-+*".indexOf(first) >= 0) {
            cursor++;
        } else {
            while (cursor < end && cursor - start < 9
                    && content.charAt(cursor) >= '0' && content.charAt(cursor) <= '9') {
                number = number * 10 + content.charAt(cursor++) - '0';
            }
            if (cursor == start || cursor == end || ".)".indexOf(content.charAt(cursor)) < 0) {
                return null;
            }
            cursor++;
        }
        if (cursor < end && content.charAt(cursor) != ' ' && content.charAt(cursor) != '\t') {
            return null;
        }
        return new MemberListMarker(cursor, number, content.charAt(cursor - 1));
    }

    private static int memberColumn(String content, int start, int end) {
        return memberColumn(content, start, end, 0);
    }

    private static int memberColumn(String content, int start, int end, int initialColumn) {
        int column = initialColumn;
        for (int index = start; index < end; index++) {
            column += content.charAt(index) == '\t' ? 4 - column % 4 : 1;
        }
        return column;
    }

    /** Prepares suffix coverage once so nested list markers cannot rescan the same thematic run. */
    private static MemberLineRule memberLineRule(String content, int start, int end) {
        int last = end - 1;
        while (last >= start && (content.charAt(last) == ' ' || content.charAt(last) == '\t')) {
            last--;
        }
        if (last < start || "=-*_".indexOf(content.charAt(last)) < 0) {
            return new MemberLineRule(end, -1, end, false);
        }
        char marker = content.charAt(last);
        int runStart = last;
        while (runStart > start && content.charAt(runStart - 1) == marker) {
            runStart--;
        }
        int cursor = last;
        int count = 0;
        int thirdLast = -1;
        while (cursor >= start) {
            char value = content.charAt(cursor);
            if (value == marker) {
                if (++count == 3) {
                    thirdLast = cursor;
                }
            } else if (value != ' ' && value != '\t') {
                break;
            }
            cursor--;
        }
        return new MemberLineRule(cursor + 1, marker == '=' ? -1 : thirdLast, runStart, marker == '=' || marker == '-');
    }

    private record MemberLineRule(int suffixStart, int thematicEnd, int setextStart, boolean setext) {
        private boolean interruptsAt(int start, boolean paragraphOpen) {
            return start >= suffixStart && (start <= thematicEnd || paragraphOpen && setext && start >= setextStart);
        }
    }

    private static int memberLineContentStart(String content, int lineStart) {
        Matcher prefix = MEMBER_LINE_CONTENT.matcher(content).region(lineStart, content.length());
        return prefix.lookingAt() ? prefix.end() : lineStart;
    }

    private static int[] matchingLabelEnds(String content) {
        return matchingLabelEnds(content, true);
    }

    private static int[] matchingLabelEnds(String content, boolean skipCodeSpans) {
        return matchingLabelEnds(content, skipCodeSpans, false);
    }

    private static int[] matchingLabelEnds(String content, boolean skipCodeSpans, boolean skipOpaqueInlines) {
        return matchingLabelEnds(content, skipCodeSpans, skipOpaqueInlines, false);
    }

    private static int[] matchingLabelEnds(
            String content, boolean skipCodeSpans, boolean skipOpaqueInlines, boolean boundCodeSpans) {
        return matchingLabelEnds(content, skipCodeSpans, skipOpaqueInlines, boundCodeSpans,
                new ReferenceDelimiters(content, boundCodeSpans));
    }

    private static int[] matchingLabelEnds(
            String content, boolean skipCodeSpans, boolean skipOpaqueInlines, boolean boundCodeSpans,
            ReferenceDelimiters delimiters) {
        int[] labelEnds = new int[content.length()];
        Arrays.fill(labelEnds, -1);
        Matcher opaque = MEMBER_OPAQUE_INLINE.matcher(content);
        Map<String, Integer> terminators = new HashMap<>();
        Deque<Integer> labelStarts = new ArrayDeque<>();
        for (int index = 0; index < content.length(); index++) {
            char value = content.charAt(index);
            int next = delimiters.next(index, skipCodeSpans, boundCodeSpans);
            if (next > index + 1) {
                index = next - 1;
                continue;
            }
            if (skipOpaqueInlines && value == '<') {
                int opaqueEnd = memberOpaqueInlineEnd(content, index, opaque, terminators);
                if (opaqueEnd > index) {
                    index = opaqueEnd - 1;
                    continue;
                }
            }
            if (value == '[') {
                labelStarts.push(index);
            } else if (value == ']' && !labelStarts.isEmpty()) {
                labelEnds[labelStarts.pop()] = index;
            }
        }
        return labelEnds;
    }

    /** Recognizes opaque inline grammar without letting a missing terminator hide later labels. */
    private static int memberOpaqueInlineEnd(
            String content, int start, Matcher opaque, Map<String, Integer> terminators) {
        if (content.startsWith("<!-->", start)) {
            return start + 5;
        }
        if (content.startsWith("<!--->", start)) {
            return start + 6;
        }
        String closing = null;
        int openingLength = 0;
        if (content.startsWith("<!--", start)) {
            closing = "-->";
            openingLength = 4;
        } else if (content.startsWith("<?", start)) {
            closing = "?>";
            openingLength = 2;
        } else if (content.startsWith("<![CDATA[", start)) {
            closing = "]]>";
            openingLength = 9;
        } else if (content.startsWith("<!", start) && start + 2 < content.length()
                && (content.charAt(start + 2) >= 'A' && content.charAt(start + 2) <= 'Z'
                    || content.charAt(start + 2) >= 'a' && content.charAt(start + 2) <= 'z')) {
            closing = ">";
            openingLength = 2;
        }
        if (closing != null) {
            int from = start + openingLength;
            Integer end = terminators.get(closing);
            if (end == null || end >= 0 && end < from) {
                end = content.indexOf(closing, from);
                terminators.put(closing, end);
            }
            return end < 0 ? -1 : end + closing.length();
        }
        return opaque.region(start, content.length()).lookingAt() ? opaque.end() : -1;
    }

    /**
     * CommonMark 0.31.2 <a href="https://spec.commonmark.org/0.31.2/#backslash-escapes">§2.4</a>
     * and <a href="https://spec.commonmark.org/0.31.2/#code-spans">§6.1</a> sequential units.
     * A single backward table finds the next raw, maximal backtick run of each exact length.
     * Every suffix can be an opener after an escaped first tick, but only whole raw runs can
     * close code. The forward transition consumes escapes only outside code and jumps over the
     * entire literal span, so its internal backslashes never affect closing delimiters. Unmatched
     * runs stay literal. Canonical local entries and structural scans reuse these same units;
     * the no-code reading relaxes only code opacity. No opener rescans for a matching closer.
     */
    private static final class ReferenceDelimiters {
        private final String content;
        private final int[] runEnds;
        private final int[] codeEnds;
        private final int[] blockEnds;
        private final boolean[] escaped;

        private ReferenceDelimiters(String content, boolean boundCodeSpans) {
            this.content = content;
            runEnds = new int[content.length()];
            codeEnds = new int[content.length()];
            Arrays.fill(codeEnds, -1);
            Map<Integer, Integer> nextRunByLength = new HashMap<>();
            for (int end = content.length(); end > 0;) {
                if (content.charAt(end - 1) != '`') {
                    end--;
                    continue;
                }
                int start = end - 1;
                while (start > 0 && content.charAt(start - 1) == '`') {
                    start--;
                }
                for (int index = start; index < end; index++) {
                    int length = end - index;
                    int closing = nextRunByLength.getOrDefault(length, -1);
                    runEnds[index] = end;
                    codeEnds[index] = closing < 0 ? -1 : closing + length;
                }
                nextRunByLength.put(end - start, start);
                end = start;
            }
            blockEnds = new int[content.length()];
            boolean[] blockStarts = boundCodeSpans ? codeSpanBlockStarts(content) : new boolean[content.length()];
            int boundary = content.length();
            for (int index = content.length() - 1; index >= 0; index--) {
                blockEnds[index] = boundary;
                if (blockStarts[index]) {
                    boundary = index;
                }
            }
            escaped = new boolean[content.length()];
            for (int index = 0; index < content.length();) {
                int next = next(index, false, false);
                if (next > index + 1) {
                    escaped[index + 1] = true;
                }
                index = next;
            }
        }

        private boolean[] escaped() {
            return escaped;
        }

        /** Returns the next outside-code position; matched code content is consumed literally. */
        private int next(int index, boolean codeSpans, boolean boundCodeSpans) {
            if (isReferenceEscape(content, index)) {
                return index + 2;
            }
            if (codeSpans && content.charAt(index) == '`') {
                int end = codeEnds[index];
                return end >= 0 && (!boundCodeSpans || end <= blockEnds[index]) ? end : runEnds[index];
            }
            return index + 1;
        }
    }

    /** Backticks use the same paragraph and container boundaries as definition suppression. */
    private static boolean[] codeSpanBlockStarts(String content) {
        boolean[] starts = new boolean[content.length()];
        var paragraphs = new MemberParagraphState();
        for (int lineStart = 0; lineStart < content.length();) {
            int lineEnd = memberLineEnd(content, lineStart);
            starts[lineStart] = paragraphs.readLine(content, lineStart, lineEnd).blockStart();
            lineStart = lineEnd == content.length() ? lineEnd : afterDefinitionLineEnd(content, lineEnd);
        }
        return starts;
    }

    /** Scans pure setext underlines and same-marker thematic breaks once, without regex backtracking. */
    static boolean memberRuleLine(CharSequence content, int start, int end) {
        if (start == end) {
            return false;
        }
        char marker = content.charAt(start);
        if ("=-*_".indexOf(marker) < 0) {
            return false;
        }
        int count = 0;
        boolean separated = false;
        boolean setext = marker == '=' || marker == '-';
        for (int index = start; index < end; index++) {
            char value = content.charAt(index);
            if (value == marker) {
                count++;
                if (separated) {
                    setext = false;
                }
            } else if (value == ' ' || value == '\t') {
                separated = true;
            } else {
                return false;
            }
        }
        return setext || marker != '=' && count >= 3;
    }

    private static boolean isEscaped(String content, int index) {
        int backslashes = 0;
        for (int cursor = index - 1; cursor >= 0 && content.charAt(cursor) == '\\'; cursor--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static int skipWhitespace(String content, int index) {
        int cursor = index;
        while (cursor < content.length() && Character.isWhitespace(content.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private Set<ReferenceTarget> visibleTargets(
            int workspaceId, Set<ReferenceTarget> targets, int currentUserId) {
        if (targets.isEmpty()) {
            return Set.of();
        }
        Map<String, List<Integer>> idsByType = new LinkedHashMap<>();
        for (ReferenceTarget target : targets) {
            String type = canonicalReferenceType(target.type());
            if (type != null && target.id() > 0) {
                idsByType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(target.id());
            }
        }
        Set<ReferenceTarget> visible = new HashSet<>();
        idsByType.forEach((type, ids) -> {
            for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
                List<Integer> batch = ids.subList(start, Math.min(start + BATCH_SIZE, ids.size()));
                for (int id : visibleIds(workspaceId, type, batch, currentUserId)) {
                    visible.add(new ReferenceTarget(type, id));
                }
            }
        });
        return visible;
    }

    private List<Integer> visibleIds(
            int workspaceId, String type, List<Integer> ids, int currentUserId) {
        return switch (type) {
            case TYPE_USER -> workspaceMapper.getMemberIdsIncludingPending(workspaceId, ids);
            case TYPE_PERSON -> personMapper.getVisibleIdsIn(workspaceId, ids);
            case TYPE_DEAL -> dealMapper.getVisibleIdsIn(workspaceId, ids);
            case TYPE_COMPANY -> companyMapper.getVisibleIdsIn(workspaceId, ids);
            case TYPE_NOTE -> noteMapper.getVisibleNoteIdsIn(workspaceId, ids, currentUserId);
            case TYPE_FILE -> attachmentMapper.getVisibleIdsIn(workspaceId, ids, currentUserId);
            case TYPE_TASK -> taskMapper.getVisibleIdsIn(workspaceId, ids);
            case TYPE_ACTIVITY -> activityMapper.getVisibleIdsIn(workspaceId, ids);
            default -> List.of();
        };
    }

    private static Optional<ReferenceTarget> parseReferenceTarget(String type, String value) {
        String canonicalType = canonicalReferenceType(type);
        if (canonicalType == null) {
            return Optional.empty();
        }
        try {
            int id = Integer.parseInt(value);
            return id > 0 ? Optional.of(new ReferenceTarget(canonicalType, id)) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static String canonicalReferenceType(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case TYPE_USER -> TYPE_USER;
            case TYPE_PERSON -> TYPE_PERSON;
            case TYPE_DEAL -> TYPE_DEAL;
            case TYPE_COMPANY -> TYPE_COMPANY;
            case TYPE_NOTE -> TYPE_NOTE;
            case TYPE_FILE -> TYPE_FILE;
            case TYPE_TASK -> TYPE_TASK;
            case TYPE_ACTIVITY -> TYPE_ACTIVITY;
            default -> null;
        };
    }

    private EntityReference build(
            int workspaceId, String sourceType, int sourceId, String type, int refId, String label) {
        EntityReference reference = new EntityReference();
        reference.setWorkspaceId(workspaceId);
        reference.setSourceType(sourceType);
        reference.setSourceId(sourceId);
        reference.setRefType(type);
        reference.setRefId(refId);
        reference.setLabel(label.length() > MAX_LABEL_LENGTH ? label.substring(0, MAX_LABEL_LENGTH) : label);
        return reference;
    }

    private Set<Integer> mentionedMemberIds(List<EntityReference> references) {
        Set<Integer> ids = new HashSet<>();
        for (EntityReference reference : references) {
            if (TYPE_USER.equals(reference.getRefType())) {
                ids.add(reference.getRefId());
            }
        }
        return ids;
    }

    /**
     * Renders prose content for plain-text contexts (e.g. notification snippets)
     * by replacing each {@code [Label](type:id)} token with {@code @Label}. Note
     * targets are masked to a neutral placeholder instead, since a snippet carries
     * no reader context: emitting a note's label here would leak a private note's
     * title to a mentioned member who cannot see it.
     *
     * @param content the raw content
     * @return the content with reference tokens flattened to their labels
     */
    public static String toPlainText(String content) {
        if (content == null) {
            return "";
        }
        String redacted = TOKEN.matcher(content).replaceAll(match ->
            TYPE_NOTE.equals(match.group(2))
                ? "a note"
                : "@" + Matcher.quoteReplacement(match.group(1)));
        MarkdownTargets targets = markdownTargets(redacted);
        boolean containsNote = targets.targets().stream()
            .anyMatch(target -> TYPE_NOTE.equals(target.type()));
        return containsNote || targets.unparseableTypes().contains(TYPE_NOTE) ? "a note" : redacted;
    }
}
