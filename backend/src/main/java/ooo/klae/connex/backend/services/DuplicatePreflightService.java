package ooo.klae.connex.backend.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.CompanyDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DuplicateCandidateDto;
import ooo.klae.connex.backend.dto.DuplicateCandidateRow;
import ooo.klae.connex.backend.dto.DuplicateIdentityKey;
import ooo.klae.connex.backend.dto.DuplicateImportPreflightReview;
import ooo.klae.connex.backend.dto.DuplicateMatchEvidenceDto;
import ooo.klae.connex.backend.dto.DuplicateMatchKind;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicateNameKey;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.util.LikePattern;

/**
 * Ranked, visibility-safe duplicate checks over canonical identities and exact names.
 *
 * <p>Email, phone, and domain evidence is {@link DuplicateMatchStrength#STRONG}. Name evidence is
 * {@link DuplicateMatchStrength#WEAK} and is accepted only when
 * {@link MatchingService#normalizeName(String)} is exactly equal. No fuzzy or edit-distance
 * matching is performed. Persistence applies owned-or-shared visibility before bounded
 * candidate selection.
 */
@Service
@RequiredArgsConstructor
public class DuplicatePreflightService {

    private static final int MAX_IDENTITY_VALUES = 16;
    private static final int PUBLIC_CANDIDATE_LIMIT = 50;
    private static final int IMPORT_CANDIDATE_LIMIT = 8;
    private static final int IMPORT_AGGREGATE_CANDIDATE_LIMIT = 1_000;
    private static final int IMPORT_REQUEST_LIMIT = 5_000;
    private static final int REQUEST_CHUNK_SIZE = 100;
    private static final int IDENTITY_KEY_CHUNK_SIZE = 200;
    private static final int NAME_KEY_CHUNK_SIZE = 100;
    private static final int LOOKUPS_PER_WORK_UNIT = 250;

    private final IdentityMapper identityMapper;
    private final DuplicateDecisionLockService duplicateDecisionLockService;
    private final MatchingService matchingService;
    private final WorkspaceService workspaceService;
    private final OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;
    private final DuplicatePreflightRateLimiter rateLimiter;

    /**
     * Checks one proposed person using {@code PERSON_CREATE}.
     *
     * @param request bounded candidate values
     * @return ranked visible candidates
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.PERSON_CREATE)
    public DuplicatePreflightResponse preflightPerson(PersonDuplicatePreflightRequest request) {
        return matchPersons(
            List.of(Objects.requireNonNull(request, "request")),
            PUBLIC_CANDIDATE_LIMIT,
            Admission.INTERACTIVE,
            null,
            null).responses().getFirst();
    }

    /**
     * Checks one proposed company using {@code COMPANY_CREATE}.
     *
     * @param request bounded candidate values
     * @return ranked visible candidates
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.COMPANY_CREATE)
    public DuplicatePreflightResponse preflightCompany(CompanyDuplicatePreflightRequest request) {
        return matchCompanies(
            List.of(Objects.requireNonNull(request, "request")),
            PUBLIC_CANDIDATE_LIMIT,
            Admission.INTERACTIVE,
            null,
            null).responses().getFirst();
    }

    /**
     * Rechecks a reviewed person immediately before interactive creation.
     *
     * @param request exact values about to be created
     * @param duplicateReviewToken token from the explicitly accepted duplicate review
     */
    public void requireReviewedPersonCreation(
            PersonDuplicatePreflightRequest request,
            String duplicateReviewToken) {
        NormalizedRequest normalized =
            normalizePerson(Objects.requireNonNull(request, "request"));
        rateLimiter.requireAllowed(workUnits(List.of(normalized)));
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        duplicateDecisionLockService.lockCurrentOrganization();
        lockIdentityGroups(
            workspaceId,
            normalized.identityKeys(),
            identityMapper::lockCurrentPersonIdentityGroup);
        DuplicatePreflightResponse response = match(
            "person",
            List.of(normalized),
            PUBLIC_CANDIDATE_LIMIT,
            false,
            identityMapper::findVisiblePersonIdentityMatches,
            identityMapper::findVisiblePersonNameMatches).getFirst();
        requireReviewed(response, duplicateReviewToken);
    }

    /**
     * Rechecks that a business-card import still resolves to one exact owned strong match.
     *
     * @param request exact reviewed card values
     * @param personId selected existing contact
     * @param duplicateReviewToken token from the exact accepted duplicate review
     */
    void requireReviewedBusinessCardPersonReuse(
            PersonDuplicatePreflightRequest request,
            int personId,
            String duplicateReviewToken) {
        NormalizedRequest normalized =
            normalizePerson(Objects.requireNonNull(request, "request"));
        rateLimiter.requireAllowed(workUnits(List.of(normalized)));
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        duplicateDecisionLockService.lockCurrentOrganization();
        lockIdentityGroups(
            workspaceId,
            normalized.identityKeys(),
            identityMapper::lockCurrentPersonIdentityGroup);
        DuplicatePreflightResponse response = match(
            "person",
            List.of(normalized),
            PUBLIC_CANDIDATE_LIMIT,
            false,
            identityMapper::findVisiblePersonIdentityMatches,
            identityMapper::findVisiblePersonNameMatches).getFirst();
        if (!isExactOwnedStrongPerson(response, personId)
                || !Objects.equals(response.reviewToken(), duplicateReviewToken)) {
            throw businessCardReuseConflict();
        }
    }

