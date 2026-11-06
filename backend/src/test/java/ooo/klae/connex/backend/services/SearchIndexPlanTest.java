package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.support.PersonVisibilityParity;
import ooo.klae.connex.backend.util.LikePattern;

@Transactional(isolation = Isolation.READ_COMMITTED)
class SearchIndexPlanTest extends AbstractServiceTest {
    @Autowired private SqlSessionFactory sessions;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void wholePhoneCandidatesUseBothCompositeIndexColumns() {
        for (String mapper : List.of("PersonMapper", "CompanyMapper")) {
            for (String statement : List.of("search", mapper.equals("PersonMapper") ? "getPersonsPage" : "getCompaniesPage")) {
                String table = mapper.equals("PersonMapper") ? "person" : "company";
                List<PlanRow> plan = explain(mapper, statement, "09096846208");
                assertTrue(plan.stream().anyMatch(row -> row.table().equals("search_phone_owned")
                    && row.key().equals("idx_" + table + "_workspace_search_phone")
                    && (row.type().equals("ref") || row.type().equals("range"))
                    && Integer.parseInt(row.keyLength()) > Integer.BYTES), plan.toString());
            }
        }
    }

    @Test
    void nameListAndGlobalPlansDoNotScanOtherWorkspaces() {
        Workspace foreign = new Workspace();
        foreign.setName("Search plan foreign workspace");
        foreign.setSlug("search-plan-" + unique());
        workspaceMapper.insert(foreign);
        for (String table : List.of("person", "company")) {
            List<Object[]> rows = new ArrayList<>();
            for (int index = 0; index < 200; index++) {
                rows.add(new Object[] {foreign.getId(), "Foreign " + index, "foreign" + index, "819011112222"});
            }
            jdbc.batchUpdate("INSERT INTO " + table
                + " (workspace_id, name, search_name, search_phone) VALUES (?, ?, ?, ?)", rows);
            Integer sharedId = jdbc.queryForObject("SELECT MIN(id) FROM " + table + " WHERE workspace_id = ?",
                Integer.class, foreign.getId());
            jdbc.update("INSERT INTO " + table + "_share (" + table + "_id, workspace_id) VALUES (?, ?)",
                Objects.requireNonNull(sharedId), workspace.getId());
        }
        newPerson(newCompany());
        for (String mapper : List.of("PersonMapper", "CompanyMapper")) {
            for (String statement : List.of("search", mapper.equals("PersonMapper") ? "getPersonsPage" : "getCompaniesPage")) {
                List<PlanRow> plan = explain(mapper, statement, "山田太郎");
                String alias = mapper.equals("PersonMapper") ? "p" : "c";
                String table = mapper.equals("PersonMapper") ? "person" : "company";
                List<PlanRow> subject = plan.stream().filter(row -> row.id() == 1 && row.table().equals(alias)).toList();
                assertFalse(subject.isEmpty(), plan.toString());
                assertTrue(subject.stream().allMatch(row -> row.type().equals("eq_ref") && row.key().equals("PRIMARY")),
                    mapper + "." + statement + ": " + plan);
                assertTrue(plan.stream().anyMatch(row -> row.table().equals("search_owned")
                    && row.type().equals("ref") && row.key().equals("idx_" + table + "_workspace")), plan.toString());
                assertTrue(plan.stream().anyMatch(row -> row.table().equals("search_shared")
                    && row.type().equals("ref") && row.key().equals("idx_" + table + "_share_workspace")), plan.toString());
            }
        }
    }

    private List<PlanRow> explain(String mapper, String statement, String raw) {
        Map<String, Object> parameters = PersonVisibilityParity.parameters();
        parameters.put("workspaceId", workspace.getId());
        parameters.put("orgWorkspaceIdsJson", "[" + workspace.getId() + "]");
        parameters.put("query", LikePattern.containing(raw));
        parameters.put("industry", List.of());
        parameters.put("ids", List.of());
        parameters.put("noIndustry", false);
        parameters.put("warmth", null);
        MappedStatement mapped = sessions.getConfiguration().getMappedStatement(
            "ooo.klae.connex.backend.mappers." + mapper + "." + statement);
        BoundSql sql = mapped.getBoundSql(parameters);
        ParameterHandler handler = new DefaultParameterHandler(mapped, parameters, sql);
        return Objects.requireNonNull(jdbc.execute((ConnectionCallback<List<PlanRow>>) connection -> {
            try (PreparedStatement query = connection.prepareStatement("EXPLAIN " + sql.getSql())) {
                handler.setParameters(query);
                try (ResultSet rows = query.executeQuery()) {
                    List<PlanRow> plan = new ArrayList<>();
                    while (rows.next()) {
                        plan.add(new PlanRow(rows.getInt("id"), Objects.toString(rows.getString("table"), ""),
                            Objects.toString(rows.getString("type"), ""), Objects.toString(rows.getString("key"), ""),
                            Objects.toString(rows.getString("key_len"), "0")));
                    }
                    return plan;
                }
            }
        }));
    }

    private record PlanRow(int id, String table, String type, String key, String keyLength) {
    }
}
