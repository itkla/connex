package ooo.klae.connex.backend.services;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.ImportPreviewResult;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.dto.RowAnalysis;
import ooo.klae.connex.backend.dto.RowError;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
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
 * write a single {@code import.*} audit summary rather than auditing each row, and they do not fire
 * per-row rule/notification triggers. Tags and auto-created custom-field definitions are resolved up
 * front through their permission-checked services; referenced companies are created through
 * {@code CompanyService} so {@code COMPANY_CREATE} is enforced even during a contact or deal import.
 */
@Service
@RequiredArgsConstructor
public class ImportService {

    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final CompanyService companyService;
    private final DealMapper dealMapper;
    private final TagMapper tagMapper;
    private final TagService tagService;
    private final PipelineMapper pipelineMapper;
    private final EmploymentService employmentService;
    private final CustomFieldDefinitionMapper customFieldDefinitionMapper;
    private final CustomFieldDefinitionService customFieldDefinitionService;
    private final CustomFieldValueService customFieldValueService;
    private final AuditService auditService;

    private static final DateTimeFormatter MYSQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_TAG_COLOR = "#CCCCCC";

    private static final Set<String> PERSON_FIELDS = Set.of("name", "email", "phone", "title", "company", "imageUrl");
    private static final Set<String> COMPANY_FIELDS = Set.of("name", "website", "industry", "phone", "address", "logoUrl");
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
        String pipelineName;
        String stageName;
        final List<String> peopleEmails = new ArrayList<>();
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
        return summarize(analyzePersons(request, workspaceId), request.getOnDuplicate());
    }

    /**
     * Commit a contact import. Creates new contacts and merges matches per {@code onDuplicate};
     * resolves referenced companies (creating missing ones), tags (create-if-missing), and any
     * auto-created custom-field definitions.
     */
    @Transactional
    @RequirePermission(Permission.PERSON_CREATE)
    public ImportResult commitPersons(ImportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String action = resolveAction(request.getOnDuplicate());
        List<PlanRow> plan = analyzePersons(request, workspaceId);

        Map<String, Integer> columnToDef = resolveCustomDefinitions("person", request.getMapping());
        Map<String, Integer> tagByName = resolveTags(plan);
        Map<String, Integer> companyByName = resolveCompanies(workspaceId, plan);

        List<PlanRow> toCreate = new ArrayList<>();
        List<Person> beans = new ArrayList<>();
        int updated = 0;
        int skipped = 0;

        for (PlanRow row : plan) {
            if (INVALID.equals(row.status)) continue;
            if (SKIP.equals(row.status)) { skipped++; continue; }
            if (MATCH.equals(row.status)) {
                if (SKIP.equals(action)) { skipped++; continue; }
                applyPersonUpdate(workspaceId, row, action, columnToDef, tagByName, companyByName);
                updated++;
                continue;
            }
            Person bean = new Person();
            bean.setWorkspaceId(workspaceId);
            bean.setName(row.std.get("name"));
            bean.setEmail(row.std.get("email"));
            bean.setPhone(row.std.get("phone"));
            bean.setTitle(row.std.get("title"));
            bean.setImageUrl(row.std.get("imageUrl"));
            Integer companyId = companyByName.get(normName(row.companyName));
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
                Integer companyId = bean.getCompany() != null ? bean.getCompany().getId() : null;
                if (companyId != null) {
                    employmentService.recordInitial(workspaceId, bean.getId(), companyId, bean.getTitle());
                }
                attachTags("person", bean.getId(), row.tagNames, tagByName);
                applyCustomValues("person", bean.getId(), row.custom, columnToDef);
            }
        }

        int created = beans.size();
        auditImport("person", created, updated, skipped);
        return new ImportResult(created, updated, skipped, collectFailures(plan));
    }

    private List<PlanRow> analyzePersons(ImportRequest request, int workspaceId) {
        Map<String, ColumnMapping> byColumn = mappingByColumn(request.getMapping(), PERSON_FIELDS);
        Map<String, CustomFieldDefinition> defs = customDefsByColumn("person", request.getMapping());
        List<PlanRow> plan = collectRows(request, byColumn, defs, "company", null, null, null);

        List<String> emails = new ArrayList<>();
        for (PlanRow row : plan) {
            String email = row.std.get("email");
            if (email != null) emails.add(email);
        }
        Map<String, Person> byEmail = new HashMap<>();
        if (!emails.isEmpty()) {
            for (Person existing : personMapper.findByEmails(workspaceId, emails.stream().distinct().toList())) {
                if (existing.getEmail() != null) byEmail.putIfAbsent(normEmail(existing.getEmail()), existing);
            }
        }
        Map<Integer, Integer> links = request.getLinks() == null ? Map.of() : request.getLinks();
        for (PlanRow row : plan) {
            if (INVALID.equals(row.status)) continue;
            Integer linked = links.get(row.rowIndex);
            if (linked != null) {
                Person existing = personMapper.getPersonById(workspaceId, linked);
                if (existing == null) { fail(row, "Linked contact #" + linked + " not found"); continue; }
                markMatch(row, existing.getId(), existing.getName());
                continue;
            }
            String email = row.std.get("email");
            Person existing = email == null ? null : byEmail.get(email);
            if (existing != null) markMatch(row, existing.getId(), existing.getName());
        }
        dedupeWithinFile(plan, r -> r.std.get("email"));
        return plan;
    }

    private void applyPersonUpdate(int workspaceId, PlanRow row, String action,
            Map<String, Integer> columnToDef, Map<String, Integer> tagByName, Map<String, Integer> companyByName) {
        Person existing = personMapper.getPersonById(workspaceId, row.matchedId);
        if (existing == null) return;
        existing.setName(merge(action, existing.getName(), row.std.get("name")));
        existing.setEmail(merge(action, existing.getEmail(), row.std.get("email")));
        existing.setPhone(merge(action, existing.getPhone(), row.std.get("phone")));
        existing.setTitle(merge(action, existing.getTitle(), row.std.get("title")));
        existing.setImageUrl(merge(action, existing.getImageUrl(), row.std.get("imageUrl")));
        Integer companyId = companyByName.get(normName(row.companyName));
        if (companyId != null && (OVERWRITE.equals(action) || existing.getCompany() == null)) {
            Company stub = new Company();
            stub.setId(companyId);
            existing.setCompany(stub);
        }
        personMapper.update(existing);
        attachTags("person", existing.getId(), row.tagNames, tagByName);
        applyCustomValues("person", existing.getId(), row.custom, columnToDef);
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
        return summarize(analyzeCompanies(request, workspaceId), request.getOnDuplicate());
    }

    /**
     * Commit a company import. Deduplicates on normalized website then normalized name.
     */
    @Transactional
    @RequirePermission(Permission.COMPANY_CREATE)
    public ImportResult commitCompanies(ImportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String action = resolveAction(request.getOnDuplicate());
        List<PlanRow> plan = analyzeCompanies(request, workspaceId);

        Map<String, Integer> columnToDef = resolveCustomDefinitions("company", request.getMapping());
        Map<String, Integer> tagByName = resolveTags(plan);

        List<PlanRow> toCreate = new ArrayList<>();
        List<Company> beans = new ArrayList<>();
        int updated = 0;
        int skipped = 0;

        for (PlanRow row : plan) {
            if (INVALID.equals(row.status)) continue;
            if (SKIP.equals(row.status)) { skipped++; continue; }
            if (MATCH.equals(row.status)) {
                if (SKIP.equals(action)) { skipped++; continue; }
                applyCompanyUpdate(workspaceId, row, action, columnToDef, tagByName);
                updated++;
                continue;
            }
            Company bean = new Company();
            bean.setWorkspaceId(workspaceId);
            bean.setName(row.std.get("name"));
            bean.setWebsite(row.std.get("website"));
            bean.setIndustry(row.std.get("industry"));
            bean.setPhone(row.std.get("phone"));
            bean.setAddress(row.std.get("address"));
            bean.setLogoUrl(row.std.get("logoUrl"));
            beans.add(bean);
            toCreate.add(row);
        }

        if (!beans.isEmpty()) {
            companyMapper.insertBatch(beans);
            for (int i = 0; i < beans.size(); i++) {
                Company bean = beans.get(i);
                PlanRow row = toCreate.get(i);
                attachTags("company", bean.getId(), row.tagNames, tagByName);
                applyCustomValues("company", bean.getId(), row.custom, columnToDef);
            }
        }

        int created = beans.size();
        auditImport("company", created, updated, skipped);
        return new ImportResult(created, updated, skipped, collectFailures(plan));
    }

    private List<PlanRow> analyzeCompanies(ImportRequest request, int workspaceId) {
        Map<String, ColumnMapping> byColumn = mappingByColumn(request.getMapping(), COMPANY_FIELDS);
        Map<String, CustomFieldDefinition> defs = customDefsByColumn("company", request.getMapping());
        List<PlanRow> plan = collectRows(request, byColumn, defs, null, null, null, null);

        Map<String, Integer> byWebsite = new HashMap<>();
        Map<String, Integer> byName = new HashMap<>();
        for (Company existing : companyMapper.getCompaniesForDedup(workspaceId)) {
            String website = normWebsite(existing.getWebsite());
            if (!website.isEmpty()) byWebsite.putIfAbsent(website, existing.getId());
            String name = normName(existing.getName());
            if (name != null) byName.putIfAbsent(name, existing.getId());
        }
        Map<Integer, Integer> links = request.getLinks() == null ? Map.of() : request.getLinks();
        for (PlanRow row : plan) {
            if (INVALID.equals(row.status)) continue;
            Integer linked = links.get(row.rowIndex);
            if (linked != null) {
                Company existing = companyMapper.getCompanyById(workspaceId, linked);
                if (existing == null) { fail(row, "Linked company #" + linked + " not found"); continue; }
                markMatch(row, existing.getId(), existing.getName());
                continue;
            }
            String website = normWebsite(row.std.get("website"));
            Integer matchId = website.isEmpty() ? null : byWebsite.get(website);
            if (matchId == null) matchId = byName.get(normName(row.std.get("name")));
            if (matchId != null) markMatch(row, matchId, row.std.get("name"));
        }
        dedupeWithinFile(plan, r -> {
            String website = normWebsite(r.std.get("website"));
            return website.isEmpty() ? normName(r.std.get("name")) : website;
        });
        return plan;
    }

    private void applyCompanyUpdate(int workspaceId, PlanRow row, String action,
            Map<String, Integer> columnToDef, Map<String, Integer> tagByName) {
        Company existing = companyMapper.getCompanyById(workspaceId, row.matchedId);
        if (existing == null) return;
        existing.setName(merge(action, existing.getName(), row.std.get("name")));
        existing.setWebsite(merge(action, existing.getWebsite(), row.std.get("website")));
        existing.setIndustry(merge(action, existing.getIndustry(), row.std.get("industry")));
        existing.setPhone(merge(action, existing.getPhone(), row.std.get("phone")));
        existing.setAddress(merge(action, existing.getAddress(), row.std.get("address")));
        existing.setLogoUrl(merge(action, existing.getLogoUrl(), row.std.get("logoUrl")));
        companyMapper.update(existing);
        attachTags("company", existing.getId(), row.tagNames, tagByName);
        applyCustomValues("company", existing.getId(), row.custom, columnToDef);
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
        return summarize(analyzeDeals(request, workspaceId), request.getOnDuplicate());
    }

    /**
     * Commit a deal import. Resolves pipeline/stage by name (defaulting to the first pipeline and its
     * first stage), links existing people by email, and deduplicates on name + company.
     */
    @Transactional
    @RequirePermission(Permission.DEAL_CREATE)
    public ImportResult commitDeals(ImportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        String action = resolveAction(request.getOnDuplicate());
        List<PlanRow> plan = analyzeDeals(request, workspaceId);

        Map<String, Integer> columnToDef = resolveCustomDefinitions("deal", request.getMapping());
        Map<String, Integer> tagByName = resolveTags(plan);
        Map<String, Integer> companyByName = resolveCompanies(workspaceId, plan);
        Map<String, Integer> personByEmail = resolveDealPeople(workspaceId, plan);
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
                applyDealUpdate(workspaceId, row, action, columnToDef, tagByName, companyByName, personByEmail,
                    stageOutcome);
                updated++;
                continue;
            }
            Integer[] resolved = resolveStage(workspaceId, row);
            if (resolved == null) { fail(row, "Unknown pipeline or stage"); continue; }
            Deal bean = new Deal();
            bean.setWorkspaceId(workspaceId);
            bean.setOwnerId(actorId);
            bean.setName(row.std.get("name"));
            bean.setValue(parseValue(row.std.get("value")));
            bean.setCurrency(row.std.get("currency"));
            bean.setExpectedCloseDate(row.std.get("expectedCloseDate"));
            bean.setPipelineId(resolved[0]);
            bean.setStageId(resolved[1]);
            bean.setCompanyId(companyByName.get(normName(row.companyName)));
            reconcileClose(bean, stageOutcome);
            beans.add(bean);
            toCreate.add(row);
        }

        if (!beans.isEmpty()) {
            dealMapper.insertBatch(beans);
            for (int i = 0; i < beans.size(); i++) {
                Deal bean = beans.get(i);
                PlanRow row = toCreate.get(i);
                attachDealTags(workspaceId, bean.getId(), row.tagNames, tagByName);
                linkDealPeople(workspaceId, bean.getId(), row.peopleEmails, personByEmail);
                applyCustomValues("deal", bean.getId(), row.custom, columnToDef);
            }
        }

        int created = beans.size();
        auditImport("deal", created, updated, skipped);
        return new ImportResult(created, updated, skipped, collectFailures(plan));
    }

    private List<PlanRow> analyzeDeals(ImportRequest request, int workspaceId) {
        Map<String, ColumnMapping> byColumn = mappingByColumn(request.getMapping(), DEAL_FIELDS);
        Map<String, CustomFieldDefinition> defs = customDefsByColumn("deal", request.getMapping());
        List<PlanRow> plan = collectRows(request, byColumn, defs, "company", "pipeline", "stage", "people");

        Map<String, Integer> byNameCompany = new HashMap<>();
        for (Deal existing : dealMapper.getDealsForDedup(workspaceId)) {
            byNameCompany.putIfAbsent(dealKey(normName(existing.getName()), existing.getCompanyId()), existing.getId());
        }
        Map<String, Integer> companyByName = existingCompanyIds(workspaceId);
        Map<Integer, Integer> links = request.getLinks() == null ? Map.of() : request.getLinks();
        for (PlanRow row : plan) {
            if (INVALID.equals(row.status)) continue;
            Integer linked = links.get(row.rowIndex);
            if (linked != null) {
                Deal existing = dealMapper.getDealById(workspaceId, linked);
                if (existing == null) { fail(row, "Linked deal #" + linked + " not found"); continue; }
                markMatch(row, existing.getId(), existing.getName());
                continue;
            }
            Integer companyId = companyByName.get(normName(row.companyName));
            Integer matchId = byNameCompany.get(dealKey(normName(row.std.get("name")), companyId));
            if (matchId != null) markMatch(row, matchId, row.std.get("name"));
        }
        dedupeWithinFile(plan,
            r -> dealKey(normName(r.std.get("name")), companyByName.get(normName(r.companyName))));
        return plan;
    }

    private void applyDealUpdate(int workspaceId, PlanRow row, String action,
            Map<String, Integer> columnToDef, Map<String, Integer> tagByName, Map<String, Integer> companyByName,
            Map<String, Integer> personByEmail, Map<Integer, String> stageOutcome) {
        Deal existing = dealMapper.getDealById(workspaceId, row.matchedId);
        if (existing == null) return;
        existing.setName(merge(action, existing.getName(), row.std.get("name")));
        existing.setCurrency(merge(action, existing.getCurrency(), row.std.get("currency")));
        existing.setExpectedCloseDate(merge(action, existing.getExpectedCloseDate(), row.std.get("expectedCloseDate")));
        String value = row.std.get("value");
        if (value != null && (OVERWRITE.equals(action) || existing.getValue() == 0d)) {
            existing.setValue(parseValue(value));
        }
        Integer companyId = companyByName.get(normName(row.companyName));
        if (companyId != null && (OVERWRITE.equals(action) || existing.getCompanyId() == null)) {
            existing.setCompanyId(companyId);
        }
        if (OVERWRITE.equals(action) && row.stageName != null) {
            Integer[] resolved = resolveStage(workspaceId, row);
            if (resolved != null) {
                existing.setPipelineId(resolved[0]);
                existing.setStageId(resolved[1]);
            }
        }
        reconcileClose(existing, stageOutcome);
        dealMapper.update(existing);
        attachDealTags(workspaceId, existing.getId(), row.tagNames, tagByName);
        linkDealPeople(workspaceId, existing.getId(), row.peopleEmails, personByEmail);
        applyCustomValues("deal", existing.getId(), row.custom, columnToDef);
    }

    private Integer[] resolveStage(int workspaceId, PlanRow row) {
        Integer pipelineId = null;
        if (row.pipelineName != null) {
            for (var pipeline : pipelineMapper.getAllPipelines(workspaceId)) {
                if (row.pipelineName.equalsIgnoreCase(pipeline.getName())) { pipelineId = pipeline.getId(); break; }
            }
            if (pipelineId == null) return null;
        } else {
            var pipelines = pipelineMapper.getAllPipelines(workspaceId);
            if (pipelines.isEmpty()) return null;
            pipelineId = pipelines.get(0).getId();
        }
        List<Stage> stages = pipelineMapper.getStagesByPipelineId(workspaceId, pipelineId);
        if (stages.isEmpty()) return null;
        Stage stage = null;
        if (row.stageName != null) {
            for (Stage candidate : stages) {
                if (row.stageName.equalsIgnoreCase(candidate.getName())) { stage = candidate; break; }
            }
            if (stage == null) return null;
        } else {
            stage = stages.get(0);
        }
        return new Integer[] { pipelineId, stage.getId() };
    }

    private void reconcileClose(Deal deal, Map<Integer, String> stageOutcome) {
        Integer stageId = deal.getStageId();
        String outcome = stageId == null ? "normal"
            : stageOutcome.computeIfAbsent(stageId, dealMapper::getStageOutcome);
        if ("won".equals(outcome)) deal.setWon(true);
        else if ("lost".equals(outcome)) deal.setWon(false);
        if (deal.getWon() == null) {
            deal.setClosedAt(null);
            deal.setClosedReason(null);
        } else if (deal.getClosedAt() == null || deal.getClosedAt().isBlank()) {
            deal.setClosedAt(LocalDateTime.now(ZoneOffset.UTC).format(MYSQL_DATETIME));
        }
    }

    private Map<String, Integer> resolveDealPeople(int workspaceId, List<PlanRow> plan) {
        Set<String> emails = new LinkedHashSet<>();
        for (PlanRow row : plan) {
            if (!INVALID.equals(row.status) && !SKIP.equals(row.status)) emails.addAll(row.peopleEmails);
        }
        Map<String, Integer> byEmail = new HashMap<>();
        if (!emails.isEmpty()) {
            for (Person person : personMapper.findByEmails(workspaceId, List.copyOf(emails))) {
                if (person.getEmail() != null) byEmail.putIfAbsent(normEmail(person.getEmail()), person.getId());
            }
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

    private Map<String, Integer> existingCompanyIds(int workspaceId) {
        Map<String, Integer> byName = new HashMap<>();
        for (Company existing : companyMapper.getCompaniesForDedup(workspaceId)) {
            String name = normName(existing.getName());
            if (name != null) byName.putIfAbsent(name, existing.getId());
        }
        return byName;
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
                    validateCustom(row, defs.get(column), value);
                    row.custom.put(column, value);
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
    }

    private void validateCustom(PlanRow row, CustomFieldDefinition def, String value) {
        switch (def.getFieldType()) {
            case "number" -> {
                try {
                    Double.parseDouble(value.replaceAll("[,\\s]", ""));
                } catch (NumberFormatException e) {
                    fail(row, "Invalid number for " + def.getLabel() + ": " + value);
                }
            }
            case "date" -> {
                try {
                    LocalDate.parse(value.trim());
                } catch (RuntimeException e) {
                    fail(row, "Invalid date for " + def.getLabel() + ": " + value);
                }
            }
            case "boolean" -> {
                if (!value.trim().toLowerCase().matches("true|false|1|0|yes|no")) {
                    fail(row, "Invalid boolean for " + def.getLabel() + ": " + value);
                }
            }
            default -> { }
        }
    }

    private static String normalizeStandard(String field, String value) {
        if ("email".equals(field)) return normEmail(value);
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
            } else if (cm.isCreateCustomField()) {
                CustomFieldDefinition placeholder = new CustomFieldDefinition();
                placeholder.setFieldType(normalizeCustomType(cm.getCustomFieldType()));
                placeholder.setLabel(customLabel(cm));
                result.put(cm.getColumn(), placeholder);
            }
        }
        return result;
    }

    private Map<String, Integer> resolveCustomDefinitions(String entityType, List<ColumnMapping> mapping) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
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
            } else if (cm.isCreateCustomField()) {
                columnToDef.put(cm.getColumn(), createDefinition(workspaceId, entityType, cm));
            }
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

    private Map<String, Integer> resolveTags(List<PlanRow> plan) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Set<String> names = new LinkedHashSet<>();
        for (PlanRow row : plan) {
            if (!INVALID.equals(row.status) && !SKIP.equals(row.status)) names.addAll(row.tagNames);
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

    private Map<String, Integer> resolveCompanies(int workspaceId, List<PlanRow> plan) {
        Map<String, Integer> byName = existingCompanyIds(workspaceId);
        Set<String> pending = new LinkedHashSet<>();
        for (PlanRow row : plan) {
            if (INVALID.equals(row.status) || SKIP.equals(row.status)) continue;
            String norm = normName(row.companyName);
            if (norm == null || byName.containsKey(norm) || !pending.add(norm)) continue;
            Company company = new Company();
            company.setName(row.companyName.trim());
            byName.put(norm, companyService.createCompany(company).getId());
        }
        return byName;
    }

    // ===================================================================================
    // Apply helpers
    // ===================================================================================

    private void attachTags(String entityType, int entityId, List<String> tagNames, Map<String, Integer> tagByName) {
        List<Integer> ids = tagIds(tagNames, tagByName);
        if (ids.isEmpty()) return;
        if ("person".equals(entityType)) personMapper.insertTags(entityId, ids);
        else if ("company".equals(entityType)) companyMapper.insertTags(entityId, ids);
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
            Map<String, Integer> columnToDef) {
        if (custom.isEmpty()) return;
        Map<Integer, Object> values = new HashMap<>();
        for (Map.Entry<String, String> entry : custom.entrySet()) {
            Integer defId = columnToDef.get(entry.getKey());
            if (defId != null) values.put(defId, entry.getValue());
        }
        if (!values.isEmpty()) customFieldValueService.applyValues(entityType, entityId, values);
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
                row.errors.isEmpty() ? null : List.copyOf(row.errors)));
        }
        return new ImportPreviewResult(plan.size(), toCreate, toUpdate, toSkip, invalid, rows);
    }

    private void auditImport(String entityType, int created, int updated, int skipped) {
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
            boolean usable = cm.isCreateCustomField()
                || (field != null && (field.startsWith("custom:") || "tags".equals(field) || allowedFields.contains(field)));
            if (usable) byColumn.put(cm.getColumn(), cm);
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

    private static String resolveAction(String onDuplicate) {
        if (SKIP.equals(onDuplicate) || OVERWRITE.equals(onDuplicate)) return onDuplicate;
        return FILL_EMPTY;
    }

    private static String merge(String action, String existing, String incoming) {
        if (incoming == null) return existing;
        if (OVERWRITE.equals(action)) return incoming;
        return (existing == null || existing.isBlank()) ? incoming : existing;
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
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> splitMulti(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return parts;
    }

    private static String normEmail(String value) {
        if (value == null) return null;
        String trimmed = value.trim().toLowerCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normName(String value) {
        if (value == null) return null;
        String trimmed = value.trim().toLowerCase().replaceAll("\\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normWebsite(String website) {
        if (website == null) return "";
        String w = website.trim().toLowerCase();
        w = w.replaceFirst("^https?://", "");
        w = w.replaceFirst("^www\\.", "");
        w = w.replaceAll("/+$", "");
        return w;
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
