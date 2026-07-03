package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
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

    private static final Set<String> AUDIT_FIELDS = Set.of("content");
    private static final String MENTION_TYPE = "note.mention";
    private static final String MENTION_CATEGORY = "note";
    private static final String MENTION_SEVERITY = "info";
    private static final String IN_APP = "in_app";
    private static final int SNIPPET_LENGTH = 140;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<Note> getAllNotes() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrate(workspaceId, noteMapper.getAllNotes(workspaceId));
    }

    public List<Note> getNotesByPersonId(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrate(workspaceId, noteMapper.getNotesByPersonId(workspaceId, personId));
    }

    public List<Note> getNotesByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!dealMapper.exists(workspaceId, dealId)) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return referenceService.hydrate(workspaceId, noteMapper.getNotesByDealId(workspaceId, dealId));
    }

    public List<Note> getNotesByAuthorId(int authorId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrate(workspaceId, noteMapper.getNotesByAuthorId(workspaceId, authorId));
    }

    public Note getNoteById(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Note note = noteMapper.getNoteById(workspaceId, id);
        if (note == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        return hydrateReferences(workspaceId, note);
    }

    @Transactional
    @RequirePermission(Permission.NOTE_CREATE)
    public Note create(Note note) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        User actor = authService.getCurrentUser();
        note.setWorkspaceId(workspaceId);
        note.setAuthor(actor);
        requireLinkedRecordsVisible(workspaceId, note);
        noteMapper.insert(note);
        auditService.record("note.create", "note", note.getId(), note.getContent(),
            "Created note",
            auditService.diff(null, note, AUDIT_FIELDS));
        List<Integer> mentioned = referenceService.syncReferences(workspaceId, note.getId(), note.getContent());
        notifyMentions(workspaceId, note, mentioned, actor);
        return hydrateReferences(workspaceId, note);
    }

    @Transactional
    @RequirePermission(Permission.NOTE_UPDATE)
    public Note update(int id, Note note) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Note before = noteMapper.getNoteById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        User actor = authService.getCurrentUser();
        note.setId(id);
        note.setWorkspaceId(workspaceId);
        note.setAuthor(before.getAuthor());
        requireLinkedRecordsVisible(workspaceId, note);
        noteMapper.update(note);
        auditService.record("note.update", "note", id, note.getContent(),
            "Updated note",
            auditService.diff(before, note, AUDIT_FIELDS));
        List<Integer> mentioned = referenceService.syncReferences(workspaceId, id, note.getContent());
        notifyMentions(workspaceId, note, mentioned, actor);
        return hydrateReferences(workspaceId, note);
    }

    @Transactional
    @RequirePermission(Permission.NOTE_DELETE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Note before = noteMapper.getNoteById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        noteMapper.delete(workspaceId, id);
        referenceService.deleteReferences(workspaceId, ReferenceService.SOURCE_NOTE, id);
        auditService.record("note.delete", "note", id, before.getContent(),
            "Deleted note",
            auditService.diff(before, null, AUDIT_FIELDS));
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
            } catch (RuntimeException ignored) {
            }
        }
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
