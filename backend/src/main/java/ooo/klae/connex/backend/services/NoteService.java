package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Note} operations.
 * Every read/write is scoped to the caller's active workspace.
 * Delegates persistence to {@code NoteMapper}, resolves inline @-references via
 * {@code ReferenceService}, and dispatches member-mention notifications.
 */

@Service
@RequiredArgsConstructor
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    private final NoteMapper noteMapper;
    private final DealMapper dealMapper;
    private final PersonMapper personMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final ReferenceService referenceService;
    private final NotificationDelivery notificationDelivery;
    private final NotificationPreferenceService notificationPreferenceService;
    private final ObjectMapper objectMapper;

    private static final Set<String> AUDIT_FIELDS = Set.of("content", "title", "visibility");
    private static final Set<String> PRIVATE_AUDIT_FIELDS = Set.of("visibility");
    private static final Set<String> VALID_VISIBILITY = Set.of("private", "workspace");
    private static final String PRIVATE = "private";
    private static final String MENTION_TYPE = "note.mention";
    private static final String MENTION_CATEGORY = "note";
    private static final String MENTION_SEVERITY = "info";
    private static final String IN_APP = "in_app";
    private static final int SNIPPET_LENGTH = 140;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<Note> getAllNotes() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int currentUserId = workspaceService.getCurrentUserId();
        return referenceService.hydrate(workspaceId, noteMapper.getVisibleNotes(workspaceId, currentUserId));
    }

    public List<Note> getNotesPage(int limit, int offset) {
        return getNotesPage(limit, offset, false);
    }

    /** Returns a bounded note page, optionally excluding every private note. */
    public List<Note> getNotesPage(int limit, int offset, boolean workspaceOnly) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (workspaceOnly) {
            return referenceService.hydrate(
                workspaceId, noteMapper.getWorkspaceNotesPage(workspaceId, limit, offset));
        }
        int currentUserId = workspaceService.getCurrentUserId();
        return referenceService.hydrate(workspaceId, noteMapper.getVisibleNotesPage(workspaceId, currentUserId, limit, offset));
    }

    public long countNotes() {
        return countNotes(false);
    }

    /** Counts notes in the active workspace, optionally excluding every private note. */
    public long countNotes(boolean workspaceOnly) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (workspaceOnly) {
            return noteMapper.countWorkspaceNotes(workspaceId);
        }
        int currentUserId = workspaceService.getCurrentUserId();
        return noteMapper.countVisibleNotes(workspaceId, currentUserId);
    }

    public List<Note> getNotesByPersonId(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int currentUserId = workspaceService.getCurrentUserId();
        return referenceService.hydrate(workspaceId, noteMapper.getVisibleNotesByPersonId(workspaceId, personId, currentUserId));
    }

    public List<Note> getNotesByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!dealMapper.exists(workspaceId, dealId)) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        int currentUserId = workspaceService.getCurrentUserId();
        return referenceService.hydrate(workspaceId, noteMapper.getVisibleNotesByDealId(workspaceId, dealId, currentUserId));
    }

    public List<Note> getNotesByAuthorId(int authorId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int currentUserId = workspaceService.getCurrentUserId();
        return referenceService.hydrate(workspaceId, noteMapper.getVisibleNotesByAuthorId(workspaceId, authorId, currentUserId));
    }

    /**
     * The notes visible to the caller that reference the given entity — note-source
     * backlinks. Private source notes are excluded in SQL for non-authors, so a
     * private note never surfaces as a backlink to someone who cannot read it.
     */
    public List<Note> getNotesReferencing(String refType, int refId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int currentUserId = workspaceService.getCurrentUserId();
        return referenceService.hydrate(workspaceId, noteMapper.getNotesReferencing(workspaceId, refType, refId, currentUserId));
    }

    public Note getNoteById(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int currentUserId = workspaceService.getCurrentUserId();
        Note note = noteMapper.getVisibleNoteById(workspaceId, id, currentUserId);
        if (note == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        return referenceService.hydrate(workspaceId, List.of(note)).get(0);
    }

    @Transactional
    @RequirePermission(Permission.NOTE_CREATE)
    public Note create(Note note) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        User actor = authService.getCurrentUser();
        note.setWorkspaceId(workspaceId);
        note.setAuthor(actor);
        note.setVisibility(normalizeVisibility(note.getVisibility(),
            note.getPerson() == null && note.getDeal() == null ? PRIVATE : "workspace"));
        requireLinkedRecordsVisible(workspaceId, note);
        note.setCreatedAt(null);
        noteMapper.insert(note);
        auditService.record("note.create", "note", note.getId(), auditLabel(note),
            "Created note",
            auditService.diff(null, note, auditFields(note.getVisibility())));
        List<Integer> mentioned = referenceService.syncReferences(workspaceId, note.getId(), note.getContent());
        if (!PRIVATE.equals(note.getVisibility())) {
            notifyMentions(workspaceId, note, mentioned, actor);
        }
        return hydrateReferences(workspaceId, note);
    }

    @Transactional
    @RequirePermission(Permission.NOTE_UPDATE)
    public Note update(int id, Note note) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        User actor = authService.getCurrentUser();
        Note before = noteMapper.getVisibleNoteById(workspaceId, id, actor.getId());
        if (before == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        note.setId(id);
        note.setWorkspaceId(workspaceId);
        note.setAuthor(before.getAuthor());
        note.setVisibility(normalizeVisibility(note.getVisibility(), before.getVisibility()));
        requireLinkedRecordsVisible(workspaceId, note);
        noteMapper.update(note);
        auditService.record("note.update", "note", id, auditLabel(note),
            "Updated note",
            auditService.diff(before, note, auditFields(note.getVisibility())));
        List<Integer> mentioned = referenceService.syncReferences(workspaceId, id, note.getContent());
        if (!PRIVATE.equals(note.getVisibility())) {
            notifyMentions(workspaceId, note, mentioned, actor);
        }
        return hydrateReferences(workspaceId, note);
    }

    @Transactional
    @RequirePermission(Permission.NOTE_DELETE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int currentUserId = workspaceService.getCurrentUserId();
        Note before = noteMapper.getVisibleNoteById(workspaceId, id, currentUserId);
        if (before == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        noteMapper.delete(workspaceId, id);
        referenceService.deleteReferences(workspaceId, ReferenceService.SOURCE_NOTE, id);
        referenceService.deleteReferencesTo(workspaceId, ReferenceService.TYPE_NOTE, id);
        auditService.record("note.delete", "note", id, auditLabel(before),
            "Deleted note",
            auditService.diff(before, null, auditFields(before.getVisibility())));
    }

    /** Deletes a visible note only when its locked current state satisfies the supplied guard. */
    @Transactional
    @RequirePermission(Permission.NOTE_DELETE)
    public void deleteIf(int id, Predicate<Note> guard) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int currentUserId = workspaceService.getCurrentUserId();
        Note before = noteMapper.getVisibleNoteByIdForUpdate(workspaceId, id, currentUserId);
        if (before == null) {
            throw new ResourceNotFoundException("Note not found with id: " + id);
        }
        if (guard == null || !guard.test(before)) {
            throw new ConflictException("Note changed and cannot be deleted");
        }
        noteMapper.delete(workspaceId, id);
        referenceService.deleteReferences(workspaceId, ReferenceService.SOURCE_NOTE, id);
        referenceService.deleteReferencesTo(workspaceId, ReferenceService.TYPE_NOTE, id);
        auditService.record("note.delete", "note", id, auditLabel(before),
            "Deleted note",
            auditService.diff(before, null, auditFields(before.getVisibility())));
    }

    private void notifyMentions(int workspaceId, Note note, List<Integer> recipientIds, User actor) {
        if (recipientIds.isEmpty()) {
            return;
        }
        String snippet = snippet(note.getContent());
        String triggeredAt = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        String noteAnchor = "?note=" + note.getId();
        String contextType = null;
        Integer contextId = null;
        String actionUrl = "/activity/notes" + noteAnchor;
        if (note.getDeal() != null && note.getDeal().getId() > 0) {
            contextType = "deal";
            contextId = note.getDeal().getId();
            actionUrl = "/records/deals/" + contextId + noteAnchor;
        } else if (note.getPerson() != null && note.getPerson().getId() > 0) {
            contextType = "person";
            contextId = note.getPerson().getId();
            actionUrl = "/records/contacts/" + contextId + noteAnchor;
        }
        for (int recipientId : recipientIds) {
            if (recipientId == actor.getId()) {
                continue;
            }
            if (!notificationPreferenceService.isEnabled(recipientId, MENTION_TYPE, IN_APP)) {
                continue;
            }
            try {
                Notification notification = new Notification();
                notification.setWorkspaceId(workspaceId);
                notification.setRecipientId(recipientId);
                notification.setType(MENTION_TYPE);
                notification.setCategory(MENTION_CATEGORY);
                notification.setSeverity(MENTION_SEVERITY);
                notification.setTemplateVersion(1);
                notification.setTitle("New mention");
                notification.setBody(actor.getDisplayName() + " mentioned you in a note");
                notification.setActorId(actor.getId());
                notification.setActorLabel(actor.getDisplayName());
                notification.setSourceType("note");
                notification.setSourceId(note.getId());
                notification.setSourceLabel(snippet);
                notification.setContextType(contextType);
                notification.setContextId(contextId);
                notification.setActionUrl(actionUrl);
                notification.setDedupeKey(MENTION_TYPE + ":" + note.getId() + ":" + recipientId);
                notification.setTriggeredAt(triggeredAt);
                notification.setData(json(Map.of("noteId", note.getId())));
                notificationDelivery.deliver(notification);
            } catch (RuntimeException e) {
                log.warn("Failed to deliver mention notification for note {} to recipient {}: {}",
                        note.getId(), recipientId, e.toString());
            }
        }
    }

    private static String normalizeVisibility(String value, String fallback) {
        if (value == null || value.isBlank() || !VALID_VISIBILITY.contains(value)) {
            return fallback;
        }
        return value;
    }

    private static Set<String> auditFields(String visibility) {
        return PRIVATE.equals(visibility) ? PRIVATE_AUDIT_FIELDS : AUDIT_FIELDS;
    }

    private static String auditLabel(Note note) {
        return PRIVATE.equals(note.getVisibility()) ? "" : note.getContent();
    }

    private void requireLinkedRecordsVisible(int workspaceId, Note note) {
        if (note.getPerson() != null && note.getPerson().getId() > 0
                && !personMapper.exists(workspaceId, note.getPerson().getId())) {
            throw new ResourceNotFoundException("Person not found with id: " + note.getPerson().getId());
        }
        if (note.getDeal() != null && note.getDeal().getId() > 0
                && !dealMapper.exists(workspaceId, note.getDeal().getId())) {
            throw new ResourceNotFoundException("Deal not found with id: " + note.getDeal().getId());
        }
    }

    private Note hydrateReferences(int workspaceId, Note note) {
        note.setReferences(referenceService.referencesFor(workspaceId, ReferenceService.SOURCE_NOTE, note.getId()));
        return note;
    }

    private static String snippet(String content) {
        String plain = ReferenceService.toPlainText(content).strip();
        return plain.length() > SNIPPET_LENGTH ? plain.substring(0, SNIPPET_LENGTH) : plain;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize notification data", exception);
        }
    }
}
