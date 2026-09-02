package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.dto.DealDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DuplicateCandidateRow;
import ooo.klae.connex.backend.dto.DuplicateMatchKind;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.DuplicateReviewException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;

@ExtendWith(MockitoExtension.class)
class DuplicatePreflightServiceTest {

    @Mock private IdentityMapper identityMapper;
    @Mock private DealMapper dealMapper;
    @Mock private CompanyMapper companyMapper;
    @Mock private DuplicateDecisionLockService duplicateDecisionLockService;
    @Mock private MatchingService matchingService;
    @Mock private WorkspaceService workspaceService;
    @Mock private OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;
    @Mock private DuplicatePreflightRateLimiter rateLimiter;
    @Mock private DealDuplicateReviewProofService dealReviewProofService;
    @Mock private DuplicatePreflightRateLimiter.CommitAdmission commitAdmission;

    private DuplicatePreflightService service;

    @BeforeEach
    void setUp() {
        service = new DuplicatePreflightService(
            identityMapper,
            dealMapper,
            companyMapper,
            duplicateDecisionLockService,
            matchingService,
            workspaceService,
            workspaceScopeControlAccess,
            rateLimiter,
            dealReviewProofService);
        org.mockito.Mockito.lenient()
            .when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        org.mockito.Mockito.lenient()
            .when(rateLimiter.requirePreviewAllowed(
                anyInt(), anyString(), anyString()))
            .thenReturn("d".repeat(64));
        org.mockito.Mockito.lenient()
            .when(workspaceScopeControlAccess.getForWorkspace(7))
            .thenReturn(new WorkspaceScope(3, List.of(7, 9), "[7,9]"));
        org.mockito.Mockito.lenient()
            .when(dealReviewProofService.issue(anyString(), anyString()))
            .thenReturn("e".repeat(64));
    }

