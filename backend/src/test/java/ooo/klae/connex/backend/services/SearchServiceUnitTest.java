package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentTemplateMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import ooo.klae.connex.backend.tenant.Permission;

class SearchServiceUnitTest {

    private final CompanyMapper companies = mock(CompanyMapper.class);
    private final PersonMapper people = mock(PersonMapper.class);
    private final DealMapper deals = mock(DealMapper.class);
    private final PipelineMapper pipelines = mock(PipelineMapper.class);
    private final TagMapper tags = mock(TagMapper.class);
    private final ActivityMapper activities = mock(ActivityMapper.class);
    private final NoteMapper notes = mock(NoteMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final AttachmentMapper attachments = mock(AttachmentMapper.class);
    private final ProductMapper products = mock(ProductMapper.class);
    private final CampaignMapper campaigns = mock(CampaignMapper.class);
    private final ReportMapper reports = mock(ReportMapper.class);
    private final DocumentTemplateMapper templates = mock(DocumentTemplateMapper.class);
    private final DealDocumentMapper documents = mock(DealDocumentMapper.class);
    private final WorkflowMapper workflows = mock(WorkflowMapper.class);
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final ReferenceService references = mock(ReferenceService.class);
    private final RecordVisibilityScopeControlAccess scopes = ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered();
    private final SearchService service = new SearchService(
        companies, people, deals, pipelines, tags, activities, notes, tasks, users, attachments,
        products, campaigns, reports, templates, documents, workflows, workspace, references, scopes);

    @Test
    void defaultSearchRunsEveryGroup() {
        authorize(EnumSet.allOf(Permission.class));

        service.search("needle");

        verify(scopes).withWorkspaceScope(eq(7), org.mockito.ArgumentMatchers.any());
        verify(scopes).getForWorkspace(7);
        verifyNoMoreInteractions(scopes);
        verify(companies).search(7, "[7,8]", "%needle%");
        verify(people).search(7, "%needle%");
        verify(deals).search(7, "%needle%");
        verify(pipelines).search(7, "[7,8]", "%needle%");
        verify(tags).search(7, "%needle%");
        verify(activities).search(7, "%needle%", 25, 0);
        verify(notes).searchVisible(7, "[7,8]", "%needle%", 11, 25, 0);
        verify(notes).getVisibleNotesPage(7, "[7,8]", 11, null, List.of(), "created", "desc", 25, 0);
        verify(tasks).search(7, "%needle%", 25, 0);
        verify(users).search(7, "%needle%");
        verify(attachments).search(7, "%needle%", 11);
        verify(products).search(7, "%needle%");
        verify(campaigns).searchCampaigns(7, "%needle%");
        verify(reports).searchDefinitions(7, "%needle%");
        verify(templates).search(7, "%needle%");
        verify(documents).search(7, "%needle%");
        verify(workflows).search(7, "%needle%");
        verify(references).hydrateDeals(7, List.of());
        verifyNoMoreInteractions(allGroupDependencies());
    }

    @Test
    void subsetSkipsEveryOtherMapperAndHydration() {
        authorize(EnumSet.allOf(Permission.class));
        Company company = new Company();
        company.setId(23);
        company.setName("needle company");
        when(companies.search(7, "[7,8]", "%needle%")).thenReturn(List.of(company));

        SearchResultsDto result = service.search("needle", Set.of("companies", "products"));

        assertEquals(23, result.getCompanies().getFirst().getId());
        assertTrue(result.getNotes().isEmpty());
        assertTrue(result.getDeals().isEmpty());
        verify(companies).search(7, "[7,8]", "%needle%");
        verify(products).search(7, "%needle%");
        verifyNoInteractions(people, deals, pipelines, tags, activities, notes, tasks, users,
            attachments, campaigns, reports, templates, documents, workflows, references);
        verify(scopes).withWorkspaceScope(eq(7), org.mockito.ArgumentMatchers.any());
        verify(scopes).getForWorkspace(7);
        verifyNoMoreInteractions(scopes, companies, products);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deals", "activities", "notes", "tasks"})
    void selectingAGroupRunsOnlyItsOwnHydration(String type) {
        authorize(EnumSet.allOf(Permission.class));
        switch (type) {
            case "deals" -> {
                service.search("needle", Set.of(type));
                verify(deals).search(7, "%needle%");
                verify(references).hydrateDeals(7, List.of());
            }
            case "activities" -> {
                Activity activity = new Activity();
                when(activities.search(7, "%needle%", 25, 0)).thenReturn(List.of(activity));
                service.search("needle", Set.of(type));
                verify(activities).search(7, "%needle%", 25, 0);
                verify(references).hydrateActivities(7, List.of(activity));
            }
            case "notes" -> {
                Note note = new Note();
                when(notes.searchVisible(7, "[7,8]", "%needle%", 11, 25, 0)).thenReturn(List.of(note));
                service.search("needle", Set.of(type));
                verify(notes).searchVisible(7, "[7,8]", "%needle%", 11, 25, 0);
                verify(notes).getVisibleNotesPage(7, "[7,8]", 11, null, List.of(), "created", "desc", 25, 0);
                verify(references).hydrate(7, List.of(note));
                verify(scopes).withWorkspaceScope(eq(7), org.mockito.ArgumentMatchers.any());
        verify(scopes).getForWorkspace(7);
                verifyNoMoreInteractions(scopes);
            }
            case "tasks" -> {
                Task task = new Task();
                when(tasks.search(7, "%needle%", 25, 0)).thenReturn(List.of(task));
                service.search("needle", Set.of(type));
                verify(tasks).search(7, "%needle%", 25, 0);
                verify(references).hydrateTasks(7, List.of(task));
            }
            default -> throw new AssertionError("Unexpected fixture type");
        }
        if (!type.equals("notes")) {
            verify(scopes).withWorkspaceScope(eq(7), org.mockito.ArgumentMatchers.any());
        }
        verifyNoMoreInteractions(allGroupDependencies());
    }

    @ParameterizedTest
    @ValueSource(strings = {"campaigns", "reports", "workflows"})
    void selectingADeniedGroupDoesNoQueryOrHydration(String type) {
        authorize(Set.of());

        SearchResultsDto result = service.search("needle", Set.of(type));

        assertTrue(result.getCampaigns().isEmpty());
        assertTrue(result.getReports().isEmpty());
        assertTrue(result.getWorkflows().isEmpty());
        verifyNoInteractions(allGroupDependencies());
    }

    @Test
    void invalidSelectorsAreRejectedBeforeAnyWorkEvenForABlankQuery() {
        for (Set<String> types : List.of(Set.<String>of(), Set.of("companies", "unknown"))) {
            assertThrows(BadRequestException.class, () -> service.search("", types));
        }
        assertThrows(BadRequestException.class, () -> service.search("needle", null));
        verifyNoInteractions(workspace);
        verifyNoInteractions(allGroupDependencies());
    }

    @Test
    void parserAcceptsRepeatedCommaSeparatedTrimmedAndDuplicateNames() {
        assertEquals(Set.of("companies", "people", "documentTemplates"),
            SearchService.parseTypes(List.of(" companies,people ", "documentTemplates", "companies,,")));
        assertEquals(16, SearchService.parseTypes(null).size());
    }

    @Test
    void parserRejectsUnknownCaseMismatchedAndEmptySelections() {
        for (List<String> types : List.of(List.<String>of(), List.of("", " , "),
                List.of("companies,unknown"), List.of("Companies"))) {
            assertThrows(BadRequestException.class, () -> SearchService.parseTypes(types));
        }
    }

    @Test
    void noteSearchSharesOneScopeAcrossBothStreamsAndPreservesEachCandidateBound() {
        authorize(Set.of());
        List<Note> batch = IntStream.rangeClosed(1, 25)
            .mapToObj(id -> note(id, "unrelated", "2026-09-01 12:00:00")).toList();
        when(notes.searchVisible(eq(7), eq("[7,8]"), eq("%needle%"), eq(11), eq(25), anyInt()))
            .thenReturn(batch);
        when(notes.getVisibleNotesPage(eq(7), eq("[7,8]"), eq(11), eq(null), eq(List.of()),
            eq("created"), eq("desc"), eq(25), anyInt())).thenReturn(batch);
        when(references.hydrate(eq(7), anyList())).thenAnswer(invocation -> invocation.getArgument(1));

        SearchResultsDto result = service.search("needle", Set.of("notes"));

        assertTrue(result.getNotes().isEmpty());
        verify(scopes).withWorkspaceScope(eq(7), org.mockito.ArgumentMatchers.any());
        verify(scopes).getForWorkspace(7);
        verifyNoMoreInteractions(scopes);
        for (int offset = 0; offset < 250; offset += 25) {
            verify(notes).searchVisible(7, "[7,8]", "%needle%", 11, 25, offset);
            verify(notes).getVisibleNotesPage(7, "[7,8]", 11, null, List.of(), "created", "desc", 25, offset);
        }
        verifyNoMoreInteractions(notes);
        verify(users, times(10)).findMatchingWorkspaceMemberIdsIn(7, "%needle%", List.of(41));
    }

    @Test
    void noteSearchKeepsMergeOrderDeduplicationAndTheTenResultLimit() {
        authorize(Set.of());
        List<Note> content = IntStream.rangeClosed(1, 8)
            .mapToObj(id -> note(id, "needle body", "2026-09-01 12:00:00")).toList();
        List<Note> authored = IntStream.rangeClosed(7, 14)
            .mapToObj(id -> note(id, "author body", "2026-09-01 12:00:00")).toList();
        when(notes.searchVisible(7, "[7,8]", "%needle%", 11, 25, 0)).thenReturn(content);
        when(notes.getVisibleNotesPage(7, "[7,8]", 11, null, List.of(), "created", "desc", 25, 0))
            .thenReturn(authored);
        when(users.findMatchingWorkspaceMemberIdsIn(7, "%needle%", List.of(41))).thenReturn(List.of(41));
        when(references.hydrate(eq(7), anyList())).thenAnswer(invocation -> invocation.getArgument(1));

        SearchResultsDto result = service.search("needle", Set.of("notes"));

        assertEquals(List.of(14, 13, 12, 11, 10, 9, 8, 7, 6, 5),
            result.getNotes().stream().map(note -> note.getId()).toList());
        assertEquals("needle body", result.getNotes().get(6).getContent());
        verify(scopes).withWorkspaceScope(eq(7), org.mockito.ArgumentMatchers.any());
        verify(scopes).getForWorkspace(7);
        verifyNoMoreInteractions(scopes);
    }

    private static Note note(int id, String content, String createdAt) {
        Note note = new Note();
        note.setId(id);
        note.setContent(content);
        note.setCreatedAt(createdAt);
        User author = new User();
        author.setId(41);
        note.setAuthor(author);
        return note;
    }

    private void authorize(Set<Permission> permissions) {
        when(scopes.getForWorkspace(7)).thenReturn(new RecordVisibilityScope(900, List.of(7, 8)));
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(workspace.getCurrentUserId()).thenReturn(11);
        when(workspace.permissionsFor(7, 11)).thenReturn(permissions);
    }

    private Object[] allGroupDependencies() {
        return new Object[] {companies, people, deals, pipelines, tags, activities, notes, tasks,
            users, attachments, products, campaigns, reports, templates, documents, workflows, references, scopes};
    }
}
