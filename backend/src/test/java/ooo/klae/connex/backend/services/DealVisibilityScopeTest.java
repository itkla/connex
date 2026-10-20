package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.beans.DealStakeholder;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Exercises physical scope reads across compound Deal operations and their early exits. */
@ExtendWith(MockitoExtension.class)
class DealVisibilityScopeTest {
    @Mock private DealMapper mapper;
    @Mock private WorkspaceService workspaces;
    @Mock private SegmentService segments;
    @Mock private PersonMapper people;
    @Mock private AuditService audit;
    @Mock private DealRiskService risks;
    @InjectMocks private DealService service;
    private ShareWorkspaceControlAccess sharing;
    private RecordVisibilityScopeControlAccess scopes;

    @BeforeEach
    void setUp() {
        sharing = mock(ShareWorkspaceControlAccess.class);
        LinkedHashMap<Integer, String> names = new LinkedHashMap<>();
        names.put(7, "Viewer");
        names.put(8, "Owner");
        names.put(9, "Sibling");
        lenient().when(sharing.getForWorkspace(7))
            .thenReturn(new ShareWorkspaceControlAccess.OrganizationWorkspaces(90, names));
        scopes = new RecordVisibilityScopeControlAccess(sharing, new TenantContext());
        ReflectionTestUtils.setField(service, "recordVisibilityScopeControlAccess", scopes);
        lenient().when(workspaces.getCurrentWorkspaceId()).thenReturn(7);
    }

    @Test
    void filteredPageAndCountBindTheSameSingleScope() {
        service.queryDealsPage(null, null, null, null, null, null, null, null, false, null, null, null, 10, 0);

        verify(sharing, times(1)).getForWorkspace(7);
        verify(mapper).getDealsPageFiltered(7, "[7,8,9]", null, null, null, null, null,
            null, null, null, null, false, null, null, null, 10, 0);
        verify(mapper).countDealsFiltered(7, "[7,8,9]", null, null, null, null, null, null, null, false, null, null, null);
    }

