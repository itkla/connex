package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.ReportSnapshot;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportAggregateRow;
import ooo.klae.connex.backend.dto.ReportOffsetSegment;

/** Verifies the report mapper XML and every dynamic aggregate branch can be resolved. */
class ReportMapperXmlTest {

    @Test
    void mapperXmlParsesAndBuildsAggregateStatements() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ReportDefinition", ReportDefinition.class);
        configuration.getTypeAliasRegistry().registerAlias("ReportSnapshot", ReportSnapshot.class);
        configuration.getTypeAliasRegistry().registerAlias("ReportAggregateRow", ReportAggregateRow.class);
        String resource = "mappers/ReportMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        ReportAggregateQuery query = new ReportAggregateQuery(
                7, "won_revenue", "date", "month",
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 0, 0),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                null, null, null, null, null,
                java.util.List.of(new ReportOffsetSegment(
                        LocalDateTime.of(2026, 1, 1, 0, 0),
                        LocalDateTime.of(2026, 2, 1, 0, 0),
                        0)));
        for (String statement : new String[] {
                "aggregateDeals", "aggregateActivities", "aggregateTasks",
                "aggregatePeople", "aggregateCompanies"}) {
            assertNotNull(configuration.getMappedStatement(ReportMapper.class.getName() + "." + statement)
                    .getBoundSql(Map.of("query", query)).getSql());
        }
    }
}
