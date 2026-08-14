package ooo.klae.connex.backend.services;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.EntityReferenceMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;

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
    private static final int BATCH_SIZE = 500;
    private static final String REFERENCE_SEPARATOR = "(?::|&colon;|\\\\:)";
    private static final Pattern TOKEN = Pattern.compile(
        "\\[([^\\]]+)\\]\\((user|person|deal|company|note|file|task|activity)"
            + REFERENCE_SEPARATOR + "(\\d+)\\)");
    private static final Pattern NOTE_TOKEN = Pattern.compile(
        "\\[([^\\]]+)\\]\\(note" + REFERENCE_SEPARATOR + "(\\d+)\\)");
    /**
     * Matches a Markdown link-reference definition pointing at a note. Every repetition is
     * possessive so an untrusted note body cannot drive backtracking, and each is unambiguous in
     * context, so refusing to give characters back does not change what the pattern matches: no
     * label alternative can consume a bare {@code ]}, and no indent run can be followed by a
     * space or tab that the next required token would accept. The trailing description is
     * {@code [ \t].*} rather than {@code [ \t]+.*} because {@code .} already covers further
     * spaces and tabs.
     */
    private static final Pattern NOTE_REFERENCE_DEFINITION = Pattern.compile(
        "(?im)^[ \\t]{0,3}+\\[(?:\\\\.|[^\\]\\\\])++\\]:[ \\t]*+"
            + "(?:\\r?\\n[ \\t]++)?<?note:(\\d+)>?(?:[ \\t].*)?$");

    /**
     * Reader-scoped prose and structured references after private-note targets are removed.
     * @param content reader-safe prose
     * @param references reader-visible structured references
     */
    public record ReaderVisibleContent(String content, List<EntityReference> references) {
        public ReaderVisibleContent {
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

    private record MarkdownNoteTargets(List<Integer> ids, boolean hasUnparseableId) {
        private boolean isEmpty() {
            return ids.isEmpty() && !hasUnparseableId;
        }
    }

    /**
     * Re-derives and persists a source entity's @/# references from its content,
     * replacing any previous set. Every valid member reference is stored (so the
     * mention chip renders regardless of who edits the source); returns the IDs of
     * members referenced for the first time (present now but not before this
     * call), so the caller can notify only newly-added mentions. Excluding the
     * acting author from notification is the caller's responsibility. Scoped to
     * {@code workspaceId}.
     *
     * @param workspaceId the owning workspace
     * @param sourceType  the entity type the references belong to ({@code note}, {@code task}, {@code comment})
     * @param sourceId    the entity whose references are being synced
     * @param content     the entity's current prose content
     * @return the user IDs newly referenced by this sync
     */
    @Transactional
    public List<Integer> syncReferences(int workspaceId, String sourceType, int sourceId, String content) {
        Set<Integer> before = mentionedMemberIds(entityReferenceMapper.findBySource(workspaceId, sourceType, sourceId));
        int currentUserId = workspaceService.getCurrentUserId();
        List<EntityReference> resolved = resolve(workspaceId, sourceType, sourceId, content, currentUserId);

        entityReferenceMapper.deleteBySource(workspaceId, sourceType, sourceId);
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
        redactInvisibleNoteReferences(workspaceId, notes);
        return notes;
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
        redactInvisibleNoteTargets(workspaceId, tasks,
            Task::getReferences, Task::setReferences, Task::getDescription, Task::setDescription);
        return tasks;
    }

    /**
     * Attaches reader-visible references to deals and masks private-note targets in their close reasons.
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
        List<ReaderVisibleContent> visible = redactInvisibleNoteTargets(
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
        redactInvisibleNoteTargets(workspaceId, activities,
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
     * Removes private-note targets from prose and structured references for the current reader.
     * The input list is resolved in one visibility query and the returned list preserves its order.
     * @param workspaceId the owning workspace
     * @param items prose/reference pairs to scope
     * @return reader-visible pairs in input order
     */
    public List<ReaderVisibleContent> redactInvisibleNoteTargets(
            int workspaceId, List<ReaderVisibleContent> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Set<Integer> targetNoteIds = new HashSet<>();
        boolean containsNoteTarget = false;
        for (ReaderVisibleContent item : items) {
            for (EntityReference reference : item.references()) {
                if (TYPE_NOTE.equals(reference.getRefType())) {
                    containsNoteTarget = true;
                    targetNoteIds.add(reference.getRefId());
                }
            }
            MarkdownNoteTargets markdownTargets = markdownNoteTargets(item.content());
            if (!markdownTargets.isEmpty()) {
                containsNoteTarget = true;
                targetNoteIds.addAll(markdownTargets.ids());
            }
        }
        if (!containsNoteTarget) {
            return List.copyOf(items);
        }
        int currentUserId = workspaceService.getCurrentUserId();
        Set<Integer> visible = visibleNoteIds(workspaceId, targetNoteIds, currentUserId);
        return items.stream()
            .map(item -> new ReaderVisibleContent(
                redactNoteTokens(item.content(), visible),
                item.references().stream()
                    .filter(reference -> !TYPE_NOTE.equals(reference.getRefType())
                        || visible.contains(reference.getRefId()))
                    .toList()))
            .toList();
    }

    private List<EntityReference> resolve(
            int workspaceId, String sourceType, int sourceId, String content, int currentUserId) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Map<String, EntityReference> unique = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(content);
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
            case TYPE_USER -> workspaceService.isMemberIncludingPending(workspaceId, refId);
            case TYPE_PERSON -> personMapper.exists(workspaceId, refId);
            case TYPE_DEAL -> dealMapper.exists(workspaceId, refId);
            case TYPE_COMPANY -> companyMapper.exists(workspaceId, refId);
            case TYPE_NOTE -> noteMapper.getVisibleNoteById(workspaceId, refId, currentUserId) != null;
            case TYPE_FILE -> attachmentMapper.exists(workspaceId, refId);
            case TYPE_TASK -> taskMapper.exists(workspaceId, refId);
            case TYPE_ACTIVITY -> activityMapper.exists(workspaceId, refId);
            default -> false;
        };
    }

    /**
     * Removes note-type reference targets (and masks their content tokens) that
     * the current reader cannot see, so a private note's label/existence never
     * leaks through a more-visible source note's stored references or content.
     */
    private void redactInvisibleNoteReferences(int workspaceId, List<Note> notes) {
        redactInvisibleNoteTargets(workspaceId, notes,
            Note::getReferences, Note::setReferences, Note::getContent, Note::setContent);
    }

    /**
     * Generic note-target redaction shared by every source type (note, task,
     * activity). For each source item it collects the note ids referenced by its
     * stored references and inline {@code note:} tokens, resolves which of those
     * notes the current reader may see, then drops the invisible references and
     * masks their content tokens to {@code (private note)}. A no-op (and no query)
     * when no note is referenced. Accessors are passed functionally so the same
     * logic covers each bean's differing reference/content fields.
     *
     * @param workspaceId   the owning workspace
     * @param items         the source items to redact in place
     * @param getReferences reads an item's resolved references
     * @param setReferences writes an item's filtered references
     * @param getContent    reads an item's stored content
     * @param setContent    writes an item's masked content
     * @param <T>           the source bean type
     */
    private <T> void redactInvisibleNoteTargets(
            int workspaceId,
            List<T> items,
            Function<T, List<EntityReference>> getReferences,
            BiConsumer<T, List<EntityReference>> setReferences,
            Function<T, String> getContent,
            BiConsumer<T, String> setContent) {
        List<ReaderVisibleContent> redacted = redactInvisibleNoteTargets(workspaceId, items.stream()
            .map(item -> new ReaderVisibleContent(getContent.apply(item), getReferences.apply(item)))
            .toList());
        for (int index = 0; index < items.size(); index++) {
            T item = items.get(index);
            ReaderVisibleContent visible = redacted.get(index);
            setReferences.accept(item, visible.references());
            setContent.accept(item, visible.content());
        }
    }

    private static String redactNoteTokens(String content, Set<Integer> visibleNoteIds) {
        if (content == null) {
            return null;
        }
        String redacted = NOTE_TOKEN.matcher(content).replaceAll(match ->
            parseNoteId(match.group(2))
                .filter(visibleNoteIds::contains)
                .map(ignored -> Matcher.quoteReplacement(match.group()))
                .orElse("(private note)"));
        MarkdownNoteTargets targets = markdownNoteTargets(redacted);
        if (targets.hasUnparseableId()
                || targets.ids().stream().anyMatch(id -> !visibleNoteIds.contains(id))) {
            return "(private note)";
        }
        return redacted;
    }

    private static MarkdownNoteTargets markdownNoteTargets(String content) {
        if (content == null || content.isBlank()) {
            return new MarkdownNoteTargets(List.of(), false);
        }
        String normalized = HtmlUtils.htmlUnescape(content.replace("&colon;", ":")).replace("\\:", ":");
        List<Integer> ids = new ArrayList<>();
        boolean hasUnparseableLink = collectInlineNoteTargetIds(normalized, ids);
        boolean hasUnparseableDefinition = collectNoteTargetIds(
            NOTE_REFERENCE_DEFINITION.matcher(normalized), ids);
        return new MarkdownNoteTargets(
            List.copyOf(ids), hasUnparseableLink || hasUnparseableDefinition);
    }

    private static boolean collectNoteTargetIds(Matcher matcher, List<Integer> ids) {
        boolean hasUnparseableId = false;
        while (matcher.find()) {
            Optional<Integer> noteId = parseNoteId(matcher.group(1));
            if (noteId.isPresent()) {
                ids.add(noteId.get());
            } else {
                hasUnparseableId = true;
            }
        }
        return hasUnparseableId;
    }

    private static boolean collectInlineNoteTargetIds(String content, List<Integer> ids) {
        boolean hasUnparseableId = false;
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
            if (!content.regionMatches(true, destinationStart, "note:", 0, 5)) {
                index = labelEnd;
                continue;
            }
            int idStart = destinationStart + 5;
            int idEnd = idStart;
            while (idEnd < content.length() && Character.isDigit(content.charAt(idEnd))) {
                idEnd++;
            }
            if (idEnd == idStart) {
                hasUnparseableId = true;
            } else {
                Optional<Integer> noteId = parseNoteId(content.substring(idStart, idEnd));
                if (noteId.isPresent()) {
                    ids.add(noteId.get());
                } else {
                    hasUnparseableId = true;
                }
            }
            index = labelEnd;
        }
        return hasUnparseableId;
    }

    private static int[] matchingLabelEnds(String content) {
        int[] labelEnds = new int[content.length()];
        Arrays.fill(labelEnds, -1);
        int[] closingBacktickRuns = closingBacktickRuns(content);
        Deque<Integer> labelStarts = new ArrayDeque<>();
        int precedingBackslashes = 0;
        for (int index = 0; index < content.length(); index++) {
            char value = content.charAt(index);
            if (value == '\\') {
                precedingBackslashes++;
                continue;
            }
            boolean escaped = precedingBackslashes % 2 == 1;
            precedingBackslashes = 0;
            if (escaped) {
                continue;
            }
            if (value == '`') {
                int delimiterLength = backtickRunLength(content, index);
                int closingDelimiter = closingBacktickRuns[index];
                if (closingDelimiter >= 0) {
                    index = closingDelimiter + delimiterLength - 1;
                } else {
                    index += delimiterLength - 1;
                }
            } else if (value == '[') {
                labelStarts.push(index);
            } else if (value == ']' && !labelStarts.isEmpty()) {
                labelEnds[labelStarts.pop()] = index;
            }
        }
        return labelEnds;
    }

    private static int backtickRunLength(String content, int start) {
        int end = start;
        while (end < content.length() && content.charAt(end) == '`') {
            end++;
        }
        return end - start;
    }

    private static int[] closingBacktickRuns(String content) {
        int[] closingRuns = new int[content.length()];
        Arrays.fill(closingRuns, -1);
        Map<Integer, Integer> nextRunByLength = new HashMap<>();
        for (int end = content.length() - 1; end >= 0;) {
            if (content.charAt(end) != '`') {
                end--;
                continue;
            }
            int start = end;
            while (start > 0 && content.charAt(start - 1) == '`') {
                start--;
            }
            int delimiterLength = end - start + 1;
            closingRuns[start] = nextRunByLength.getOrDefault(delimiterLength, -1);
            nextRunByLength.put(delimiterLength, start);
            end = start - 1;
        }
        return closingRuns;
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

    private Set<Integer> visibleNoteIds(int workspaceId, Set<Integer> targetNoteIds, int currentUserId) {
        if (targetNoteIds.isEmpty()) {
            return Set.of();
        }
        List<Integer> ids = new ArrayList<>(targetNoteIds);
        Set<Integer> visible = new HashSet<>();
        for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
            List<Integer> batch = ids.subList(start, Math.min(start + BATCH_SIZE, ids.size()));
            visible.addAll(noteMapper.getVisibleNoteIdsIn(workspaceId, batch, currentUserId));
        }
        return visible;
    }

    private static Optional<Integer> parseNoteId(String value) {
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
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
        return markdownNoteTargets(redacted).isEmpty() ? redacted : "a note";
    }
}