    /**
     * Rechecks a reviewed company immediately before interactive creation.
     *
     * @param request exact values about to be created
     * @param duplicateReviewToken token from the explicitly accepted duplicate review
     */
    public void requireReviewedCompanyCreation(
            CompanyDuplicatePreflightRequest request,
            String duplicateReviewToken) {
        NormalizedRequest normalized =
            normalizeCompany(Objects.requireNonNull(request, "request"));
        rateLimiter.requireAllowed(workUnits(List.of(normalized)));
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        duplicateDecisionLockService.lockCurrentOrganization();
        lockIdentityGroups(
            workspaceId,
            normalized.identityKeys(),
            identityMapper::lockCurrentCompanyIdentityGroup);
        DuplicatePreflightResponse response = match(
            "company",
            List.of(normalized),
            PUBLIC_CANDIDATE_LIMIT,
            false,
            identityMapper::findVisibleCompanyIdentityMatches,
            identityMapper::findVisibleCompanyNameMatches).getFirst();
        requireReviewed(response, duplicateReviewToken);
    }

    /**
     * Applies the person matcher to a bounded CSV preview.
     *
     * @param requests proposed rows in source order
     * @param reviewContext server-derived fingerprint of the complete import request
     * @return bounded results plus their one-use rendered-review proof
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.PERSON_CREATE)
    public DuplicateImportPreflightReview preflightPersonImportPreview(
            List<PersonDuplicatePreflightRequest> requests,
            String reviewContext) {
        MatchOutcome outcome = matchPersons(
            boundedImportRequests(requests),
            IMPORT_CANDIDATE_LIMIT,
            Admission.PREVIEW,
            requireReviewContext(reviewContext),
            null);
        return new DuplicateImportPreflightReview(
            outcome.responses(),
            Objects.requireNonNull(outcome.reviewProof()));
    }

    /**
     * Applies the person matcher to a bounded CSV commit.
     *
     * @param requests proposed rows in source order
     * @param reviewContext server-derived fingerprint of the complete import request
     * @param reviewProof one-use proof returned by the exact preview being committed
     * @return one bounded result per row
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.PERSON_CREATE)
    public List<DuplicatePreflightResponse> preflightPersonImportCommit(
            List<PersonDuplicatePreflightRequest> requests,
            String reviewContext,
            ImportCommitAdmission admission) {
        return matchPersons(
            boundedImportRequests(requests),
            IMPORT_CANDIDATE_LIMIT,
            Admission.COMMIT,
            requireReviewContext(reviewContext),
            admission).responses();
    }

    /**
     * Applies the company matcher to a bounded CSV preview.
     *
     * @param requests proposed rows in source order
     * @param reviewContext server-derived fingerprint of the complete import request
     * @return bounded results plus their one-use rendered-review proof
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.COMPANY_CREATE)
    public DuplicateImportPreflightReview preflightCompanyImportPreview(
            List<CompanyDuplicatePreflightRequest> requests,
            String reviewContext) {
        MatchOutcome outcome = matchCompanies(
            boundedImportRequests(requests),
            IMPORT_CANDIDATE_LIMIT,
            Admission.PREVIEW,
            requireReviewContext(reviewContext),
            null);
        return new DuplicateImportPreflightReview(
            outcome.responses(),
            Objects.requireNonNull(outcome.reviewProof()));
    }

    /**
     * Applies the company matcher to a bounded CSV commit.
     *
     * @param requests proposed rows in source order
     * @param reviewContext server-derived fingerprint of the complete import request
     * @param reviewProof one-use proof returned by the exact preview being committed
     * @return one bounded result per row
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.COMPANY_CREATE)
    public List<DuplicatePreflightResponse> preflightCompanyImportCommit(
            List<CompanyDuplicatePreflightRequest> requests,
            String reviewContext,
            ImportCommitAdmission admission) {
        return matchCompanies(
            boundedImportRequests(requests),
            IMPORT_CANDIDATE_LIMIT,
            Admission.COMMIT,
            requireReviewContext(reviewContext),
            admission).responses();
    }

    DuplicateImportPreflightReview preflightImportPreview(
            List<PersonDuplicatePreflightRequest> personRequests,
            List<CompanyDuplicatePreflightRequest> companyRequests,
            String reviewContext) {
        ImportPreviewSession session = beginImportPreview(
            personRequests,
            companyRequests,
            reviewContext);
        completeImportPreview(session, null);
        return new DuplicateImportPreflightReview(
            session.responses(),
            session.reviewProof());
    }

    ImportPreviewSession beginImportPreview(
            List<PersonDuplicatePreflightRequest> personRequests,
            List<CompanyDuplicatePreflightRequest> companyRequests,
            String reviewContext) {
        PendingImportMatch pending = beginMatchImport(
            boundedImportRequests(personRequests),
            boundedImportRequests(companyRequests),
            Admission.PREVIEW,
            requireReviewContext(reviewContext),
            null);
        return new ImportPreviewSession(pending);
    }

    List<DuplicatePreflightResponse> preflightImportCommit(
            List<PersonDuplicatePreflightRequest> personRequests,
            List<CompanyDuplicatePreflightRequest> companyRequests,
            String reviewContext,
            ImportCommitAdmission admission) {
        ImportCommitSession session = beginImportCommit(
            personRequests,
            companyRequests,
            reviewContext,
            admission);
        completeImportCommit(session, null);
        return session.responses();
    }

    ImportCommitSession beginImportCommit(
            List<PersonDuplicatePreflightRequest> personRequests,
            List<CompanyDuplicatePreflightRequest> companyRequests,
            String reviewContext,
            ImportCommitAdmission admission) {
        return new ImportCommitSession(beginMatchImport(
            boundedImportRequests(personRequests),
            boundedImportRequests(companyRequests),
            Admission.COMMIT,
            requireReviewContext(reviewContext),
            admission));
    }

    void completeImportPreview(
            ImportPreviewSession session,
            String decisionFingerprint) {
        Objects.requireNonNull(session, "preview session").complete(decisionFingerprint);
    }

    void completeImportCommit(
            ImportCommitSession session,
            String decisionFingerprint) {
        Objects.requireNonNull(session, "commit session").complete(decisionFingerprint);
    }

    /**
     * Claims a one-use import review proof before the organization-wide mutation lock is acquired.
     *
     * @param reviewProof proof returned by the exact rendered preview
     * @param reviewContext server-derived fingerprint of the complete import request
     * @return opaque admission for commit-time workflow and result validation
     */
    public ImportCommitAdmission claimImportCommit(
            String reviewProof,
            String reviewContext) {
        DuplicatePreflightRateLimiter.CommitAdmission admission =
            rateLimiter.claimCommitAllowed(
                reviewProof,
                requireReviewContext(reviewContext));
        if (admission == null) {
            throw new ConflictException(
                "Import review is missing or expired; preview the import again");
        }
        releaseClaimAfterTransaction(admission);
        return new ImportCommitAdmission(admission);
    }

