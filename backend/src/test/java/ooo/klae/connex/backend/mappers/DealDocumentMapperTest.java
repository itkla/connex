package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.dto.GeneratedDocumentSummaryDto;
import ooo.klae.connex.backend.dto.MemberScope;

class DealDocumentMapperTest extends AbstractMapperTest {
    @Autowired private DealDocumentMapper mapper;
    @Autowired private SqlSessionFactory sqlSessionFactory;
    @Autowired private DataSource dataSource;

    @Test
    void workspacePageAndSearchCanUseRecencyIndexAndReturnNewestFirst() {
        seedDocumentHistory();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(List.of("workspace_id", "generated_at", "id"), jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deal_document'
                  AND INDEX_NAME = 'idx_deal_document_recency'
                ORDER BY SEQ_IN_INDEX
                """, String.class));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", workspace.getId());
        parameters.put("memberScope", MemberScope.allTeam());
        parameters.put("limit", 25);
        parameters.put("offset", 0);
        assertRecencyIndexIsAvailable("getWorkspacePage", parameters);
        var page = mapper.getWorkspacePage(workspace.getId(), null, null, null, null,
                MemberScope.allTeam(), 25, 0);
        assertEquals(25, page.size());
        assertNewestFirst(page);

        parameters.put("query", "%recency%");
        assertRecencyIndexIsAvailable("search", parameters);
        var results = mapper.search(workspace.getId(), "%recency%");
        assertEquals(10, results.size());
        assertNewestFirst(results);
    }

    private void seedDocumentHistory() {
        var company = newCompany();
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        List<Object[]> documents = new ArrayList<>();
        LocalDateTime generatedAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (int dealIndex = 0; dealIndex < 32; dealIndex++) {
            var deal = newDeal(pipeline, stage, company);
            for (int version = 1; version <= 32; version++) {
                documents.add(new Object[] {workspace.getId(), deal.getId(), version,
                        Timestamp.valueOf(generatedAt.plusMinutes(dealIndex * 32L + version % 16))});
            }
        }
        new JdbcTemplate(dataSource).batchUpdate("""
                INSERT INTO deal_document
                    (workspace_id, deal_id, version, type, title, content, currency, generated_at)
                VALUES (?, ?, ?, 'quote', 'Recency fixture', '{}', 'JPY', ?)
                """, documents);
    }

    private void assertRecencyIndexIsAvailable(String statement, Map<String, Object> parameters) {
        var mapped = sqlSessionFactory.getConfiguration()
                .getMappedStatement(DealDocumentMapper.class.getName() + "." + statement);
        var bound = mapped.getBoundSql(parameters);
        List<Map<String, Object>> plan = new JdbcTemplate(dataSource).query(connection -> {
            var explain = connection.prepareStatement("EXPLAIN " + bound.getSql());
            new DefaultParameterHandler(mapped, parameters, bound).setParameters(explain);
            return explain;
        }, new ColumnMapRowMapper());
        Map<String, Object> documentAccess = plan.stream()
                .filter(row -> "dd".equals(row.get("table")))
                .findFirst().orElseThrow();
        assertTrue(List.of(String.valueOf(documentAccess.get("possible_keys")).split(","))
                .contains("idx_deal_document_recency"),
                () -> statement + ": " + plan);
    }

    private void assertNewestFirst(List<GeneratedDocumentSummaryDto> documents) {
        var newestFirst = Comparator.comparing(GeneratedDocumentSummaryDto::generatedAt)
                .thenComparingInt(GeneratedDocumentSummaryDto::id).reversed();
        assertEquals(documents.stream().sorted(newestFirst).toList(), documents);
    }
}
