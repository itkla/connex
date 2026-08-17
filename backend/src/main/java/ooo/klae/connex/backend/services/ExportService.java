package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.util.CsvFormulaGuard;

/**
 * Builds RFC-4180 CSV exports for contacts, companies, deals, and products, scoped to the active
 * workspace and honoring the same filters as the list endpoints. Every cell is quoted when needed
 * and neutralized against CSV formula injection (leading formula operators and control characters,
 * including their full-width variants, are prefixed with an apostrophe). Record exports append
 * non-archived custom fields and tags; product exports use the catalog's fixed schema.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private final WorkspaceService workspaceService;
    private final DealService dealService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final ProductMapper productMapper;
    private final PipelineMapper pipelineMapper;
    private final TagMapper tagMapper;
    private final CustomFieldDefinitionMapper customFieldDefinitionMapper;
    private final CustomFieldValueService customFieldValueService;

    /**
     * CSV of contacts matching the given list filters and member scope (all contacts when
     * unfiltered). Archived contacts are never exported: an export is the active working set.
     */
    public String exportPersons(String query, List<String> companies, List<String> titles, boolean noCompany,
            MemberScope memberScope) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Person> people = personMapper.getPersonsFiltered(
            workspaceId, query, companies, titles, noCompany, memberScope, false);
        List<CustomFieldDefinition> defs = activeDefinitions(workspaceId, "person");
        Map<Integer, Map<Integer, Object>> custom =
            customFieldValueService.getForEntities("person", people.stream().map(Person::getId).toList());
        Map<Integer, List<String>> tags = groupTags(tagMapper.getPersonTagNames(workspaceId));

        StringBuilder sb = new StringBuilder();
        writeRow(sb, headers(List.of("id", "name", "email", "phone", "title", "company"), defs));
        for (Person p : people) {
            List<String> row = new ArrayList<>();
            row.add(Integer.toString(p.getId()));
            row.add(p.getName());
            row.add(p.getEmail());
            row.add(p.getPhone());
            row.add(p.getTitle());
            row.add(p.getCompany() != null ? p.getCompany().getName() : null);
            appendCustom(row, defs, custom.get(p.getId()));
            row.add(joinTags(tags.get(p.getId())));
            writeRow(sb, row);
        }
        return sb.toString();
    }

    /**
     * CSV of companies matching the given list filters and member scope (all companies when unfiltered).
     */
    public String exportCompanies(String query, List<String> industry, boolean noIndustry, List<Integer> ids,
            MemberScope memberScope) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Company> companies = companyMapper.getCompaniesFiltered(
            workspaceId, query, industry, noIndustry, ids, memberScope, false);
        List<CustomFieldDefinition> defs = activeDefinitions(workspaceId, "company");
        Map<Integer, Map<Integer, Object>> custom =
            customFieldValueService.getForEntities("company", companies.stream().map(Company::getId).toList());
        Map<Integer, List<String>> tags = groupTags(tagMapper.getCompanyTagNames(workspaceId));

        StringBuilder sb = new StringBuilder();
        writeRow(sb, headers(List.of("id", "name", "website", "industry", "phone", "address"), defs));
        for (Company c : companies) {
            List<String> row = new ArrayList<>();
            row.add(Integer.toString(c.getId()));
            row.add(c.getName());
            row.add(c.getWebsite());
            row.add(c.getIndustry());
            row.add(c.getPhone());
            row.add(c.getAddress());
            appendCustom(row, defs, custom.get(c.getId()));
            row.add(joinTags(tags.get(c.getId())));
            writeRow(sb, row);
        }
        return sb.toString();
    }

    /** CSV of products matching the current catalog search (all products when unfiltered). */
    public String exportProducts(String query) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Product> products = productMapper.getFiltered(workspaceId, query);

        StringBuilder sb = new StringBuilder();
        writeRow(sb, List.of(
            "id", "sku", "name", "description", "active", "unit", "unitPrice", "currency",
            "taxRate", "billingFrequency", "effectiveStart", "effectiveEnd"));
        for (Product product : products) {
            writeRow(sb, List.of(
                Integer.toString(product.getId()),
                value(product.getSku()),
                value(product.getName()),
                value(product.getDescription()),
                Boolean.toString(product.isActive()),
                value(product.getUnit()),
                product.getUnitPrice() == null ? "" : product.getUnitPrice().toPlainString(),
                value(product.getCurrency()),
                product.getTaxRate() == null ? "" : product.getTaxRate().toPlainString(),
                value(product.getBillingFrequency()),
                product.getEffectiveStart() == null ? "" : product.getEffectiveStart().toString(),
                product.getEffectiveEnd() == null ? "" : product.getEffectiveEnd().toString()));
        }
        return sb.toString();
    }

    /**
     * CSV of deals matching the given list filters and member scope (all deals when unfiltered).
     */
    public String exportDeals(String query, String currency, List<Integer> pipelineIds, List<Integer> stageIds,
            List<Integer> companyIds, boolean noCompany, List<String> statuses, List<String> risks,
            MemberScope memberScope) {
        List<Deal> deals = dealService.queryDealsForExport(
            query, currency, pipelineIds, stageIds, companyIds, noCompany, statuses, risks, memberScope);
        return renderDeals(deals);
    }

    /** CSV of deals matching both a Smart Segment and the complete native list filter. */
    public String exportSegmentDeals(SegmentDefinition definition, String query, String currency,
            List<Integer> pipelineIds, List<Integer> stageIds, List<Integer> companyIds,
            boolean noCompany, List<String> statuses, List<String> risks, MemberScope memberScope) {
        List<Deal> deals = dealService.querySegmentDealsForExport(
            definition, query, currency, pipelineIds, stageIds, companyIds,
            noCompany, statuses, risks, memberScope);
        return renderDeals(deals);
    }

    private String renderDeals(List<Deal> deals) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<CustomFieldDefinition> defs = activeDefinitions(workspaceId, "deal");
        Map<Integer, Map<Integer, Object>> custom =
            customFieldValueService.getForEntities("deal", deals.stream().map(Deal::getId).toList());
        Map<Integer, List<String>> tags = groupTags(tagMapper.getDealTagNames(workspaceId));

        Map<Integer, String> companyNames = new HashMap<>();
        for (Company c : companyMapper.getCompaniesForDedup(workspaceId)) companyNames.put(c.getId(), c.getName());
        Map<Integer, String> pipelineNames = new HashMap<>();
        Map<Integer, String> stageNames = new HashMap<>();
        for (var pipeline : pipelineMapper.getAllPipelines(workspaceId)) {
            pipelineNames.put(pipeline.getId(), pipeline.getName());
            for (var stage : pipelineMapper.getStagesByPipelineId(workspaceId, pipeline.getId())) {
                stageNames.put(stage.getId(), stage.getName());
            }
        }

        StringBuilder sb = new StringBuilder();
        writeRow(sb, headers(
            List.of("id", "name", "value", "currency", "company", "pipeline", "stage", "expectedCloseDate", "status"),
            defs));
        for (Deal d : deals) {
            List<String> row = new ArrayList<>();
            row.add(Integer.toString(d.getId()));
            row.add(d.getName());
            row.add(formatValue(d.getValue()));
            row.add(d.getCurrency());
            row.add(d.getCompanyId() != null ? companyNames.get(d.getCompanyId()) : null);
            row.add(d.getPipelineId() != null ? pipelineNames.get(d.getPipelineId()) : null);
            row.add(d.getStageId() != null ? stageNames.get(d.getStageId()) : null);
            row.add(d.getExpectedCloseDate());
            row.add(dealStatus(d.getWon()));
            appendCustom(row, defs, custom.get(d.getId()));
            row.add(joinTags(tags.get(d.getId())));
            writeRow(sb, row);
        }
        return sb.toString();
    }

    private List<CustomFieldDefinition> activeDefinitions(int workspaceId, String entityType) {
        List<CustomFieldDefinition> defs = new ArrayList<>();
        for (CustomFieldDefinition d : customFieldDefinitionMapper.getByEntityType(workspaceId, entityType)) {
            if (!d.isArchived()) defs.add(d);
        }
        defs.sort(Comparator.comparingInt(CustomFieldDefinition::getPosition));
        return defs;
    }

    private static List<String> headers(List<String> base, List<CustomFieldDefinition> defs) {
        List<String> headers = new ArrayList<>(base);
        for (CustomFieldDefinition d : defs) headers.add(d.getLabel());
        headers.add("tags");
        return headers;
    }

    private static void appendCustom(List<String> row, List<CustomFieldDefinition> defs, Map<Integer, Object> values) {
        for (CustomFieldDefinition d : defs) {
            Object v = values == null ? null : values.get(d.getId());
            row.add(v == null ? "" : String.valueOf(v));
        }
    }

    private static Map<Integer, List<String>> groupTags(List<Map<String, Object>> rows) {
        Map<Integer, List<String>> byId = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get("entityId");
            Object name = row.get("name");
            if (!(id instanceof Number num) || name == null) continue;
            byId.computeIfAbsent(num.intValue(), k -> new ArrayList<>()).add(String.valueOf(name));
        }
        return byId;
    }

    private static String joinTags(List<String> tags) {
        return tags == null ? "" : String.join(", ", tags);
    }

    private static String dealStatus(Boolean won) {
        if (won == null) return "open";
        return won ? "won" : "lost";
    }

    private static String formatValue(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static void writeRow(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cells.get(i)));
        }
        sb.append("\r\n");
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        String s = CsvFormulaGuard.guard(value);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
