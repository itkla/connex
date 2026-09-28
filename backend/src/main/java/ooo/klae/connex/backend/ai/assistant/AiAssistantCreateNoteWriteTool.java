package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateNote;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.services.NoteService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Immediately writes one note on a person or deal, with the visibility the request names.
 *
 * <p>The framework holds the target {@code FOR UPDATE} before this tool calls
 * {@link NoteService#create}, which settles the visibility the note is stored with; the outcome
 * reports that stored visibility, not the requested one. The write is read back by the record the
 * returned note links to, compared with the target the framework resolved. The inverse deletes the
 * note only while its content, title, visibility and links still match what this write created.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantCreateNoteWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "create_note";
    private static final String EXECUTED = "executed";
    private static final String NOTE = "note";
    private static final Set<String> TARGET_KINDS = Set.of("person", "deal");

    private final NoteService noteService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolTier tier() {
        return ToolTier.AUTO;
    }

    @Override
    public Class<? extends AiAssistantWriteToolRequest> requestType() {
        return CreateNote.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return TARGET_KINDS;
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of(
                "note.content", "note.title", "note.visibility", "note.person", "note.deal");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(Permission.NOTE_CREATE, Permission.NOTE_DELETE);
    }

    @Override
    public Lock lock(String targetKind) {
        return new Lock(false, TargetLock.RECORD_UPDATE);
    }

    @Override
    public List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory) {
        return List.of();
    }

    @Override
    public Outcome apply(Execution execution) {
        if (!(execution.row().request() instanceof CreateNote request)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        Target target = execution.row().target();
        Note note = new Note();
        note.setContent(request.content());
        note.setTitle(request.title());
        note.setVisibility(request.visibility());
        link(note, target);
        Note created = noteService.create(note);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", EXECUTED);
        outcome.put("recordType", NOTE);
        if (created.getTitle() != null) {
            outcome.put("title", created.getTitle());
        }
        outcome.put("visibility", created.getVisibility());
        return new Outcome(
                outcome,
                new Inverse(
                        NOTE,
                        created.getId(),
                        AiAssistantWriteTool.fingerprint(objectMapper, noteState(created)),
                        true,
                        Map.of()),
                new ReadBack(
                        target.kind() + "Id",
                        target.id(),
                        linkedId(created, target.kind())));
    }

    @Override
    public void undo(Authority authority, Inverse inverse) {
        if (!NOTE.equals(inverse.entityKind())) {
            throw new ConflictException("Assistant tool undo metadata is invalid");
        }
        noteService.deleteIf(
                inverse.entityId(),
                current -> inverse.fingerprint().equals(
                        AiAssistantWriteTool.fingerprint(objectMapper, noteState(current))));
    }

    @Override
    public boolean inverseAvailable() {
        return true;
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of();
    }

    @Override
    public Diff diff(Review review) {
        return null;
    }

    @Override
    public String requestSummary(Review review) {
        return "Create a note";
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Note created";
    }

    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        AiAssistantWriteTool.copyText(storedOutcome, result, "title");
        AiAssistantWriteTool.copyText(storedOutcome, result, "visibility");
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of("title", "visibility");
    }

    private static void link(Note note, Target target) {
        if ("person".equals(target.kind())) {
            Person person = new Person();
            person.setId(target.id());
            note.setPerson(person);
        } else {
            Deal deal = new Deal();
            deal.setId(target.id());
            note.setDeal(deal);
        }
    }

    /** The record the created note is linked to, as the note service returned it. */
    private static Integer linkedId(Note note, String targetKind) {
        if ("person".equals(targetKind)) {
            return note.getPerson() == null ? null : note.getPerson().getId();
        }
        return note.getDeal() == null ? null : note.getDeal().getId();
    }

    private static Map<String, Object> noteState(Note note) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("content", note.getContent());
        state.put("title", note.getTitle());
        state.put("visibility", note.getVisibility());
        state.put("personId", note.getPerson() == null ? 0 : note.getPerson().getId());
        state.put("dealId", note.getDeal() == null ? 0 : note.getDeal().getId());
        return state;
    }
}
