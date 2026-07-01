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

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.NoteReference;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteReferenceMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;

import lombok.RequiredArgsConstructor;

/**
 * Derives the structured @-references for a note from its content tokens
 * ({@code [Label](type:id)}) and persists them, replacing the previous set.
 * Every token is validated against the active workspace before it is stored, so
 * a client can never inject a reference to an entity outside the current tenant.
 * Members ({@code user}) drive mention notifications; contacts ({@code person}),
 * deals, and companies are stored as inline record references (no notification).
 */
@Service
@RequiredArgsConstructor
public class ReferenceService {

    private final NoteReferenceMapper noteReferenceMapper;
    private final WorkspaceService workspaceService;
    private final PersonMapper personMapper;
    private final DealMapper dealMapper;
    private final CompanyMapper companyMapper;

    static final String TYPE_USER = "user";
    static final String TYPE_PERSON = "person";
    static final String TYPE_DEAL = "deal";
    static final String TYPE_COMPANY = "company";
    private static final int MAX_REFERENCES = 100;
    private static final int MAX_LABEL_LENGTH = 255;
    private static final Pattern TOKEN =
        Pattern.compile("\\[([^\\]]+)\\]\\((user|person|deal|company):(\\d+)\\)");

    /**
     * Re-derives and persists a note's @-references from its content, replacing
     * any previous set. Every valid member reference is stored (so the mention
     * chip renders regardless of who edits the note); returns the IDs of members
     * referenced for the first time (present now but not before this call), so
     * the caller can notify only newly-added mentions. Excluding the acting
     * author from notification is the caller's responsibility. Scoped to
     * {@code workspaceId}.
     *
     * @param workspaceId the owning workspace
     * @param noteId      the note whose references are being synced
     * @param content     the note's current content
     * @return the user IDs newly referenced by this sync
     */
    @Transactional
    public List<Integer> syncReferences(int workspaceId, int noteId, String content) {
        Set<Integer> before = mentionedMemberIds(noteReferenceMapper.findByNote(workspaceId, noteId));
        List<NoteReference> resolved = resolve(workspaceId, noteId, content);

        noteReferenceMapper.deleteByNote(workspaceId, noteId);
        for (NoteReference reference : resolved) {
            noteReferenceMapper.insert(reference);
        }

        Set<Integer> added = mentionedMemberIds(resolved);
        added.removeAll(before);
        return new ArrayList<>(added);
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

    private List<NoteReference> resolve(int workspaceId, int noteId, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Map<String, NoteReference> unique = new LinkedHashMap<>();
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
            unique.put(key, build(workspaceId, noteId, type, refId, matcher.group(1)));
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

    private NoteReference build(int workspaceId, int noteId, String type, int refId, String label) {
        NoteReference reference = new NoteReference();
        reference.setWorkspaceId(workspaceId);
        reference.setNoteId(noteId);
        reference.setRefType(type);
        reference.setRefId(refId);
        reference.setLabel(label.length() > MAX_LABEL_LENGTH ? label.substring(0, MAX_LABEL_LENGTH) : label);
        return reference;
    }

    private Set<Integer> mentionedMemberIds(List<NoteReference> references) {
        Set<Integer> ids = new HashSet<>();
        for (NoteReference reference : references) {
            if (TYPE_USER.equals(reference.getRefType())) {
                ids.add(reference.getRefId());
            }
        }
        return ids;
    }

    /**
     * Renders note content for plain-text contexts (e.g. notification snippets)
     * by replacing each {@code [Label](type:id)} token with {@code @Label}.
     *
     * @param content the raw note content
     * @return the content with reference tokens flattened to their labels
     */
    public static String toPlainText(String content) {
        if (content == null) {
            return "";
        }
        return TOKEN.matcher(content).replaceAll(match -> "@" + Matcher.quoteReplacement(match.group(1)));
    }
}
