package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RecordCommentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.support.RecordVisibilityOperationFixture;

/** Keeps optional relationship work behind its existing guards at each enclosing read entry. */
@ExtendWith(MockitoExtension.class)
class RecordVisibilityEnclosingReadScopeTest {
    @Mock private WorkspaceService workspaceService;
    @Mock private PersonMapper personMapper;
    @Mock private DealMapper dealMapper;
    @Mock private CompanyMapper companyMapper;
    @Mock private TagMapper tagMapper;
    @Mock private ActivityMapper activityMapper;
    @Mock private NoteMapper noteMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private RecordCommentMapper recordCommentMapper;
    @Mock private ReferenceService referenceService;
    @Mock private MatchingService matchingService;
    @Mock private DuplicatePreflightRateLimiter rateLimiter;
    @InjectMocks private DealService deals;
    @InjectMocks private PersonService people;
    @InjectMocks private TagService tags;
    @InjectMocks private RecordCommentService comments;
    @InjectMocks private DuplicatePreflightService duplicatePreflight;
    private RecordVisibilityOperationFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new RecordVisibilityOperationFixture(7);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        org.mockito.Mockito.lenient().when(fixture.control.findOrganizationWorkspacesForShare(7))
            .thenThrow(new DataAccessResourceFailureException("control unavailable"));
        for (Object service : List.of(deals, people, tags, comments, duplicatePreflight)) {
            ReflectionTestUtils.setField(service, "recordVisibilityScopeControlAccess", fixture.scopes);
        }
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void emptyTopDealsSkipTheScope() {
        var result = deals.getTopDeals("USD", null);

        assertEquals(List.of(), result.topOpen());
        assertEquals(List.of(), result.topWon());
        verifyNoInteractions(fixture.control, companyMapper);
    }

    @Test
    void summariesWithoutScopedLabelsSkipTheScope() {
        Deal deal = new Deal();
        deal.setId(41);
        when(dealMapper.getDealById(7, 41)).thenReturn(deal);

        assertEquals(41, deals.getDealSummary(41).getId());
        verifyNoInteractions(fixture.control, companyMapper);
    }

    @Test
    void missingDealRefusesBeforeOptionalLabelScope() {
        assertThrows(ResourceNotFoundException.class, () -> deals.getDealSummary(41));
        verifyNoInteractions(fixture.control, companyMapper);
    }

    @Test
    void personDetailSharesOneScopeAcrossPersonAndDealQueries() {
        Person person = new Person();
        person.setId(41);
        when(personMapper.getPersonById(7, "[7,17]", 41)).thenReturn(person);
        try (var detailFixture = new RecordVisibilityOperationFixture(7)) {
            ReflectionTestUtils.setField(people, "recordVisibilityScopeControlAccess", detailFixture.scopes);
            when(referenceService.hydrateDeals(7, List.of())).thenAnswer(invocation -> {
                assertEquals("[7,17]", detailFixture.scopes.getForWorkspace(7).workspaceIdsJson());
                return List.of();
            });

            assertSame(person, people.getPersonById(41));
            assertEquals(0, person.getDeals().length);

            var order = inOrder(personMapper, detailFixture.control, dealMapper);
            order.verify(detailFixture.control).findOrganizationWorkspacesForShare(7);
            order.verify(personMapper).getPersonById(7, "[7,17]", 41);
            order.verify(dealMapper).getDealsByPersonId(7, "[7,17]", 41);
            verifyNoMoreInteractions(detailFixture.control, dealMapper);
            verifyNoInteractions(fixture.control, companyMapper);
        }
    }

    @Test
    void personDetailScopeFailureEscapesBeforeTheDealQuery() {
        var failure = new DataAccessResourceFailureException("control unavailable");
        doThrow(failure).when(fixture.control).findOrganizationWorkspacesForShare(7);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> people.getPersonById(41)));

        verify(fixture.control).findOrganizationWorkspacesForShare(7);
        verifyNoMoreInteractions(fixture.control);
        verifyNoInteractions(personMapper, dealMapper, companyMapper, referenceService);
    }

    @Test
    void missingPersonRefusesBeforeOptionalRelationshipQueries() {
        try (var detailFixture = new RecordVisibilityOperationFixture(7)) {
            ReflectionTestUtils.setField(people, "recordVisibilityScopeControlAccess", detailFixture.scopes);
            assertThrows(ResourceNotFoundException.class, () -> people.getPersonById(41));
            verify(detailFixture.control).findOrganizationWorkspacesForShare(7);
            verifyNoInteractions(dealMapper, companyMapper, referenceService);
        }
    }

    @Test
    void tagWithoutDealsSkipsTheScope() {
        when(tagMapper.getTagById(7, 41)).thenReturn(new Tag());

        assertEquals(List.of(), tags.getDealsByTagId(41));
        verifyNoInteractions(fixture.control, companyMapper);
    }

    @Test
    void missingTagRefusesBeforeCollectionScope() {
        assertThrows(ResourceNotFoundException.class, () -> tags.getTagById(41));
        assertThrows(ResourceNotFoundException.class, () -> tags.getCompaniesByTagId(41));
        verifyNoInteractions(fixture.control, companyMapper);
    }

    @Test
    void personCommentReadsShareTheirRequiredScopeAndSkipCompanyQueries() {
        try (var commentFixture = new RecordVisibilityOperationFixture(7)) {
            ReflectionTestUtils.setField(comments, "recordVisibilityScopeControlAccess", commentFixture.scopes);
            when(personMapper.exists(7, "[7,17]", 41)).thenReturn(true);
            assertEquals(List.of(), comments.listThreads("person", 41, "all", 25, 0));
            assertEquals(0, comments.countThreads("person", 41, "all"));
            assertEquals(List.of(), comments.getIndicators("person", List.of(41)));
            verify(commentFixture.control, org.mockito.Mockito.times(3)).findOrganizationWorkspacesForShare(7);
            verifyNoInteractions(fixture.control, companyMapper);
        }
    }

    @Test
    void dealCommentReadsWithEmptyProjectionsSkipTheScope() {
        when(dealMapper.exists(7, 41)).thenReturn(true);
        assertEquals(List.of(), comments.listThreads("deal", 41, "all", 25, 0));
        assertEquals(0, comments.countThreads("deal", 41, "all"));
        assertEquals(List.of(), comments.getIndicators("deal", List.of(41)));
        verifyNoInteractions(fixture.control, personMapper, companyMapper);
    }

    @Test
    void advisoryDealDuplicateCheckWithoutACompanySkipsTheScope() {
        when(matchingService.normalizeName("Forecast")).thenReturn(java.util.Optional.of("forecast"));

        assertFalse(duplicatePreflight.dealCandidatesExist(
            new ooo.klae.connex.backend.dto.DealDuplicatePreflightRequest("Forecast", null, null)));

        verifyNoInteractions(fixture.control, companyMapper);
    }
}