    @Test
    void riskCandidatesAssessmentPageAndCountShareTheCapturedScope() {
        when(mapper.getFilteredDealIds(eq(7), eq("[7,8,9]"), any(), any(), any(), any(), any(), any(), any(),
            eq(false), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(41));

        service.queryDealsPage(null, null, null, null, null, null, null, null, false,
            null, List.of("none"), null, 10, 0);

        verify(sharing, times(1)).getForWorkspace(7);
        verify(risks).assessDeals(eq(7), eq(List.of(41)), any(),
            eq(new RecordVisibilityScopeControlAccess.RecordVisibilityScope(90, List.of(7, 8, 9))));
        verify(mapper).countDealsFiltered(7, "[7,8,9]", null, null, null, null, null, null, null,
            false, null, List.of(41), null);
    }

    @Test
    void bothVisibilityFacetsUseOneSnapshot() {
        service.getDealFacets();

        verify(sharing, times(1)).getForWorkspace(7);
        verify(mapper).countsByCompany(7, "[7,8,9]");
        verify(mapper).countsByPerson(7, "[7,8,9]");
    }

    @Test
    void missingDealAndZeroRowRoleUpdateKeepTheirExistingRefusals() {
        assertThrows(ooo.klae.connex.backend.exceptions.ResourceNotFoundException.class,
            () -> service.getPeopleByDealId(41));
        verifyNoInteractions(sharing);
        Deal deal = new Deal();
        deal.setId(41);
        when(mapper.getDealById(7, 41)).thenReturn(deal);

        assertThrows(ooo.klae.connex.backend.exceptions.ResourceNotFoundException.class,
            () -> service.updatePersonRole(41, 11, "hidden"));

        verify(sharing, times(1)).getForWorkspace(7);
        verify(mapper).updatePersonRole(7, "[7,8,9]", 41, 11, "hidden");
        verifyNoInteractions(audit);
    }

    @Test
    void emptySegmentsAndPrimaryIdListsNeverReadAScope() {
        var definition = new SegmentDefinition();
        when(segments.evaluate("deal", definition)).thenReturn(List.of());

        assertEquals(0, service.querySegmentDealsPage(definition, null, null, null, null,
            null, null, null, null, false, null, null, null, 10, 0).total());
        assertEquals(List.of(), service.getPrimaryContacts(null));
        assertEquals(List.of(), service.getPrimaryContacts(List.of()));

        verifyNoInteractions(sharing, mapper);
    }

    @Test
    void emptyPrimaryContactListsDoNotResolveWorkspaceContext() {
        assertEquals(List.of(), service.getPrimaryContacts(null));
        assertEquals(List.of(), service.getPrimaryContacts(List.of()));

        verifyNoInteractions(workspaces, sharing, mapper);
    }

    @Test
    void replacementReusesOneScopeAcrossBeforeAllLinksAndAfter() {
        Deal deal = new Deal();
        deal.setId(41);
        when(mapper.getDealById(7, 41)).thenReturn(deal);
        List<DealPerson> links = IntStream.rangeClosed(11, 13).mapToObj(id -> {
            Person person = new Person();
            person.setId(id);
            when(people.getPersonById(7, id)).thenReturn(person);
            DealPerson link = new DealPerson();
            link.setPerson(person);
            link.setRole("champion");
            return link;
        }).toList();

        service.replacePeople(41, links);

        verify(sharing, times(1)).getForWorkspace(7);
        verify(mapper, times(2)).getDealPeopleByDealId(7, "[7,8,9]", 41);
        for (int id : List.of(11, 12, 13)) {
            verify(mapper).addPerson(7, "[7,8,9]", 41, id, "champion");
        }
        var order = inOrder(mapper);
        order.verify(mapper).getDealById(7, 41);
        order.verify(mapper).getDealPeopleByDealId(7, "[7,8,9]", 41);
        order.verify(mapper).clearPeople(7, 41);
    }

    @Test
    void riskSkipsScopeForEmptyMissingClosedAndExcludedDeals() {
        DealRiskService risk = risk(mock(ScoringService.class));
        assertEquals(List.of(), risk.assessDeals(7, List.of()));
        assertEquals(List.of(), risk.assessDeals(7, List.of(41)));
        risk.assessDeal(7, 41);
        Deal closed = new Deal();
        closed.setId(41);
        closed.setClosedAt("2026-01-01 00:00:00");
        when(mapper.getDealById(7, 41)).thenReturn(closed);
        risk.assessDeal(7, 41);
        closed.setClosedAt(null);
        closed.setRiskExcluded(true);
        risk.assessDeal(7, 41);
        verifyNoInteractions(sharing);
    }

    @Test
    void riskWarmthBatchesAndRepeatedReadsReuseOnePhysicalLookup() {
        ScoringService scoring = mock(ScoringService.class);
        DealRiskService risk = risk(scoring);
        Deal deal = new Deal();
        deal.setId(41);
        when(mapper.getByIds(7, List.of(41))).thenReturn(List.of(deal));
        List<DealStakeholder> stakeholders = IntStream.rangeClosed(1, 1_001).mapToObj(id -> {
            DealStakeholder stakeholder = new DealStakeholder();
            stakeholder.setDealId(41);
            stakeholder.setPersonId(id);
            return stakeholder;
        }).toList();
        when(mapper.getDealStakeholdersByDealIds(7, "[7,8,9]", List.of(41))).thenReturn(stakeholders);

        scopes.withDeferredWorkspaceScopeRead(7, () -> {
            risk.assessDeals(7, List.of(41));
            return risk.assessDeals(7, List.of(41));
        });

        verify(sharing, times(1)).getForWorkspace(7);
        verify(scoring, times(4)).scoreContacts(eq(7), anySet());
        verify(mapper, times(2)).getDealStakeholdersByDealIds(7, "[7,8,9]", List.of(41));
    }

    @Test
    void deferredFailureIsMemoizedWithoutMovingIndependentHandlersOutsideTheirLoop() {
        RuntimeException failure = new IllegalStateException("Unavailable control scope");
        when(sharing.getForWorkspace(7)).thenThrow(failure);
        DealRiskService risk = risk(mock(ScoringService.class));
        Deal deal = new Deal();
        deal.setId(41);
        when(mapper.getByIds(eq(7), anyList())).thenReturn(List.of(deal));

        int handled = scopes.withDeferredWorkspaceScope(7, () -> {
            int failures = 0;
            for (int id : List.of(41, 42, 43)) {
                assertSame(failure, assertThrows(IllegalStateException.class, () -> risk.assessDeals(7, List.of(id))));
                failures++;
            }
            return failures;
        });

        assertEquals(3, handled);
        verify(sharing, times(1)).getForWorkspace(7);
        verify(mapper, never()).getDealStakeholdersByDealIds(eq(7), any(), anyList());
    }

    @Test
    void exportPipelineAndStageLoopsReuseOneScopeAfterSuccessfulStepUp() {
        var security = mock(SessionSecurityService.class);
        var pipelines = mock(ooo.klae.connex.backend.mappers.PipelineMapper.class);
        List<ooo.klae.connex.backend.beans.Pipeline> parents = IntStream.rangeClosed(21, 23).mapToObj(id -> {
            var pipeline = new ooo.klae.connex.backend.beans.Pipeline();
            pipeline.setId(id);
            pipeline.setName("Pipeline " + id);
            return pipeline;
        }).toList();
        when(pipelines.getAllPipelines(7, "[7,8,9]")).thenReturn(parents);
        var exporter = new ExportService(security, workspaces, mock(DealService.class), people,
            mock(ooo.klae.connex.backend.mappers.CompanyMapper.class),
            mock(ooo.klae.connex.backend.mappers.ProductMapper.class), pipelines, scopes,
            mock(ooo.klae.connex.backend.mappers.TagMapper.class),
            mock(ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper.class),
            mock(CustomFieldValueService.class), mock(DisqualificationReasonService.class), audit);

        exporter.exportDeals(null, null, null, null, null, null, false, null, null, null);

        var order = inOrder(security, sharing);
        order.verify(security).requireExportStepUp();
        order.verify(sharing).getForWorkspace(7);
        verify(sharing, times(1)).getForWorkspace(7);
        for (int id : List.of(21, 22, 23)) {
            verify(pipelines).getStagesByPipelineId(7, "[7,8,9]", id);
        }
    }

    private DealRiskService risk(ScoringService scoring) {
        return new DealRiskService(mapper, scopes, mock(ActivityMapper.class), mock(NoteMapper.class),
            mock(TaskMapper.class), scoring, Clock.systemUTC());
    }
}