    @Test
    void ranksCanonicalIdentityAheadOfExactNormalizedName() {
        when(matchingService.normalizeIdentifier(
                IdentityKind.EMAIL, "ADA@EXAMPLE.COM"))
            .thenReturn(Optional.of("ada@example.com"));
        when(matchingService.normalizeName("Ada Lovelace"))
            .thenReturn(Optional.of("ada lovelace"));
        when(matchingService.normalizeName("Ada Record"))
            .thenReturn(Optional.of("ada record"));
        when(identityMapper.findVisiblePersonIdentityMatches(
                eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(row(
                12, 7, "Ada Record", "email", "ada@example.com")));
        when(identityMapper.findVisiblePersonNameMatches(
                eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(row(
                18, 9, "Ada Lovelace", "name", "ada lovelace")));

        DuplicatePreflightResponse response = service.preflightPerson(
            new PersonDuplicatePreflightRequest(
                "Ada Lovelace",
                List.of("ADA@EXAMPLE.COM"),
                List.of()));

        assertEquals(2, response.candidates().size());
        assertEquals(12, response.candidates().getFirst().recordId());
        assertEquals(
            DuplicateMatchStrength.STRONG,
            response.candidates().getFirst().strength());
        assertEquals(
            DuplicateMatchKind.EMAIL,
            response.candidates().getFirst().matches().getFirst().kind());
        assertEquals(
            DuplicateMatchStrength.WEAK,
            response.candidates().getLast().strength());
        assertTrue(response.candidates().getFirst().ownedByActiveWorkspace());
        assertTrue(!response.candidates().getLast().ownedByActiveWorkspace());
    }

    @Test
    void identityOnlyPreflightProducesAReviewToken() {
        when(matchingService.normalizeIdentifier(
                IdentityKind.EMAIL, "probe@example.com"))
            .thenReturn(Optional.of("probe@example.com"));
        when(identityMapper.findVisiblePersonIdentityMatches(
                eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(row(
                12, 7, "Probe Record", "email", "probe@example.com")));

        DuplicatePreflightResponse response = service.preflightPerson(
            new PersonDuplicatePreflightRequest(
                null,
                List.of("probe@example.com"),
                List.of()));

        assertEquals(64, response.reviewToken().length());
        assertEquals(1, response.candidates().size());
    }

    @Test
    void businessCardReuseAcceptsTheExactOwnedStrongCandidate() {
        PersonDuplicatePreflightRequest request = new PersonDuplicatePreflightRequest(
            null, List.of("probe@example.com"), List.of());
        when(matchingService.normalizeIdentifier(
            IdentityKind.EMAIL, "probe@example.com"))
            .thenReturn(Optional.of("probe@example.com"));
        when(identityMapper.findVisiblePersonIdentityMatches(
            eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(
                row(12, 7, "Probe", "email", "probe@example.com")));
        DuplicatePreflightResponse review = service.preflightPerson(request);

        service.requireReviewedBusinessCardPersonReuse(
            request, 12, review.reviewToken());

        verify(identityMapper).lockCurrentPersonIdentityGroup(
            7, "email", "probe@example.com");
    }

    @Test
    void businessCardReuseRejectsSharedWeakAndAmbiguousCandidates() {
        PersonDuplicatePreflightRequest sharedRequest = new PersonDuplicatePreflightRequest(
            null, List.of("shared@example.com"), List.of());
        when(matchingService.normalizeIdentifier(
            IdentityKind.EMAIL, "shared@example.com"))
            .thenReturn(Optional.of("shared@example.com"));
        when(identityMapper.findVisiblePersonIdentityMatches(
            eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(
                row(12, 9, "Shared", "email", "shared@example.com")));
        DuplicatePreflightResponse sharedReview = service.preflightPerson(sharedRequest);

        assertThrows(
            ConflictException.class,
            () -> service.requireReviewedBusinessCardPersonReuse(
                sharedRequest, 12, sharedReview.reviewToken()));

        PersonDuplicatePreflightRequest weakRequest = new PersonDuplicatePreflightRequest(
            "Weak Candidate", List.of(), List.of());
        when(matchingService.normalizeName("Weak Candidate"))
            .thenReturn(Optional.of("weak candidate"));
        when(identityMapper.findVisiblePersonNameMatches(
            eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(
                row(13, 7, "Weak Candidate", "name", "weak candidate")));
        DuplicatePreflightResponse weakReview = service.preflightPerson(weakRequest);

        assertThrows(
            ConflictException.class,
            () -> service.requireReviewedBusinessCardPersonReuse(
                weakRequest, 13, weakReview.reviewToken()));

        PersonDuplicatePreflightRequest ambiguousRequest = new PersonDuplicatePreflightRequest(
            null, List.of("ambiguous@example.com"), List.of());
        when(matchingService.normalizeIdentifier(
            IdentityKind.EMAIL, "ambiguous@example.com"))
            .thenReturn(Optional.of("ambiguous@example.com"));
        when(identityMapper.findVisiblePersonIdentityMatches(
            eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(
                row(14, 7, "First", "email", "ambiguous@example.com"),
                row(15, 7, "Second", "email", "ambiguous@example.com")));
        DuplicatePreflightResponse ambiguousReview =
            service.preflightPerson(ambiguousRequest);

        assertThrows(
            ConflictException.class,
            () -> service.requireReviewedBusinessCardPersonReuse(
                ambiguousRequest, 14, ambiguousReview.reviewToken()));
    }

    @Test
    void businessCardReuseRejectsTruncatedAndStaleReviews() {
        PersonDuplicatePreflightRequest request = new PersonDuplicatePreflightRequest(
            null, List.of("crowded@example.com"), List.of());
        when(matchingService.normalizeIdentifier(
            IdentityKind.EMAIL, "crowded@example.com"))
            .thenReturn(Optional.of("crowded@example.com"));
        List<DuplicateCandidateRow> crowded = java.util.stream.IntStream.rangeClosed(1, 51)
            .mapToObj(id -> row(
                id, 7, "Candidate " + id, "email", "crowded@example.com"))
            .toList();
        when(identityMapper.findVisiblePersonIdentityMatches(
            eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(crowded);
        DuplicatePreflightResponse truncatedReview = service.preflightPerson(request);

        assertTrue(truncatedReview.truncated());
        assertThrows(
            ConflictException.class,
            () -> service.requireReviewedBusinessCardPersonReuse(
                request, 1, truncatedReview.reviewToken()));

        when(identityMapper.findVisiblePersonIdentityMatches(
            eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(
                row(1, 7, "Candidate 1", "email", "crowded@example.com")));

        assertThrows(
            ConflictException.class,
            () -> service.requireReviewedBusinessCardPersonReuse(
                request, 1, truncatedReview.reviewToken()));
    }

    @Test
    void reviewedCreationRejectsAStaleCandidateToken() {
        PersonDuplicatePreflightRequest request =
            new PersonDuplicatePreflightRequest(
                null,
                List.of("changing@example.com"),
                List.of());
        when(matchingService.normalizeIdentifier(
                IdentityKind.EMAIL, "changing@example.com"))
            .thenReturn(Optional.of("changing@example.com"));
        when(identityMapper.findVisiblePersonIdentityMatches(
                eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(
                row(12, 7, "First Candidate", "email", "changing@example.com")));

        DuplicatePreflightResponse reviewed = service.preflightPerson(request);
        when(identityMapper.findVisiblePersonIdentityMatches(
                eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(
                row(18, 7, "Changed Candidate", "email", "changing@example.com")));

        DuplicateReviewException exception = assertThrows(
            DuplicateReviewException.class,
            () -> service.requireReviewedPersonCreation(request, reviewed.reviewToken()));
        assertEquals("DUPLICATE_REVIEW_STALE", exception.getCode());
        org.mockito.InOrder locks =
            org.mockito.Mockito.inOrder(duplicateDecisionLockService, identityMapper);
        locks.verify(duplicateDecisionLockService).lockCurrentOrganization();
        locks.verify(identityMapper).lockCurrentPersonIdentityGroup(
            7, "email", "changing@example.com");
        verify(rateLimiter, org.mockito.Mockito.times(2)).requireAllowed(1);
    }

    @Test
    void reviewedCreationUsesRequiredCodeWhenCandidatesExistWithoutProof() {
        PersonDuplicatePreflightRequest request = new PersonDuplicatePreflightRequest(
            null, List.of("required@example.com"), List.of());
        when(matchingService.normalizeIdentifier(IdentityKind.EMAIL, "required@example.com"))
            .thenReturn(Optional.of("required@example.com"));
        when(identityMapper.findVisiblePersonIdentityMatches(
                eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(row(
                12, 7, "Candidate", "email", "required@example.com")));

        DuplicateReviewException exception = assertThrows(
            DuplicateReviewException.class,
            () -> service.requireReviewedPersonCreation(request, null));

        assertEquals("DUPLICATE_REVIEW_REQUIRED", exception.getCode());
    }

    @Test
    void reviewedCreationRejectsChangedDisplayedCandidateContext() {
        PersonDuplicatePreflightRequest request =
            new PersonDuplicatePreflightRequest(
                null,
                List.of("changing@example.com"),
                List.of());
        when(matchingService.normalizeIdentifier(
                IdentityKind.EMAIL, "changing@example.com"))
            .thenReturn(Optional.of("changing@example.com"));
        when(identityMapper.findVisiblePersonIdentityMatches(
                eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(
                row(12, 7, "First Candidate", "email", "changing@example.com")));

        DuplicatePreflightResponse reviewed = service.preflightPerson(request);
        when(identityMapper.findVisiblePersonIdentityMatches(
                eq(7), eq("[7,9]"), anyList(), eq(51)))
            .thenReturn(List.of(
                row(12, 7, "Renamed Candidate", "email", "changing@example.com")));

        assertThrows(
            ConflictException.class,
            () -> service.requireReviewedPersonCreation(
                request, reviewed.reviewToken()));
    }

    @Test
    void dealPreflightReturnsEveryExactNameAndCompanyCandidate() {
        DealDuplicatePreflightRequest request = new DealDuplicatePreflightRequest(
            " Renewal ",
            18,
            null);
        when(matchingService.normalizeName(" Renewal "))
            .thenReturn(Optional.of("renewal"));
        when(matchingService.normalizeName("RENEWAL"))
            .thenReturn(Optional.of("renewal"));
        when(matchingService.normalizeName("Renewal"))
            .thenReturn(Optional.of("renewal"));
        when(matchingService.normalizeName("Different"))
            .thenReturn(Optional.of("different"));
        when(dealMapper.findDuplicatePreflightCandidates(
                7, "renewal", 18, 51)).thenReturn(List.of(
            deal(13, "Renewal", 18),
            deal(12, "RENEWAL", 18),
            deal(15, "Different", 18)));
        Company company = new Company();
        company.setId(18);
        company.setName("Acme");
        when(companyMapper.getCompanyById(7, 18)).thenReturn(company);

        DuplicatePreflightResponse response = service.preflightDeal(request);

        assertEquals("deal", response.recordType());
        assertEquals(List.of(12, 13), response.candidates().stream()
            .map(candidate -> candidate.recordId()).toList());
        assertEquals("Acme", response.candidates().getFirst().companyName());
        assertEquals(
            DuplicateMatchKind.DEAL_KEY,
            response.candidates().getFirst().matches().getFirst().kind());
        assertEquals("e".repeat(64), response.reviewToken());
        InOrder order = inOrder(
            rateLimiter,
            duplicateDecisionLockService,
            dealMapper,
            dealReviewProofService);
        order.verify(rateLimiter).requireAllowed(1);
        order.verify(duplicateDecisionLockService).lockCurrentOrganization();
        order.verify(dealMapper).findDuplicatePreflightCandidates(
            7, "renewal", 18, 51);
        order.verify(dealReviewProofService).issue(anyString(), anyString());
        verify(dealMapper, never()).getDealsForDedup(7);
    }

    @Test
    void dealPreflightPreservesAStillConsumableAcknowledgedProof() {
        String acknowledgedToken = "a".repeat(64);
        DealDuplicatePreflightRequest request = new DealDuplicatePreflightRequest(
            "Renewal",
            null,
            acknowledgedToken);
        when(matchingService.normalizeName("Renewal"))
            .thenReturn(Optional.of("renewal"));
        when(dealMapper.findDuplicatePreflightCandidates(
                7, "renewal", null, 51)).thenReturn(List.of(
            deal(12, "Renewal", null)));
        when(dealReviewProofService.isConsumable(
                eq(acknowledgedToken),
                anyString(),
                anyString()))
            .thenReturn(true);

        DuplicatePreflightResponse response = service.preflightDeal(request);

        assertEquals(acknowledgedToken, response.reviewToken());
        verify(dealReviewProofService).isConsumable(
            eq(acknowledgedToken),
            anyString(),
            anyString());
        verify(dealReviewProofService, never()).issue(anyString(), anyString());
    }

    @Test
    void dealPreflightTruncationCannotAuthorizeAnAmbiguousCreate() {
        DealDuplicatePreflightRequest request = new DealDuplicatePreflightRequest(
            "Renewal",
            null,
            null);
        when(matchingService.normalizeName("Renewal"))
            .thenReturn(Optional.of("renewal"));
        when(dealMapper.findDuplicatePreflightCandidates(
                7, "renewal", null, 51)).thenReturn(
            IntStream.rangeClosed(1, 51)
                .mapToObj(id -> deal(id, "Renewal", null))
                .toList());

        DuplicatePreflightResponse response = service.preflightDeal(request);

        assertEquals(50, response.candidates().size());
        assertTrue(response.truncated());
        assertThrows(
            ConflictException.class,
            () -> service.requireReviewedDealCreation(
                request,
                response.reviewToken()));
        verify(dealReviewProofService, never()).consume(
            any(),
            anyString(),
            anyString());
    }

    @Test
    void reviewedDealCreationUsesRateLimitThenOrganizationLockThenRematch() {
        DealDuplicatePreflightRequest request = new DealDuplicatePreflightRequest(
            "Renewal",
            null,
            null);
        when(matchingService.normalizeName("Renewal"))
            .thenReturn(Optional.of("renewal"));
        when(dealMapper.findDuplicatePreflightCandidates(
                7, "renewal", null, 51)).thenReturn(List.of(
            deal(12, "Renewal", null)));
        when(dealReviewProofService.consume(
                any(),
                anyString(),
                anyString()))
            .thenReturn(true);
        clearInvocations(
            rateLimiter,
            duplicateDecisionLockService,
            dealMapper,
            dealReviewProofService);

        service.requireReviewedDealCreation(request, "e".repeat(64));

        InOrder order = inOrder(
            rateLimiter,
            duplicateDecisionLockService,
            dealMapper,
            dealReviewProofService);
        order.verify(rateLimiter).requireAllowed(1);
        order.verify(duplicateDecisionLockService).lockCurrentOrganization();
        order.verify(dealMapper).findDuplicatePreflightCandidates(
            7, "renewal", null, 51);
        order.verify(dealReviewProofService).consume(
            eq("e".repeat(64)),
            anyString(),
            anyString());
    }

    @Test
    void reviewedDealCreationAllowsEmptyCandidateSetWithoutProof() {
        DealDuplicatePreflightRequest request = new DealDuplicatePreflightRequest(
            "Unique renewal",
            null,
            null);
        when(matchingService.normalizeName("Unique renewal"))
            .thenReturn(Optional.of("unique renewal"));
        when(dealMapper.findDuplicatePreflightCandidates(
                7, "unique renewal", null, 51)).thenReturn(List.of());

        service.requireReviewedDealCreation(request, null);

        verify(dealReviewProofService, never()).consume(
            any(),
            anyString(),
            anyString());
    }

    @Test
    void reviewedDealCreationInvalidatesSubmittedProofWhenCandidatesBecomeEmpty() {
        DealDuplicatePreflightRequest request = new DealDuplicatePreflightRequest(
            "Unique renewal",
            null,
            null);
        String reviewToken = "e".repeat(64);
        when(matchingService.normalizeName("Unique renewal"))
            .thenReturn(Optional.of("unique renewal"));
        when(dealMapper.findDuplicatePreflightCandidates(
                7, "unique renewal", null, 51)).thenReturn(List.of());

        service.requireReviewedDealCreation(request, reviewToken);

        verify(dealReviewProofService).invalidateSubmitted(eq(reviewToken), anyString());
        verify(dealReviewProofService, never()).consume(
            any(),
            anyString(),
            anyString());
    }

    @Test
    void reviewedDealCreationRejectsMissingReusedOrChangedReview() {
        DealDuplicatePreflightRequest request = new DealDuplicatePreflightRequest(
            "Renewal",
            18,
            null);
        when(matchingService.normalizeName("Renewal"))
            .thenReturn(Optional.of("renewal"));
        when(dealMapper.findDuplicatePreflightCandidates(
                7, "renewal", 18, 51)).thenReturn(List.of(
            deal(12, "Renewal", 18)));
        when(dealReviewProofService.consume(
                any(),
                anyString(),
                anyString()))
            .thenReturn(false);

        assertThrows(
            ConflictException.class,
            () -> service.requireReviewedDealCreation(request, null));
        assertThrows(
            ConflictException.class,
            () -> service.requireReviewedDealCreation(
                request,
                "e".repeat(64)));
    }

    @Test
    void previewAndCommitUseTheSamePiiFreeWorkflowFingerprint() {
        when(matchingService.normalizeName("Probe"))
            .thenReturn(Optional.of("probe"));
        when(identityMapper.findVisiblePersonNameMatches(
                eq(7), eq("[7,9]"), anyList(), anyInt()))
            .thenReturn(List.of());
        List<PersonDuplicatePreflightRequest> requests = List.of(
            new PersonDuplicatePreflightRequest(
                "Probe", List.of(), List.of()));

        var preview =
            service.preflightPersonImportPreview(requests, "a".repeat(64));

        ArgumentCaptor<String> previewFingerprint =
            ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> commitFingerprint =
            ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resultFingerprint =
            ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).requirePreviewAllowed(
            eq(1), previewFingerprint.capture(), eq("a".repeat(64)));
        verify(rateLimiter).recordPreviewResult(
            eq(previewFingerprint.getValue()),
            eq(preview.reviewProof()),
            resultFingerprint.capture());
        when(rateLimiter.claimCommitAllowed(
                preview.reviewProof(), "a".repeat(64)))
            .thenReturn(commitAdmission);
        when(rateLimiter.requireCommitAllowed(
                anyString(), eq(commitAdmission)))
            .thenReturn(resultFingerprint.getValue());
        DuplicatePreflightService.ImportCommitAdmission admission =
            service.claimImportCommit(
                preview.reviewProof(), "a".repeat(64));

        service.preflightPersonImportCommit(
            requests, "a".repeat(64), admission);

        verify(rateLimiter).requireCommitAllowed(
            commitFingerprint.capture(), eq(commitAdmission));
        assertEquals(
            previewFingerprint.getValue(),
            commitFingerprint.getValue());
        assertEquals(64, previewFingerprint.getValue().length());
        assertTrue(!previewFingerprint.getValue().contains("probe"));
    }

    @Test
    void importWorkflowFingerprintBindsTheCompleteReviewContext() {
        when(matchingService.normalizeName("Probe"))
            .thenReturn(Optional.of("probe"));
        when(identityMapper.findVisiblePersonNameMatches(
                eq(7), eq("[7,9]"), anyList(), anyInt()))
            .thenReturn(List.of());
        List<PersonDuplicatePreflightRequest> requests = List.of(
            new PersonDuplicatePreflightRequest(
                "Probe", List.of(), List.of()));

        var preview =
            service.preflightPersonImportPreview(requests, "a".repeat(64));

        ArgumentCaptor<String> previewFingerprint =
            ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> commitFingerprint =
            ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resultFingerprint =
            ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).requirePreviewAllowed(
            eq(1), previewFingerprint.capture(), eq("a".repeat(64)));
        verify(rateLimiter).recordPreviewResult(
            eq(previewFingerprint.getValue()),
            eq(preview.reviewProof()),
            resultFingerprint.capture());
        when(rateLimiter.claimCommitAllowed(
                preview.reviewProof(), "b".repeat(64)))
            .thenReturn(commitAdmission);
        when(rateLimiter.requireCommitAllowed(
                anyString(), eq(commitAdmission)))
            .thenReturn(resultFingerprint.getValue());
        DuplicatePreflightService.ImportCommitAdmission admission =
            service.claimImportCommit(
                preview.reviewProof(), "b".repeat(64));

        service.preflightPersonImportCommit(
            requests, "b".repeat(64), admission);

        verify(rateLimiter).requireCommitAllowed(
            commitFingerprint.capture(), eq(commitAdmission));
        assertTrue(!previewFingerprint.getValue().equals(
            commitFingerprint.getValue()));
    }

    @Test
    void importCommitRejectsCandidatesWithoutMatchingPreviewResult() {
        assertThrows(
            ConflictException.class,
            () -> service.claimImportCommit(null, "a".repeat(64)));
    }

    @Test
    void importCommitAcceptsTheExactReviewedCandidateResult() {
        when(matchingService.normalizeIdentifier(
                IdentityKind.EMAIL, "probe@example.com"))
            .thenReturn(Optional.of("probe@example.com"));
        when(identityMapper.findVisiblePersonIdentityMatches(
                eq(7), eq("[7,9]"), anyList(), eq(9)))
            .thenReturn(List.of(
                row(12, 7, "Probe", "email", "probe@example.com")));
        PersonDuplicatePreflightRequest request =
            new PersonDuplicatePreflightRequest(
                null,
                List.of("probe@example.com"),
                List.of());

        var preview = service.preflightPersonImportPreview(
            List.of(request), "a".repeat(64));
        ArgumentCaptor<String> resultFingerprint =
            ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).recordPreviewResult(
            anyString(), eq(preview.reviewProof()), resultFingerprint.capture());
        when(rateLimiter.claimCommitAllowed(
                preview.reviewProof(), "a".repeat(64)))
            .thenReturn(commitAdmission);
        when(rateLimiter.requireCommitAllowed(
                anyString(), eq(commitAdmission)))
            .thenReturn(resultFingerprint.getValue());
        DuplicatePreflightService.ImportCommitAdmission admission =
            service.claimImportCommit(
                preview.reviewProof(), "a".repeat(64));

        DuplicatePreflightResponse committed =
            service.preflightPersonImportCommit(
                List.of(request), "a".repeat(64), admission).getFirst();

        assertEquals(12, committed.candidates().getFirst().recordId());
    }

    @Test
    void importCommitRejectsAChangedDeferredDecisionFingerprint() {
        when(matchingService.normalizeName("Probe"))
            .thenReturn(Optional.of("probe"));
        when(identityMapper.findVisiblePersonNameMatches(
                eq(7), eq("[7,9]"), anyList(), eq(9)))
            .thenReturn(List.of());
        List<PersonDuplicatePreflightRequest> requests = List.of(
            new PersonDuplicatePreflightRequest(
                "Probe", List.of(), List.of()));

        DuplicatePreflightService.ImportPreviewSession preview =
            service.beginImportPreview(
                requests, List.of(), "a".repeat(64));
        service.completeImportPreview(preview, "e".repeat(64));
        ArgumentCaptor<String> resultFingerprint =
            ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).recordPreviewResult(
            anyString(), eq(preview.reviewProof()), resultFingerprint.capture());
        when(rateLimiter.claimCommitAllowed(
                preview.reviewProof(), "a".repeat(64)))
            .thenReturn(commitAdmission);
        when(rateLimiter.requireCommitAllowed(
                anyString(), eq(commitAdmission)))
            .thenReturn(resultFingerprint.getValue());
        DuplicatePreflightService.ImportCommitAdmission admission =
            service.claimImportCommit(
                preview.reviewProof(), "a".repeat(64));

        DuplicatePreflightService.ImportCommitSession commit =
            service.beginImportCommit(
                requests, List.of(), "a".repeat(64), admission);

        assertThrows(
            ConflictException.class,
            () -> service.completeImportCommit(
                commit, "f".repeat(64)));
    }

    @Test
    void importCommitRejectsWhenAReviewedCandidateDisappears() {
        when(matchingService.normalizeIdentifier(
                IdentityKind.EMAIL, "probe@example.com"))
            .thenReturn(Optional.of("probe@example.com"));
        when(identityMapper.findVisiblePersonIdentityMatches(
                eq(7), eq("[7,9]"), anyList(), eq(9)))
            .thenReturn(List.of(
                row(12, 7, "Probe", "email", "probe@example.com")));
        PersonDuplicatePreflightRequest request =
            new PersonDuplicatePreflightRequest(
                null,
                List.of("probe@example.com"),
                List.of());

        var preview = service.preflightPersonImportPreview(
            List.of(request), "a".repeat(64));
        ArgumentCaptor<String> resultFingerprint =
            ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).recordPreviewResult(
            anyString(), eq(preview.reviewProof()), resultFingerprint.capture());
        when(rateLimiter.claimCommitAllowed(
                preview.reviewProof(), "a".repeat(64)))
            .thenReturn(commitAdmission);
        when(rateLimiter.requireCommitAllowed(
                anyString(), eq(commitAdmission)))
            .thenReturn(resultFingerprint.getValue());
        DuplicatePreflightService.ImportCommitAdmission admission =
            service.claimImportCommit(
                preview.reviewProof(), "a".repeat(64));
        when(identityMapper.findVisiblePersonIdentityMatches(
                eq(7), eq("[7,9]"), anyList(), eq(9)))
            .thenReturn(List.of());

        assertThrows(
            ConflictException.class,
            () -> service.preflightPersonImportCommit(
                List.of(request), "a".repeat(64), admission));
    }

    @Test
    void emptyImportSessionsSkipTheWorkspaceScopeRead() {
        DuplicatePreflightService.ImportPreviewSession preview =
            service.beginImportPreview(List.of(), List.of(), "a".repeat(64));
        service.completeImportPreview(preview, "e".repeat(64));

        assertEquals("d".repeat(64), preview.reviewProof());
        assertTrue(preview.responses().isEmpty());
        verify(workspaceScopeControlAccess, never()).getForWorkspace(anyInt());
        verify(identityMapper, never()).findVisiblePersonIdentityMatches(
            anyInt(), anyString(), anyList(), anyInt());
        verify(identityMapper, never()).findVisibleCompanyIdentityMatches(
            anyInt(), anyString(), anyList(), anyInt());
        verify(rateLimiter).recordPreviewResult(
            anyString(), eq(preview.reviewProof()), anyString());
    }

    @Test
    void rejectsAnImportWhoseBoundedRowsStillExceedTheAggregateCandidateCap() {
        when(matchingService.normalizeName("Probe"))
            .thenReturn(Optional.of("probe"));
        List<DuplicateCandidateRow> rows = java.util.stream.IntStream.range(0, 9)
            .mapToObj(index -> row(
                index + 1, 7, "Probe", "name", "probe"))
            .toList();
        when(identityMapper.findVisiblePersonNameMatches(
                eq(7), eq("[7,9]"), anyList(), eq(9)))
            .thenReturn(rows);
        PersonDuplicatePreflightRequest request =
            new PersonDuplicatePreflightRequest(
                "Probe", List.of(), List.of());

        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> service.preflightPersonImportPreview(
                Collections.nCopies(126, request),
                "a".repeat(64)));

        assertTrue(exception.getMessage().contains("split the import"));
        verify(rateLimiter).cancelPreview(anyString(), eq("d".repeat(64)));
    }

    @Test
    void rejectsInvalidAndOversizedProbesBeforeQueries() {
        when(matchingService.normalizeName(" "))
            .thenReturn(Optional.empty());
        when(matchingService.normalizeIdentifier(
                IdentityKind.EMAIL, "not-email"))
            .thenReturn(Optional.empty());
        when(matchingService.normalizeIdentifier(
                IdentityKind.PHONE, "invalid"))
            .thenReturn(Optional.empty());
        assertThrows(
            BadRequestException.class,
            () -> service.preflightPerson(
                new PersonDuplicatePreflightRequest(
                    " ",
                    List.of("not-email"),
                    List.of("invalid"))));

        PersonDuplicatePreflightRequest row =
            new PersonDuplicatePreflightRequest(
                "Bounded row", List.of(), List.of());
        assertThrows(
            BadRequestException.class,
            () -> service.preflightPersonImportPreview(
                Collections.nCopies(5_001, row),
                "a".repeat(64)));
    }

    private static Deal deal(int id, String name, Integer companyId) {
        Deal deal = new Deal();
        deal.setId(id);
        deal.setName(name);
        deal.setCompanyId(companyId);
        return deal;
    }

    private static DuplicateCandidateRow row(
            int recordId,
            int workspaceId,
            String name,
            String kind,
            String normalizedValue) {
        DuplicateCandidateRow row = new DuplicateCandidateRow();
        row.setRecordId(recordId);
        row.setRecordWorkspaceId(workspaceId);
        row.setName(name);
        row.setKind(kind);
        row.setNormalizedValue(normalizedValue);
        return row;
    }
}
