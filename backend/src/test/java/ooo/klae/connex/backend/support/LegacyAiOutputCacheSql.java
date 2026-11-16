package ooo.klae.connex.backend.support;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Frozen pre-#485 cache DELETE; DML callers must use a rollback-controlled migrated-default transaction. */
public final class LegacyAiOutputCacheSql {
    private final DataSource dataSource;
    private final String defaultCatalog;
    private final SqlSessionTemplate sessions;

    /** Loads only the immutable original statement into a test-only namespace. */
    public LegacyAiOutputCacheSql(DataSource dataSource, String defaultCatalog) {
        this.dataSource = dataSource;
        this.defaultCatalog = defaultCatalog;
        Configuration configuration = new Configuration(new Environment(
            "legacy-cache-delete", new SpringManagedTransactionFactory(), dataSource));
        new XMLMapperBuilder(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)),
            configuration, "legacy-cache-delete", configuration.getSqlFragments()).parse();
        sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    }

    /** Executes the frozen delete only on the caller's default-catalog transaction connection. */
    public int deleteForPerson(int workspaceId, int personId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Legacy cache DML requires a rollback-controlled transaction");
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (!defaultCatalog.equals(connection.getCatalog())) {
                throw new IllegalStateException("Legacy cache DML requires the default catalog");
            }
            try (var tables = connection.getMetaData().getTables(defaultCatalog, null, "flyway_schema_history", null)) {
                if (!tables.next()) {
                    throw new IllegalStateException("Legacy cache DML requires a migrated catalog");
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not verify the legacy cache catalog", exception);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
        return sessions.delete("legacy.AiOutputCacheMapper.deleteForPerson",
            Map.of("workspaceId", workspaceId, "personId", personId));
    }

    /** Discards local query state after savepoint rollback without changing the frozen SQL. */
    public void clearCache() {
        sessions.clearCache();
    }

    private static final String XML = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="legacy.AiOutputCacheMapper">
          <delete id="deleteForPerson">
            DELETE c FROM ai_output_cache c
            JOIN workspace owner_workspace ON owner_workspace.id = #{workspaceId}
            JOIN workspace cache_workspace
              ON cache_workspace.id = c.workspace_id AND cache_workspace.org_id = owner_workspace.org_id
            WHERE (
              c.feature LIKE 'report.narrative:%'
              OR (c.feature LIKE 'intro.rationale:%'
                AND (c.subject_a_id = #{personId} OR c.subject_b_id = #{personId}))
              OR (c.feature LIKE 'deal.risk_rationale:%'
                AND c.subject_a_id IN (
                  SELECT dp.deal_id FROM deal_person dp WHERE dp.person_id = #{personId}
                  UNION
                  SELECT dp.deal_id
                  FROM deal_person dp
                  JOIN deal d ON d.id = dp.deal_id AND d.workspace_id = c.workspace_id
                  JOIN person_edge e
                    ON e.workspace_id = c.workspace_id
                   AND ((e.source_person_id = dp.person_id AND e.target_person_id = #{personId})
                     OR (e.target_person_id = dp.person_id AND e.source_person_id = #{personId}))
                ))
              OR (c.feature LIKE 'deal.brief:%'
                AND c.subject_a_id IN (
                  SELECT dp.deal_id FROM deal_person dp WHERE dp.person_id = #{personId}
                  UNION
                  SELECT a.deal_id FROM activity a
                  WHERE a.person_id = #{personId} AND a.deal_id IS NOT NULL
                  UNION
                  SELECT n.deal_id FROM note n
                  WHERE n.person_id = #{personId} AND n.deal_id IS NOT NULL
                  UNION
                  SELECT t.deal_id FROM task t
                  WHERE t.person_id = #{personId} AND t.deal_id IS NOT NULL
                  UNION
                  SELECT dp.deal_id
                  FROM deal_person dp
                  JOIN deal d ON d.id = dp.deal_id AND d.workspace_id = c.workspace_id
                  JOIN person_edge e
                    ON e.workspace_id = c.workspace_id
                   AND ((e.source_person_id = dp.person_id AND e.target_person_id = #{personId})
                     OR (e.target_person_id = dp.person_id AND e.source_person_id = #{personId}))
                ))
            )
          </delete>
        </mapper>
        """;
}
