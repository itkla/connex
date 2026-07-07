package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Activity;
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
 * {@code sourceId}): notes, tasks, activities, and introductions share this machinery.
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
    private static final int INVALID_NOTE_REFERENCE_ID = -1;
    private static final Pattern TOKEN =
        Pattern.compile("\\[([^\\]]+)\\]\\(\\s*<?(user|person|deal|company|note|file|task|activity):(\\d+)>?(?:\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?\\s*\\)");
    private static final Pattern NOTE_TOKEN = Pattern.compile("\\[([^\\]]+)\\]\\(\\s*<?note:(\\d+)>?(?:\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?\\s*\\)");
    private static final Pattern NOTE_REFERENCE_LINK = Pattern.compile("\\[([^\\]]+)\\]\\[([^\\]]*)\\]");
    private static final Pattern NOTE_SHORTCUT_REFERENCE_LINK = Pattern.compile("(?<!!)(?<!\\])\\[([^\\]]+)\\](?![\\[(])");
    private static final Pattern NOTE_REFERENCE_DEFINITION =
        Pattern.compile("(?m)^\\s{0,3}\\[([^\\]]+)\\]:[ \\t]*<?note:(\\d+)>?(?:[ \\t]+.*)?$");

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
     * @param sourceType  the entity type the references belong to ({@code note}, {@code task})
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

    /**
     * Removes every reference pointing AT a target entity, invoked when the
     * target itself is deleted so no dangling inbound chip is left behind,
     * including references stored in workspaces where the target was shared.
     *
     * @param workspaceId the workspace deleting the target
     * @param refType     the deleted target's type (e.g. {@code note}, {@code file})
     * @param refId       the deleted target's id
     */
    public void deleteReferencesTo(int workspaceId, String refType, int refId) {
        entityReferenceMapper.deleteByTarget(workspaceId, refType, refId);
    }

    public void deleteReferencesToInWorkspace(int workspaceId, String refType, int refId) {
        entityReferenceMapper.deleteByTargetInWorkspace(workspaceId, refType, refId);
    }

    /**
     * Whether the current reader may know that a reference target exists.
     * Note targets apply author/workspace visibility; other target types are
     * workspace-scoped today.
     *
     * @param workspaceId    the owning workspace
     * @param refType        the referenced entity type
     * @param refId          the referenced entity id
     * @param currentUserId  the reader id
     * @return true when the target is visible to the reader
     */
    public boolean isTargetVisible(int workspaceId, String refType, int refId, int currentUserId) {
        return isVisible(workspaceId, refType, refId, currentUserId);
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
        for (EntityReference reference : entityReferenceMapper.findBySources(workspaceId, sourceType, sourceIds)) {
            bySource.computeIfAbsent(reference.getSourceId(), key -> new ArrayList<>()).add(reference);
        }
        return bySource;
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
        Set<Integer> targetNoteIds = new HashSet<>();
        boolean sawNoteReference = false;
        for (T item : items) {
            List<EntityReference> references = getReferences.apply(item);
            if (references != null) {
                for (EntityReference reference : references) {
                    if (TYPE_NOTE.equals(reference.getRefType())) {
                        targetNoteIds.add(reference.getRefId());
                    }
                }
            }
            String content = getContent.apply(item);
            if (content != null) {
                sawNoteReference = sawNoteReference || hasNoteReference(content);
                targetNoteIds.addAll(noteTargetIds(content));
            }
        }
        if (targetNoteIds.isEmpty() && !sawNoteReference) {
            return;
        }
        int currentUserId = workspaceService.getCurrentUserId();
        Set<Integer> visible = targetNoteIds.isEmpty()
            ? Set.of()
            : new HashSet<>(noteMapper.getVisibleNoteIdsIn(workspaceId, new ArrayList<>(targetNoteIds), currentUserId));
        for (T item : items) {
            List<EntityReference> references = getReferences.apply(item);
            if (references != null) {
                setReferences.accept(item, references.stream()
                    .filter(reference -> !TYPE_NOTE.equals(reference.getRefType())
                        || visible.contains(reference.getRefId()))
                    .toList());
            }
            setContent.accept(item, redactNoteTokens(getContent.apply(item), visible));
        }
    }

    private static String redactNoteTokens(String content, Set<Integer> visibleNoteIds) {
        if (content == null) {
            return null;
        }
        Map<String, Integer> definitions = noteReferenceDefinitions(content);
        String redacted = NOTE_TOKEN.matcher(content).replaceAll(match -> {
            Integer noteId = parsePositiveInt(match.group(2));
            return noteId != null && visibleNoteIds.contains(noteId)
                ? Matcher.quoteReplacement(match.group())
                : "(private note)";
        });
        redacted = NOTE_REFERENCE_LINK.matcher(redacted).replaceAll(match -> {
            String key = match.group(2).isBlank() ? match.group(1) : match.group(2);
            Integer noteId = definitions.get(referenceKey(key));
            return noteId == null || noteId > 0 && visibleNoteIds.contains(noteId)
                ? Matcher.quoteReplacement(match.group())
                : "(private note)";
        });
        redacted = NOTE_REFERENCE_DEFINITION.matcher(redacted).replaceAll(match -> {
            Integer noteId = parsePositiveInt(match.group(2));
            return noteId == null || !visibleNoteIds.contains(noteId) ? "" : Matcher.quoteReplacement(match.group());
        });
        return redactShortcutReferenceLinks(redacted, definitions, visibleNoteIds);
    }

    private static String redactShortcutReferenceLinks(
            String content, Map<String, Integer> definitions, Set<Integer> visibleNoteIds) {
        return NOTE_SHORTCUT_REFERENCE_LINK.matcher(content).replaceAll(match -> {
            Integer noteId = definitions.get(referenceKey(match.group(1)));
            return noteId == null || noteId > 0 && visibleNoteIds.contains(noteId)
                ? Matcher.quoteReplacement(match.group())
                : "(private note)";
        });
    }

    private static Set<Integer> noteTargetIds(String content) {
        Set<Integer> ids = new HashSet<>();
        Matcher inline = NOTE_TOKEN.matcher(content);
        while (inline.find()) {
            Integer noteId = parsePositiveInt(inline.group(2));
            if (noteId != null) {
                ids.add(noteId);
            }
        }
        Matcher definition = NOTE_REFERENCE_DEFINITION.matcher(content);
        while (definition.find()) {
            Integer noteId = parsePositiveInt(definition.group(2));
            if (noteId != null) {
                ids.add(noteId);
            }
        }
        return ids;
    }

    private static boolean hasNoteReference(String content) {
        return NOTE_TOKEN.matcher(content).find() || NOTE_REFERENCE_DEFINITION.matcher(content).find();
    }

    private static Map<String, Integer> noteReferenceDefinitions(String content) {
        Map<String, Integer> definitions = new LinkedHashMap<>();
        Matcher matcher = NOTE_REFERENCE_DEFINITION.matcher(content);
        while (matcher.find()) {
            Integer noteId = parsePositiveInt(matcher.group(2));
            definitions.putIfAbsent(referenceKey(matcher.group(1)), noteId == null ? INVALID_NOTE_REFERENCE_ID : noteId);
        }
        return definitions;
    }

    private static String referenceKey(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
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
        return TOKEN.matcher(content).replaceAll(match ->
            TYPE_NOTE.equals(match.group(2))
                ? "a note"
                : "@" + Matcher.quoteReplacement(match.group(1)));
    }
}
