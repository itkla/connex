package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.EntityReferenceMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

class ReferenceServiceReaderVisibilityTest {
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final WorkspaceMapper members = mock(WorkspaceMapper.class);
    private final PersonMapper people = mock(PersonMapper.class);
    private final DealMapper deals = mock(DealMapper.class);
    private final CompanyMapper companies = mock(CompanyMapper.class);
    private final NoteMapper notes = mock(NoteMapper.class);
    private final AttachmentMapper files = mock(AttachmentMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final ActivityMapper activities = mock(ActivityMapper.class);
    private final EntityReferenceMapper stored = mock(EntityReferenceMapper.class);
    private final ReferenceService service = new ReferenceService(stored, workspace, people, deals, companies,
            notes, files, tasks, activities, members);

    static Stream<Arguments> referenceLayouts() {
        return ReferenceContentReaderTest.referenceLayouts();
    }

    static Stream<Arguments> tableDisagreements() {
        return ReferenceContentReaderTest.tableDisagreements();
    }

    static Stream<Arguments> normalizedTableDestinations() {
        return ReferenceContentReaderTest.normalizedTableDestinations();
    }

    static Stream<Arguments> externalAndEmbeddedDestinations() {
        return ReferenceContentReaderTest.externalAndEmbeddedDestinations();
    }

    @ParameterizedTest
    @MethodSource("normalizedTableDestinations")
    void normalizedCensusStillRequiresScopedVisibility(String type, String content) {
        tableDisagreementsStillRequireScopedVisibility(type, content);
    }

    @ParameterizedTest
    @MethodSource("externalAndEmbeddedDestinations")
    void externalUrlsAndEmbeddedWordsRemainVisibleWithoutInternalLookups(String type, String content) {
        assertEquals(content, service.redactInvisibleTargets(7,
                List.of(new ReferenceService.ReaderVisibleContent(content, List.of()))).getFirst().content(), type);
        verifyNoInteractions(members, people, deals, companies, notes, files, tasks, activities, stored);
    }

    @ParameterizedTest
    @MethodSource("tableDisagreements")
    void tableDisagreementsStillRequireScopedVisibility(String type, String content) {
        when(workspace.getCurrentUserId()).thenReturn(13);
        assertEquals(type.equals("note") ? "(private note)" : "(unavailable reference)",
                service.redactInvisibleTargets(7,
                        List.of(new ReferenceService.ReaderVisibleContent(content, List.of()))).getFirst().content());
        verifyTargetLookup(type);
        makeVisible(type);
        assertEquals(content, service.redactInvisibleTargets(7,
                List.of(new ReferenceService.ReaderVisibleContent(content, List.of()))).getFirst().content());
    }

    @ParameterizedTest
    @MethodSource("referenceLayouts")
    void everyParserLayoutUsesWorkspaceAndReaderVisibility(String type, String content) {
        when(workspace.getCurrentUserId()).thenReturn(13);
        String hidden = service.redactInvisibleTargets(7,
                List.of(new ReferenceService.ReaderVisibleContent(content, List.of()))).getFirst().content();

        assertEquals(type.equals("note") ? "(private note)" : "(unavailable reference)", hidden, content);
        verifyTargetLookup(type);

        makeVisible(type);

        assertEquals(content, service.redactInvisibleTargets(7,
                List.of(new ReferenceService.ReaderVisibleContent(content, List.of()))).getFirst().content(), content);
        verifyNoInteractions(stored);
        if (type.equals("user") && !content.startsWith("<")) {
            assertTrue(ReferenceService.memberLabelsFromReaderVisibleContent(content).contains("Former"));
        }
    }

    @Test
    void referenceDiscoveryDoesNotStopAtTheWriteSideHundredReferenceCap() {
        String content = "[Visible](company:7) ".repeat(101) + "[Secret][n]\n\n> [n]: note:42";
        when(companies.getVisibleIdsIn(7, List.of(7))).thenReturn(List.of(7));

        assertEquals("(private note)", service.redactInvisibleTargets(7,
                List.of(new ReferenceService.ReaderVisibleContent(content, List.of()))).getFirst().content());
        verify(notes).getVisibleNoteIdsIn(7, List.of(42), 0);
    }

    @Test
    void encodedDelimitersRetainThePreviousConservativeNoteRedaction() {
        String content = "&#91;Secret&#93;(note:42)";

        assertEquals("(private note)", service.redactInvisibleTargets(7,
                List.of(new ReferenceService.ReaderVisibleContent(content, List.of()))).getFirst().content());
        assertEquals("a note", ReferenceService.toPlainText(content));
    }

    @Test
    void storedReferencesKeepTheirOwnVisibilityWhenIncompleteProseFailsClosed() {
        EntityReference reference = new EntityReference();
        reference.setRefType("company");
        reference.setRefId(7);
        when(companies.getVisibleIdsIn(7, List.of(7))).thenReturn(List.of(7));
        String content = "x".repeat(ReferenceContentReader.MAX_INPUT_LENGTH + 1);

        var result = service.redactInvisibleTargets(7,
                List.of(new ReferenceService.ReaderVisibleContent(content, List.of(reference)))).getFirst();

        assertEquals("(unavailable reference)", result.content());
        assertEquals(List.of(reference), result.references());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[Secret][m]\n\nSee [^a\\]b].\n\n[^a\\]b]:\n    [m]:\n    note:42",
            "See [^a\\]b].\n\nprefix `\n[^a\\]b]: [Secret](note:42 \"title\")\n    `",
            "[Secret][m]\n\n> > See [^f].\n> >\n> > [^f]:\n> >     [m]:\n> >     note:42"
    })
    void unsupportedRendererFootnotesFailClosedEvenForOtherwiseVisibleTargets(String content) {
        when(workspace.getCurrentUserId()).thenReturn(13);
        makeVisible("note");

        assertEquals("(unavailable reference)", service.redactInvisibleTargets(7,
                List.of(new ReferenceService.ReaderVisibleContent(content, List.of()))).getFirst().content());
        assertEquals("(unavailable reference)", ReferenceService.toPlainText(content));
    }

    @ParameterizedTest
    @ValueSource(strings = {"overflow", "input", "blocks", "inlines", "table", "brackets"})
    void unsafeAndIncompleteBodiesCannotPassTheVisibilityOrSnippetBoundary(String kind) {
        String content = switch (kind) {
            case "overflow" -> "[Secret][n]\n\n> [n]: note:2147483648";
            case "input" -> "x".repeat(ReferenceContentReader.MAX_INPUT_LENGTH + 1);
            case "blocks" -> "> ".repeat(100) + "plain text";
            case "inlines" -> "**".repeat(100) + "plain text" + "**".repeat(100);
            case "table" -> "| x ".repeat(258);
            case "brackets" -> "[".repeat(131_000) + "[](u)".repeat(26_000);
            default -> throw new IllegalArgumentException(kind);
        };

        assertEquals("(unavailable reference)", service.redactInvisibleTargets(7,
                List.of(new ReferenceService.ReaderVisibleContent(content, List.of()))).getFirst().content());
        assertEquals(kind.equals("overflow") ? "a note" : "(unavailable reference)", ReferenceService.toPlainText(content));
    }

    private void makeVisible(String type) {
        switch (type) {
            case "user" -> when(members.getMemberIdsIncludingPending(eq(7), anyList())).thenReturn(List.of(42));
            case "person" -> when(people.getVisibleIdsIn(eq(7), anyList())).thenReturn(List.of(42));
            case "deal" -> when(deals.getVisibleIdsIn(eq(7), anyList())).thenReturn(List.of(42));
            case "company" -> when(companies.getVisibleIdsIn(eq(7), anyList())).thenReturn(List.of(42));
            case "note" -> when(notes.getVisibleNoteIdsIn(eq(7), anyList(), eq(13))).thenReturn(List.of(42));
            case "file" -> when(files.getVisibleIdsIn(eq(7), anyList(), eq(13))).thenReturn(List.of(42));
            case "task" -> when(tasks.getVisibleIdsIn(eq(7), anyList())).thenReturn(List.of(42));
            case "activity" -> when(activities.getVisibleIdsIn(eq(7), anyList())).thenReturn(List.of(42));
            default -> throw new IllegalArgumentException(type);
        }
    }

    private void verifyTargetLookup(String type) {
        switch (type) {
            case "user" -> verify(members).getMemberIdsIncludingPending(7, List.of(42));
            case "person" -> verify(people).getVisibleIdsIn(7, List.of(42));
            case "deal" -> verify(deals).getVisibleIdsIn(7, List.of(42));
            case "company" -> verify(companies).getVisibleIdsIn(7, List.of(42));
            case "note" -> verify(notes).getVisibleNoteIdsIn(7, List.of(42), 13);
            case "file" -> verify(files).getVisibleIdsIn(7, List.of(42), 13);
            case "task" -> verify(tasks).getVisibleIdsIn(7, List.of(42));
            case "activity" -> verify(activities).getVisibleIdsIn(7, List.of(42));
            default -> throw new IllegalArgumentException(type);
        }
    }
}
