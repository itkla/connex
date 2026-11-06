package ooo.klae.connex.backend.services;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import ooo.klae.connex.backend.util.RecordInsertBatches;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.IdentityMatchRow;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonLeadSource;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.DealImportDateReview;
import ooo.klae.connex.backend.dto.CompanyDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DuplicateCandidateDto;
import ooo.klae.connex.backend.dto.DuplicateImportPreflightReview;
import ooo.klae.connex.backend.dto.DuplicateMatchKind;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.ImportPreviewResult;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.RowAnalysis;
import ooo.klae.connex.backend.dto.ImportCompanyDependency;
import ooo.klae.connex.backend.dto.ImportProblem;
import ooo.klae.connex.backend.exceptions.ImportValidationException;
import ooo.klae.connex.backend.dto.RowError;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.DealLineItemMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.validation.CurrencyCodes;

/**
 * Bulk CSV import for contacts, companies, and deals. The frontend parses the CSV, maps columns to
 * Connex fields, and posts structured rows here; this service validates each row, deduplicates
 * against existing records on high-confidence keys (person email, company website/name, deal
 * name+company) and against other rows in the same file, then either creates new records or merges
 * into matched ones per the {@code onDuplicate} strategy ("fill_empty", "skip", "overwrite").
 *
 * <p>For throughput and to keep the audit log readable, imports use the batch-insert mappers and
 * write a single {@code import.*} audit summary for the imported records rather than auditing each
 * row, and do not fire per-row rule/notification triggers for them. Writable matched targets are
 * locked in ascending record-id order before any dependency is created. Imports that may create a
 * custom-field definition first lock its template-set synchronization root, matching guided and
 * template-administration writers, while definition creation remains after target revalidation.
 * Tags, referenced companies, and auto-created definitions are resolved in stable key order through
 * their permission-checked services.
 *
 * <p>Identity provenance uses {@code csv-row:N}, where {@code N} is the one-based ordinal in the
 * reviewed row payload. The client parser does not retain enough source metadata to claim a
 * physical CSV line number, especially for blank or multiline records.
 */
@Service
@RequiredArgsConstructor
public class ImportService {

    private final WorkspaceService workspaceService;
    private final DuplicateDecisionLockService duplicateDecisionLockService;
    private final AuthService authService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final IdentityMapper identityMapper;
    private final CompanyService companyService;
    private final DealMapper dealMapper;
    private final DealLineItemMapper dealLineItemMapper;
    private final DealValueService dealValueService;
    private final DealOutcomeWriter dealOutcomeWriter;
    private final TagMapper tagMapper;
    private final TagService tagService;
    private final PipelineMapper pipelineMapper;
    private final RecordVisibilityScopeControlAccess recordVisibilityScopeControlAccess;
    private final EmploymentService employmentService;
    private final DealStageHistoryService dealStageHistoryService;
    private final CustomFieldDefinitionMapper customFieldDefinitionMapper;
    private final CustomFieldDefinitionService customFieldDefinitionService;
    private final RecordCreationTemplateMapper recordCreationTemplateMapper;
    private final CustomFieldValueService customFieldValueService;
    private final AuditService auditService;
    private final IdentityIntakeService identityIntakeService;
    private final MatchingService matchingService;
    private final DuplicatePreflightService duplicatePreflightService;

    private static final String DEFAULT_TAG_COLOR = "#CCCCCC";
    private static final int DEAL_VALUE_SCALE = 2;
    private static final int MAX_VALUE_LENGTH = 128;
    private static final int MAX_NUMERIC_DIGITS = 20;
    private static final int DEAL_VALUE_INTEGER_DIGITS = 13;
    private static final BigDecimal DEAL_VALUE_ROUNDING_LIMIT = new BigDecimal("9999999999999.995");
    private static final int CUSTOM_NUMBER_SCALE = 4;
    private static final String DEAL_VALUE_LINE_ITEM_CONFLICT =
        "Cannot import a deal value while line items exist; update or remove the line items first";
    private static final String DEAL_CURRENCY_LINE_ITEM_CONFLICT =
        "Cannot change the deal currency while it has line items; remove the line items first";

    private static final Set<String> PERSON_FIELDS = Set.of("name", "email", "phone", "title", "company");
    private static final Set<String> COMPANY_FIELDS = Set.of("name", "website", "industry", "phone", "address");
    private static final Set<String> DEAL_FIELDS =
        Set.of("name", "value", "currency", "pipeline", "stage", "company", "expectedCloseDate", "closedAt", "createdAt", "people");
    private static final DateTimeFormatter IMPORT_DATETIME =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final Set<String> AUTO_CUSTOM_TYPES = Set.of("text", "textarea", "number", "date", "boolean", "url");

    private static final String FILL_EMPTY = "fill_empty";
    private static final String SKIP = "skip";
    private static final String OVERWRITE = "overwrite";

    private static final String CREATE = "create";
    private static final String MATCH = "match";
    private static final String INVALID = "invalid";

    /**
     * One parsed import row, after validation and dedup. Standard fields land in {@code std} keyed by
     * Connex field name, custom columns in {@code custom} keyed by CSV column. Entity-specific
     * references ({@code companyName}, deal pipeline/stage/people) are resolved at commit time.
     */
    private static final class PlanRow {
        int rowIndex;
        String status;
        Integer matchedId;
        String label;
        final List<String> errors = new ArrayList<>();
        final Map<String, String> std = new HashMap<>();
        final Map<String, Integer> stdSourceRows = new HashMap<>();
        final Map<String, String> custom = new HashMap<>();
        final List<String> tagNames = new ArrayList<>();
        final List<PlanRow> sourceRows = new ArrayList<>();
        final Set<CanonicalIdentity> missingCanonicalIdentities = new HashSet<>();
        boolean failedBecauseMatchedTargetVanished;
        String companyName;
        String companyDependencyKey;
        String companyDependencyError;
        List<DuplicateCandidateDto> companyDependencyCandidates = List.of();
        boolean companyDependencyRequired;
        boolean companyDependencyTruncated;
        Integer selectedCompanyId;
        String pipelineName;
        String stageName;
        Integer resolvedCompanyId;
        Integer resolvedPipelineId;
        Integer resolvedStageId;
        final List<String> peopleEmails = new ArrayList<>();
        final List<DuplicateCandidateDto> duplicateCandidates = new ArrayList<>();
        boolean duplicateCandidatesTruncated;
        boolean automaticIdentityMatch;
        DealImportDateReview dealDates;
        Integer reviewedStageId;
        String reviewedOutcome;
    }

    private enum ImportPhase {
        PREVIEW,
        COMMIT
    }

    private record ImportAnalysis(
            List<PlanRow> plan,
            List<PlanRow> mutations,
            String reviewProof) {
    }

    private record DealUpdateValues(
            String currency,
            BigDecimal value,
            boolean shouldUpdateValue) {
    }

    // ===================================================================================
    // Contacts
    // ===================================================================================

    /**
     * Dry-run a contact import: validate and deduplicate without writing, returning per-row outcomes
     * and aggregate counts for the review step.
     */
    @RequirePermission(Permission.PERSON_CREATE)
    public ImportPreviewResult previewPersons(ImportRequest request) {
        return recordVisibilityScopeControlAccess.withDeferredWorkspaceScope(
            workspaceService.getCurrentWorkspaceId(), () -> previewPersonsWithinVisibilityScope(request, null));
    }

