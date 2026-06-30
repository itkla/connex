package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.NoteReference;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.NoteReferenceMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
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
    private final NoteReferenceMapper noteReferenceMapper;
    private final DealMapper dealMapper;
    private final PersonMapper personMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final ReferenceService referenceService;
    private final NotificationMapper notificationMapper;
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
        return hydrateReferences(workspaceId, noteMapper.getAllNotes(workspaceId));
    }

    public List<Note> getNotesByPersonId(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return hydrateReferences(workspaceId, noteMapper.getNotesByPersonId(workspaceId, personId));
    }

    public List<Note> getNotesByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!dealMapper.exists(workspaceId, dealId)) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return hydrateReferences(workspaceId, noteMapper.getNotesByDealId(workspaceId, dealId));
    }

    public List<Note> getNotesByAuthorId(int authorId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return hydrateReferences(workspaceId, noteMapper.getNotesByAuthorId(workspaceId, authorId));
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
        noteMapper.insert(note);
        auditService.record("note.create", "note", note.getId(), note.getContent(),
            "Created note",
            auditService.diff(null, note, AUDIT_FIELDS));
        List<Integer> mentioned =
            referenceService.syncReferences(workspaceId, note.getId(), note.getContent(), actor.getId());
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
        noteMapper.update(note);
        auditService.record("note.update", "note", id, note.getContent(),
            "Updated note",
            auditService.diff(before, note, AUDIT_FIELDS));
        List<Integer> mentioned =
            referenceService.syncReferences(workspaceId, id, note.getContent(), actor.getId());
        notifyMentions(workspaceId, note, mentioned, actor);
        return hydrateReferences(workspaceId, note);
    }

    @RequirePermission(Permission.NOTE_DELETE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Note before = noteMapper.getNoteById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        noteMapper.delete(workspaceId, id);
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
        for (int recipientId : recipientIds) {
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
                applyContext(workspaceId, notification, note);
                notification.setDedupeKey(MENTION_TYPE + ":" + note.getId() + ":" + recipientId);
                notification.setTriggeredAt(triggeredAt);
                notification.setData(json(Map.of("noteId", note.getId())));
                notificationMapper.upsert(notification);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void applyContext(int workspaceId, Notification notification, Note note) {
        Integer dealId = note.getDeal() != null && note.getDeal().getId() > 0 ? note.getDeal().getId() : null;
        Integer personId = note.getPerson() != null && note.getPerson().getId() > 0 ? note.getPerson().getId() : null;
        if (dealId != null && dealMapper.exists(workspaceId, dealId)) {
            notification.setContextType("deal");
            notification.setContextId(dealId);
            notification.setActionUrl("/records/deals/" + dealId);
        } else if (personId != null && personMapper.exists(workspaceId, personId)) {
            notification.setContextType("person");
            notification.setContextId(personId);
            notification.setActionUrl("/records/contacts/" + personId);
        } else {
            notification.setActionUrl("/activity/notes");
        }
    }

    private Note hydrateReferences(int workspaceId, Note note) {
        note.setReferences(noteReferenceMapper.findByNote(workspaceId, note.getId()));
        return note;
    }

    private List<Note> hydrateReferences(int workspaceId, List<Note> notes) {
        if (notes.isEmpty()) {
            return notes;
        }
        List<Integer> noteIds = notes.stream().map(Note::getId).toList();
        Map<Integer, List<NoteReference>> byNote = new LinkedHashMap<>();
        for (NoteReference reference : noteReferenceMapper.findByNotes(workspaceId, noteIds)) {
            byNote.computeIfAbsent(reference.getNoteId(), key -> new ArrayList<>()).add(reference);
        }
        for (Note note : notes) {
            note.setReferences(byNote.getOrDefault(note.getId(), List.of()));
        }
        return notes;
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
