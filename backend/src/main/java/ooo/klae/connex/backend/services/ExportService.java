package ooo.klae.connex.backend.services;

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
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;

/**
 * Builds RFC-4180 CSV exports for contacts, companies, and deals, scoped to the active workspace and
 * honoring the same filters as the list endpoints. Every cell is quoted when needed and neutralized
 * against CSV formula injection (a leading {@code = + - @}, tab, or CR is prefixed with an
 * apostrophe). Standard fields come first, then one column per non-archived custom-field definition,
 * then a {@code tags} column.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private final WorkspaceService workspaceService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final PipelineMapper pipelineMapper;
    private final TagMapper tagMapper;
    private final CustomFieldDefinitionMapper customFieldDefinitionMapper;
    private final CustomFieldValueService customFieldValueService;

    /**
     * CSV of contacts matching the given list filters (all contacts when no filter is given).
     */
    public String exportPersons(String query, List<String> companies, List<String> titles, boolean noCompany) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Person> people = personMapper.getPersonsFiltered(workspaceId, query, companies, titles, noCompany);
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
     * CSV of companies (optionally filtered by tag).
     */
    public String exportCompanies(Integer tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Company> companies = tagId != null
            ? companyMapper.getCompaniesByTagId(workspaceId, tagId)
            : companyMapper.getAllCompanies(workspaceId);
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

    /**
     * CSV of deals matching the given list filter (all deals when no filter is given).
     */
    public String exportDeals(Integer pipelineId, Integer stageId, Integer companyId, Integer personId, Integer tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Deal> deals = dealsFor(workspaceId, pipelineId, stageId, companyId, personId, tagId);
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

    private List<Deal> dealsFor(int workspaceId, Integer pipelineId, Integer stageId, Integer companyId,
            Integer personId, Integer tagId) {
        if (pipelineId != null) return dealMapper.getDealsByPipelineId(workspaceId, pipelineId);
        if (stageId != null) return dealMapper.getDealsByStageId(workspaceId, stageId);
        if (companyId != null) return dealMapper.getDealsByCompanyId(workspaceId, companyId);
        if (personId != null) return dealMapper.getDealsByPersonId(workspaceId, personId);
        if (tagId != null) return dealMapper.getDealsByTagId(workspaceId, tagId);
        return dealMapper.getAllDeals(workspaceId);
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

    private static String formatValue(double value) {
        if (Double.isFinite(value) && value == Math.rint(value)) return Long.toString((long) value);
        return Double.toString(value);
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
        String s = value;
        char first = s.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            s = "'" + s;
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