    private ImportPreviewResult previewPersonsWithinVisibilityScope(ImportRequest request, @Nullable RecordVisibilityScope scope) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String action = resolveAction(request.getOnDuplicate());
        ImportAnalysis analysis =
            analyzePersons(request, workspaceId, ImportPhase.PREVIEW, null, scope);
        List<PlanRow> plan = analysis.plan();
        try {
            preflightPreview(
                "person",
                workspaceId,
                request.getMapping(),
                analysis.mutations(),
                action,
                Permission.PERSON_UPDATE);
            ImportPreviewResult result = summarize(
                plan, analysis.mutations(), action);
            result.setDuplicateReviewProof(analysis.reviewProof());
            return result;
        } catch (RuntimeException exception) {
            duplicatePreflightService.cancelImportPreview(analysis.reviewProof());
            throw exception;
        }
    }

    /**
     * Commit a contact import. Creates new contacts and merges matches per {@code onDuplicate};
     * resolves referenced companies (creating missing ones), tags (create-if-missing), and any
     * auto-created custom-field definitions.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.PERSON_CREATE)
    public ImportResult commitPersons(ImportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String action = resolveAction(request.getOnDuplicate());
        String reviewContext = importReviewContext("person", workspaceId, request);
        DuplicatePreflightService.ImportCommitAdmission admission =
            duplicatePreflightService.claimImportCommit(
                request.getDuplicateReviewProof(), reviewContext);
        RecordVisibilityScope scope = recordVisibilityScopeControlAccess.getForWorkspace(workspaceId);
        return recordVisibilityScopeControlAccess.withWorkspaceScope(workspaceId, scope, () -> {

            lockImportPermission(workspaceId, Permission.PERSON_CREATE);
            duplicateDecisionLockService.lockCurrentOrganization();
            int actorId = authService.getCurrentUser().getId();
            ImportAnalysis analysis = analyzePersons(
                request, workspaceId, ImportPhase.COMMIT, admission, reviewContext, scope);
            List<PlanRow> plan = analysis.plan();
            List<PlanRow> mutations = analysis.mutations();
            requireUpdatePermission(mutations, action, Permission.PERSON_UPDATE);
            lockCustomFieldTemplateSetBeforeTargets(
                "person", request.getMapping(), mutations, action);

            Map<Integer, Person> lockedTargets = lockMatchedTargets(
                mutations,
                action,
                id -> personMapper.getOwnedPersonByIdForUpdate(workspaceId, id),
                "contact");
            revalidatePersonTargets(mutations, lockedTargets);
            serializeAndRevalidatePersonIdentities(workspaceId, mutations, action);
            Map<String, Integer> columnToDef = resolveCustomDefinitions(
                "person", request.getMapping(), mutations, action);
            Map<Integer, Map<Integer, Object>> customValues =
                existingCustomValues("person", mutations, action);
            Map<String, Integer> tagByName = resolveTags(mutations, action);
            Map<String, Integer> companyByName = resolveCompanies(mutations, action);

            List<PlanRow> toCreate = new ArrayList<>();
            List<Person> beans = new ArrayList<>();
            int updated = 0;
            int skipped = 0;

            for (PlanRow row : mutations) {
                if (INVALID.equals(row.status)) continue;
                if (SKIP.equals(row.status)) { skipped++; continue; }
                if (MATCH.equals(row.status)) {
                    if (SKIP.equals(action)) { skipped++; continue; }
                    if (applyPersonUpdate(
                            workspaceId,
                            row,
                            action,
                            columnToDef,
                            customValues,
                            tagByName,
                            companyByName,
                            lockedTargets)) {
                        updated++;
                    }
                    continue;
                }
                Person bean = new Person();
                bean.setWorkspaceId(workspaceId);
                bean.setOwnerId(actorId);
                bean.setLeadSource(PersonLeadSource.IMPORT);
                bean.setName(row.std.get("name"));
                bean.setEmail(row.std.get("email"));
                bean.setPhone(row.std.get("phone"));
                bean.setTitle(row.std.get("title"));
                Integer companyId = companyId(row, companyByName);
                if (companyId != null) {
                    Company stub = new Company();
                    stub.setId(companyId);
                    bean.setCompany(stub);
                }
                beans.add(bean);
                toCreate.add(row);
            }

            if (!beans.isEmpty()) {
                RecordInsertBatches.people(beans).forEach(personMapper::insertBatch);
                for (int i = 0; i < beans.size(); i++) {
                    Person bean = beans.get(i);
                    PlanRow row = toCreate.get(i);
                    recordPersonIdentities(
                        workspaceId,
                        bean,
                        row,
                        bean.getEmail() != null,
                        bean.getPhone() != null);
                    Integer companyId = bean.getCompany() != null ? bean.getCompany().getId() : null;
                    if (companyId != null) {
                        employmentService.recordInitial(workspaceId, bean.getId(), companyId, bean.getTitle());
                    }
                    attachTags(workspaceId, "person", bean.getId(), row.tagNames, tagByName);
                    applyCustomValues(
                        "person",
                        bean.getId(),
                        row.custom,
                        columnToDef,
                        action,
                        Map.of());
                }
            }

            int created = beans.size();
            List<RowError> failed = collectFailures(plan);
            auditImport(
                "person",
                created,
                updated,
                skipped,
                failed.size(),
                allRowsFailedBecauseTargetsVanished(plan));
            return new ImportResult(created, updated, skipped, failed);
        });
    }

    private ImportAnalysis analyzePersons(
            ImportRequest request,
            int workspaceId,
            ImportPhase phase,
            DuplicatePreflightService.ImportCommitAdmission admission, @Nullable RecordVisibilityScope scope) {
        return analyzePersons(
            request,
            workspaceId,
            phase,
            admission,
            importReviewContext("person", workspaceId, request), scope);
    }

    private ImportAnalysis analyzePersons(
            ImportRequest request,
            int workspaceId,
            ImportPhase phase,
            DuplicatePreflightService.ImportCommitAdmission admission,
            String reviewContext, @Nullable RecordVisibilityScope scope) {
        Map<String, ColumnMapping> byColumn = mappingByColumn(request.getMapping(), PERSON_FIELDS);
        Map<String, CustomFieldDefinition> defs = customDefsByColumn("person", request.getMapping());
        List<PlanRow> plan = collectRows(request, byColumn, defs, "company", null, null, null);

        String reviewProof = applyPersonDuplicatePreflight(
            plan,
            phase,
            reviewContext,
            admission);
        try {
            Map<Integer, Integer> links =
                request.getLinks() == null ? Map.of() : request.getLinks();
            for (PlanRow row : plan) {
                if (INVALID.equals(row.status) || SKIP.equals(row.status)) continue;
                Integer linked = links.get(row.rowIndex);
                if (linked != null) {
                    Person existing = getOwnedPerson(workspaceId, linked, scope);
                    if (existing == null) {
                        fail(row, "Linked contact #" + linked + " not found");
                        continue;
                    }
                    if (!personIsProcessable(existing)) {
                        fail(row, "Linked contact #" + linked + " is unavailable");
                        continue;
                    }
                    if (manualLinkConflicts(
                            row, linked, "person", "contact")) {
                        continue;
                    }
                    markMatch(row, existing.getId(), existing.getName());
                    continue;
                }
                if (row.duplicateCandidatesTruncated) {
                    fail(row, "Duplicate candidates were truncated; split or refine the import");
                    continue;
                }
                applyStrongMatch(row, "person", "contact");
            }
            String action = resolveAction(request.getOnDuplicate());
            validateWithinFileStrongKeys(plan, this::personIdentityKeys);
            List<PlanRow> mutations =
                coalesceByCanonicalTarget(plan, action, this::personIdentityKeys);
            applyCompanyDependencyFailures(
                mutations,
                row -> personCompanyDependencyRequired(
                    workspaceId, row, action, scope));
            return new ImportAnalysis(plan, mutations, reviewProof);
        } catch (RuntimeException exception) {
            cancelPreviewAnalysis(phase, reviewProof);
            throw exception;
        }
    }

    private boolean applyPersonUpdate(int workspaceId, PlanRow row, String action,
            Map<String, Integer> columnToDef,
            Map<Integer, Map<Integer, Object>> customValues,
            Map<String, Integer> tagByName,
            Map<String, Integer> companyByName,
            Map<Integer, Person> lockedTargets) {
        Person existing = lockedTargets.get(row.matchedId);
        if (existing == null) {
            fail(row, "Matched contact #" + row.matchedId + " not found");
            return false;
        }
        Integer beforeCompanyId = existing.getCompany() != null ? existing.getCompany().getId() : null;
        String beforeName = existing.getName();
        String beforeEmail = existing.getEmail();
        String beforePhone = existing.getPhone();
        String beforeTitle = existing.getTitle();
        existing.setName(merge(action, existing.getName(), row.std.get("name")));
        existing.setEmail(merge(action, existing.getEmail(), row.std.get("email")));
        existing.setPhone(merge(action, existing.getPhone(), row.std.get("phone")));
        existing.setTitle(merge(action, existing.getTitle(), row.std.get("title")));
        Integer companyId = companyId(row, companyByName);
        if (companyId != null && (OVERWRITE.equals(action) || existing.getCompany() == null)) {
            Company stub = new Company();
            stub.setId(companyId);
            existing.setCompany(stub);
        }
        Integer afterCompanyId = existing.getCompany() != null ? existing.getCompany().getId() : null;
        boolean emailAcquired = row.std.containsKey("email")
            && (!Objects.equals(beforeEmail, existing.getEmail())
                || missingCanonicalIdentity(
                    row, IdentityKind.EMAIL, existing.getEmail()));
        boolean phoneAcquired = row.std.containsKey("phone")
            && (!Objects.equals(beforePhone, existing.getPhone())
                || missingCanonicalIdentity(
                    row, IdentityKind.PHONE, existing.getPhone()));
        boolean parentChanged =
            !Objects.equals(beforeName, existing.getName())
                || !Objects.equals(beforeEmail, existing.getEmail())
                || !Objects.equals(beforePhone, existing.getPhone())
                || !Objects.equals(beforeTitle, existing.getTitle())
                || !Objects.equals(beforeCompanyId, afterCompanyId);
        if (parentChanged) {
            personMapper.update(existing);
        }
        boolean identitiesChanged = recordPersonIdentities(
            workspaceId, existing, row, emailAcquired, phoneAcquired);
        if (afterCompanyId != null && !afterCompanyId.equals(beforeCompanyId)) {
            employmentService.recordTransition(workspaceId, existing.getId(), afterCompanyId, existing.getTitle());
        }
        boolean referencesChanged = attachTags(
            workspaceId, "person", existing.getId(), row.tagNames, tagByName);
        boolean customChanged = applyCustomValues(
            "person",
            existing.getId(),
            row.custom,
            columnToDef,
            action,
            customValues.getOrDefault(existing.getId(), Map.of()));
        return parentChanged
            || identitiesChanged
            || referencesChanged
            || customChanged;
    }

    private Person getOwnedPerson(int workspaceId, int personId, @Nullable RecordVisibilityScope scope) {
        if (!personMapper.existsOwned(workspaceId, personId)) return null;
        String personWorkspaceIdsJson = (scope == null
            ? recordVisibilityScopeControlAccess.getForWorkspace(workspaceId) : scope).workspaceIdsJson();
        return personMapper.getPersonById(workspaceId, personWorkspaceIdsJson, personId);
    }

    private boolean personCompanyDependencyRequired(
            int workspaceId,
            PlanRow row,
            String action, @Nullable RecordVisibilityScope scope) {
        if (row.companyName == null || !willWrite(row, action)) {
            return false;
        }
        if (!MATCH.equals(row.status) || OVERWRITE.equals(action)) {
            return true;
        }
        if (row.matchedId == null) {
            return true;
        }
        Person existing = getOwnedPerson(workspaceId, row.matchedId, scope);
        return existing == null || existing.getCompany() == null;
    }

    private String applyPersonDuplicatePreflight(
            List<PlanRow> plan,
            ImportPhase phase,
            String reviewContext,
            DuplicatePreflightService.ImportCommitAdmission admission) {
        List<PlanRow> eligible = plan.stream()
            .filter(row -> CREATE.equals(row.status) || MATCH.equals(row.status))
            .toList();
        List<PersonDuplicatePreflightRequest> personRequests = eligible.stream()
            .map(row -> new PersonDuplicatePreflightRequest(
                row.std.get("name"),
                valueList(row.std.get("email")),
                valueList(row.std.get("phone"))))
            .toList();
        CompanyDependencyReview companyReview = companyDependencyReview(plan);
        List<DuplicatePreflightResponse> responses;
        String reviewProof;
        if (phase == ImportPhase.PREVIEW) {
            DuplicateImportPreflightReview review =
                duplicatePreflightService.preflightImportPreview(
                    personRequests,
                    companyReview.requests(),
                    reviewContext);
            reviewProof = review.reviewProof();
            try {
                responses = review.responses();
                int personCount = personRequests.size();
                applyDuplicateCandidates(eligible, responses.subList(0, personCount));
                applyCompanyDependencyCandidates(
                    companyReview,
                    responses.subList(personCount, responses.size()));
                return reviewProof;
            } catch (RuntimeException exception) {
                duplicatePreflightService.cancelImportPreview(reviewProof);
                throw exception;
            }
        } else {
            responses = duplicatePreflightService.preflightImportCommit(
                personRequests,
                companyReview.requests(),
                reviewContext,
                admission);
            reviewProof = null;
        }
        int personCount = personRequests.size();
        applyDuplicateCandidates(eligible, responses.subList(0, personCount));
        applyCompanyDependencyCandidates(
            companyReview,
            responses.subList(personCount, responses.size()));
        return reviewProof;
    }

    // ===================================================================================
    // Companies
    // ===================================================================================

    /**
     * Dry-run a company import: validate and deduplicate without writing.
     */
    @RequirePermission(Permission.COMPANY_CREATE)
    public ImportPreviewResult previewCompanies(ImportRequest request) {
        return recordVisibilityScopeControlAccess.withDeferredWorkspaceScope(
            workspaceService.getCurrentWorkspaceId(), () -> previewCompaniesWithinVisibilityScope(request));
    }

    private ImportPreviewResult previewCompaniesWithinVisibilityScope(ImportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String action = resolveAction(request.getOnDuplicate());
        ImportAnalysis analysis =
            analyzeCompanies(request, workspaceId, ImportPhase.PREVIEW, null, null);
        List<PlanRow> plan = analysis.plan();
        try {
            preflightPreview(
                "company",
                workspaceId,
                request.getMapping(),
                analysis.mutations(),
                action,
                Permission.COMPANY_UPDATE);
            ImportPreviewResult result = summarize(
                plan, analysis.mutations(), action);
            result.setDuplicateReviewProof(analysis.reviewProof());
            return result;
        } catch (RuntimeException exception) {
            duplicatePreflightService.cancelImportPreview(analysis.reviewProof());
            throw exception;
        }
    }

    /**
     * Commit a company import. Deduplicates on normalized website then normalized name.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.COMPANY_CREATE)
    public ImportResult commitCompanies(ImportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String action = resolveAction(request.getOnDuplicate());
        String reviewContext = importReviewContext("company", workspaceId, request);
        DuplicatePreflightService.ImportCommitAdmission admission =
            duplicatePreflightService.claimImportCommit(
                request.getDuplicateReviewProof(), reviewContext);
        RecordVisibilityScope scope = recordVisibilityScopeControlAccess.getForWorkspace(workspaceId);
        return recordVisibilityScopeControlAccess.withWorkspaceScope(workspaceId, scope, () -> {

            lockImportPermission(workspaceId, Permission.COMPANY_CREATE);
            duplicateDecisionLockService.lockCurrentOrganization();
            int actorId = authService.getCurrentUser().getId();
            ImportAnalysis analysis = analyzeCompanies(
                request, workspaceId, ImportPhase.COMMIT, admission, reviewContext, scope);
            List<PlanRow> plan = analysis.plan();
            List<PlanRow> mutations = analysis.mutations();
            requireUpdatePermission(mutations, action, Permission.COMPANY_UPDATE);
            lockCustomFieldTemplateSetBeforeTargets(
                "company", request.getMapping(), mutations, action);

            Map<Integer, Company> lockedTargets = lockMatchedTargets(
                mutations,
                action,
                id -> companyMapper.getOwnedCompanyByIdForUpdate(workspaceId, id),
                "company");
            serializeAndRevalidateCompanyIdentities(
                workspaceId, mutations, action);
            Map<String, Integer> columnToDef = resolveCustomDefinitions(
                "company", request.getMapping(), mutations, action);
            Map<Integer, Map<Integer, Object>> customValues =
                existingCustomValues("company", mutations, action);
            Map<String, Integer> tagByName = resolveTags(mutations, action);

            List<PlanRow> toCreate = new ArrayList<>();
            List<Company> beans = new ArrayList<>();
            int updated = 0;
            int skipped = 0;

            for (PlanRow row : mutations) {
                if (INVALID.equals(row.status)) continue;
                if (SKIP.equals(row.status)) { skipped++; continue; }
                if (MATCH.equals(row.status)) {
                    if (SKIP.equals(action)) { skipped++; continue; }
                    if (applyCompanyUpdate(
                            workspaceId,
                            row,
                            action,
                            columnToDef,
                            customValues,
                            tagByName,
                            lockedTargets)) {
                        updated++;
                    }
                    continue;
                }
                Company bean = new Company();
                bean.setWorkspaceId(workspaceId);
                bean.setOwnerId(actorId);
                bean.setName(row.std.get("name"));
                bean.setWebsite(row.std.get("website"));
                bean.setIndustry(row.std.get("industry"));
                bean.setPhone(row.std.get("phone"));
                bean.setAddress(row.std.get("address"));
                beans.add(bean);
                toCreate.add(row);
            }

            if (!beans.isEmpty()) {
                RecordInsertBatches.companies(beans).forEach(companyMapper::insertBatch);
                for (int i = 0; i < beans.size(); i++) {
                    Company bean = beans.get(i);
                    PlanRow row = toCreate.get(i);
                    recordCompanyIdentities(
                        workspaceId,
                        bean,
                        row,
                        bean.getWebsite() != null,
                        bean.getPhone() != null);
                    attachTags(workspaceId, "company", bean.getId(), row.tagNames, tagByName);
                    applyCustomValues(
                        "company",
                        bean.getId(),
                        row.custom,
                        columnToDef,
                        action,
                        Map.of());
                }
            }

            int created = beans.size();
            List<RowError> failed = collectFailures(plan);
            auditImport(
                "company",
                created,
                updated,
                skipped,
                failed.size(),
                allRowsFailedBecauseTargetsVanished(plan));
            return new ImportResult(created, updated, skipped, failed);
        });
    }

    private ImportAnalysis analyzeCompanies(
            ImportRequest request,
            int workspaceId,
            ImportPhase phase,
            DuplicatePreflightService.ImportCommitAdmission admission, @Nullable RecordVisibilityScope operationVisibilityScope) {
        return analyzeCompanies(
            request,
            workspaceId,
            phase,
            admission,
            importReviewContext("company", workspaceId, request), operationVisibilityScope);
    }

    private ImportAnalysis analyzeCompanies(
            ImportRequest request,
            int workspaceId,
            ImportPhase phase,
            DuplicatePreflightService.ImportCommitAdmission admission,
            String reviewContext, @Nullable RecordVisibilityScope operationVisibilityScope) {
        Map<String, ColumnMapping> byColumn = mappingByColumn(request.getMapping(), COMPANY_FIELDS);
        Map<String, CustomFieldDefinition> defs = customDefsByColumn("company", request.getMapping());
        List<PlanRow> plan = collectRows(request, byColumn, defs, null, null, null, null);

        String reviewProof = applyCompanyDuplicatePreflight(
            plan,
            phase,
            reviewContext,
            admission);
        try {
            Map<Integer, Integer> links =
                request.getLinks() == null ? Map.of() : request.getLinks();
            for (PlanRow row : plan) {
                if (INVALID.equals(row.status) || SKIP.equals(row.status)) continue;
                Integer linked = links.get(row.rowIndex);
                if (linked != null) {
                    Company existing = getOwnedCompany(workspaceId, linked, operationVisibilityScope);
                    if (existing == null) {
                        fail(row, "Linked company #" + linked + " not found");
                        continue;
                    }
                    if (manualLinkConflicts(
                            row, linked, "company", "company")) {
                        continue;
                    }
                    markMatch(row, existing.getId(), existing.getName());
                    continue;
                }
                if (row.duplicateCandidatesTruncated) {
                    fail(row, "Duplicate candidates were truncated; split or refine the import");
                    continue;
                }
                applyStrongMatch(row, "company", "company");
            }
            Function<PlanRow, List<String>> keys = row -> identityKeys(
                IdentityKind.DOMAIN, row.std.get("website"),
                IdentityKind.PHONE, row.std.get("phone"));
            validateWithinFileStrongKeys(plan, keys);
            List<PlanRow> mutations = coalesceByCanonicalTarget(
                plan, resolveAction(request.getOnDuplicate()), keys);
            return new ImportAnalysis(plan, mutations, reviewProof);
        } catch (RuntimeException exception) {
            cancelPreviewAnalysis(phase, reviewProof);
            throw exception;
        }
    }

    private boolean applyCompanyUpdate(int workspaceId, PlanRow row, String action,
            Map<String, Integer> columnToDef,
            Map<Integer, Map<Integer, Object>> customValues,
            Map<String, Integer> tagByName,
            Map<Integer, Company> lockedTargets) {
        Company existing = lockedTargets.get(row.matchedId);
        if (existing == null) {
            fail(row, "Matched company #" + row.matchedId + " not found");
            return false;
        }
        String beforeName = existing.getName();
        String beforeWebsite = existing.getWebsite();
        String beforeIndustry = existing.getIndustry();
        String beforePhone = existing.getPhone();
        String beforeAddress = existing.getAddress();
        existing.setName(merge(action, existing.getName(), row.std.get("name")));
        existing.setWebsite(merge(action, existing.getWebsite(), row.std.get("website")));
        existing.setIndustry(merge(action, existing.getIndustry(), row.std.get("industry")));
        existing.setPhone(merge(action, existing.getPhone(), row.std.get("phone")));
        existing.setAddress(merge(action, existing.getAddress(), row.std.get("address")));
        boolean websiteAcquired = row.std.containsKey("website")
            && (!Objects.equals(beforeWebsite, existing.getWebsite())
                || missingCanonicalIdentity(
                    row, IdentityKind.DOMAIN, existing.getWebsite()));
        boolean phoneAcquired = row.std.containsKey("phone")
            && (!Objects.equals(beforePhone, existing.getPhone())
                || missingCanonicalIdentity(
                    row, IdentityKind.PHONE, existing.getPhone()));
        boolean parentChanged =
            !Objects.equals(beforeName, existing.getName())
                || !Objects.equals(beforeWebsite, existing.getWebsite())
                || !Objects.equals(beforeIndustry, existing.getIndustry())
                || !Objects.equals(beforePhone, existing.getPhone())
                || !Objects.equals(beforeAddress, existing.getAddress());
        if (parentChanged) {
            companyMapper.update(existing);
        }
        boolean identitiesChanged = recordCompanyIdentities(
            workspaceId, existing, row, websiteAcquired, phoneAcquired);
        boolean referencesChanged = attachTags(
            workspaceId, "company", existing.getId(), row.tagNames, tagByName);
        boolean customChanged = applyCustomValues(
            "company",
            existing.getId(),
            row.custom,
            columnToDef,
            action,
            customValues.getOrDefault(existing.getId(), Map.of()));
        return parentChanged
            || identitiesChanged
            || referencesChanged
            || customChanged;
    }

    private Company getOwnedCompany(int workspaceId, int companyId, @Nullable RecordVisibilityScope operationVisibilityScope) {
        if (!companyMapper.existsOwned(workspaceId, companyId)) return null;
        return companyMapper.getCompanyById(workspaceId, (operationVisibilityScope == null
            ? recordVisibilityScopeControlAccess.getForWorkspace(workspaceId)
            : operationVisibilityScope).workspaceIdsJson(), companyId);
    }

    private String applyCompanyDuplicatePreflight(
            List<PlanRow> plan,
            ImportPhase phase,
            String reviewContext,
            DuplicatePreflightService.ImportCommitAdmission admission) {
        List<PlanRow> eligible = plan.stream()
            .filter(row -> CREATE.equals(row.status) || MATCH.equals(row.status))
            .toList();
        List<CompanyDuplicatePreflightRequest> requests = eligible.stream()
            .map(row -> new CompanyDuplicatePreflightRequest(
                row.std.get("name"),
                valueList(row.std.get("website")),
                valueList(row.std.get("phone"))))
            .toList();
        if (phase == ImportPhase.PREVIEW) {
            DuplicateImportPreflightReview review =
                duplicatePreflightService.preflightCompanyImportPreview(
                    requests, reviewContext);
            String reviewProof = review.reviewProof();
            try {
                applyDuplicateCandidates(eligible, review.responses());
                return reviewProof;
            } catch (RuntimeException exception) {
                duplicatePreflightService.cancelImportPreview(reviewProof);
                throw exception;
            }
        }
        List<DuplicatePreflightResponse> responses =
            duplicatePreflightService.preflightCompanyImportCommit(
                requests, reviewContext, admission);
        applyDuplicateCandidates(eligible, responses);
        return null;
    }

    private void applyDuplicateCandidates(
            List<PlanRow> rows,
            List<DuplicatePreflightResponse> responses) {
        if (rows.size() != responses.size()) {
            throw new IllegalStateException(
                "Duplicate preflight did not preserve import row cardinality");
        }
        for (int index = 0; index < rows.size(); index++) {
            PlanRow row = rows.get(index);
            DuplicatePreflightResponse response = responses.get(index);
            row.duplicateCandidates.addAll(response.candidates());
            row.duplicateCandidatesTruncated = response.truncated();
        }
    }

    private CompanyDependencyReview companyDependencyReview(List<PlanRow> plan) {
        Map<String, List<PlanRow>> rowsByName = new LinkedHashMap<>();
        for (PlanRow row : plan) {
            if (INVALID.equals(row.status) || SKIP.equals(row.status) || row.companyName == null) {
                continue;
            }
            Optional<String> normalized =
                matchingService.normalizeName(row.companyName);
            if (normalized.isEmpty()) {
                fail(row, "Company name is invalid");
                continue;
            }
            rowsByName.computeIfAbsent(
                normalized.orElseThrow(),
                ignored -> new ArrayList<>()).add(row);
        }
        List<CompanyDuplicatePreflightRequest> requests = new ArrayList<>();
        List<List<PlanRow>> rowsByRequest = new ArrayList<>();
        for (Map.Entry<String, List<PlanRow>> entry : rowsByName.entrySet()) {
            List<PlanRow> rows = entry.getValue();
            for (PlanRow row : rows) {
                row.companyDependencyKey = entry.getKey();
            }
            requests.add(new CompanyDuplicatePreflightRequest(
                rows.getFirst().companyName,
                List.of(),
                List.of()));
            rowsByRequest.add(List.copyOf(rows));
        }
        return new CompanyDependencyReview(
            List.copyOf(requests),
            List.copyOf(rowsByRequest));
    }

    private void applyCompanyDependencyCandidates(
            CompanyDependencyReview review,
            List<DuplicatePreflightResponse> responses) {
        if (review.requests().size() != responses.size()) {
            throw new IllegalStateException(
                "Company dependency preflight did not preserve request cardinality");
        }
        for (int index = 0; index < responses.size(); index++) {
            DuplicatePreflightResponse response = responses.get(index);
            List<PlanRow> rows = review.rowsByRequest().get(index);
            for (PlanRow row : rows) {
                row.companyDependencyCandidates = response.candidates();
                row.companyDependencyTruncated = response.truncated();
                if (response.truncated()) {
                    row.companyDependencyError =
                        "Company dependency candidates were truncated";
                } else if (row.selectedCompanyId != null) {
                    boolean visible = response.candidates().stream().anyMatch(candidate ->
                        "company".equals(candidate.recordType())
                            && candidate.recordId() == row.selectedCompanyId);
                    if (visible) {
                        row.resolvedCompanyId = row.selectedCompanyId;
                    } else {
                        row.companyDependencyError = "Company selection is no longer available";
                    }
                } else if (!response.candidates().isEmpty()) {
                    row.companyDependencyError = response.candidates().size() > 1
                        ? "Multiple visible companies match the company name"
                        : matchingService.normalizeName(review.requests().get(index).name())
                            .equals(matchingService.normalizeName(response.candidates().getFirst().name()))
                            ? "Company name requires an explicit company selection"
                            : "Company name variant requires an explicit company selection";
                }
            }
        }
    }

    private static void applyCompanyDependencyFailures(
            List<PlanRow> plan,
            Predicate<PlanRow> dependencyRequired) {
        for (PlanRow row : plan) {
            row.companyDependencyRequired = dependencyRequired.test(row);
            if (row.companyDependencyRequired) {
                Set<Integer> choices = row.sourceRows.stream()
                    .map(source -> source.selectedCompanyId)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
                if (choices.size() > 1) {
                    fail(row, "Conflicting company choices for combined rows");
                    continue;
                }
                boolean unresolvedContributor = row.sourceRows.stream().anyMatch(source ->
                    Objects.equals(source.companyDependencyKey, row.companyDependencyKey)
                        && source.companyDependencyError != null);
                if (unresolvedContributor && row.companyDependencyError == null) {
                    fail(row, "Company name requires an explicit company selection");
                    continue;
                }
            }
            if (row.companyDependencyError != null
                    && row.companyDependencyRequired) {
                fail(row, row.companyDependencyError);
            }
        }
        for (PlanRow row : plan) {
            for (PlanRow source : row.sourceRows) {
                source.companyDependencyRequired = row.companyDependencyRequired
                    && Objects.equals(source.companyDependencyKey, row.companyDependencyKey);
            }
        }
    }

    private static void applyStrongMatch(
            PlanRow row,
            String recordType,
            String entityLabel) {
        List<DuplicateCandidateDto> strong = row.duplicateCandidates.stream()
            .filter(candidate -> recordType.equals(candidate.recordType()))
            .filter(ImportService::hasAutomaticMatchEvidence)
            .toList();
        if (strong.isEmpty()) return;
        if (strong.size() > 1) {
            fail(row, "Multiple visible " + entityLabel + " records match supplied identities");
            return;
        }
        DuplicateCandidateDto candidate = strong.getFirst();
        if (!candidate.ownedByActiveWorkspace()) {
            fail(row, "A shared " + entityLabel + " matches supplied identities");
            return;
        }
        markMatch(row, candidate.recordId(), candidate.name());
        row.automaticIdentityMatch = true;
    }

    private static boolean hasAutomaticMatchEvidence(DuplicateCandidateDto candidate) {
        return candidate.strength() == DuplicateMatchStrength.STRONG
            && (!"person".equals(candidate.recordType())
                || candidate.matches().stream().anyMatch(match ->
                    match.kind() == DuplicateMatchKind.EMAIL
                        && match.strength() == DuplicateMatchStrength.STRONG));
    }

    private static boolean manualLinkConflicts(
            PlanRow row,
            int linkedId,
            String recordType,
            String entityLabel) {
        boolean conflicts = row.duplicateCandidates.stream()
            .filter(candidate -> recordType.equals(candidate.recordType()))
            .filter(ImportService::hasAutomaticMatchEvidence)
            .anyMatch(candidate -> candidate.recordId() != linkedId);
        if (conflicts) {
            fail(
                row,
                "Supplied identity belongs to another " + entityLabel);
        }
        return conflicts;
    }

    private record CompanyDependencyReview(
            List<CompanyDuplicatePreflightRequest> requests,
            List<List<PlanRow>> rowsByRequest) {
    }

    private static List<String> valueList(String value) {
        return value == null ? List.of() : List.of(value);
    }

    // ===================================================================================
    // Deals
    // ===================================================================================

    /**
     * Dry-run a deal import: validate and deduplicate without writing.
     */
    @RequirePermission(Permission.DEAL_CREATE)
    public ImportPreviewResult previewDeals(ImportRequest request) {
        RecordVisibilityScope resolvedVisibilityScope = recordVisibilityScopeControlAccess.getForWorkspace(workspaceService.getCurrentWorkspaceId());
        return recordVisibilityScopeControlAccess.withWorkspaceScope(
            workspaceService.getCurrentWorkspaceId(), resolvedVisibilityScope, () -> previewDealsWithinVisibilityScope(request, resolvedVisibilityScope));
    }

    private ImportPreviewResult previewDealsWithinVisibilityScope(ImportRequest request, RecordVisibilityScope operationVisibilityScope) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String defaultCurrency = workspaceService.getCurrentDefaultCurrency();
        RecordVisibilityScope scope = operationVisibilityScope;
        String action = resolveAction(request.getOnDuplicate());
        ImportAnalysis analysis = analyzeDeals(
            request,
            workspaceId,
            ImportPhase.PREVIEW,
            null,
            importReviewContext("deal", workspaceId, request, defaultCurrency), scope);
        List<PlanRow> plan = analysis.plan();
        try {
            preflightPreview(
                "deal",
                workspaceId,
                request.getMapping(),
                analysis.mutations(),
                action,
                Permission.DEAL_UPDATE);
            ImportPreviewResult result = summarize(
                plan, analysis.mutations(), action);
            result.setDuplicateReviewProof(analysis.reviewProof());
            result.setDefaultCurrency(defaultCurrency);
            return result;
        } catch (RuntimeException exception) {
            duplicatePreflightService.cancelImportPreview(analysis.reviewProof());
            throw exception;
        }
    }

    /**
     * Commit a deal import. Resolves pipeline/stage by name (defaulting to the first pipeline and its
     * first stage), links existing people by email, and deduplicates on name + company.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.DEAL_CREATE)
    public ImportResult commitDeals(ImportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String defaultCurrency = workspaceService.getCurrentDefaultCurrency();
        String action = resolveAction(request.getOnDuplicate());
        String reviewContext = importReviewContext("deal", workspaceId, request, defaultCurrency);
        DuplicatePreflightService.ImportCommitAdmission admission =
            duplicatePreflightService.claimImportCommit(
                request.getDuplicateReviewProof(), reviewContext);
        RecordVisibilityScope scope = recordVisibilityScopeControlAccess.getForWorkspace(workspaceId);
        return recordVisibilityScopeControlAccess.withWorkspaceScope(workspaceId, scope, () -> {

            lockImportPermission(workspaceId, Permission.DEAL_CREATE);
            duplicateDecisionLockService.lockCurrentOrganization();
            int actorId = authService.getCurrentUser().getId();
            ImportAnalysis analysis = analyzeDeals(
                request,
                workspaceId,
                ImportPhase.COMMIT,
                admission,
                reviewContext, scope);
            List<PlanRow> plan = analysis.plan();
            List<PlanRow> mutations = analysis.mutations();
            requireUpdatePermission(mutations, action, Permission.DEAL_UPDATE);
            lockCustomFieldTemplateSetBeforeTargets(
                "deal", request.getMapping(), mutations, action);

            Map<Integer, Deal> lockedTargets = lockMatchedTargets(
                mutations,
                action,
                id -> dealMapper.getDealByIdForUpdate(workspaceId, id),
                "deal");
            validateDealUpdates(workspaceId, mutations, action, lockedTargets);
            reviewDealDates(workspaceId, mutations, action, scope,
                workspaceService.getCurrentAnalyticsTimezone(), lockedTargets);
            Map<String, Integer> columnToDef = resolveCustomDefinitions(
                "deal", request.getMapping(), mutations, action);
            Map<Integer, Map<Integer, Object>> customValues =
                existingCustomValues("deal", mutations, action);
            Map<String, Integer> tagByName = resolveTags(mutations, action);
            Map<String, Integer> companyByName = resolveCompanies(mutations, action);
            Map<String, Integer> personByEmail =
                resolveDealPeople(workspaceId, mutations, action);
            List<PlanRow> toCreate = new ArrayList<>();
            List<Deal> beans = new ArrayList<>();
            List<DealOutcomeWriter.NewDeal> newDeals = new ArrayList<>();
            List<DealOutcomeWriter.OutcomeChange> outcomeChanges = new ArrayList<>();
            int updated = 0;
            int skipped = 0;

            for (PlanRow row : mutations) {
                if (INVALID.equals(row.status)) continue;
                if (SKIP.equals(row.status)) { skipped++; continue; }
                if (MATCH.equals(row.status)) {
                    if (SKIP.equals(action)) { skipped++; continue; }
                    if (applyDealUpdate(
                            workspaceId,
                            row,
                            action,
                            columnToDef,
                            customValues,
                            tagByName,
                            companyByName,
                            personByEmail,
                            outcomeChanges,
                            lockedTargets, scope)) {
                        updated++;
                    }
                    continue;
                }
                Deal bean = new Deal();
                bean.setWorkspaceId(workspaceId);
                bean.setOwnerId(actorId);
                bean.setName(row.std.get("name"));
                bean.setValue(parseValue(row.std.get("value")));
                String currency = row.std.get("currency");
                bean.setCurrency(currency != null ? currency : defaultCurrency);
                bean.setExpectedCloseDate(row.std.get("expectedCloseDate"));
                bean.setClosedAt(row.std.get("closedAt"));
                bean.setCreatedAt(row.std.get("createdAt"));
                bean.setPipelineId(row.resolvedPipelineId);
                bean.setStageId(row.resolvedStageId);
                bean.setCompanyId(companyId(row, companyByName));
                newDeals.add(new DealOutcomeWriter.NewDeal(bean, null, row.reviewedOutcome));
                beans.add(bean);
                toCreate.add(row);
            }

            if (!beans.isEmpty()) {
                outcomeChanges.addAll(dealOutcomeWriter.createBatch(newDeals));
                for (int i = 0; i < beans.size(); i++) {
                    Deal bean = beans.get(i);
                    PlanRow row = toCreate.get(i);
                    if (bean.getStageId() != null) {
                        dealStageHistoryService.recordInitial(
                            workspaceId, bean.getId(), bean.getStageId(), bean.getWon(), scope);
                    }
                    attachDealTags(workspaceId, bean.getId(), row.tagNames, tagByName);
                    linkDealPeople(workspaceId, bean.getId(), row.peopleEmails, personByEmail, scope);
                    applyCustomValues(
                        "deal",
                        bean.getId(),
                        row.custom,
                        columnToDef,
                        action,
                        Map.of());
                }
            }

            int created = beans.size();
            List<RowError> failed = collectFailures(plan);
            auditImport(
                "deal",
                created,
                updated,
                skipped,
                failed.size(),
                allRowsFailedBecauseTargetsVanished(plan));
            outcomeChanges.forEach(dealOutcomeWriter::publish);
            return new ImportResult(created, updated, skipped, failed);
        });
    }

    private ImportAnalysis analyzeDeals(
            ImportRequest request,
            int workspaceId,
            ImportPhase phase,
            DuplicatePreflightService.ImportCommitAdmission admission,
            String reviewContext, @Nullable RecordVisibilityScope scope) {
        Map<String, ColumnMapping> byColumn = mappingByColumn(request.getMapping(), DEAL_FIELDS);
        Map<String, CustomFieldDefinition> defs = customDefsByColumn("deal", request.getMapping());
        List<PlanRow> plan = collectRows(request, byColumn, defs, "company", "pipeline", "stage", "people");
        ZoneId dateZone = ZoneId.of(workspaceService.getCurrentAnalyticsTimezone());
        validateDealDates(plan, dateZone);
        CompanyDependencyReview companyReview = companyDependencyReview(plan);
        DuplicatePreflightService.ImportPreviewSession previewSession = null;
        DuplicatePreflightService.ImportCommitSession commitSession = null;
        List<DuplicatePreflightResponse> responses;
        String reviewProof = null;
        if (phase == ImportPhase.PREVIEW) {
            previewSession = duplicatePreflightService.beginImportPreview(
                List.of(),
                companyReview.requests(),
                reviewContext);
            responses = previewSession.responses();
            reviewProof = previewSession.reviewProof();
        } else {
            commitSession = duplicatePreflightService.beginImportCommit(
                List.of(),
                companyReview.requests(),
                reviewContext,
                admission);
            responses = commitSession.responses();
        }
        try {
            applyCompanyDependencyCandidates(companyReview, responses);
            Map<String, List<Integer>> byNameCompany = new HashMap<>();
            for (Deal existing : dealMapper.getDealsForDedup(workspaceId)) {
                String normalizedName = normName(existing.getName());
                if (normalizedName == null) {
                    continue;
                }
                byNameCompany.computeIfAbsent(
                    DealDuplicateKey.of(normalizedName, existing.getCompanyId()),
                    ignored -> new ArrayList<>()).add(existing.getId());
            }
            Map<Integer, Integer> links =
                request.getLinks() == null ? Map.of() : request.getLinks();
            for (PlanRow row : plan) {
                if (INVALID.equals(row.status) || SKIP.equals(row.status)) continue;
                Integer linked = links.get(row.rowIndex);
                if (linked != null) {
                    Deal existing = dealMapper.getDealById(workspaceId, linked);
                    if (existing == null) {
                        fail(row, "Linked deal #" + linked + " not found");
                        continue;
                    }
                    markMatch(row, existing.getId(), existing.getName());
                    continue;
                }
                boolean companyKnown =
                    row.companyName == null || row.resolvedCompanyId != null;
                String normalizedName = normName(row.std.get("name"));
                if (!companyKnown && normalizedName != null && !SKIP.equals(resolveAction(request.getOnDuplicate()))
                        && row.companyDependencyCandidates.stream().anyMatch(candidate ->
                            byNameCompany.containsKey(DealDuplicateKey.of(normalizedName, candidate.recordId())))) {
                    workspaceService.requirePermission(Permission.DEAL_UPDATE);
                }
                List<Integer> matchIds = companyKnown && normalizedName != null
                    ? byNameCompany.get(DealDuplicateKey.of(
                        normalizedName,
                        row.resolvedCompanyId))
                    : null;
                if (matchIds != null && matchIds.size() > 1) {
                    fail(row, "Multiple owned deals match the imported name and company");
                } else if (matchIds != null && !matchIds.isEmpty()) {
                    markMatch(row, matchIds.getFirst(), row.std.get("name"));
                }
            }
            Map<Integer, Integer> currencyTargets = new HashMap<>();
            for (PlanRow row : plan) {
                if (!row.std.containsKey("currency")) continue;
                Integer target = row.matchedId;
                if (target == null && INVALID.equals(row.status)) {
                    target = links.get(row.rowIndex);
                    boolean companyKnown = row.companyName == null || row.resolvedCompanyId != null;
                    String normalizedName = normName(row.std.get("name"));
                    List<Integer> matches = companyKnown && normalizedName != null
                        ? byNameCompany.get(DealDuplicateKey.of(normalizedName, row.resolvedCompanyId)) : null;
                    if (target == null && matches != null && matches.size() == 1) target = matches.getFirst();
                }
                if (target != null) currencyTargets.put(row.rowIndex, target);
            }
            List<Integer> currencyTargetIds = currencyTargets.values().stream().distinct().sorted().toList();
            Map<Integer, Deal> currencyTargetsById = currencyTargetIds.isEmpty() ? Map.of()
                : dealMapper.getByIds(workspaceId, currencyTargetIds).stream()
                    .collect(Collectors.toMap(Deal::getId, Function.identity()));
            for (PlanRow row : plan) {
                String currency = row.std.get("currency");
                if (currency == null) continue;
                Integer currencyTarget = currencyTargets.get(row.rowIndex);
                Deal existing = currencyTarget == null ? null : currencyTargetsById.get(currencyTarget);
                try {
                    row.std.put("currency", existing == null ? CurrencyCodes.required(currency)
                        : CurrencyCodes.updated(currency, existing.getCurrency()));
                } catch (BadRequestException exception) {
                    fail(row, "CURRENCY_INVALID");
                }
            }
            String action = resolveAction(request.getOnDuplicate());
            List<PlanRow> mutations = coalesceByCanonicalTarget(
                plan, action, row -> List.of(dealCanonicalKey(row)));
            resolveStages(workspaceId, mutations, scope);
            applyCompanyDependencyFailures(
                mutations,
                row -> dealCompanyDependencyRequired(
                    workspaceId, row, action));
            reviewDealDates(workspaceId, mutations, action, scope, dateZone.getId(), null);
            String decisionFingerprint = dealDecisionFingerprint(plan);
            if (phase == ImportPhase.PREVIEW) {
                duplicatePreflightService.completeImportPreview(
                    Objects.requireNonNull(previewSession),
                    decisionFingerprint);
            } else {
                duplicatePreflightService.completeImportCommit(
                    Objects.requireNonNull(commitSession),
                    decisionFingerprint);
            }
            return new ImportAnalysis(plan, mutations, reviewProof);
        } catch (RuntimeException exception) {
            cancelPreviewAnalysis(phase, reviewProof);
            throw exception;
        }
    }

    private static void validateDealDates(List<PlanRow> plan, ZoneId zone) {
        for (PlanRow row : plan) {
            for (String field : List.of("closedAt", "createdAt")) {
                String raw = row.std.get(field);
                if (raw == null) continue;
                try {
                    Instant instant = raw.matches("(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{4}/[0-9]{2}/[0-9]{2})")
                        ? LocalDate.parse(raw.replace('/', '-')).atStartOfDay(zone).toInstant()
                        : Instant.parse(raw);
                    LocalDateTime utc = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
                    if (utc.getYear() < 1000 || utc.getYear() > 9999) {
                        throw new DateTimeException("Date is outside the database range");
                    }
                    row.std.put(field, utc.format(IMPORT_DATETIME));
                } catch (DateTimeException exception) {
                    fail(row, "Invalid " + ("closedAt".equals(field) ? "Closed at" : "Created at")
                        + ": expected yyyy-MM-dd, yyyy/MM/dd or an ISO instant (years 1000–9999)");
                }
            }
        }
    }

    private void reviewDealDates(
            int workspaceId, List<PlanRow> mutations, String action,
            RecordVisibilityScope scope, String timezone, Map<Integer, Deal> lockedTargets) {
        Map<Integer, String> outcomes = new HashMap<>();
        Instant now = Instant.now();
        for (PlanRow row : mutations) {
            if (!willWrite(row, action)) continue;
            Deal existing = MATCH.equals(row.status) && row.matchedId != null
                ? lockedTargets == null ? dealMapper.getDealById(workspaceId, row.matchedId)
                    : lockedTargets.get(row.matchedId)
                : null;
            Integer stageId = existing == null || OVERWRITE.equals(action) && row.stageName != null
                ? row.resolvedStageId : existing.getStageId();
            String outcome = outcomeFor(workspaceId, stageId, outcomes, scope);
            boolean closed = "won".equals(outcome) || "lost".equals(outcome)
                || existing != null && existing.getWon() != null;
            String closedAt = closed
                ? merge(action, existing == null ? null : existing.getClosedAt(), row.std.get("closedAt"))
                : null;
            String createdAt = merge(action, existing == null ? null : existing.getCreatedAt(), row.std.get("createdAt"));
            DealImportDateReview dates = new DealImportDateReview(
                importDateInstant(closedAt), importDateInstant(createdAt), timezone,
                closed && closedAt == null,
                closedAt != null && LocalDateTime.parse(closedAt, IMPORT_DATETIME)
                    .toInstant(ZoneOffset.UTC).isAfter(now));
            if (lockedTargets != null && (!dates.equals(row.dealDates)
                    || !Objects.equals(stageId, row.reviewedStageId)
                    || !Objects.equals(outcome, row.reviewedOutcome))) {
                throw new ConflictException("Deal dates changed. Preview the import again before committing");
            }
            row.dealDates = dates;
            row.reviewedStageId = stageId;
            row.reviewedOutcome = outcome;
            for (PlanRow source : row.sourceRows) {
                source.dealDates = dates;
                source.reviewedStageId = stageId;
                source.reviewedOutcome = outcome;
            }
        }
    }

    private static String importDateInstant(String stored) {
        return stored == null ? null : LocalDateTime.parse(stored, IMPORT_DATETIME)
            .toInstant(ZoneOffset.UTC).toString();
    }

    private boolean dealCompanyDependencyRequired(
            int workspaceId,
            PlanRow row,
            String action) {
        if (row.companyName == null || !willWrite(row, action)) {
            return false;
        }
        if (!MATCH.equals(row.status) || OVERWRITE.equals(action)) {
            return true;
        }
        if (row.matchedId == null) {
            return true;
        }
        Deal existing = dealMapper.getDealById(workspaceId, row.matchedId);
        return existing == null || existing.getCompanyId() == null;
    }

    private static String dealDecisionFingerprint(List<PlanRow> plan) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        updateReviewDigest(digest, "connex-deal-import-decision-v2");
        updateReviewDigest(digest, Integer.toString(plan.size()));
        for (PlanRow row : plan) {
            updateReviewDigest(digest, Integer.toString(row.rowIndex));
            updateReviewDigest(digest, row.status);
            updateReviewDigest(
                digest,
                row.matchedId == null ? null : row.matchedId.toString());
            updateReviewDigest(digest, row.dealDates == null ? null : row.dealDates.toString());
            updateReviewDigest(digest, Objects.toString(row.reviewedStageId, null));
            updateReviewDigest(digest, row.reviewedOutcome);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean applyDealUpdate(
            int workspaceId,
            PlanRow row,
            String action,
            Map<String, Integer> columnToDef,
            Map<Integer, Map<Integer, Object>> customValues,
            Map<String, Integer> tagByName,
            Map<String, Integer> companyByName,
            Map<String, Integer> personByEmail,
            List<DealOutcomeWriter.OutcomeChange> outcomeChanges,
            Map<Integer, Deal> lockedTargets, RecordVisibilityScope scope) {
        Deal existing = Objects.requireNonNull(lockedTargets.get(row.matchedId));
        Integer beforeStageId = existing.getStageId();
        Boolean beforeOutcome = existing.getWon();
        String beforeName = existing.getName();
        BigDecimal beforeValue = existing.getValue();
        String beforeCurrency = existing.getCurrency();
        Integer beforePipelineId = existing.getPipelineId();
        Integer beforeCompanyId = existing.getCompanyId();
        String beforeExpectedCloseDate = existing.getExpectedCloseDate();
        String beforeClosedAt = existing.getClosedAt();
        String beforeClosedReason = existing.getClosedReason();
        DealUpdateValues updateValues = dealUpdateValues(existing, row, action);
        existing.setName(merge(action, existing.getName(), row.std.get("name")));
        existing.setCurrency(updateValues.currency());
        existing.setExpectedCloseDate(merge(action, existing.getExpectedCloseDate(), row.std.get("expectedCloseDate")));
        existing.setClosedAt(merge(action, existing.getClosedAt(), row.std.get("closedAt")));
        String importedCreatedAt = merge(action, existing.getCreatedAt(), row.std.get("createdAt"));
        boolean createdAtChanged = !Objects.equals(existing.getCreatedAt(), importedCreatedAt);
        boolean valueChanged = false;
        if (updateValues.shouldUpdateValue()) {
            existing.setValue(dealValueService.setManualValue(
                workspaceId, existing, updateValues.value()));
            valueChanged = true;
        }
        Integer companyId = companyId(row, companyByName);
        if (companyId != null && (OVERWRITE.equals(action) || existing.getCompanyId() == null)) {
            existing.setCompanyId(companyId);
        }
        if (OVERWRITE.equals(action) && row.stageName != null && row.resolvedStageId != null) {
            existing.setPipelineId(row.resolvedPipelineId);
            existing.setStageId(row.resolvedStageId);
        }
        String outcome = row.reviewedOutcome;
        dealOutcomeWriter.applyOutcome(existing, outcome);
        Integer afterStageId = existing.getStageId();
        boolean parentChanged =
            !Objects.equals(beforeName, existing.getName())
                || beforeValue.compareTo(existing.getValue()) != 0
                || !Objects.equals(beforeCurrency, existing.getCurrency())
                || !Objects.equals(beforePipelineId, existing.getPipelineId())
                || !Objects.equals(beforeStageId, afterStageId)
                || !Objects.equals(beforeCompanyId, existing.getCompanyId())
                || !Objects.equals(
                    beforeExpectedCloseDate, existing.getExpectedCloseDate())
                || !Objects.equals(beforeClosedAt, existing.getClosedAt())
                || !Objects.equals(beforeClosedReason, existing.getClosedReason())
                || !Objects.equals(beforeOutcome, existing.getWon());
        if (parentChanged) {
            outcomeChanges.add(dealOutcomeWriter.write(workspaceId, existing, beforeOutcome, null, outcome));
        }
        if (createdAtChanged) {
            dealMapper.updateImportedCreatedAt(workspaceId, existing.getId(), importedCreatedAt);
        }
        if (afterStageId != null && !afterStageId.equals(beforeStageId)) {
            dealStageHistoryService.recordTransition(
                workspaceId, existing.getId(), afterStageId, beforeOutcome, existing.getWon(), scope);
        }
        boolean tagsChanged = attachDealTags(
            workspaceId, existing.getId(), row.tagNames, tagByName);
        boolean peopleChanged = linkDealPeople(
            workspaceId, existing.getId(), row.peopleEmails, personByEmail, scope);
        boolean customChanged = applyCustomValues(
            "deal",
            existing.getId(),
            row.custom,
            columnToDef,
            action,
            customValues.getOrDefault(existing.getId(), Map.of()));
        return parentChanged || createdAtChanged || valueChanged || tagsChanged || peopleChanged || customChanged;
    }

    private void validateDealUpdates(
            int workspaceId,
            List<PlanRow> plan,
            String action,
            Map<Integer, Deal> lockedTargets) {
        for (PlanRow row : plan) {
            if (!MATCH.equals(row.status) || !willWrite(row, action)) {
                continue;
            }
            Deal existing = lockedTargets.get(row.matchedId);
            if (existing == null) {
                fail(row, "Matched deal #" + row.matchedId + " not found");
                continue;
            }
            DealUpdateValues updateValues;
            try {
                updateValues = dealUpdateValues(existing, row, action);
            } catch (BadRequestException exception) {
                fail(row, "CURRENCY_INVALID");
                continue;
            }
            boolean currencyChanged = !CurrencyCodes.equivalent(updateValues.currency(), existing.getCurrency());
            if ((currencyChanged || updateValues.shouldUpdateValue())
                    && dealLineItemMapper.countByDealIdForUpdate(
                        workspaceId, existing.getId()) > 0) {
                fail(row, currencyChanged
                    ? DEAL_CURRENCY_LINE_ITEM_CONFLICT
                    : DEAL_VALUE_LINE_ITEM_CONFLICT);
            }
        }
    }

    private static DealUpdateValues dealUpdateValues(
            Deal existing,
            PlanRow row,
            String action) {
        String currency = CurrencyCodes.updated(merge(
            action, existing.getCurrency(), row.std.get("currency")), existing.getCurrency());
        String value = row.std.get("value");
        BigDecimal parsedValue = parseValue(value);
        boolean shouldUpdateValue = value != null
            && (OVERWRITE.equals(action) || existing.getValue().signum() == 0)
            && existing.getValue().compareTo(parsedValue) != 0;
        return new DealUpdateValues(currency, parsedValue, shouldUpdateValue);
    }

    private void resolveStages(int workspaceId, List<PlanRow> plan, RecordVisibilityScope scope) {
        String orgWorkspaceIdsJson = scope.workspaceIdsJson();
        var pipelines = pipelineMapper.getAllPipelines(workspaceId, orgWorkspaceIdsJson);
        Map<String, Integer> pipelineByName = new HashMap<>();
        Map<Integer, Map<String, Integer>> stageByName = new HashMap<>();
        Map<Integer, Integer> firstStage = new HashMap<>();
        for (var pipeline : pipelines) {
            pipelineByName.put(pipeline.getName().toLowerCase(), pipeline.getId());
            Map<String, Integer> stages = new HashMap<>();
            List<Stage> list = pipelineMapper.getStagesByPipelineId(workspaceId, orgWorkspaceIdsJson, pipeline.getId());
            for (Stage stage : list) stages.put(stage.getName().toLowerCase(), stage.getId());
            stageByName.put(pipeline.getId(), stages);
            if (!list.isEmpty()) firstStage.put(pipeline.getId(), list.get(0).getId());
        }
        Integer defaultPipeline = pipelines.isEmpty() ? null : pipelines.get(0).getId();
        for (PlanRow row : plan) {
            if (INVALID.equals(row.status) || SKIP.equals(row.status)) continue;
            Integer pid = row.pipelineName != null ? pipelineByName.get(row.pipelineName.toLowerCase()) : defaultPipeline;
            if (pid == null) { fail(row, "Unknown pipeline or stage"); continue; }
            Map<String, Integer> stages = stageByName.getOrDefault(pid, Map.of());
            Integer sid = row.stageName != null ? stages.get(row.stageName.toLowerCase()) : firstStage.get(pid);
            if (sid == null) { fail(row, "Unknown pipeline or stage"); continue; }
            row.resolvedPipelineId = pid;
            row.resolvedStageId = sid;
        }
    }

    private String outcomeFor(int workspaceId, Integer stageId, Map<Integer, String> stageOutcome, RecordVisibilityScope scope) {
        return stageId == null ? "normal"
            : stageOutcome.computeIfAbsent(
                stageId, sid -> dealOutcomeWriter.stageOutcome(workspaceId, sid, scope));
    }

    private Map<String, Integer> resolveDealPeople(int workspaceId, List<PlanRow> plan, String action) {
        Set<String> emails = new LinkedHashSet<>();
        for (PlanRow row : plan) {
            if (willWrite(row, action)) emails.addAll(row.peopleEmails);
        }
        Map<String, Integer> byEmail = new HashMap<>();
        Map<String, List<IdentityMatchRow>> matches = currentPersonIdentityMatches(
            workspaceId, IdentityKind.EMAIL, emails);
        for (Map.Entry<String, List<IdentityMatchRow>> entry : matches.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new ImportValidationException("IMPORT_PARTICIPANT_AMBIGUOUS",
                    "Multiple contacts match a deal participant email");
            }
            byEmail.put(entry.getKey(), entry.getValue().getFirst().getRecordId());
        }
        return byEmail;
    }

    private boolean linkDealPeople(int workspaceId, int dealId, List<String> emails, Map<String, Integer> personByEmail, RecordVisibilityScope scope) {
        Set<Integer> linked = new LinkedHashSet<>();
        for (String email : emails) {
            Integer personId = personByEmail.get(email);
            if (personId != null) linked.add(personId);
        }
        if (linked.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Integer personId : linked) {
            changed |= dealMapper.addPersonIfAbsent(
                workspaceId, scope.workspaceIdsJson(), dealId, personId) > 0;
        }
        return changed;
    }

    private boolean attachDealTags(int workspaceId, int dealId, List<String> tagNames, Map<String, Integer> tagByName) {
        List<Integer> ids = tagIds(tagNames, tagByName);
        if (ids.isEmpty()) {
            return false;
        }
        return dealMapper.insertTags(workspaceId, dealId, ids) > 0;
    }

    private Map<String, List<IdentityMatchRow>> currentPersonIdentityMatches(
            int workspaceId,
            IdentityKind kind,
            Set<String> normalizedValues) {
        if (normalizedValues.isEmpty()) {
            return Map.of();
        }
        return matchesByValue(identityMapper.findCurrentPersonIdentityMatches(
            workspaceId, kind.getDatabaseValue(), List.copyOf(normalizedValues)));
    }

    private static Map<String, List<IdentityMatchRow>> matchesByValue(
            List<IdentityMatchRow> matches) {
        Map<String, List<IdentityMatchRow>> byValue = new HashMap<>();
        for (IdentityMatchRow match : matches) {
            byValue.computeIfAbsent(
                match.getNormalizedValue(), ignored -> new ArrayList<>()).add(match);
        }
        return byValue;
    }

    private String canonicalIdentity(IdentityKind kind, String rawValue) {
        return matchingService.normalizeIdentifier(kind, rawValue).orElse(null);
    }

    private static String importReviewContext(
            String recordType,
            int workspaceId,
            ImportRequest request) {
        return importReviewContext(recordType, workspaceId, request, null);
    }

    private static String importReviewContext(
            String recordType, int workspaceId, ImportRequest request, String defaultCurrency) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        updateReviewDigest(digest, "connex-import-review-v1");
        updateReviewDigest(digest, recordType);
        if (defaultCurrency != null) updateReviewDigest(digest, defaultCurrency);
        updateReviewDigest(digest, Integer.toString(workspaceId));
        updateReviewDigest(digest, resolveAction(request.getOnDuplicate()));
        updateReviewDigest(digest, Integer.toString(request.getRows().size()));
        for (Map<String, String> row : request.getRows()) {
            Map<String, String> orderedRow = new TreeMap<>(row);
            updateReviewDigest(digest, Integer.toString(orderedRow.size()));
            for (Map.Entry<String, String> entry : orderedRow.entrySet()) {
                updateReviewDigest(digest, entry.getKey());
                updateReviewDigest(digest, entry.getValue());
            }
        }
        updateReviewDigest(digest, Integer.toString(request.getMapping().size()));
        for (ColumnMapping mapping : request.getMapping()) {
            updateReviewDigest(digest, mapping.getColumn());
            updateReviewDigest(digest, mapping.getField());
            updateReviewDigest(
                digest,
                mapping.getCreateCustomField() == null
                    ? null
                    : mapping.getCreateCustomField().toString());
            updateReviewDigest(digest, mapping.getCustomFieldType());
            updateReviewDigest(digest, mapping.getCustomFieldLabel());
        }
        Map<Integer, Integer> orderedLinks = request.getLinks() == null
            ? Map.of()
            : new TreeMap<>(request.getLinks());
        updateReviewDigest(digest, Integer.toString(orderedLinks.size()));
        for (Map.Entry<Integer, Integer> entry : orderedLinks.entrySet()) {
            updateReviewDigest(digest, Integer.toString(entry.getKey()));
            updateReviewDigest(digest, Integer.toString(entry.getValue()));
        }
        Map<Integer, Integer> companyLinks = request.getCompanyLinks() == null
            ? Map.of() : new TreeMap<>(request.getCompanyLinks());
        updateReviewDigest(digest, Integer.toString(companyLinks.size()));
        for (Map.Entry<Integer, Integer> entry : companyLinks.entrySet()) {
            updateReviewDigest(digest, Integer.toString(entry.getKey()));
            updateReviewDigest(digest, Integer.toString(entry.getValue()));
        }
        Set<Integer> skippedRows = request.getSkippedRows() == null
            ? Set.of() : new TreeSet<>(request.getSkippedRows());
        updateReviewDigest(digest, Integer.toString(skippedRows.size()));
        for (Integer index : skippedRows) {
            updateReviewDigest(digest, Integer.toString(index));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateReviewDigest(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private String dealCanonicalKey(PlanRow row) {
        String normalizedName = normName(row.std.get("name"));
        if (normalizedName == null) {
            return "deal-row:" + row.rowIndex;
        }
        String companyKey;
        if (row.resolvedCompanyId != null) {
            companyKey = "id:" + row.resolvedCompanyId;
        } else if (row.companyDependencyKey != null) {
            companyKey = "name:" + row.companyDependencyKey;
        } else {
            companyKey = "none";
        }
        return "deal:"
            + encodedCanonicalPart(normalizedName)
            + encodedCanonicalPart(companyKey);
    }

    private static String encodedCanonicalPart(String value) {
        return value.length() + ":" + value;
    }

    /**
     * Parses an imported deal value against the bounds of the {@code deal.value} column, mirroring
     * the numeric guard {@code CustomFieldValueService} applies to custom fields. Every bound is
     * asserted on the parsed representation — length, scale, and integer-digit count — before any
     * rescaling, because {@code setScale} on an unbounded exponent such as {@code 1E100000000}
     * materializes a hundred-million-digit unscaled integer. The integer-digit comparison widens to
     * {@code long} so an extreme negative exponent cannot wrap it into a passing value. Preview and
     * commit both parse here, so a row admitted by one is admitted by the other.
     *
     * @param raw the cell value, or null when the column is absent
     * @return the value rounded to the persisted scale
     * @throws BadRequestException when the value is unparseable or outside the column's range
     */
    private static BigDecimal parseValue(String raw) {
        if (raw == null) return BigDecimal.ZERO.setScale(DEAL_VALUE_SCALE);
        String cell = raw.length() > 40 ? raw.substring(0, 40) + "..." : raw;
        if (raw.length() > MAX_VALUE_LENGTH) {
            throw new BadRequestException("Deal value is out of range: " + cell);
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.replaceAll("[,\\s]", ""));
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Invalid deal value: " + cell);
        }
        if (value.precision() > MAX_NUMERIC_DIGITS
                || value.scale() > MAX_NUMERIC_DIGITS
                || (long) value.precision() - value.scale() > DEAL_VALUE_INTEGER_DIGITS
                || value.abs().compareTo(DEAL_VALUE_ROUNDING_LIMIT) >= 0) {
            throw new BadRequestException("Deal value is out of range: " + cell);
        }
        return value.setScale(DEAL_VALUE_SCALE, RoundingMode.HALF_UP);
    }

    // ===================================================================================
    // Shared row collection + validation
    // ===================================================================================

    private List<PlanRow> collectRows(ImportRequest request, Map<String, ColumnMapping> byColumn,
            Map<String, CustomFieldDefinition> defs, String companyField, String pipelineField,
            String stageField, String peopleField) {
        List<Map<String, String>> rows = request.getRows();
        Set<Integer> skippedRows = request.getSkippedRows() == null ? Set.of() : request.getSkippedRows();
        Map<Integer, Integer> companyLinks = request.getCompanyLinks() == null ? Map.of() : request.getCompanyLinks();
        if (skippedRows.stream().anyMatch(index -> index == null || index < 0 || index >= rows.size())
                || companyLinks.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                    || entry.getKey() < 0 || entry.getKey() >= rows.size()
                    || entry.getValue() == null || entry.getValue() <= 0)) {
            throw new ImportValidationException("IMPORT_ROW_CHOICE_INVALID", "Import row choices are outside the file");
        }
        List<PlanRow> plan = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> raw = rows.get(i);
            PlanRow row = new PlanRow();
            row.rowIndex = i;
            row.status = CREATE;
            row.selectedCompanyId = companyLinks.get(i);
            if (skippedRows.contains(i)) {
                row.status = SKIP;
                plan.add(row);
                continue;
            }
            for (Map.Entry<String, ColumnMapping> entry : byColumn.entrySet()) {
                String column = entry.getKey();
                ColumnMapping mapping = entry.getValue();
                String value = cell(raw, column);
                if (value == null) continue;
                String field = mapping.getField();
                if ("tags".equals(field)) {
                    row.tagNames.addAll(splitMulti(value));
                } else if (companyField != null && companyField.equals(field)) {
                    row.companyName = value;
                } else if (pipelineField != null && pipelineField.equals(field)) {
                    row.pipelineName = value;
                } else if (stageField != null && stageField.equals(field)) {
                    row.stageName = value;
                } else if (peopleField != null && peopleField.equals(field)) {
                    for (String part : splitMulti(value)) {
                        String email = normEmail(part);
                        if (email != null) row.peopleEmails.add(email);
                    }
                } else if (defs.containsKey(column)) {
                    CustomFieldDefinition def = defs.get(column);
                    String stored = "number".equals(def.getFieldType()) ? value.replaceAll("[,\\s]", "") : value;
                    validateCustom(row, def, stored);
                    row.custom.put(column, stored);
                } else if (field != null) {
                    row.std.put(field, normalizeStandard(field, value));
                    row.stdSourceRows.put(field, i);
                }
            }
            row.label = row.std.get("name");
            validateRequired(row);
            plan.add(row);
        }
        return plan;
    }

    /**
     * Reports whether a value is address-shaped: exactly one {@code @} with a non-empty local part,
     * no whitespace, and a domain carrying an interior dot. Scans in linear time so an untrusted
     * import cell cannot drive quadratic regex backtracking.
     *
     * @param email the raw cell value
     * @return true when the value is address-shaped
     */
    private static boolean isEmailShaped(String email) {
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            return false;
        }
        for (int index = 0; index < email.length(); index++) {
            if (Character.isWhitespace(email.charAt(index))) {
                return false;
            }
        }
        int dot = email.indexOf('.', at + 2);
        return dot >= 0 && dot < email.length() - 1;
    }

    private void validateRequired(PlanRow row) {
        String name = row.std.get("name");
        if (name == null || name.isBlank()) {
            fail(row, "Name is required");
            return;
        }
        if (name.length() > 255) fail(row, "Name exceeds 255 characters");
        String email = row.std.get("email");
        if (email != null && !isEmailShaped(email)) {
            fail(row, "Invalid email: " + email);
        }
        String date = row.std.get("expectedCloseDate");
        if (date != null) {
            try {
                LocalDate.parse(date);
            } catch (RuntimeException e) {
                fail(row, "Invalid date (expected YYYY-MM-DD): " + date);
            }
        }
        String value = row.std.get("value");
        if (value != null) {
            try {
                parseValue(value);
            } catch (BadRequestException exception) {
                fail(row, exception.getMessage());
            }
        }
    }

    private void validateCustom(PlanRow row, CustomFieldDefinition def, String value) {
        try {
            customFieldValueService.validateValue(def, value);
        } catch (BadRequestException e) {
            fail(row, e.getMessage());
        }
    }

    private String normalizeStandard(String field, String value) {
        if ("email".equals(field)) return value.toLowerCase(Locale.ROOT);
        return value;
    }

    // ===================================================================================
    // Resolution: custom-field definitions, tags, companies
    // ===================================================================================

    private Map<String, CustomFieldDefinition> customDefsByColumn(String entityType, List<ColumnMapping> mapping) {
        Map<Integer, CustomFieldDefinition> byId = new HashMap<>();
        for (CustomFieldDefinition def
                : customFieldDefinitionMapper.getByEntityType(workspaceService.getCurrentWorkspaceId(), entityType)) {
            if (!def.isArchived()) byId.put(def.getId(), def);
        }
        Map<String, CustomFieldDefinition> result = new HashMap<>();
        Map<String, String> columnsByTarget = new HashMap<>();
        for (ColumnMapping cm : mapping) {
            String field = cm.getField();
            String targetKey = null;
            if (field != null && field.startsWith("custom:")) {
                CustomFieldDefinition def = byId.get(parseCustomId(field));
                if (def == null) {
                    throw new ImportValidationException("IMPORT_CUSTOM_UNAVAILABLE", "Mapped custom field is unavailable");
                }
                targetKey = def.getFieldKey();
                result.put(cm.getColumn(), def);
            } else if (Boolean.TRUE.equals(cm.getCreateCustomField())) {
                targetKey = customDefinitionKey(workspaceService.getCurrentWorkspaceId(), entityType, cm);
                CustomFieldDefinition placeholder = new CustomFieldDefinition();
                placeholder.setFieldType(normalizeCustomType(cm.getCustomFieldType()));
                placeholder.setLabel(customLabel(cm));
                result.put(cm.getColumn(), placeholder);
            }
            if (targetKey != null) {
                String previous = columnsByTarget.putIfAbsent(targetKey, cm.getColumn());
                if (previous != null) {
                    throw customColumnCollision("IMPORT_MAPPING_DUPLICATE",
                        "Multiple columns resolve to the same custom field", previous, cm.getColumn());
                }
            }
        }
        return result;
    }

    private Map<String, Integer> resolveCustomDefinitions(String entityType, List<ColumnMapping> mapping,
            List<PlanRow> plan, String action) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Set<String> usedNewColumns = usedNewCustomFieldColumns(plan, action);
        Map<Integer, CustomFieldDefinition> byId = new HashMap<>();
        for (CustomFieldDefinition def : customFieldDefinitionMapper.getByEntityType(workspaceId, entityType)) {
            if (!def.isArchived()) byId.put(def.getId(), def);
        }
        Map<String, Integer> columnToDef = new HashMap<>();
        for (ColumnMapping cm : mapping) {
            String field = cm.getField();
            if (field != null && field.startsWith("custom:")) {
                int id = parseCustomId(field);
                if (byId.containsKey(id)) columnToDef.put(cm.getColumn(), id);
            }
        }
        List<ColumnMapping> newMappings = mapping.stream()
            .filter(cm -> Boolean.TRUE.equals(cm.getCreateCustomField()))
            .filter(cm -> usedNewColumns.contains(cm.getColumn()))
            .toList();
        Map<String, String> definitionKeys = newCustomFieldKeys(workspaceId, entityType, newMappings);
        List<ColumnMapping> orderedMappings = newMappings.stream()
            .sorted(Comparator.comparing((ColumnMapping cm) -> definitionKeys.get(cm.getColumn()))
                .thenComparing(ColumnMapping::getColumn, String.CASE_INSENSITIVE_ORDER))
            .toList();
        for (ColumnMapping cm : orderedMappings) {
            columnToDef.put(cm.getColumn(), createDefinition(
                workspaceId, entityType, cm, definitionKeys.get(cm.getColumn())));
        }
        return columnToDef;
    }

    private Map<String, String> newCustomFieldKeys(
            int workspaceId, String entityType, List<ColumnMapping> mapping) {
        Map<String, String> keysByColumn = new LinkedHashMap<>();
        Map<String, String> columnsByKey = new HashMap<>();
        for (ColumnMapping cm : mapping) {
            String key = customDefinitionKey(workspaceId, entityType, cm);
            String prior = columnsByKey.putIfAbsent(key, cm.getColumn());
            if (prior != null) {
                throw customColumnCollision("IMPORT_CUSTOM_COLLISION",
                    "Columns '" + prior + "' and '" + cm.getColumn()
                        + "' would create the same custom field", prior, cm.getColumn());
            }
            keysByColumn.put(cm.getColumn(), key);
        }
        return keysByColumn;
    }

    private static ImportValidationException customColumnCollision(
            String code, String diagnostic, String first, String second) {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put(first, "IMPORT_MAPPING_DUPLICATE");
        errors.put(second, "IMPORT_MAPPING_DUPLICATE");
        return new ImportValidationException(code, diagnostic, errors);
    }

    private String customDefinitionKey(int workspaceId, String entityType, ColumnMapping cm) {
        String label = customLabel(cm);
        String key = slug(label);
        CustomFieldDefinition existing = customFieldDefinitionMapper.getByKey(workspaceId, entityType, key);
        if (sameCustomDefinition(existing, cm)) {
            return key;
        }
        String alternativeKey = hashedFieldKey(key, label, normalizeCustomType(cm.getCustomFieldType()));
        CustomFieldDefinition alternative = customFieldDefinitionMapper.getByKey(workspaceId, entityType, alternativeKey);
        if (sameCustomDefinition(alternative, cm)) {
            return alternativeKey;
        }
        if (existing == null) {
            return key;
        }
        if (alternative == null) {
            return alternativeKey;
        }
        throw new ImportValidationException("IMPORT_CUSTOM_CONFLICT",
            "Custom field '" + label + "' conflicts with an existing field; choose a different label or map to an existing field");
    }

    private static boolean sameCustomDefinition(@Nullable CustomFieldDefinition definition, ColumnMapping cm) {
        return definition != null
            && !definition.isArchived()
            && customLabel(cm).equals(definition.getLabel())
            && normalizeCustomType(cm.getCustomFieldType()).equals(definition.getFieldType());
    }

    private void lockCustomFieldTemplateSetBeforeTargets(
            String entityType,
            List<ColumnMapping> mapping,
            List<PlanRow> plan,
            String action) {
        Set<String> usedNewColumns = usedNewCustomFieldColumns(plan, action);
        List<ColumnMapping> usedMappings = mapping.stream()
            .filter(cm -> Boolean.TRUE.equals(cm.getCreateCustomField()))
            .filter(cm -> usedNewColumns.contains(cm.getColumn()))
            .toList();
        if (usedMappings.isEmpty()) {
            return;
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Map<String, String> definitionKeys = newCustomFieldKeys(workspaceId, entityType, usedMappings);
        Set<String> missingBeforeLock = missingDefinitionKeys(workspaceId, entityType, List.copyOf(definitionKeys.values()));
        if (!missingBeforeLock.isEmpty()) {
            workspaceService.lockAndRequirePermissions(
                workspaceId,
                Map.of(
                    workspaceService.getCurrentUserId(),
                    Set.of(Permission.CUSTOM_FIELD_MANAGE)));
        }
        RecordCreationRecordType recordType = RecordCreationRecordType.valueOf(entityType);
        recordCreationTemplateMapper.insertSetIfAbsent(workspaceId, recordType.name());
        if (recordCreationTemplateMapper.getSetForUpdate(workspaceId, recordType.name()) == null) {
            throw new IllegalStateException("Record creation template set could not be locked");
        }
        Map<String, String> lockedKeys = newCustomFieldKeys(workspaceId, entityType, usedMappings);
        if (!lockedKeys.equals(definitionKeys)
                || !missingDefinitionKeys(workspaceId, entityType, List.copyOf(lockedKeys.values())).equals(missingBeforeLock)) {
            throw new ConflictException(
                "Custom field definitions changed; review the import again");
        }
    }

    private Set<String> missingDefinitionKeys(
            int workspaceId, String entityType, List<String> definitionKeys) {
        Set<String> missing = new TreeSet<>();
        for (String key : definitionKeys) {
            if (customFieldDefinitionMapper.getByKey(workspaceId, entityType, key) == null) {
                missing.add(key);
            }
        }
        return missing;
    }

    private void lockImportPermission(int workspaceId, Permission permission) {
        workspaceService.lockAndRequirePermissions(
            workspaceId,
            Map.of(
                workspaceService.getCurrentUserId(),
                Set.of(permission)));
    }

    private int createDefinition(int workspaceId, String entityType, ColumnMapping cm, String key) {
        String type = normalizeCustomType(cm.getCustomFieldType());
        String label = customLabel(cm);
        CustomFieldDefinition existing = customFieldDefinitionMapper.getByKey(workspaceId, entityType, key);
        if (existing != null && sameCustomDefinition(existing, cm)) return existing.getId();
        if (existing != null) {
            throw new ConflictException("Custom field definitions changed; review the import again");
        }
        CustomFieldDefinition def = new CustomFieldDefinition();
        def.setEntityType(entityType);
        def.setFieldKey(key);
        def.setLabel(label);
        def.setFieldType(type);
        return customFieldDefinitionService.create(def, List.of()).getId();
    }

    private Map<String, Integer> resolveTags(List<PlanRow> plan, String action) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (PlanRow row : plan) {
            if (willWrite(row, action)) names.addAll(row.tagNames);
        }
        Map<String, Integer> byName = new HashMap<>();
        for (String name : names) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            Tag tag = tagMapper.getTagByName(workspaceId, trimmed);
            if (tag == null) {
                tag = new Tag();
                tag.setName(trimmed);
                tag.setColor(DEFAULT_TAG_COLOR);
                tag = tagService.create(tag);
            }
            byName.put(trimmed.toLowerCase(), tag.getId());
        }
        return byName;
    }

    private Map<String, Integer> resolveCompanies(List<PlanRow> plan, String action) {
        Map<String, Integer> byName = new HashMap<>();
        Map<String, String> pending = new TreeMap<>();
        for (PlanRow row : plan) {
            if (!willWrite(row, action)) continue;
            String norm = normName(row.companyName);
            if (!row.companyDependencyRequired
                    || norm == null
                    || row.resolvedCompanyId != null
                    || byName.containsKey(norm)) {
                continue;
            }
            pending.putIfAbsent(norm, row.companyName.trim());
        }
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            Company company = new Company();
            company.setName(entry.getValue());
            byName.put(entry.getKey(), companyService.createCompany(company).getId());
        }
        return byName;
    }

    private Integer companyId(
            PlanRow row,
            Map<String, Integer> createdCompanyIds) {
        if (!row.companyDependencyRequired) {
            return null;
        }
        return row.resolvedCompanyId != null
            ? row.resolvedCompanyId
            : createdCompanyIds.get(normName(row.companyName));
    }

    private void cancelPreviewAnalysis(ImportPhase phase, String reviewProof) {
        if (phase == ImportPhase.PREVIEW) {
            duplicatePreflightService.cancelImportPreview(reviewProof);
        }
    }

    private <T> Map<Integer, T> lockMatchedTargets(
            List<PlanRow> plan,
            String action,
            Function<Integer, T> locker,
            String entityLabel) {
        Set<Integer> matchedIds = new TreeSet<>();
        for (PlanRow row : plan) {
            if (MATCH.equals(row.status) && willWrite(row, action) && row.matchedId != null) {
                matchedIds.add(row.matchedId);
            }
        }
        Map<Integer, T> lockedTargets = new HashMap<>();
        for (Integer matchedId : matchedIds) {
            T target = locker.apply(matchedId);
            if (target != null) {
                lockedTargets.put(matchedId, target);
                continue;
            }
            for (PlanRow row : plan) {
                if (MATCH.equals(row.status) && matchedId.equals(row.matchedId)) {
                    failVanishedMatchedTarget(
                        row,
                        "Matched " + entityLabel + " #" + matchedId + " not found");
                }
            }
        }
        return lockedTargets;
    }

    private void revalidatePersonTargets(
            List<PlanRow> plan,
            Map<Integer, Person> lockedTargets) {
        for (Integer personId : new TreeSet<>(lockedTargets.keySet())) {
            Person target = lockedTargets.get(personId);
            if (!personIsProcessable(target)) {
                failMatchedRows(plan, personId, "Matched contact #" + personId + " is unavailable");
            }
        }
    }

    private void serializeAndRevalidatePersonIdentities(
            int workspaceId,
            List<PlanRow> plan,
            String action) {
        serializeAndRevalidateIdentities(
            workspaceId,
            plan,
            action,
            this::personCanonicalIdentities,
            identityMapper::lockCurrentPersonIdentityGroup,
            identityMapper::findCurrentPersonIdentityMatches,
            key -> IdentityKind.EMAIL.getDatabaseValue().equals(key.kind()),
            "contact");
    }

    private void serializeAndRevalidateCompanyIdentities(
            int workspaceId,
            List<PlanRow> plan,
            String action) {
        serializeAndRevalidateIdentities(
            workspaceId,
            plan,
            action,
            row -> canonicalIdentities(
                IdentityKind.DOMAIN,
                row.std.get("website"),
                IdentityKind.PHONE,
                row.std.get("phone")),
            identityMapper::lockCurrentCompanyIdentityGroup,
            identityMapper::findCurrentCompanyIdentityMatches,
            key -> true,
            "company");
    }

    private void serializeAndRevalidateIdentities(
            int workspaceId,
            List<PlanRow> plan,
            String action,
            Function<PlanRow, List<CanonicalIdentity>> identities,
            IdentityGroupLocker locker,
            CurrentIdentityMatcher matcher,
            Predicate<CanonicalIdentity> exclusiveIdentity,
            String entityLabel) {
        Map<PlanRow, List<CanonicalIdentity>> keysByRow = new LinkedHashMap<>();
        Set<CanonicalIdentity> keys = new TreeSet<>(Comparator
            .comparing(CanonicalIdentity::kind)
            .thenComparing(CanonicalIdentity::normalizedValue));
        for (PlanRow row : plan) {
            if (!willWrite(row, action)
                    || !(CREATE.equals(row.status) || MATCH.equals(row.status))) {
                continue;
            }
            List<CanonicalIdentity> rowKeys =
                suppliedCanonicalIdentities(row, identities);
            keysByRow.put(row, rowKeys);
            keys.addAll(rowKeys);
        }
        for (CanonicalIdentity key : keys) {
            locker.apply(workspaceId, key.kind(), key.normalizedValue());
        }
        Map<CanonicalIdentity, Set<Integer>> matchesByKey = new HashMap<>();
        Map<String, List<String>> valuesByKind = keys.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                CanonicalIdentity::kind,
                TreeMap::new,
                java.util.stream.Collectors.mapping(
                    CanonicalIdentity::normalizedValue,
                    java.util.stream.Collectors.toList())));
        for (Map.Entry<String, List<String>> entry : valuesByKind.entrySet()) {
            for (IdentityMatchRow match
                    : matcher.apply(workspaceId, entry.getKey(), entry.getValue())) {
                CanonicalIdentity key = new CanonicalIdentity(
                    match.getKind(), match.getNormalizedValue());
                matchesByKey.computeIfAbsent(key, ignored -> new TreeSet<>())
                    .add(match.getRecordId());
            }
        }
        for (Map.Entry<PlanRow, List<CanonicalIdentity>> entry : keysByRow.entrySet()) {
            PlanRow row = entry.getKey();
            Set<Integer> currentRecordIds = entry.getValue().stream()
                .filter(exclusiveIdentity)
                .flatMap(key -> matchesByKey.getOrDefault(key, Set.of()).stream())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (CREATE.equals(row.status) && !currentRecordIds.isEmpty()) {
                fail(row, "A " + entityLabel
                    + " acquired a supplied identity after duplicate review");
                continue;
            }
            if (MATCH.equals(row.status)
                    && currentRecordIds.stream()
                        .anyMatch(id -> !Objects.equals(id, row.matchedId))) {
                fail(row, "A supplied identity belongs to another "
                    + entityLabel + " after duplicate review");
                continue;
            }
            if (row.automaticIdentityMatch
                    && !currentRecordIds.contains(row.matchedId)) {
                fail(row, "Matched " + entityLabel + " #" + row.matchedId
                    + " no longer uniquely carries the supplied identities");
            }
            if (MATCH.equals(row.status)) {
                entry.getValue().stream()
                    .filter(key -> !matchesByKey
                        .getOrDefault(key, Set.of())
                        .contains(row.matchedId))
                    .forEach(row.missingCanonicalIdentities::add);
            }
        }
    }

    private static List<CanonicalIdentity> suppliedCanonicalIdentities(
            PlanRow row,
            Function<PlanRow, List<CanonicalIdentity>> identities) {
        List<PlanRow> sources = row.sourceRows.isEmpty()
            ? List.of(row)
            : row.sourceRows;
        return sources.stream()
            .flatMap(source -> identities.apply(source).stream())
            .distinct()
            .sorted(Comparator
                .comparing(CanonicalIdentity::kind)
                .thenComparing(CanonicalIdentity::normalizedValue))
            .toList();
    }

    private static void failMatchedRows(
            List<PlanRow> plan,
            int matchedId,
            String message) {
        for (PlanRow row : plan) {
            if (MATCH.equals(row.status) && Objects.equals(row.matchedId, matchedId)) {
                fail(row, message);
            }
        }
    }

    private static boolean personIsProcessable(Person person) {
        return person != null
            && person.getSuspendedAt() == null
            && person.getProvisionCeasedAt() == null;
    }

    private static boolean willWrite(PlanRow row, String action) {
        if (INVALID.equals(row.status) || SKIP.equals(row.status)) return false;
        return !(MATCH.equals(row.status) && SKIP.equals(action));
    }

    private void preflightPreview(String entityType, int workspaceId, List<ColumnMapping> mapping,
            List<PlanRow> plan, String action, Permission updatePermission) {
        requireUpdatePermission(plan, action, updatePermission);
        requireCustomFieldPermission(entityType, workspaceId, mapping, plan, action);
        requireTagPermission(workspaceId, plan, action);
        requireCompanyPermission(plan, action);
    }

    private void requireCustomFieldPermission(String entityType, int workspaceId, List<ColumnMapping> mapping,
            List<PlanRow> plan, String action) {
        Set<String> usedNewColumns = usedNewCustomFieldColumns(plan, action);
        List<ColumnMapping> usedMappings = mapping.stream()
            .filter(cm -> Boolean.TRUE.equals(cm.getCreateCustomField()))
            .filter(cm -> usedNewColumns.contains(cm.getColumn()))
            .toList();
        Map<String, String> keys = newCustomFieldKeys(workspaceId, entityType, usedMappings);
        if (!missingDefinitionKeys(workspaceId, entityType, List.copyOf(keys.values())).isEmpty()) {
            workspaceService.requirePermission(Permission.CUSTOM_FIELD_MANAGE);
        }
    }

    private void requireTagPermission(int workspaceId, List<PlanRow> plan, String action) {
        Set<String> names = new LinkedHashSet<>();
        for (PlanRow row : plan) {
            if (willWrite(row, action)) names.addAll(row.tagNames);
        }
        for (String name : names) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty() && tagMapper.getTagByName(workspaceId, trimmed) == null) {
                workspaceService.requirePermission(Permission.TAG_MANAGE);
                return;
            }
        }
    }

    private void requireCompanyPermission(List<PlanRow> plan, String action) {
        for (PlanRow row : plan) {
            if (!willWrite(row, action)
                    || !row.companyDependencyRequired) {
                continue;
            }
            String name = normName(row.companyName);
            if (name != null && row.resolvedCompanyId == null) {
                workspaceService.requirePermission(Permission.COMPANY_CREATE);
                return;
            }
        }
    }

    private static Set<String> usedNewCustomFieldColumns(List<PlanRow> plan, String action) {
        Set<String> columns = new HashSet<>();
        for (PlanRow row : plan) {
            if (willWrite(row, action)) columns.addAll(row.custom.keySet());
        }
        return columns;
    }

    // ===================================================================================
    // Apply helpers
    // ===================================================================================

    private boolean attachTags(int workspaceId, String entityType, int entityId, List<String> tagNames, Map<String, Integer> tagByName) {
        List<Integer> ids = tagIds(tagNames, tagByName);
        if (ids.isEmpty()) {
            return false;
        }
        if ("person".equals(entityType)) {
            return personMapper.insertTags(workspaceId, entityId, ids) > 0;
        }
        return companyMapper.insertTags(workspaceId, entityId, ids) > 0;
    }

    private static List<Integer> tagIds(List<String> tagNames, Map<String, Integer> tagByName) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String name : tagNames) {
            Integer id = tagByName.get(name.trim().toLowerCase());
            if (id != null) ids.add(id);
        }
        return new ArrayList<>(ids);
    }

    private boolean applyCustomValues(
            String entityType,
            int entityId,
            Map<String, String> custom,
            Map<String, Integer> columnToDef,
            String action,
            Map<Integer, Object> existing) {
        if (custom.isEmpty()) {
            return false;
        }
        boolean fillEmpty = FILL_EMPTY.equals(action);
        Map<Integer, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry
                : new TreeMap<>(custom).entrySet()) {
            Integer defId = columnToDef.get(entry.getKey());
            if (defId == null) continue;
            if (fillEmpty) {
                Object current = existing.get(defId);
                if (current != null && !String.valueOf(current).isBlank()) continue;
            }
            if (customValueEquals(existing.get(defId), entry.getValue())) {
                continue;
            }
            values.put(defId, entry.getValue());
        }
        if (values.isEmpty()) {
            return false;
        }
        customFieldValueService.applyValues(entityType, entityId, values);
        return true;
    }

    private Map<Integer, Map<Integer, Object>> existingCustomValues(
            String entityType,
            List<PlanRow> mutations,
            String action) {
        if (SKIP.equals(action)) {
            return Map.of();
        }
        List<Integer> entityIds = mutations.stream()
            .filter(row -> MATCH.equals(row.status))
            .filter(row -> !row.custom.isEmpty())
            .map(row -> row.matchedId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        return customFieldValueService.getForEntities(
            entityType, entityIds);
    }

    private static boolean customValueEquals(
            Object existing,
            String incoming) {
        if (existing == null) {
            return false;
        }
        if (existing instanceof BigDecimal number) {
            try {
                BigDecimal canonicalIncoming = new BigDecimal(incoming)
                    .setScale(CUSTOM_NUMBER_SCALE, RoundingMode.HALF_UP);
                return number.compareTo(canonicalIncoming) == 0;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        if (existing instanceof Boolean bool) {
            return switch (incoming.toLowerCase(Locale.ROOT)) {
                case "true", "1", "yes" -> bool;
                case "false", "0", "no" -> !bool;
                default -> false;
            };
        }
        return String.valueOf(existing).equals(incoming);
    }

    private boolean recordPersonIdentities(
            int workspaceId,
            Person person,
            PlanRow row,
            boolean emailAcquired,
            boolean phoneAcquired) {
        if (!emailAcquired && !phoneAcquired) {
            return false;
        }
        identityIntakeService.recordPerson(
            workspaceId,
            person.getId(),
            person.getEmail(),
            person.getPhone(),
            IdentityAcquisitionSource.CSV_IMPORT,
            csvRowRef(row, "email"),
            csvRowRef(row, "phone"),
            emailAcquired,
            phoneAcquired);
        return true;
    }

    private boolean recordCompanyIdentities(
            int workspaceId,
            Company company,
            PlanRow row,
            boolean websiteAcquired,
            boolean phoneAcquired) {
        if (!websiteAcquired && !phoneAcquired) {
            return false;
        }
        identityIntakeService.recordCompany(
            workspaceId,
            company.getId(),
            company.getWebsite(),
            company.getPhone(),
            IdentityAcquisitionSource.CSV_IMPORT,
            csvRowRef(row, "website"),
            csvRowRef(row, "phone"),
            websiteAcquired,
            phoneAcquired);
        return true;
    }

    private void requireUpdatePermission(List<PlanRow> plan, String action, Permission updatePermission) {
        if (!SKIP.equals(action) && plan.stream().anyMatch(row -> MATCH.equals(row.status))) {
            workspaceService.requirePermission(updatePermission);
        }
    }

    // ===================================================================================
    // Preview summary + audit
    // ===================================================================================

    private ImportPreviewResult summarize(
            List<PlanRow> plan,
            List<PlanRow> mutations,
            String onDuplicate) {
        String action = resolveAction(onDuplicate);
        List<RowAnalysis> rows = new ArrayList<>(plan.size());
        Map<String, List<DuplicateCandidateDto>> companyCandidates = new LinkedHashMap<>();
        int toCreate = 0;
        int toUpdate = 0;
        int toSkip = 0;
        int invalid = 0;
        Map<PlanRow, MergePreviewInfo> mergeInfo =
            new java.util.IdentityHashMap<>();
        for (PlanRow mutation : mutations) {
            if (mutation.sourceRows.size() <= 1) {
                continue;
            }
            MergePreviewInfo info = new MergePreviewInfo(
                mutation.rowIndex, mutation.sourceRows.size());
            for (PlanRow source : mutation.sourceRows) {
                mergeInfo.put(source, info);
            }
        }
        for (PlanRow row : plan) {
            String status = row.status;
            if (INVALID.equals(status)) {
                invalid++;
            } else if (MATCH.equals(status)) {
                if (SKIP.equals(action)) status = SKIP;
            }
            MergePreviewInfo info = mergeInfo.get(row);
            RowAnalysis analysis = new RowAnalysis(
                row.rowIndex,
                status,
                row.matchedId,
                row.label,
                info == null ? null : info.canonicalRowIndex(),
                info == null ? null : info.mergedRowCount(),
                row.errors.isEmpty() ? null : List.copyOf(row.errors),
                row.duplicateCandidates.isEmpty()
                    ? null
                    : List.copyOf(row.duplicateCandidates),
                row.dealDates);
            if ((row.companyDependencyRequired || INVALID.equals(row.status) && row.selectedCompanyId != null)
                    && row.companyDependencyKey != null) {
                analysis.setCompanyDependency(new ImportCompanyDependency(
                    row.companyName, row.companyDependencyKey, row.resolvedCompanyId,
                    row.companyDependencyTruncated));
                companyCandidates.putIfAbsent(row.companyDependencyKey, row.companyDependencyCandidates);
            }
            rows.add(analysis);
        }
        for (PlanRow mutation : mutations) {
            if (INVALID.equals(mutation.status)) {
                continue;
            }
            if (SKIP.equals(mutation.status)
                    || MATCH.equals(mutation.status) && SKIP.equals(action)) {
                toSkip++;
            } else if (MATCH.equals(mutation.status)) {
                toUpdate++;
            } else {
                toCreate++;
            }
        }
        ImportPreviewResult result = new ImportPreviewResult(plan.size(), toCreate, toUpdate, toSkip, invalid, rows);
        result.setCompanyCandidates(Map.copyOf(companyCandidates));
        return result;
    }

    private void auditImport(
            String entityType,
            int created,
            int updated,
            int skipped,
            int failed,
            boolean allRowsFailedBecauseTargetsVanished) {
        if (created + updated + skipped == 0 && allRowsFailedBecauseTargetsVanished) return;
        if (created + updated + skipped + failed == 0) return;
        auditService.record("import." + entityType, entityType, null, "CSV import",
            "Imported " + entityType + "s: " + created + " created, " + updated
                + " updated, " + skipped + " skipped, " + failed + " failed",
            Map.of(
                "created", created,
                "updated", updated,
                "skipped", skipped,
                "failed", failed));
    }

    private static List<RowError> collectFailures(List<PlanRow> plan) {
        List<RowError> failed = new ArrayList<>();
        for (PlanRow row : plan) {
            if (INVALID.equals(row.status)) {
                failed.add(new RowError(row.rowIndex, String.join("; ", row.errors),
                    List.copyOf(row.errors),
                    row.errors.stream().map(ImportProblem::fromDiagnostic).toList()));
            }
        }
        return failed;
    }

    private static boolean allRowsFailedBecauseTargetsVanished(List<PlanRow> plan) {
        if (plan.isEmpty()) {
            return false;
        }
        for (PlanRow row : plan) {
            if (!INVALID.equals(row.status)
                    || !row.failedBecauseMatchedTargetVanished) {
                return false;
            }
        }
        return true;
    }

    // ===================================================================================
    // Low-level helpers
    // ===================================================================================

    private Map<String, ColumnMapping> mappingByColumn(List<ColumnMapping> mapping, Set<String> allowedFields) {
        Map<String, ColumnMapping> byColumn = new HashMap<>();
        for (ColumnMapping cm : mapping) {
            String field = cm.getField();
            boolean usable = Boolean.TRUE.equals(cm.getCreateCustomField())
                || (field != null && (field.startsWith("custom:") || "tags".equals(field) || allowedFields.contains(field)));
            if (usable) byColumn.put(cm.getColumn(), cm);
        }
        Set<String> seenFields = new HashSet<>();
        for (ColumnMapping cm : byColumn.values()) {
            String field = cm.getField();
            if (field == null || "tags".equals(field) || field.startsWith("custom:")) continue;
            if (!seenFields.add(field)) {
                throw new ImportValidationException("IMPORT_MAPPING_DUPLICATE", "Multiple columns are mapped to the same field: " + field);
            }
        }
        return byColumn;
    }

    private static void validateWithinFileStrongKeys(
            List<PlanRow> plan,
            Function<PlanRow, List<String>> keysFn) {
        Map<PlanRow, List<String>> keysByRow = new LinkedHashMap<>();
        Map<String, List<PlanRow>> rowsByKey = new LinkedHashMap<>();
        for (PlanRow row : plan) {
            if (!(CREATE.equals(row.status) || MATCH.equals(row.status))) {
                continue;
            }
            List<String> keys = keysFn.apply(row).stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
            keysByRow.put(row, keys);
            for (String key : keys) {
                rowsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        Set<PlanRow> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (PlanRow start : keysByRow.keySet()) {
            if (visited.contains(start)) continue;
            Set<PlanRow> component = strongKeyComponent(start, keysByRow, rowsByKey);
            visited.addAll(component);
            Set<Integer> matchedIds = component.stream()
                .filter(row -> MATCH.equals(row.status))
                .map(row -> row.matchedId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> commonKeys = new LinkedHashSet<>(keysByRow.getOrDefault(start, List.of()));
            for (PlanRow row : component) {
                commonKeys.retainAll(keysByRow.getOrDefault(row, List.of()));
            }
            if (matchedIds.size() > 1
                    || matchedIds.isEmpty()
                        && component.size() > 1
                        && commonKeys.isEmpty()) {
                String error = matchedIds.size() > 1
                    ? "Canonical identity is linked to multiple import targets"
                    : "Connected canonical identities require explicit duplicate resolution";
                component.stream()
                    .filter(row -> !INVALID.equals(row.status))
                    .forEach(row -> fail(row, error));
            }
        }
    }

    private static Set<PlanRow> strongKeyComponent(
            PlanRow start,
            Map<PlanRow, List<String>> keysByRow,
            Map<String, List<PlanRow>> rowsByKey) {
        Set<PlanRow> component =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<PlanRow> pending = new ArrayList<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            PlanRow row = pending.removeLast();
            if (!component.add(row)) continue;
            for (String key : keysByRow.getOrDefault(row, List.of())) {
                pending.addAll(rowsByKey.getOrDefault(key, List.of()));
            }
        }
        return component;
    }

    private List<PlanRow> coalesceByCanonicalTarget(
            List<PlanRow> plan,
            String action,
            Function<PlanRow, List<String>> keysFn) {
        Map<PlanRow, List<String>> keysByRow = new LinkedHashMap<>();
        Map<String, List<PlanRow>> rowsByKey = new LinkedHashMap<>();
        Map<Integer, List<PlanRow>> rowsByMatchedId = new LinkedHashMap<>();
        for (PlanRow row : plan) {
            if (!(CREATE.equals(row.status) || MATCH.equals(row.status))) {
                continue;
            }
            List<String> keys = keysFn.apply(row).stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
            keysByRow.put(row, keys);
            for (String key : keys) {
                rowsByKey.computeIfAbsent(
                    key, ignored -> new ArrayList<>()).add(row);
            }
            if (MATCH.equals(row.status) && row.matchedId != null) {
                rowsByMatchedId.computeIfAbsent(
                    row.matchedId, ignored -> new ArrayList<>()).add(row);
            }
        }
        Set<PlanRow> visited =
            java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        List<PlanRow> mutations = new ArrayList<>();
        for (PlanRow start : keysByRow.keySet()) {
            if (visited.contains(start)) {
                continue;
            }
            List<PlanRow> component = canonicalTargetComponent(
                start, keysByRow, rowsByKey, rowsByMatchedId);
            visited.addAll(component);
            component.sort(Comparator.comparingInt(row -> row.rowIndex));
            mutations.add(mergeCanonicalRows(component, action));
        }
        plan.stream().filter(row -> SKIP.equals(row.status)).forEach(mutations::add);
        return List.copyOf(mutations);
    }

    private static List<PlanRow> canonicalTargetComponent(
            PlanRow start,
            Map<PlanRow, List<String>> keysByRow,
            Map<String, List<PlanRow>> rowsByKey,
            Map<Integer, List<PlanRow>> rowsByMatchedId) {
        Set<PlanRow> component =
            java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        List<PlanRow> pending = new ArrayList<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            PlanRow row = pending.removeLast();
            if (!component.add(row)) {
                continue;
            }
            for (String key : keysByRow.getOrDefault(row, List.of())) {
                pending.addAll(rowsByKey.getOrDefault(key, List.of()));
            }
            if (MATCH.equals(row.status) && row.matchedId != null) {
                pending.addAll(rowsByMatchedId.getOrDefault(
                    row.matchedId, List.of()));
            }
        }
        return new ArrayList<>(component);
    }

    private static PlanRow mergeCanonicalRows(
            List<PlanRow> sources,
            String action) {
        PlanRow mutation = new PlanRow();
        mutation.rowIndex = sources.getFirst().rowIndex;
        mutation.sourceRows.addAll(sources);
        Set<Integer> matchedIds = sources.stream()
            .filter(row -> MATCH.equals(row.status))
            .map(row -> row.matchedId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        mutation.status = matchedIds.isEmpty() ? CREATE : MATCH;
        if (matchedIds.size() > 1) {
            fail(
                mutation,
                "Canonical import target resolves to multiple matched records");
            return mutation;
        }
        if (!matchedIds.isEmpty()) {
            mutation.matchedId = matchedIds.iterator().next();
            PlanRow matchedSource = sources.stream()
                .filter(row -> Objects.equals(
                    mutation.matchedId, row.matchedId))
                .findFirst()
                .orElseThrow();
            mutation.label = matchedSource.label;
            for (PlanRow source : sources) {
                markMatch(
                    source, mutation.matchedId, mutation.label);
            }
        }
        mutation.automaticIdentityMatch = sources.stream()
            .anyMatch(row -> row.automaticIdentityMatch);
        boolean overwrite = OVERWRITE.equals(action);
        for (PlanRow source : sources) {
            mergeFields(
                mutation.std,
                mutation.stdSourceRows,
                source.std,
                source.stdSourceRows,
                overwrite);
            mergeFields(
                mutation.custom,
                null,
                source.custom,
                null,
                overwrite);
            unionIgnoreCase(mutation.tagNames, source.tagNames);
            unionExact(mutation.peopleEmails, source.peopleEmails);
        }
        PlanRow companySource = selectedSource(
            sources, row -> row.companyName != null, overwrite);
        if (companySource != null) {
            mutation.companyName = companySource.companyName;
            mutation.companyDependencyKey =
                companySource.companyDependencyKey;
            mutation.companyDependencyError =
                companySource.companyDependencyError;
            mutation.companyDependencyCandidates =
                companySource.companyDependencyCandidates;
            mutation.resolvedCompanyId =
                companySource.resolvedCompanyId;
        }
        PlanRow stageSource = selectedSource(
            sources,
            row -> row.pipelineName != null || row.stageName != null,
            overwrite);
        if (stageSource != null) {
            mutation.pipelineName = stageSource.pipelineName;
            mutation.stageName = stageSource.stageName;
        }
        if (mutation.label == null) {
            mutation.label = mutation.std.get("name");
        }
        return mutation;
    }

    private static void mergeFields(
            Map<String, String> target,
            Map<String, Integer> targetSourceRows,
            Map<String, String> incoming,
            Map<String, Integer> incomingSourceRows,
            boolean overwrite) {
        for (Map.Entry<String, String> entry
                : new TreeMap<>(incoming).entrySet()) {
            if (!overwrite && target.containsKey(entry.getKey())) {
                continue;
            }
            target.put(entry.getKey(), entry.getValue());
            if (targetSourceRows != null && incomingSourceRows != null) {
                Integer sourceRow = incomingSourceRows.get(entry.getKey());
                if (sourceRow != null) {
                    targetSourceRows.put(entry.getKey(), sourceRow);
                }
            }
        }
    }

    private static PlanRow selectedSource(
            List<PlanRow> sources,
            Predicate<PlanRow> eligible,
            boolean overwrite) {
        PlanRow selected = null;
        for (PlanRow source : sources) {
            if (!eligible.test(source)) {
                continue;
            }
            if (!overwrite) {
                return source;
            }
            selected = source;
        }
        return selected;
    }

    private static void unionIgnoreCase(
            List<String> target,
            List<String> incoming) {
        Set<String> normalized = target.stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(
                LinkedHashSet::new));
        for (String value : incoming) {
            if (normalized.add(value.toLowerCase(Locale.ROOT))) {
                target.add(value);
            }
        }
    }

    private static void unionExact(
            List<String> target,
            List<String> incoming) {
        Set<String> seen = new LinkedHashSet<>(target);
        for (String value : incoming) {
            if (seen.add(value)) {
                target.add(value);
            }
        }
    }

    private List<String> identityKeys(
            IdentityKind firstKind,
            String firstValue,
            IdentityKind secondKind,
            String secondValue) {
        return canonicalIdentities(firstKind, firstValue, secondKind, secondValue).stream()
            .map(CanonicalIdentity::dedupeKey)
            .toList();
    }

    private List<String> personIdentityKeys(PlanRow row) {
        return personCanonicalIdentities(row).stream()
            .filter(key -> IdentityKind.EMAIL.getDatabaseValue().equals(key.kind()))
            .map(CanonicalIdentity::dedupeKey)
            .toList();
    }

    private List<CanonicalIdentity> personCanonicalIdentities(PlanRow row) {
        return canonicalIdentities(
            IdentityKind.EMAIL,
            row.std.get("email"),
            IdentityKind.PHONE,
            row.std.get("phone"));
    }

    private List<CanonicalIdentity> canonicalIdentities(
            IdentityKind firstKind,
            String firstValue,
            IdentityKind secondKind,
            String secondValue) {
        List<CanonicalIdentity> keys = new ArrayList<>(2);
        addCanonicalIdentity(keys, firstKind, firstValue);
        addCanonicalIdentity(keys, secondKind, secondValue);
        return List.copyOf(keys);
    }

    private void addCanonicalIdentity(
            List<CanonicalIdentity> keys,
            IdentityKind kind,
            String rawValue) {
        String normalized = canonicalIdentity(kind, rawValue);
        if (normalized != null) {
            keys.add(new CanonicalIdentity(kind.getDatabaseValue(), normalized));
        }
    }

    private boolean missingCanonicalIdentity(
            PlanRow row,
            IdentityKind kind,
            String rawValue) {
        String normalized = canonicalIdentity(kind, rawValue);
        return normalized != null
            && row.missingCanonicalIdentities.contains(
                new CanonicalIdentity(
                    kind.getDatabaseValue(), normalized));
    }

    private static String resolveAction(String onDuplicate) {
        if (SKIP.equals(onDuplicate) || OVERWRITE.equals(onDuplicate)) return onDuplicate;
        return FILL_EMPTY;
    }

    private static String merge(String action, String existing, String incoming) {
        if (incoming == null) return existing;
        if (OVERWRITE.equals(action)) return incoming;
        return (existing == null || existing.isBlank()) ? incoming : existing;
    }

    private static String csvRowRef(
            PlanRow row,
            String field) {
        Integer sourceRow = row.stdSourceRows.get(field);
        return sourceRow == null ? null : "csv-row:" + (sourceRow + 1);
    }

    private record CanonicalIdentity(String kind, String normalizedValue) {
        private String dedupeKey() {
            return kind + ":" + normalizedValue;
        }
    }

    private record MergePreviewInfo(
            int canonicalRowIndex,
            int mergedRowCount) {
    }

    @FunctionalInterface
    private interface IdentityGroupLocker {
        List<Long> apply(int workspaceId, String kind, String normalizedValue);
    }

    @FunctionalInterface
    private interface CurrentIdentityMatcher {
        List<IdentityMatchRow> apply(
            int workspaceId,
            String kind,
            List<String> normalizedValues);
    }

    private static void markMatch(PlanRow row, Integer id, String label) {
        row.status = MATCH;
        row.matchedId = id;
        if (label != null) row.label = label;
    }

    private static void fail(PlanRow row, String error) {
        row.status = INVALID;
        if (!row.errors.contains(error)) {
            row.errors.add(error);
        }
        for (PlanRow source : row.sourceRows) {
            fail(source, error);
        }
    }

    private static void failVanishedMatchedTarget(PlanRow row, String error) {
        row.failedBecauseMatchedTargetVanished = true;
        for (PlanRow source : row.sourceRows) {
            source.failedBecauseMatchedTargetVanished = true;
        }
        fail(row, error);
    }

    private static String cell(Map<String, String> row, String column) {
        String value = row.get(column);
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > 1 && trimmed.charAt(0) == '\'' && "=+-@\t\r".indexOf(trimmed.charAt(1)) >= 0) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<String> splitMulti(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return parts;
    }

    private String normEmail(String value) {
        return canonicalIdentity(IdentityKind.EMAIL, value);
    }

    private String normName(String value) {
        return matchingService.normalizeName(value).orElse(null);
    }

    private static int parseCustomId(String field) {
        try {
            return Integer.parseInt(field.substring("custom:".length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String normalizeCustomType(String type) {
        String t = type == null ? "text" : type.trim().toLowerCase(Locale.ROOT);
        return AUTO_CUSTOM_TYPES.contains(t) ? t : "text";
    }

    private static String customLabel(ColumnMapping cm) {
        String label = cm.getCustomFieldLabel();
        return ((label == null || label.isBlank()) ? cm.getColumn() : label).trim();
    }

    private static String slug(String label) {
        String trimmed = label.trim();
        String collapsed = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        int start = 0;
        int end = collapsed.length();
        while (start < end && collapsed.charAt(start) == '_') {
            start++;
        }
        while (end > start && collapsed.charAt(end - 1) == '_') {
            end--;
        }
        String s = collapsed.substring(start, end);
        if (s.isEmpty() || s.length() > 64 || trimmed.chars().anyMatch(character -> character > 127)) {
            return hashedFieldKey(s.isEmpty() ? "field" : s, trimmed, null);
        }
        return s;
    }

    private static String hashedFieldKey(String prefix, String label, @Nullable String type) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        updateReviewDigest(digest, label);
        updateReviewDigest(digest, type);
        String suffix = HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        return prefix.substring(0, Math.min(prefix.length(), 31)) + "_" + suffix;
    }
}