    private void releaseClaimAfterTransaction(
            DuplicatePreflightRateLimiter.CommitAdmission admission) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    rateLimiter.releaseCommitAdmission(admission);
                }
            });
    }

    void cancelImportPreview(String reviewProof) {
        if (reviewProof != null) {
            rateLimiter.cancelPreview(reviewProof);
        }
    }

    private MatchOutcome matchPersons(
            List<PersonDuplicatePreflightRequest> requests,
            int candidateLimit,
            Admission admission,
            String reviewContext,
            ImportCommitAdmission commitAdmission) {
        List<NormalizedRequest> normalized = requests.stream()
            .map(this::normalizePerson)
            .toList();
        AdmissionContext admissionContext =
            admit("person", normalized, admission, reviewContext, commitAdmission);
        try {
            List<DuplicatePreflightResponse> responses = match(
                "person",
                normalized,
                candidateLimit,
                admission != Admission.INTERACTIVE,
                identityMapper::findVisiblePersonIdentityMatches,
                identityMapper::findVisiblePersonNameMatches);
            completeAdmission(admissionContext, responses);
            return new MatchOutcome(responses, admissionContext.reviewProof());
        } catch (RuntimeException exception) {
            cancelFailedPreview(admissionContext);
            throw exception;
        }
    }

    private MatchOutcome matchCompanies(
            List<CompanyDuplicatePreflightRequest> requests,
            int candidateLimit,
            Admission admission,
            String reviewContext,
            ImportCommitAdmission commitAdmission) {
        List<NormalizedRequest> normalized = requests.stream()
            .map(this::normalizeCompany)
            .toList();
        AdmissionContext admissionContext =
            admit("company", normalized, admission, reviewContext, commitAdmission);
        try {
            List<DuplicatePreflightResponse> responses = match(
                "company",
                normalized,
                candidateLimit,
                admission != Admission.INTERACTIVE,
                identityMapper::findVisibleCompanyIdentityMatches,
                identityMapper::findVisibleCompanyNameMatches);
            completeAdmission(admissionContext, responses);
            return new MatchOutcome(responses, admissionContext.reviewProof());
        } catch (RuntimeException exception) {
            cancelFailedPreview(admissionContext);
            throw exception;
        }
    }

    private PendingImportMatch beginMatchImport(
            List<PersonDuplicatePreflightRequest> personRequests,
            List<CompanyDuplicatePreflightRequest> companyRequests,
            Admission admission,
            String reviewContext,
            ImportCommitAdmission commitAdmission) {
        List<NormalizedRequest> normalizedPersons = personRequests.stream()
            .map(this::normalizePerson)
            .toList();
        List<NormalizedRequest> normalizedCompanies = companyRequests.stream()
            .map(this::normalizeCompany)
            .toList();
        List<NormalizedRequest> combined = new ArrayList<>(
            normalizedPersons.size() + normalizedCompanies.size());
        combined.addAll(normalizedPersons);
        combined.addAll(normalizedCompanies);
        String workflowFingerprint = combinedWorkflowFingerprint(
            normalizedPersons,
            normalizedCompanies,
            reviewContext);
        AdmissionContext admissionContext = admitFingerprint(
            workUnits(combined),
            workflowFingerprint,
            admission,
            reviewContext,
            commitAdmission);
        try {
            List<DuplicatePreflightResponse> responses = new ArrayList<>(combined.size());
            responses.addAll(match(
                "person",
                normalizedPersons,
                IMPORT_CANDIDATE_LIMIT,
                false,
                identityMapper::findVisiblePersonIdentityMatches,
                identityMapper::findVisiblePersonNameMatches));
            responses.addAll(match(
                "company",
                normalizedCompanies,
                IMPORT_CANDIDATE_LIMIT,
                false,
                identityMapper::findVisibleCompanyIdentityMatches,
                identityMapper::findVisibleCompanyNameMatches));
            int aggregateCandidates = responses.stream()
                .mapToInt(response -> response.candidates().size())
                .sum();
            if (aggregateCandidates > IMPORT_AGGREGATE_CANDIDATE_LIMIT) {
                throw new BadRequestException(
                    "Duplicate review exceeds 1000 candidates; split the import");
            }
            List<DuplicatePreflightResponse> immutable = List.copyOf(responses);
            return new PendingImportMatch(immutable, admissionContext);
        } catch (RuntimeException exception) {
            cancelFailedPreview(admissionContext);
            throw exception;
        }
    }

    private static void requireReviewed(
            DuplicatePreflightResponse response,
            String duplicateReviewToken) {
        if (response.truncated()) {
            throw new ConflictException(
                "Duplicate candidates changed before creation; review them again");
        }
        if (!response.candidates().isEmpty()
                && !Objects.equals(response.reviewToken(), duplicateReviewToken)) {
            throw new ConflictException(
                "Possible duplicates must be reviewed before creation");
        }
    }

    private static boolean isExactOwnedStrongPerson(
            DuplicatePreflightResponse response,
            int personId) {
        if (response.truncated() || response.candidates().size() != 1) {
            return false;
        }
        DuplicateCandidateDto candidate = response.candidates().getFirst();
        return candidate.recordId() == personId
            && candidate.ownedByActiveWorkspace()
            && candidate.strength() == DuplicateMatchStrength.STRONG;
    }

    private static ConflictException businessCardReuseConflict() {
        return new ConflictException(
            "Existing contact is no longer eligible for business-card reuse; review duplicates again");
    }

    private AdmissionContext admit(
            String recordType,
            List<NormalizedRequest> requests,
            Admission admission,
            String reviewContext,
            ImportCommitAdmission commitAdmission) {
        int workUnits = workUnits(requests);
        if (admission == Admission.INTERACTIVE) {
            rateLimiter.requireAllowed(workUnits);
            return new AdmissionContext(admission, null, null, null);
        }
        String workflowFingerprint =
            workflowFingerprint(recordType, requests, reviewContext);
        return admitFingerprint(
            workUnits,
            workflowFingerprint,
            admission,
            reviewContext,
            commitAdmission);
    }

    private AdmissionContext admitFingerprint(
            int workUnits,
            String workflowFingerprint,
            Admission admission,
            String reviewContext,
            ImportCommitAdmission commitAdmission) {
        if (admission == Admission.PREVIEW) {
            String issuedProof =
                rateLimiter.requirePreviewAllowed(
                    workUnits,
                    workflowFingerprint,
                    Objects.requireNonNull(reviewContext));
            return new AdmissionContext(
                admission,
                workflowFingerprint,
                issuedProof,
                null);
        }
        String reviewedResultFingerprint =
            rateLimiter.requireCommitAllowed(
                workflowFingerprint,
                commitAdmission == null ? null : commitAdmission.admission());
        if (reviewedResultFingerprint == null) {
            throw new ConflictException(
                "Import review is missing or expired; preview the import again");
        }
        return new AdmissionContext(
            admission,
            workflowFingerprint,
            null,
            reviewedResultFingerprint);
    }

    private void cancelFailedPreview(AdmissionContext context) {
        if (context.admission() == Admission.PREVIEW) {
            rateLimiter.cancelPreview(
                Objects.requireNonNull(context.workflowFingerprint()),
                Objects.requireNonNull(context.reviewProof()));
        }
    }

    private void completeAdmission(
            AdmissionContext context,
            List<DuplicatePreflightResponse> responses) {
        completeAdmission(context, responses, null);
    }

    private void completeAdmission(
            AdmissionContext context,
            List<DuplicatePreflightResponse> responses,
            String decisionFingerprint) {
        if (context.admission() == Admission.INTERACTIVE) {
            return;
        }
        String resultFingerprint =
            resultFingerprint(responses, decisionFingerprint);
        if (context.admission() == Admission.PREVIEW) {
            rateLimiter.recordPreviewResult(
                Objects.requireNonNull(context.workflowFingerprint()),
                Objects.requireNonNull(context.reviewProof()),
                resultFingerprint);
            return;
        }
        if (!Objects.equals(context.reviewedResultFingerprint(), resultFingerprint)) {
            throw new ConflictException(
                "Duplicate candidates changed before import; review them again");
        }
    }

    private List<DuplicatePreflightResponse> match(
            String recordType,
            List<NormalizedRequest> requests,
            int candidateLimit,
            boolean enforceImportAggregateLimit,
            IdentityQuery identityQuery,
            NameQuery nameQuery) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String orgWorkspaceIdsJson =
            workspaceScopeControlAccess.getForWorkspace(workspaceId).workspaceIdsJson();
        List<DuplicatePreflightResponse> responses = new ArrayList<>(requests.size());
        int aggregateCandidates = 0;
        for (int offset = 0; offset < requests.size(); offset += REQUEST_CHUNK_SIZE) {
            List<NormalizedRequest> chunk = requests.subList(
                offset, Math.min(offset + REQUEST_CHUNK_SIZE, requests.size()));
            List<DuplicatePreflightResponse> chunkResponses = matchChunk(
                workspaceId,
                orgWorkspaceIdsJson,
                recordType,
                chunk,
                candidateLimit,
                identityQuery,
                nameQuery);
            for (DuplicatePreflightResponse response : chunkResponses) {
                aggregateCandidates += response.candidates().size();
                if (enforceImportAggregateLimit
                        && aggregateCandidates > IMPORT_AGGREGATE_CANDIDATE_LIMIT) {
                    throw new BadRequestException(
                        "Duplicate review exceeds 1000 candidates; split the import");
                }
                responses.add(response);
            }
        }
        return List.copyOf(responses);
    }

    private List<DuplicatePreflightResponse> matchChunk(
            int workspaceId,
            String orgWorkspaceIdsJson,
            String recordType,
            List<NormalizedRequest> requests,
            int candidateLimit,
            IdentityQuery identityQuery,
            NameQuery nameQuery) {
        List<DuplicateIdentityKey> identityKeys = requests.stream()
            .flatMap(request -> request.identityKeys().stream())
            .distinct()
            .toList();
        List<DuplicateNameKey> nameKeys = requests.stream()
            .map(NormalizedRequest::normalizedName)
            .flatMap(Optional::stream)
            .distinct()
            .map(name -> new DuplicateNameKey(name, namePattern(name)))
            .toList();
        int perKeyLimit = candidateLimit + 1;
        Map<DuplicateIdentityKey, List<DuplicateCandidateRow>> identityRows =
            identityRows(workspaceId, orgWorkspaceIdsJson, identityKeys, perKeyLimit, identityQuery);
        Map<String, List<DuplicateCandidateRow>> nameRows =
            nameRows(workspaceId, orgWorkspaceIdsJson, nameKeys, perKeyLimit, nameQuery);
        return requests.stream()
            .map(request -> response(
                workspaceId,
                recordType,
                request,
                candidateLimit,
                perKeyLimit,
                identityRows,
                nameRows))
            .toList();
    }

    private DuplicatePreflightResponse response(
            int workspaceId,
            String recordType,
            NormalizedRequest request,
            int candidateLimit,
            int perKeyLimit,
            Map<DuplicateIdentityKey, List<DuplicateCandidateRow>> identityRows,
            Map<String, List<DuplicateCandidateRow>> nameRows) {
        Map<Integer, CandidateBuilder> builders = new LinkedHashMap<>();
        boolean truncated = false;
        for (DuplicateIdentityKey key : request.identityKeys()) {
            List<DuplicateCandidateRow> rows = identityRows.getOrDefault(key, List.of());
            if (rows.size() >= perKeyLimit) {
                truncated = true;
            }
            for (DuplicateCandidateRow row : rows) {
                candidate(builders, row, workspaceId, recordType)
                    .addEvidence(evidence(key));
            }
        }
        if (request.normalizedName().isPresent()) {
            String normalizedName = request.normalizedName().orElseThrow();
            List<DuplicateCandidateRow> rows =
                nameRows.getOrDefault(normalizedName, List.of());
            if (rows.size() >= perKeyLimit) {
                truncated = true;
            }
            for (DuplicateCandidateRow row : rows) {
                if (!normalizedName.equals(
                        matchingService.normalizeName(row.getName()).orElse(null))) {
                    continue;
                }
                candidate(builders, row, workspaceId, recordType)
                    .addEvidence(new DuplicateMatchEvidenceDto(
                        DuplicateMatchKind.NAME,
                        normalizedName,
                        DuplicateMatchStrength.WEAK));
            }
        }
        List<RankedCandidate> ranked = builders.values().stream()
            .map(CandidateBuilder::build)
            .sorted(ranking())
            .toList();
        if (ranked.size() > candidateLimit) {
            truncated = true;
        }
        List<DuplicateCandidateDto> candidates = ranked.stream()
            .limit(candidateLimit)
            .map(RankedCandidate::candidate)
            .toList();
        return new DuplicatePreflightResponse(
            recordType,
            candidates,
            truncated,
            reviewToken(workspaceId, recordType, request, candidates, truncated));
    }

    private static String reviewToken(
            int workspaceId,
            String recordType,
            NormalizedRequest request,
            List<DuplicateCandidateDto> candidates,
            boolean truncated) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        updateDigest(digest, Integer.toString(workspaceId));
        updateDigest(digest, recordType);
        updateDigest(digest, Boolean.toString(truncated));
        for (DuplicateIdentityKey key : request.identityKeys()) {
            updateDigest(digest, key.kind());
            updateDigest(digest, key.normalizedValue());
        }
        updateDigest(digest, Boolean.toString(request.normalizedName().isPresent()));
        updateDigest(digest, request.normalizedName().orElse(""));
        for (DuplicateCandidateDto candidate : candidates) {
            updateDigest(digest, Integer.toString(candidate.recordId()));
            updateDigest(digest, candidate.recordType());
            updateDigest(digest, candidate.name());
            updateDigest(digest, candidate.companyName());
            updateDigest(digest, candidate.title());
            updateDigest(digest, candidate.website());
            updateDigest(digest, candidate.industry());
            updateDigest(digest, Boolean.toString(candidate.ownedByActiveWorkspace()));
            updateDigest(digest, candidate.strength().name());
            for (DuplicateMatchEvidenceDto evidence : candidate.matches()) {
                updateDigest(digest, evidence.kind().name());
                updateDigest(digest, evidence.normalizedValue());
                updateDigest(digest, evidence.strength().name());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String resultFingerprint(
            List<DuplicatePreflightResponse> responses,
            String decisionFingerprint) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        updateDigest(digest, Integer.toString(responses.size()));
        for (DuplicatePreflightResponse response : responses) {
            updateDigest(digest, response.reviewToken());
        }
        updateDigest(digest, decisionFingerprint);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void lockIdentityGroups(
            int workspaceId,
            List<DuplicateIdentityKey> keys,
            IdentityGroupLocker locker) {
        for (DuplicateIdentityKey key : keys) {
            locker.apply(workspaceId, key.kind(), key.normalizedValue());
        }
    }

    private Map<DuplicateIdentityKey, List<DuplicateCandidateRow>> identityRows(
            int workspaceId,
            String orgWorkspaceIdsJson,
            List<DuplicateIdentityKey> keys,
            int perKeyLimit,
            IdentityQuery query) {
        Map<DuplicateIdentityKey, List<DuplicateCandidateRow>> rows = new HashMap<>();
        for (int offset = 0; offset < keys.size(); offset += IDENTITY_KEY_CHUNK_SIZE) {
            List<DuplicateIdentityKey> chunk =
                keys.subList(offset, Math.min(offset + IDENTITY_KEY_CHUNK_SIZE, keys.size()));
            for (DuplicateCandidateRow row
                    : query.apply(workspaceId, orgWorkspaceIdsJson, chunk, perKeyLimit)) {
                DuplicateIdentityKey key = new DuplicateIdentityKey(
                    Objects.requireNonNull(row.getKind(), "match kind"),
                    Objects.requireNonNull(row.getNormalizedValue(), "match value"));
                rows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        return rows;
    }

    private Map<String, List<DuplicateCandidateRow>> nameRows(
            int workspaceId,
            String orgWorkspaceIdsJson,
            List<DuplicateNameKey> keys,
            int perKeyLimit,
            NameQuery query) {
        Map<String, List<DuplicateCandidateRow>> rows = new HashMap<>();
        for (int offset = 0; offset < keys.size(); offset += NAME_KEY_CHUNK_SIZE) {
            List<DuplicateNameKey> chunk =
                keys.subList(offset, Math.min(offset + NAME_KEY_CHUNK_SIZE, keys.size()));
            for (DuplicateCandidateRow row
                    : query.apply(workspaceId, orgWorkspaceIdsJson, chunk, perKeyLimit)) {
                String normalizedName =
                    Objects.requireNonNull(row.getNormalizedValue(), "normalized name");
                rows.computeIfAbsent(normalizedName, ignored -> new ArrayList<>()).add(row);
            }
        }
        return rows;
    }

    private NormalizedRequest normalizePerson(PersonDuplicatePreflightRequest request) {
        Objects.requireNonNull(request, "person request");
        requireIdentityBound(request.emails(), request.phones());
        Set<DuplicateIdentityKey> keys = new LinkedHashSet<>();
        addKeys(keys, IdentityKind.EMAIL, request.emails());
        addKeys(keys, IdentityKind.PHONE, request.phones());
        return normalizedRequest(keys, request.name());
    }

    private NormalizedRequest normalizeCompany(CompanyDuplicatePreflightRequest request) {
        Objects.requireNonNull(request, "company request");
        requireIdentityBound(request.websites(), request.phones());
        Set<DuplicateIdentityKey> keys = new LinkedHashSet<>();
        addKeys(keys, IdentityKind.DOMAIN, request.websites());
        addKeys(keys, IdentityKind.PHONE, request.phones());
        return normalizedRequest(keys, request.name());
    }

    @SafeVarargs
    private final void requireIdentityBound(List<String>... values) {
        int count = Arrays.stream(values)
            .filter(Objects::nonNull)
            .mapToInt(List::size)
            .sum();
        if (count > MAX_IDENTITY_VALUES) {
            throw new BadRequestException(
                "At most " + MAX_IDENTITY_VALUES + " identity values may be checked");
        }
    }

    private void addKeys(
            Set<DuplicateIdentityKey> keys,
            IdentityKind kind,
            List<String> rawValues) {
        if (rawValues == null) {
            return;
        }
        for (String rawValue : rawValues) {
            matchingService.normalizeIdentifier(kind, rawValue)
                .ifPresent(normalized ->
                    keys.add(new DuplicateIdentityKey(kind.getDatabaseValue(), normalized)));
        }
    }

    private NormalizedRequest normalizedRequest(
            Set<DuplicateIdentityKey> keys,
            String rawName) {
        List<DuplicateIdentityKey> sortedKeys = keys.stream()
            .sorted(Comparator
                .comparing(DuplicateIdentityKey::kind)
                .thenComparing(DuplicateIdentityKey::normalizedValue))
            .toList();
        Optional<String> normalizedName = matchingService.normalizeName(rawName);
        if (sortedKeys.isEmpty() && normalizedName.isEmpty()) {
            throw new BadRequestException("At least one valid identity or name is required");
        }
        return new NormalizedRequest(sortedKeys, normalizedName);
    }

    private static <T> List<T> boundedImportRequests(List<T> requests) {
        Objects.requireNonNull(requests, "import requests");
        if (requests.size() > IMPORT_REQUEST_LIMIT) {
            throw new BadRequestException(
                "At most " + IMPORT_REQUEST_LIMIT + " import rows may be checked");
        }
        return List.copyOf(requests);
    }

    private static String requireReviewContext(String candidate) {
        String reviewContext = Objects.requireNonNull(candidate, "review context");
        if (reviewContext.length() != 64) {
            throw new IllegalArgumentException(
                "Duplicate-preflight review context must be SHA-256");
        }
        return reviewContext;
    }

    private static int workUnits(List<NormalizedRequest> requests) {
        int lookups = requests.stream()
            .mapToInt(request ->
                Math.max(
                    1,
                    request.identityKeys().size() + (request.normalizedName().isPresent() ? 1 : 0)))
            .sum();
        return Math.max(1, Math.ceilDiv(lookups, LOOKUPS_PER_WORK_UNIT));
    }

    private static String workflowFingerprint(
            String recordType,
            List<NormalizedRequest> requests,
            String reviewContext) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        updateDigest(digest, recordType);
        updateDigest(digest, reviewContext);
        updateDigest(digest, Integer.toString(requests.size()));
        for (NormalizedRequest request : requests) {
            updateDigest(digest, "row");
            for (DuplicateIdentityKey key : request.identityKeys()) {
                updateDigest(digest, key.kind());
                updateDigest(digest, key.normalizedValue());
            }
            updateDigest(digest, request.normalizedName().orElse(""));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String combinedWorkflowFingerprint(
            List<NormalizedRequest> persons,
            List<NormalizedRequest> companies,
            String reviewContext) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        updateDigest(digest, "connex-import-duplicate-review-v1");
        updateDigest(digest, reviewContext);
        updateDigest(digest, workflowFingerprint("person", persons, reviewContext));
        updateDigest(digest, workflowFingerprint("company", companies, reviewContext));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDigest(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String namePattern(String normalizedName) {
        return Arrays.stream(normalizedName.split(" "))
            .map(LikePattern::escape)
            .collect(Collectors.joining("%", "%", "%"));
    }

    private CandidateBuilder candidate(
            Map<Integer, CandidateBuilder> builders,
            DuplicateCandidateRow row,
            int workspaceId,
            String recordType) {
        return builders.computeIfAbsent(
            row.getRecordId(),
            ignored -> new CandidateBuilder(
                row, recordType, row.getRecordWorkspaceId() == workspaceId));
    }

    private static DuplicateMatchEvidenceDto evidence(DuplicateIdentityKey key) {
        return new DuplicateMatchEvidenceDto(
            matchKind(key.kind()),
            key.normalizedValue(),
            DuplicateMatchStrength.STRONG);
    }

    private static DuplicateMatchKind matchKind(String kind) {
        return switch (kind) {
            case "email" -> DuplicateMatchKind.EMAIL;
            case "phone" -> DuplicateMatchKind.PHONE;
            case "domain" -> DuplicateMatchKind.DOMAIN;
            case "external_id" -> DuplicateMatchKind.EXTERNAL_ID;
            default -> throw new IllegalStateException("Unsupported duplicate match kind");
        };
    }

    private Comparator<RankedCandidate> ranking() {
        return Comparator
            .comparing((RankedCandidate candidate) ->
                candidate.candidate().strength() == DuplicateMatchStrength.STRONG ? 0 : 1)
            .thenComparing(
                RankedCandidate::strongEvidenceCount,
                Comparator.reverseOrder())
            .thenComparing(
                RankedCandidate::evidenceCount,
                Comparator.reverseOrder())
            .thenComparing(candidate ->
                candidate.candidate().ownedByActiveWorkspace() ? 0 : 1)
            .thenComparing(RankedCandidate::normalizedName)
            .thenComparing(candidate -> candidate.candidate().recordId());
    }

    private String normalizedName(String name) {
        return matchingService.normalizeName(name).orElse("");
    }

    private enum Admission {
        INTERACTIVE,
        PREVIEW,
        COMMIT
    }

    private record NormalizedRequest(
            List<DuplicateIdentityKey> identityKeys,
            Optional<String> normalizedName) {
    }

    private record AdmissionContext(
            Admission admission,
            String workflowFingerprint,
            String reviewProof,
            String reviewedResultFingerprint) {
    }

    private record MatchOutcome(
            List<DuplicatePreflightResponse> responses,
            String reviewProof) {
    }

    private record PendingImportMatch(
            List<DuplicatePreflightResponse> responses,
            AdmissionContext admissionContext) {
    }

    final class ImportPreviewSession {
        private final PendingImportMatch pending;
        private boolean completed;

        private ImportPreviewSession(PendingImportMatch pending) {
            this.pending = Objects.requireNonNull(pending, "pending preview");
        }

        List<DuplicatePreflightResponse> responses() {
            return pending.responses();
        }

        String reviewProof() {
            return Objects.requireNonNull(
                pending.admissionContext().reviewProof(),
                "review proof");
        }

        private void complete(String decisionFingerprint) {
            if (completed) {
                throw new IllegalStateException(
                    "Duplicate import preview session was already completed");
            }
            completed = true;
            completeAdmission(
                pending.admissionContext(),
                pending.responses(),
                decisionFingerprint);
        }
    }

    final class ImportCommitSession {
        private final PendingImportMatch pending;
        private boolean completed;

        private ImportCommitSession(PendingImportMatch pending) {
            this.pending = Objects.requireNonNull(pending, "pending commit");
        }

        List<DuplicatePreflightResponse> responses() {
            return pending.responses();
        }

        private void complete(String decisionFingerprint) {
            if (completed) {
                throw new IllegalStateException(
                    "Duplicate import commit session was already completed");
            }
            completed = true;
            completeAdmission(
                pending.admissionContext(),
                pending.responses(),
                decisionFingerprint);
        }
    }

    record ImportCommitAdmission(
            DuplicatePreflightRateLimiter.CommitAdmission admission) {

        ImportCommitAdmission {
            Objects.requireNonNull(admission, "commit admission");
        }
    }

    private record RankedCandidate(
            DuplicateCandidateDto candidate,
            int strongEvidenceCount,
            int evidenceCount,
            String normalizedName) {
    }

    private final class CandidateBuilder {
        private final DuplicateCandidateRow row;
        private final String recordType;
        private final boolean owned;
        private final Map<DuplicateMatchKind, Map<String, DuplicateMatchEvidenceDto>> evidence =
            new EnumMap<>(DuplicateMatchKind.class);

        private CandidateBuilder(
                DuplicateCandidateRow row,
                String recordType,
                boolean owned) {
            this.row = Objects.requireNonNull(row, "candidate row");
            this.recordType = recordType;
            this.owned = owned;
        }

        private void addEvidence(DuplicateMatchEvidenceDto match) {
            evidence.computeIfAbsent(match.kind(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(match.normalizedValue(), match);
        }

        private RankedCandidate build() {
            List<DuplicateMatchEvidenceDto> matches = evidence.values().stream()
                .flatMap(matchesByValue -> matchesByValue.values().stream())
                .sorted(Comparator
                    .comparing((DuplicateMatchEvidenceDto match) ->
                        match.strength() == DuplicateMatchStrength.STRONG ? 0 : 1)
                    .thenComparing(DuplicateMatchEvidenceDto::kind)
                    .thenComparing(DuplicateMatchEvidenceDto::normalizedValue))
                .toList();
            int strongEvidence = (int) matches.stream()
                .filter(match -> match.strength() == DuplicateMatchStrength.STRONG)
                .count();
            DuplicateMatchStrength strength = strongEvidence > 0
                ? DuplicateMatchStrength.STRONG
                : DuplicateMatchStrength.WEAK;
            DuplicateCandidateDto candidate = new DuplicateCandidateDto(
                row.getRecordId(),
                recordType,
                Objects.requireNonNull(row.getName(), "candidate name"),
                row.getCompanyName(),
                row.getTitle(),
                row.getWebsite(),
                row.getIndustry(),
                owned,
                strength,
                matches);
            return new RankedCandidate(
                candidate,
                strongEvidence,
                matches.size(),
                DuplicatePreflightService.this.normalizedName(candidate.name()));
        }
    }

    @FunctionalInterface
    private interface IdentityQuery {
        List<DuplicateCandidateRow> apply(
            int workspaceId,
            String orgWorkspaceIdsJson,
            List<DuplicateIdentityKey> keys,
            int perKeyLimit);
    }

    @FunctionalInterface
    private interface NameQuery {
        List<DuplicateCandidateRow> apply(
            int workspaceId,
            String orgWorkspaceIdsJson,
            List<DuplicateNameKey> keys,
            int perKeyLimit);
    }

    @FunctionalInterface
    private interface IdentityGroupLocker {
        List<Long> apply(
            int workspaceId,
            String kind,
            String normalizedValue);
    }
}
