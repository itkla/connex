package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.EntityReferenceMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;

import lombok.RequiredArgsConstructor;

/**
 * Derives the structured @/# references for an entity's prose field from its
 * content tokens ({@code [Label](type:id)}) and persists them, replacing the
 * previous set. Every token is validated against the active workspace before it
 * is stored, so a client can never inject a reference to an entity outside the
 * current tenant. Members ({@code user}) drive mention notifications; contacts
 * ({@code person}), deals, and companies are stored as inline record references
 * (no notification). The source entity is polymorphic ({@code sourceType} /
 * {@code sourceId}): notes and tasks share this machinery.
 */
@Service
@RequiredArgsConstructor
public class ReferenceService {

    private final EntityReferenceMapper entityReferenceMapper;
    private final WorkspaceService workspaceService;
    private final PersonMapper personMapper;
    private final DealMapper dealMapper;
    private final CompanyMapper companyMapper;

    public static final String SOURCE_NOTE = "note";
    public static final String SOURCE_TASK = "task";

    static final String TYPE_USER = "user";
    static final String TYPE_PERSON = "person";
    static final String TYPE_DEAL = "deal";
    static final String TYPE_COMPANY = "company";
    private static final int MAX_REFERENCES = 100;
    private static final int MAX_LABEL_LENGTH = 255;
    private static final Pattern TOKEN =
        Pattern.compile("\\[([^\\]]+)\\]\\((user|person|deal|company):(\\d+)\\)");

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
        List<EntityReference> resolved = resolve(workspaceId, sourceType, sourceId, content);

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
        return tasks;
    }

    private Map<Integer, List<EntityReference>> referencesBySource(
            int workspaceId, String sourceType, List<Integer> sourceIds) {
        Map<Integer, List<EntityReference>> bySource = new LinkedHashMap<>();
        for (EntityReference reference : entityReferenceMapper.findBySources(workspaceId, sourceType, sourceIds)) {
            bySource.computeIfAbsent(reference.getSourceId(), key -> new ArrayList<>()).add(reference);
        }
        return bySource;
    }

    private List<EntityReference> resolve(int workspaceId, String sourceType, int sourceId, String content) {
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
            if (!isVisible(workspaceId, type, refId)) {
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

    private boolean isVisible(int workspaceId, String type, int refId) {
        return switch (type) {
            case TYPE_USER -> workspaceService.isMemberIncludingPending(workspaceId, refId);
            case TYPE_PERSON -> personMapper.exists(workspaceId, refId);
            case TYPE_DEAL -> dealMapper.exists(workspaceId, refId);
            case TYPE_COMPANY -> companyMapper.exists(workspaceId, refId);
            default -> false;
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
     * by replacing each {@code [Label](type:id)} token with {@code @Label}.
     *
     * @param content the raw content
     * @return the content with reference tokens flattened to their labels
     */
    public static String toPlainText(String content) {
        if (content == null) {
            return "";
        }
        return TOKEN.matcher(content).replaceAll(match -> "@" + Matcher.quoteReplacement(match.group(1)));
    }
}
