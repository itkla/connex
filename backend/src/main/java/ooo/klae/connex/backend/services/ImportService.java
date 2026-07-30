package ooo.klae.connex.backend.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import java.util.function.Predicate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.IdentityMatchRow;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.CompanyDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DuplicateCandidateDto;
import ooo.klae.connex.backend.dto.DuplicateImportPreflightReview;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.ImportPreviewResult;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.RowAnalysis;
import ooo.klae.connex.backend.dto.RowError;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.DealLineItemMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

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
 * locked in ascending record-id order before any dependency is created. Tags, referenced
 * companies, and auto-created custom-field definitions are resolved in stable key order through
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
    private final TagMapper tagMapper;
    private final TagService tagService;
    private final PipelineMapper pipelineMapper;
    private final EmploymentService employmentService;
    private final DealStageHistoryService dealStageHistoryService;
    private final CustomFieldDefinitionMapper customFieldDefinitionMapper;
    private final CustomFieldDefinitionService customFieldDefinitionService;
    private final CustomFieldValueService customFieldValueService;
    private final AuditService auditService;
    private final IdentityIntakeService identityIntakeService;
    private final MatchingService matchingService;
    private final DuplicatePreflightService duplicatePreflightService;

    private static final DateTimeFormatter MYSQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_TAG_COLOR = "#CCCCCC";
    private static final String DEFAULT_CURRENCY = "USD";

    private static final Set<String> PERSON_FIELDS = Set.of("name", "email", "phone", "title", "company");
    private static final Set<String> COMPANY_FIELDS = Set.of("name", "website", "industry", "phone", "address");
    private static final Set<String> DEAL_FIELDS =
        Set.of("name", "value", "currency", "pipeline", "stage", "company", "expectedCloseDate", "people");
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
        final Map<String, String> custom = new HashMap<>();
        final List<String> tagNames = new ArrayList<>();
        String companyName;
        String companyDependencyKey;
        String companyDependencyError;
        List<DuplicateCandidateDto> companyDependencyCandidates = List.of();
        boolean companyDependencyRequired;
        String pipelineName;
        String stageName;
        Integer resolvedCompanyId;
        Integer resolvedPipelineId;
        Integer resolvedStageId;
        final List<String> peopleEmails = new ArrayList<>();
        final List<DuplicateCandidateDto> duplicateCandidates = new ArrayList<>();
        boolean duplicateCandidatesTruncated;
        boolean automaticIdentityMatch;
    }

    private enum ImportPhase {
        PREVIEW,
        COMMIT
    }

    private record ImportAnalysis(List<PlanRow> plan, String reviewProof) {
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
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String action = resolveAction(request.getOnDuplicate());
        ImportAnalysis analysis =
            analyzePersons(request, workspaceId, ImportPhase.PREVIEW, null);
        List<PlanRow> plan = analysis.plan();
        try {
            preflightPreview(
                "person",
                workspaceId,
                request.getMapping(),
                plan,
                action,
                Permission.PERSON_UPDATE);
            ImportPreviewResult result = summarize(plan, action);
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
        duplicateDecisionLockService.lockCurrentOrganization();
        int actorId = authService.getCurrentUser().getId();
        List<PlanRow> plan =
            analyzePersons(
                request, workspaceId, ImportPhase.COMMIT, admission, reviewContext).plan();
        requireUpdatePermission(plan, action, Permission.PERSON_UPDATE);

        Map<Integer, Person> lockedTargets = lockMatchedTargets(
            plan,
            action,
            id -> personMapper.getOwnedPersonByIdForUpdate(workspaceId, id),
            "contact");
        revalidatePersonTargets(plan, lockedTargets);
        serializeAndRevalidatePersonIdentities(workspaceId, plan, action);
        Map<String, Integer> columnToDef = resolveCustomDefinitions(
            "person", request.getMapping(), plan, action);
        Map<String, Integer> tagByName = resolveTags(plan, action);
        Map<String, Integer> companyByName = resolveCompanies(plan, action);

        List<PlanRow> toCreate = new ArrayList<>();
        List<Person> beans = new ArrayList<>();
        int updated = 0;
        int skipped = 0;

        for (PlanRow row : plan) {
            if (INVALID.equals(row.status)) continue;
            if (SKIP.equals(row.status)) { skipped++; continue; }
            if (MATCH.equals(row.status)) {
                if (SKIP.equals(action)) { skipped++; continue; }
                if (applyPersonUpdate(
                        workspaceId,
                        row,
                        action,
                        columnToDef,
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
            personMapper.insertBatch(beans);
            for (int i = 0; i < beans.size(); i++) {
                Person bean = beans.get(i);
                PlanRow row = toCreate.get(i);
                identityIntakeService.recordPerson(
                    workspaceId, bean.getId(), bean.getEmail(), bean.getPhone(),
                    IdentityAcquisitionSource.CSV_IMPORT, csvRowRef(row));
                Integer companyId = bean.getCompany() != null ? bean.getCompany().getId() : null;
                if (companyId != null) {
                    employmentService.recordInitial(workspaceId, bean.getId(), companyId, bean.getTitle());
                }
                attachTags(workspaceId, "person", bean.getId(), row.tagNames, tagByName);
                applyCustomValues("person", bean.getId(), row.custom, columnToDef, action, false);
            }
        }

        int created = beans.size();
        auditImport("person", created, updated, skipped);
        return new ImportResult(created, updated, skipped, collectFailures(plan));
    }

    private ImportAnalysis analyzePersons(
            ImportRequest request,
            int workspaceId,
            ImportPhase phase,
            DuplicatePreflightService.ImportCommitAdmission admission) {
        return analyzePersons(
            request,
            workspaceId,
            phase,
            admission,
            importReviewContext("person", workspaceId, request));
    }

    private ImportAnalysis analyzePersons(
            ImportRequest request,
            int workspaceId,
            ImportPhase phase,
            DuplicatePreflightService.ImportCommitAdmission admission,
            String reviewContext) {
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
                if (INVALID.equals(row.status)) continue;
                Integer linked = links.get(row.rowIndex);
                if (linked != null) {
                    Person existing = getOwnedPerson(workspaceId, linked);
                    if (existing == null) {
                        fail(row, "Linked contact #" + linked + " not found");
                        continue;
                    }
                    if (!personIsProcessable(existing)) {
                        fail(row, "Linked contact #" + linked + " is unavailable");
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
            dedupeWithinFileByStrongKeys(
                plan,
                resolveAction(request.getOnDuplicate()),
                this::personIdentityKeys);
            String action = resolveAction(request.getOnDuplicate());
            applyCompanyDependencyFailures(
                plan,
                row -> personCompanyDependencyRequired(
                    workspaceId, row, action));
            return new ImportAnalysis(plan, reviewProof);
        } catch (RuntimeException exception) {
            cancelPreviewAnalysis(phase, reviewProof);
            throw exception;
        }
    }

    private boolean applyPersonUpdate(int workspaceId, PlanRow row, String action,
            Map<String, Integer> columnToDef,
            Map<String, Integer> tagByName,
            Map<String, Integer> companyByName,
            Map<Integer, Person> lockedTargets) {
        Person existing = lockedTargets.get(row.matchedId);
        if (existing == null) {
            fail(row, "Matched contact #" + row.matchedId + " not found");
            return false;
        }
        Integer beforeCompanyId = existing.getCompany() != null ? existing.getCompany().getId() : null;
        boolean emailAcquired = fieldAcquired(action, existing.getEmail(), row.std.get("email"));
        boolean phoneAcquired = fieldAcquired(action, existing.getPhone(), row.std.get("phone"));
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
        personMapper.update(existing);
        identityIntakeService.recordPerson(
            workspaceId, existing.getId(), existing.getEmail(), existing.getPhone(),
            IdentityAcquisitionSource.CSV_IMPORT, csvRowRef(row),
            emailAcquired, phoneAcquired);
        Integer afterCompanyId = existing.getCompany() != null ? existing.getCompany().getId() : null;
        if (afterCompanyId != null && !afterCompanyId.equals(beforeCompanyId)) {
            employmentService.recordTransition(workspaceId, existing.getId(), afterCompanyId, existing.getTitle());
        }
        attachTags(workspaceId, "person", existing.getId(), row.tagNames, tagByName);
        applyCustomValues("person", existing.getId(), row.custom, columnToDef, action, true);
        return true;
    }

    private Person getOwnedPerson(int workspaceId, int personId) {
        if (!personMapper.existsOwned(workspaceId, personId)) return null;
        return personMapper.getPersonById(workspaceId, personId);
    }

    private boolean personCompanyDependencyRequired(
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
        Person existing = getOwnedPerson(workspaceId, row.matchedId);
        return existing == null || existing.getCompany() == null;
    }

    private String applyPersonDuplicatePreflight(
            List<PlanRow> plan,
            ImportPhase phase,
            String reviewContext,
            DuplicatePreflightService.ImportCommitAdmission admission) {
        List<PlanRow> eligible = plan.stream()
            .filter(row -> !INVALID.equals(row.status))
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
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String action = resolveAction(request.getOnDuplicate());
        ImportAnalysis analysis =
            analyzeCompanies(request, workspaceId, ImportPhase.PREVIEW, null);
        List<PlanRow> plan = analysis.plan();
        try {
            preflightPreview(
                "company",
                workspaceId,
                request.getMapping(),
                plan,
                action,
                Permission.COMPANY_UPDATE);
            ImportPreviewResult result = summarize(plan, action);
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
        duplicateDecisionLockService.lockCurrentOrganization();
        int actorId = authService.getCurrentUser().getId();
        List<PlanRow> plan =
            analyzeCompanies(
                request, workspaceId, ImportPhase.COMMIT, admission, reviewContext).plan();
        requireUpdatePermission(plan, action, Permission.COMPANY_UPDATE);

        Map<Integer, Company> lockedTargets = lockMatchedTargets(
            plan,
            action,
            id -> companyMapper.getOwnedCompanyByIdForUpdate(workspaceId, id),
            "company");
        serializeAndRevalidateCompanyIdentities(workspaceId, plan, action);
        Map<String, Integer> columnToDef = resolveCustomDefinitions(
            "company", request.getMapping(), plan, action);
        Map<String, Integer> tagByName = resolveTags(plan, action);

        List<PlanRow> toCreate = new ArrayList<>();
        List<Company> beans = new ArrayList<>();
        int updated = 0;
        int skipped = 0;

        for (PlanRow row : plan) {
            if (INVALID.equals(row.status)) continue;
            if (SKIP.equals(row.status)) { skipped++; continue; }
            if (MATCH.equals(row.status)) {
                if (SKIP.equals(action)) { skipped++; continue; }
                if (applyCompanyUpdate(
                        workspaceId,
                        row,
                        action,
                        columnToDef,
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
            companyMapper.insertBatch(beans);
            for (int i = 0; i < beans.size(); i++) {
                Company bean = beans.get(i);
                PlanRow row = toCreate.get(i);
                identityIntakeService.recordCompany(
                    workspaceId, bean.getId(), bean.getWebsite(), bean.getPhone(),
                    IdentityAcquisitionSource.CSV_IMPORT, csvRowRef(row));
                attachTags(workspaceId, "company", bean.getId(), row.tagNames, tagByName);
                applyCustomValues("company", bean.getId(), row.custom, columnToDef, action, false);
            }
        }

        int created = beans.size();
        auditImport("company", created, updated, skipped);
        return new ImportResult(created, updated, skipped, collectFailures(plan));
    }

    private ImportAnalysis analyzeCompanies(
            ImportRequest request,
            int workspaceId,
            ImportPhase phase,
            DuplicatePreflightService.ImportCommitAdmission admission) {
        return analyzeCompanies(
            request,
            workspaceId,
            phase,
            admission,
            importReviewContext("company", workspaceId, request));
    }

    private ImportAnalysis analyzeCompanies(
            ImportRequest request,
            int workspaceId,
            ImportPhase phase,
            DuplicatePreflightService.ImportCommitAdmission admission,
            String reviewContext) {
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
                if (INVALID.equals(row.status)) continue;
                Integer linked = links.get(row.rowIndex);
                if (linked != null) {
                    Company existing = getOwnedCompany(workspaceId, linked);
                    if (existing == null) {
                        fail(row, "Linked company #" + linked + " not found");
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
            dedupeWithinFileByStrongKeys(
                plan,
                resolveAction(request.getOnDuplicate()),
                row -> identityKeys(
                    IdentityKind.DOMAIN, row.std.get("website"),
                    IdentityKind.PHONE, row.std.get("phone")));
            return new ImportAnalysis(plan, reviewProof);
        } catch (RuntimeException exception) {
            cancelPreviewAnalysis(phase, reviewProof);
            throw exception;
        }
    }

    private boolean applyCompanyUpdate(int workspaceId, PlanRow row, String action,
            Map<String, Integer> columnToDef,
            Map<String, Integer> tagByName,
            Map<Integer, Company> lockedTargets) {
        Company existing = lockedTargets.get(row.matchedId);
        if (existing == null) {
            fail(row, "Matched company #" + row.matchedId + " not found");
            return false;
        }
        boolean websiteAcquired =
            fieldAcquired(action, existing.getWebsite(), row.std.get("website"));
        boolean phoneAcquired = fieldAcquired(action, existing.getPhone(), row.std.get("phone"));
        existing.setName(merge(action, existing.getName(), row.std.get("name")));
        existing.setWebsite(merge(action, existing.getWebsite(), row.std.get("website")));
        existing.setIndustry(merge(action, existing.getIndustry(), row.std.get("industry")));
        existing.setPhone(merge(action, existing.getPhone(), row.std.get("phone")));
        existing.setAddress(merge(action, existing.getAddress(), row.std.get("address")));
        companyMapper.update(existing);
        identityIntakeService.recordCompany(
            workspaceId, existing.getId(), existing.getWebsite(), existing.getPhone(),
            IdentityAcquisitionSource.CSV_IMPORT, csvRowRef(row),
            websiteAcquired, phoneAcquired);
        attachTags(workspaceId, "company", existing.getId(), row.tagNames, tagByName);
        applyCustomValues("company", existing.getId(), row.custom, columnToDef, action, true);
        return true;
    }

    private Company getOwnedCompany(int workspaceId, int companyId) {
        if (!companyMapper.existsOwned(workspaceId, companyId)) return null;
        return companyMapper.getCompanyById(workspaceId, companyId);
    }

    private String applyCompanyDuplicatePreflight(
            List<PlanRow> plan,
            ImportPhase phase,
            String reviewContext,
            DuplicatePreflightService.ImportCommitAdmission admission) {
        List<PlanRow> eligible = plan.stream()
            .filter(row -> !INVALID.equals(row.status))
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
            if (INVALID.equals(row.status) || row.companyName == null) {
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

    private static void applyCompanyDependencyCandidates(
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
                if (response.truncated()) {
                    row.companyDependencyError =
                        "Company dependency candidates were truncated";
                } else if (response.candidates().size() > 1) {
                    row.companyDependencyError =
                        "Multiple visible companies match the company name";
                } else if (response.candidates().size() == 1) {
                    row.resolvedCompanyId =
                        response.candidates().getFirst().recordId();
                }
            }
        }
    }

    private static void applyCompanyDependencyFailures(
            List<PlanRow> plan,
            Predicate<PlanRow> dependencyRequired) {
        for (PlanRow row : plan) {
            row.companyDependencyRequired = dependencyRequired.test(row);
            if (row.companyDependencyError != null
                    && row.companyDependencyRequired) {
                fail(row, row.companyDependencyError);
            }
        }
        Set<String> displayedDependencies = new HashSet<>();
        for (PlanRow row : plan) {
            if (row.companyDependencyRequired
                    && row.companyDependencyKey != null
                    && !row.companyDependencyCandidates.isEmpty()
                    && displayedDependencies.add(row.companyDependencyKey)) {
                row.duplicateCandidates.addAll(
                    row.companyDependencyCandidates);
            }
        }
    }

    private static void applyStrongMatch(
            PlanRow row,
            String recordType,
            String entityLabel) {
        List<DuplicateCandidateDto> strong = row.duplicateCandidates.stream()
            .filter(candidate -> recordType.equals(candidate.recordType()))
            .filter(candidate -> candidate.strength() == DuplicateMatchStrength.STRONG)
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
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String action = resolveAction(request.getOnDuplicate());
        ImportAnalysis analysis = analyzeDeals(
            request,
            workspaceId,
            ImportPhase.PREVIEW,
            null,
            importReviewContext("deal", workspaceId, request));
        List<PlanRow> plan = analysis.plan();
        try {
            preflightPreview(
                "deal",
                workspaceId,
                request.getMapping(),
                plan,
                action,
                Permission.DEAL_UPDATE);
            ImportPreviewResult result = summarize(plan, action);
            result.setDuplicateReviewProof(analysis.reviewProof());
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
        String action = resolveAction(request.getOnDuplicate());
        String reviewContext = importReviewContext("deal", workspaceId, request);
        DuplicatePreflightService.ImportCommitAdmission admission =
            duplicatePreflightService.claimImportCommit(
                request.getDuplicateReviewProof(), reviewContext);
        duplicateDecisionLockService.lockCurrentOrganization();
        int actorId = authService.getCurrentUser().getId();
        List<PlanRow> plan = analyzeDeals(
            request,
            workspaceId,
            ImportPhase.COMMIT,
            admission,
            reviewContext).plan();
        requireUpdatePermission(plan, action, Permission.DEAL_UPDATE);

        Map<Integer, Deal> lockedTargets = lockMatchedTargets(
            plan,
            action,
            id -> dealMapper.getDealByIdForUpdate(workspaceId, id),
            "deal");
        Map<String, Integer> columnToDef = resolveCustomDefinitions(
            "deal", request.getMapping(), plan, action);
        Map<String, Integer> tagByName = resolveTags(plan, action);
        Map<String, Integer> companyByName = resolveCompanies(plan, action);
        Map<String, Integer> personByEmail = resolveDealPeople(workspaceId, plan, action);
        Map<Integer, String> stageOutcome = new HashMap<>();

        List<PlanRow> toCreate = new ArrayList<>();
        List<Deal> beans = new ArrayList<>();
        int updated = 0;
        int skipped = 0;

        for (PlanRow row : plan) {
            if (INVALID.equals(row.status)) continue;
            if (SKIP.equals(row.status)) { skipped++; continue; }
            if (MATCH.equals(row.status)) {
                if (SKIP.equals(action)) { skipped++; continue; }
                if (applyDealUpdate(
                        workspaceId,
                        row,
                        action,
                        columnToDef,
                        tagByName,
                        companyByName,
                        personByEmail,
                        stageOutcome,
                        lockedTargets)) {
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
            bean.setCurrency(currency != null ? currency : DEFAULT_CURRENCY);
            bean.setExpectedCloseDate(row.std.get("expectedCloseDate"));
            bean.setPipelineId(row.resolvedPipelineId);
            bean.setStageId(row.resolvedStageId);
            bean.setCompanyId(companyId(row, companyByName));
            reconcileClose(workspaceId, bean, stageOutcome);
            beans.add(bean);
            toCreate.add(row);
        }

        if (!beans.isEmpty()) {
            dealMapper.insertBatch(beans);
            for (int i = 0; i < beans.size(); i++) {
                Deal bean = beans.get(i);
                PlanRow row = toCreate.get(i);
                if (bean.getStageId() != null) {
                    dealStageHistoryService.recordInitial(
                        workspaceId, bean.getId(), bean.getStageId(), bean.getWon());
                }
                attachDealTags(workspaceId, bean.getId(), row.tagNames, tagByName);
                linkDealPeople(workspaceId, bean.getId(), row.peopleEmails, personByEmail);
                applyCustomValues("deal", bean.getId(), row.custom, columnToDef, action, false);
            }
        }

        int created = beans.size();
        auditImport("deal", created, updated, skipped);
        return new ImportResult(created, updated, skipped, collectFailures(plan));
    }

    private ImportAnalysis analyzeDeals(
            ImportRequest request,
            int workspaceId,
            ImportPhase phase,
            DuplicatePreflightService.ImportCommitAdmission admission,
            String reviewContext) {
        Map<String, ColumnMapping> byColumn = mappingByColumn(request.getMapping(), DEAL_FIELDS);
        Map<String, CustomFieldDefinition> defs = customDefsByColumn("deal", request.getMapping());
        List<PlanRow> plan = collectRows(request, byColumn, defs, "company", "pipeline", "stage", "people");
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
            Map<String, Integer> byNameCompany = new HashMap<>();
            for (Deal existing : dealMapper.getDealsForDedup(workspaceId)) {
                byNameCompany.putIfAbsent(
                    dealKey(normName(existing.getName()), existing.getCompanyId()),
                    existing.getId());
            }
            Map<Integer, Integer> links =
                request.getLinks() == null ? Map.of() : request.getLinks();
            for (PlanRow row : plan) {
                if (INVALID.equals(row.status)) continue;
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
                Integer matchId = companyKnown
                    ? byNameCompany.get(dealKey(
                        normName(row.std.get("name")),
                        row.resolvedCompanyId))
                    : null;
                if (matchId != null) markMatch(row, matchId, row.std.get("name"));
            }
            resolveStages(workspaceId, plan);
            dedupeWithinFile(plan,
                r -> normName(r.std.get("name")) + " "
                    + (normName(r.companyName) == null ? "" : normName(r.companyName)));
            String action = resolveAction(request.getOnDuplicate());
            applyCompanyDependencyFailures(
                plan,
                row -> dealCompanyDependencyRequired(
                    workspaceId, row, action));
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
            return new ImportAnalysis(plan, reviewProof);
        } catch (RuntimeException exception) {
            cancelPreviewAnalysis(phase, reviewProof);
            throw exception;
        }
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
        updateReviewDigest(digest, "connex-deal-import-decision-v1");
        updateReviewDigest(digest, Integer.toString(plan.size()));
        for (PlanRow row : plan) {
            updateReviewDigest(digest, Integer.toString(row.rowIndex));
            updateReviewDigest(digest, row.status);
            updateReviewDigest(
                digest,
                row.matchedId == null ? null : row.matchedId.toString());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean applyDealUpdate(
            int workspaceId,
            PlanRow row,
            String action,
            Map<String, Integer> columnToDef,
            Map<String, Integer> tagByName,
            Map<String, Integer> companyByName,
            Map<String, Integer> personByEmail,
            Map<Integer, String> stageOutcome,
            Map<Integer, Deal> lockedTargets) {
        Deal existing = lockedTargets.get(row.matchedId);
        if (existing == null) {
            fail(row, "Matched deal #" + row.matchedId + " not found");
            return false;
        }
        Integer beforeStageId = existing.getStageId();
        Boolean beforeOutcome = existing.getWon();
        existing.setName(merge(action, existing.getName(), row.std.get("name")));
        existing.setCurrency(merge(action, existing.getCurrency(), row.std.get("currency")));
        existing.setExpectedCloseDate(merge(action, existing.getExpectedCloseDate(), row.std.get("expectedCloseDate")));
        String value = row.std.get("value");
        boolean shouldUpdateValue = value != null && (OVERWRITE.equals(action) || existing.getValue() == 0d);
        if (shouldUpdateValue && dealLineItemMapper.countByDealIdForUpdate(workspaceId, existing.getId()) == 0) {
            existing.setValue(parseValue(value));
        }
        Integer companyId = companyId(row, companyByName);
        if (companyId != null && (OVERWRITE.equals(action) || existing.getCompanyId() == null)) {
            existing.setCompanyId(companyId);
        }
        if (OVERWRITE.equals(action) && row.stageName != null && row.resolvedStageId != null) {
            existing.setPipelineId(row.resolvedPipelineId);
            existing.setStageId(row.resolvedStageId);
        }
        reconcileClose(workspaceId, existing, stageOutcome);
        dealMapper.update(existing);
        Integer afterStageId = existing.getStageId();
        if (afterStageId != null && !afterStageId.equals(beforeStageId)) {
            dealStageHistoryService.recordTransition(
                workspaceId, existing.getId(), afterStageId, beforeOutcome, existing.getWon());
        }
        attachDealTags(workspaceId, existing.getId(), row.tagNames, tagByName);
        linkDealPeople(workspaceId, existing.getId(), row.peopleEmails, personByEmail);
        applyCustomValues("deal", existing.getId(), row.custom, columnToDef, action, true);
        return true;
    }

    private void resolveStages(int workspaceId, List<PlanRow> plan) {
        var pipelines = pipelineMapper.getAllPipelines(workspaceId);
        Map<String, Integer> pipelineByName = new HashMap<>();
        Map<Integer, Map<String, Integer>> stageByName = new HashMap<>();
        Map<Integer, Integer> firstStage = new HashMap<>();
        for (var pipeline : pipelines) {
            pipelineByName.put(pipeline.getName().toLowerCase(), pipeline.getId());
            Map<String, Integer> stages = new HashMap<>();
            List<Stage> list = pipelineMapper.getStagesByPipelineId(workspaceId, pipeline.getId());
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

    private void reconcileClose(int workspaceId, Deal deal, Map<Integer, String> stageOutcome) {
        Integer stageId = deal.getStageId();
        String outcome = stageId == null ? "normal"
            : stageOutcome.computeIfAbsent(stageId, sid -> dealMapper.getStageOutcome(workspaceId, sid));
        if ("won".equals(outcome)) deal.setWon(true);
        else if ("lost".equals(outcome)) deal.setWon(false);
        if (deal.getWon() == null) {
            deal.setClosedAt(null);
            deal.setClosedReason(null);
        } else if (deal.getClosedAt() == null || deal.getClosedAt().isBlank()) {
            deal.setClosedAt(LocalDateTime.now(ZoneOffset.UTC).format(MYSQL_DATETIME));
        }
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
                throw new BadRequestException(
                    "Multiple contacts match a deal participant email");
            }
            byEmail.put(entry.getKey(), entry.getValue().getFirst().getRecordId());
        }
        return byEmail;
    }

    private void linkDealPeople(int workspaceId, int dealId, List<String> emails, Map<String, Integer> personByEmail) {
        Set<Integer> linked = new LinkedHashSet<>();
        for (String email : emails) {
            Integer personId = personByEmail.get(email);
            if (personId != null) linked.add(personId);
        }
        for (Integer personId : linked) {
            dealMapper.addPerson(workspaceId, dealId, personId, null);
        }
    }

    private void attachDealTags(int workspaceId, int dealId, List<String> tagNames, Map<String, Integer> tagByName) {
        List<Integer> ids = tagIds(tagNames, tagByName);
        if (!ids.isEmpty()) dealMapper.insertTags(workspaceId, dealId, ids);
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
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        updateReviewDigest(digest, "connex-import-review-v1");
        updateReviewDigest(digest, recordType);
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

    private static String dealKey(String name, Integer companyId) {
        return name + " " + (companyId == null ? "" : companyId);
    }

    private static double parseValue(String raw) {
        if (raw == null) return 0d;
        String cleaned = raw.replaceAll("[,\\s]", "");
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    // ===================================================================================
    // Shared row collection + validation
    // ===================================================================================

    private List<PlanRow> collectRows(ImportRequest request, Map<String, ColumnMapping> byColumn,
            Map<String, CustomFieldDefinition> defs, String companyField, String pipelineField,
            String stageField, String peopleField) {
        List<Map<String, String>> rows = request.getRows();
        List<PlanRow> plan = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> raw = rows.get(i);
            PlanRow row = new PlanRow();
            row.rowIndex = i;
            row.status = CREATE;
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
                }
            }
            row.label = row.std.get("name");
            validateRequired(row);
            plan.add(row);
        }
        return plan;
    }

    private void validateRequired(PlanRow row) {
        String name = row.std.get("name");
        if (name == null || name.isBlank()) {
            fail(row, "Name is required");
            return;
        }
        if (name.length() > 255) fail(row, "Name exceeds 255 characters");
        String email = row.std.get("email");
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
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
                Double.parseDouble(value.replaceAll("[,\\s]", ""));
            } catch (NumberFormatException e) {
                fail(row, "Invalid value: " + value);
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
        for (ColumnMapping cm : mapping) {
            String field = cm.getField();
            if (field != null && field.startsWith("custom:")) {
                CustomFieldDefinition def = byId.get(parseCustomId(field));
                if (def != null) result.put(cm.getColumn(), def);
            } else if (Boolean.TRUE.equals(cm.getCreateCustomField())) {
                CustomFieldDefinition placeholder = new CustomFieldDefinition();
                placeholder.setFieldType(normalizeCustomType(cm.getCustomFieldType()));
                placeholder.setLabel(customLabel(cm));
                result.put(cm.getColumn(), placeholder);
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
        Map<String, String> newFieldKeys = new HashMap<>();
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
            .sorted(Comparator.comparing((ColumnMapping cm) -> slug(customLabel(cm)))
                .thenComparing(ColumnMapping::getColumn, String.CASE_INSENSITIVE_ORDER))
            .toList();
        for (ColumnMapping cm : newMappings) {
            String prior = newFieldKeys.putIfAbsent(slug(customLabel(cm)), cm.getColumn());
            if (prior != null) {
                throw new BadRequestException(
                    "Columns '" + prior + "' and '" + cm.getColumn()
                        + "' would create the same custom field");
            }
            columnToDef.put(cm.getColumn(), createDefinition(workspaceId, entityType, cm));
        }
        return columnToDef;
    }

    private int createDefinition(int workspaceId, String entityType, ColumnMapping cm) {
        String type = normalizeCustomType(cm.getCustomFieldType());
        String label = customLabel(cm);
        String key = slug(label);
        CustomFieldDefinition existing = customFieldDefinitionMapper.getByKey(workspaceId, entityType, key);
        if (existing != null) return existing.getId();
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
                    fail(row, "Matched " + entityLabel + " #" + matchedId + " not found");
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
            "company");
    }

    private void serializeAndRevalidateIdentities(
            int workspaceId,
            List<PlanRow> plan,
            String action,
            Function<PlanRow, List<CanonicalIdentity>> identities,
            IdentityGroupLocker locker,
            CurrentIdentityMatcher matcher,
            String entityLabel) {
        Map<PlanRow, List<CanonicalIdentity>> keysByRow = new LinkedHashMap<>();
        Set<CanonicalIdentity> keys = new TreeSet<>(Comparator
            .comparing(CanonicalIdentity::kind)
            .thenComparing(CanonicalIdentity::normalizedValue));
        for (PlanRow row : plan) {
            if (!willWrite(row, action)
                    || !(CREATE.equals(row.status) || row.automaticIdentityMatch)) {
                continue;
            }
            List<CanonicalIdentity> rowKeys = identities.apply(row);
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
                .flatMap(key -> matchesByKey.getOrDefault(key, Set.of()).stream())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (CREATE.equals(row.status) && !currentRecordIds.isEmpty()) {
                fail(row, "A " + entityLabel
                    + " acquired a supplied identity after duplicate review");
                continue;
            }
            if (row.automaticIdentityMatch
                    && !currentRecordIds.equals(Set.of(row.matchedId))) {
                fail(row, "Matched " + entityLabel + " #" + row.matchedId
                    + " no longer uniquely carries the supplied identities");
            }
        }
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
        for (ColumnMapping cm : mapping) {
            if (!Boolean.TRUE.equals(cm.getCreateCustomField()) || !usedNewColumns.contains(cm.getColumn())) {
                continue;
            }
            String key = slug(customLabel(cm));
            if (customFieldDefinitionMapper.getByKey(workspaceId, entityType, key) == null) {
                workspaceService.requirePermission(Permission.CUSTOM_FIELD_MANAGE);
                return;
            }
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

    private void attachTags(int workspaceId, String entityType, int entityId, List<String> tagNames, Map<String, Integer> tagByName) {
        List<Integer> ids = tagIds(tagNames, tagByName);
        if (ids.isEmpty()) return;
        if ("person".equals(entityType)) personMapper.insertTags(workspaceId, entityId, ids);
        else if ("company".equals(entityType)) companyMapper.insertTags(workspaceId, entityId, ids);
    }

    private static List<Integer> tagIds(List<String> tagNames, Map<String, Integer> tagByName) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String name : tagNames) {
            Integer id = tagByName.get(name.trim().toLowerCase());
            if (id != null) ids.add(id);
        }
        return new ArrayList<>(ids);
    }

    private void applyCustomValues(String entityType, int entityId, Map<String, String> custom,
            Map<String, Integer> columnToDef, String action, boolean isUpdate) {
        if (custom.isEmpty()) return;
        boolean fillEmpty = isUpdate && FILL_EMPTY.equals(action);
        Map<Integer, Object> existing = fillEmpty
            ? customFieldValueService.getForEntities(entityType, List.of(entityId)).getOrDefault(entityId, Map.of())
            : Map.of();
        Map<Integer, Object> values = new HashMap<>();
        for (Map.Entry<String, String> entry : custom.entrySet()) {
            Integer defId = columnToDef.get(entry.getKey());
            if (defId == null) continue;
            if (fillEmpty) {
                Object current = existing.get(defId);
                if (current != null && !String.valueOf(current).isBlank()) continue;
            }
            values.put(defId, entry.getValue());
        }
        if (!values.isEmpty()) customFieldValueService.applyValues(entityType, entityId, values);
    }

    private void requireUpdatePermission(List<PlanRow> plan, String action, Permission updatePermission) {
        if (!SKIP.equals(action) && plan.stream().anyMatch(row -> MATCH.equals(row.status))) {
            workspaceService.requirePermission(updatePermission);
        }
    }

    // ===================================================================================
    // Preview summary + audit
    // ===================================================================================

    private ImportPreviewResult summarize(List<PlanRow> plan, String onDuplicate) {
        String action = resolveAction(onDuplicate);
        List<RowAnalysis> rows = new ArrayList<>(plan.size());
        int toCreate = 0;
        int toUpdate = 0;
        int toSkip = 0;
        int invalid = 0;
        for (PlanRow row : plan) {
            String status = row.status;
            if (INVALID.equals(status)) {
                invalid++;
            } else if (SKIP.equals(status)) {
                toSkip++;
            } else if (MATCH.equals(status)) {
                if (SKIP.equals(action)) { status = SKIP; toSkip++; } else { toUpdate++; }
            } else {
                toCreate++;
            }
            rows.add(new RowAnalysis(row.rowIndex, status, row.matchedId, row.label,
                row.errors.isEmpty() ? null : List.copyOf(row.errors),
                row.duplicateCandidates.isEmpty()
                    ? null
                    : List.copyOf(row.duplicateCandidates)));
        }
        return new ImportPreviewResult(plan.size(), toCreate, toUpdate, toSkip, invalid, rows);
    }

    private void auditImport(String entityType, int created, int updated, int skipped) {
        if (created + updated + skipped == 0) return;
        auditService.record("import." + entityType, entityType, null, "CSV import",
            "Imported " + entityType + "s: " + created + " created, " + updated + " updated, " + skipped + " skipped",
            Map.of("created", created, "updated", updated, "skipped", skipped));
    }

    private static List<RowError> collectFailures(List<PlanRow> plan) {
        List<RowError> failed = new ArrayList<>();
        for (PlanRow row : plan) {
            if (INVALID.equals(row.status)) {
                failed.add(new RowError(row.rowIndex, String.join("; ", row.errors)));
            }
        }
        return failed;
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
                throw new BadRequestException("Multiple columns are mapped to the same field: " + field);
            }
        }
        return byColumn;
    }

    private static void dedupeWithinFile(List<PlanRow> plan, Function<PlanRow, String> keyFn) {
        Set<String> seen = new HashSet<>();
        for (PlanRow row : plan) {
            if (!CREATE.equals(row.status)) continue;
            String key = keyFn.apply(row);
            if (key == null || key.isBlank()) continue;
            if (!seen.add(key)) row.status = SKIP;
        }
    }

    private static void dedupeWithinFileByStrongKeys(
            List<PlanRow> plan,
            String action,
            Function<PlanRow, List<String>> keysFn) {
        Map<PlanRow, List<String>> keysByRow = new LinkedHashMap<>();
        Map<String, List<PlanRow>> rowsByKey = new LinkedHashMap<>();
        for (PlanRow row : plan) {
            if (!willWrite(row, action)) continue;
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
            if (matchedIds.size() > 1 || component.size() > 1 && commonKeys.isEmpty()) {
                String error = matchedIds.size() > 1
                    ? "Canonical identity is linked to multiple import targets"
                    : "Connected canonical identities require explicit duplicate resolution";
                component.stream()
                    .filter(row -> !INVALID.equals(row.status))
                    .forEach(row -> fail(row, error));
            }
        }
        Set<String> seen = new HashSet<>();
        reserveStrongKeys(plan, action, MATCH, keysByRow, seen);
        reserveStrongKeys(plan, action, CREATE, keysByRow, seen);
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

    private static void reserveStrongKeys(
            List<PlanRow> plan,
            String action,
            String status,
            Map<PlanRow, List<String>> keysByRow,
            Set<String> seen) {
        for (PlanRow row : plan) {
            if (!status.equals(row.status) || !willWrite(row, action)) continue;
            List<String> keys = keysByRow.getOrDefault(row, List.of());
            if (keys.stream().anyMatch(seen::contains)) {
                row.status = SKIP;
            } else {
                seen.addAll(keys);
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

    private static String resolveAction(String onDuplicate) {
        if (SKIP.equals(onDuplicate) || OVERWRITE.equals(onDuplicate)) return onDuplicate;
        return FILL_EMPTY;
    }

    private static String merge(String action, String existing, String incoming) {
        if (incoming == null) return existing;
        if (OVERWRITE.equals(action)) return incoming;
        return (existing == null || existing.isBlank()) ? incoming : existing;
    }

    private static boolean fieldAcquired(String action, String existing, String incoming) {
        return incoming != null
            && (OVERWRITE.equals(action)
                || existing == null
                || existing.isBlank()
                || incoming.equals(existing));
    }

    private static String csvRowRef(PlanRow row) {
        return "csv-row:" + (row.rowIndex + 1);
    }

    private record CanonicalIdentity(String kind, String normalizedValue) {
        private String dedupeKey() {
            return kind + ":" + normalizedValue;
        }
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
        row.errors.add(error);
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
        String t = type == null ? "text" : type.trim().toLowerCase();
        return AUTO_CUSTOM_TYPES.contains(t) ? t : "text";
    }

    private static String customLabel(ColumnMapping cm) {
        String label = cm.getCustomFieldLabel();
        return (label == null || label.isBlank()) ? cm.getColumn() : label.trim();
    }

    private static String slug(String label) {
        String s = label.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "field";
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
