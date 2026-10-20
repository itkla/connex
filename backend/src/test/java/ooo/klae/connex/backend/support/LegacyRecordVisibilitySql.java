package ooo.klae.connex.backend.support;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * Frozen pre-#485 statement text used only as a semantic-parity oracle. These statements retain
 * their original control-table joins and may execute only on the migrated single catalog,
 * never on a scratch tenant catalog. No production mapper loads these copies.
 */
public final class LegacyRecordVisibilitySql {
    private final SqlSessionFactory sessions;
    private final String defaultCatalog;

    /** Creates an oracle over the physical default-catalog data source. */
    public LegacyRecordVisibilitySql(DataSource dataSource, String defaultCatalog) {
        this.defaultCatalog = defaultCatalog;
        Configuration configuration = new Configuration(
            new Environment("pre-485-record-visibility", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String xml : List.of(INTRODUCTION, DUPLICATE_REVIEW)) {
            String resource = "pre-485-" + configuration.getMappedStatementNames().size();
            new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                configuration, resource, configuration.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** Executes an original projection after refusing an unmigrated or non-default catalog. */
    public <T> List<T> selectList(String statement, Map<String, Object> parameters) {
        try (SqlSession session = sessions.openSession()) {
            requireMigratedDefaultCatalog(session);
            return session.selectList("ooo.klae.connex.backend.mappers." + statement, parameters);
        }
    }

    /** Executes an original count after refusing an unmigrated or non-default catalog. */
    public long count(String statement, Map<String, Object> parameters) {
        try (SqlSession session = sessions.openSession()) {
            requireMigratedDefaultCatalog(session);
            return session.selectOne("ooo.klae.connex.backend.mappers." + statement, parameters);
        }
    }

    private void requireMigratedDefaultCatalog(SqlSession session) {
        try {
            if (!defaultCatalog.equals(session.getConnection().getCatalog())) {
                throw new IllegalStateException("Legacy visibility SQL requires the default catalog");
            }
            try (var tables = session.getConnection().getMetaData()
                    .getTables(defaultCatalog, null, "flyway_schema_history", null)) {
                if (!tables.next()) {
                    throw new IllegalStateException("Legacy visibility SQL requires a migrated catalog");
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not verify the legacy SQL catalog", exception);
        }
    }

    private static final String INTRODUCTION = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="ooo.klae.connex.backend.mappers.IntroductionMapper">
          <sql id="visibleCandidateCompany">
            (c.workspace_id = #{workspaceId}
             OR EXISTS (SELECT 1 FROM company_share cs
                JOIN workspace vws ON vws.id = cs.workspace_id
                JOIN workspace ows ON ows.id = c.workspace_id AND ows.org_id = vws.org_id
                WHERE cs.company_id = c.id AND cs.workspace_id = #{workspaceId}))
            AND c.archived_at IS NULL
          </sql>

          <sql id="eligibleGraphEvidence">
            EXISTS (
              SELECT 1
              FROM person_edge e
              JOIN person peer
                ON peer.id = IF(e.source_person_id = p.id, e.target_person_id, e.source_person_id)
                AND peer.workspace_id = #{workspaceId}
                AND peer.intro_excluded = FALSE
                AND peer.suspended_at IS NULL
                AND peer.archived_at IS NULL
              WHERE e.workspace_id = #{workspaceId}
                AND (e.source_person_id = p.id OR e.target_person_id = p.id)
            )
          </sql>

          <select id="findCandidatePersons" resultType="ooo.klae.connex.backend.beans.IntroCandidatePerson">
            SELECT p.id         AS id,
                   p.name       AS name,
                   p.title      AS title,
                   c.id         AS company_id,
                   c.name       AS company_name,
                   p.image_url  AS image_url
            FROM person p
            LEFT JOIN company c ON c.id = p.company_id AND <include refid="visibleCandidateCompany"/>
            WHERE p.workspace_id = #{workspaceId}
              AND p.intro_excluded = FALSE
              AND p.suspended_at IS NULL
              AND p.archived_at IS NULL
              AND (
                EXISTS (SELECT 1 FROM activity a WHERE a.workspace_id = #{workspaceId} AND a.person_id = p.id)
                OR EXISTS (SELECT 1 FROM note n
                  WHERE n.workspace_id = #{workspaceId} AND n.visibility = 'workspace' AND n.person_id = p.id)
                OR EXISTS (SELECT 1 FROM task t WHERE t.workspace_id = #{workspaceId} AND t.person_id = p.id)
                OR <include refid="eligibleGraphEvidence"/>
              )
          </select>

          <select id="findCandidatePersonsForReport" resultType="ooo.klae.connex.backend.beans.IntroCandidatePerson">
            SELECT p.id         AS id,
                   p.name       AS name,
                   p.title      AS title,
                   c.id         AS company_id,
                   c.name       AS company_name,
                   p.image_url  AS image_url
            FROM person p
            LEFT JOIN company c ON c.id = p.company_id AND <include refid="visibleCandidateCompany"/>
            WHERE p.workspace_id = #{workspaceId}
              AND p.intro_excluded = FALSE
              AND p.suspended_at IS NULL
              AND p.archived_at IS NULL
              AND (
                EXISTS (SELECT 1 FROM activity a WHERE a.workspace_id = #{workspaceId} AND a.person_id = p.id)
                OR EXISTS (SELECT 1 FROM note n
                  WHERE n.workspace_id = #{workspaceId} AND n.visibility = 'workspace' AND n.person_id = p.id)
                OR EXISTS (SELECT 1 FROM task t WHERE t.workspace_id = #{workspaceId} AND t.person_id = p.id)
                OR <include refid="eligibleGraphEvidence"/>
              )
            ORDER BY p.id
            LIMIT #{limit}
          </select>

          <select id="findWarmPathCandidates" resultType="ooo.klae.connex.backend.beans.IntroCandidatePerson">
            SELECT p.id         AS id,
                   p.name       AS name,
                   p.title      AS title,
                   c.id         AS company_id,
                   c.name       AS company_name,
                   p.image_url  AS image_url
            FROM person p
            LEFT JOIN company c ON c.id = p.company_id AND <include refid="visibleCandidateCompany"/>
            WHERE p.workspace_id = #{workspaceId}
              AND p.intro_excluded = FALSE
              AND p.suspended_at IS NULL
              AND p.archived_at IS NULL
          </select>
        </mapper>
        """;

    private static final String DUPLICATE_REVIEW = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="ooo.klae.connex.backend.mappers.DuplicateReviewMapper">
          <resultMap id="reviewItemRow" type="ooo.klae.connex.backend.dto.DuplicateReviewItemRow">
            <id property="id" column="id"/>
            <result property="itemType" column="item_type"/>
            <result property="recordType" column="record_type"/>
            <result property="kind" column="kind"/>
            <result property="lowRecordId" column="low_record_id"/>
            <result property="lowName" column="low_name"/>
            <result property="lowCompanyName" column="low_company_name"/>
            <result property="lowOwnerId" column="low_owner_id"/>
            <result property="lowOwnedByActiveWorkspace" column="low_owned_by_active_workspace"/>
            <result property="highRecordId" column="high_record_id"/>
            <result property="highName" column="high_name"/>
            <result property="highCompanyName" column="high_company_name"/>
            <result property="highOwnerId" column="high_owner_id"/>
            <result property="highOwnedByActiveWorkspace" column="high_owned_by_active_workspace"/>
            <result property="collisionSize" column="collision_size"/>
            <result property="detectedAt" column="detected_at"/>
            <result property="state" column="state"/>
            <result property="evidenceFingerprint" column="evidence_fingerprint"/>
            <result property="dismissedAt" column="dismissed_at"/>
            <result property="dismissedByUserId" column="dismissed_by_user_id"/>
          </resultMap>

          <sql id="visibleReviewJoins">
            FROM duplicate_review_decision d
            LEFT JOIN person_identity evidence_person
              ON evidence_person.workspace_id = d.workspace_id
              AND evidence_person.id = d.evidence_person_identity_id
            LEFT JOIN company_identity evidence_company
              ON evidence_company.workspace_id = d.workspace_id
              AND evidence_company.id = d.evidence_company_identity_id
            LEFT JOIN person evidence_person_record
              ON evidence_person_record.workspace_id = evidence_person.workspace_id
              AND evidence_person_record.id = evidence_person.person_id
              AND evidence_person_record.archived_at IS NULL
              AND evidence_person_record.suspended_at IS NULL
              AND evidence_person_record.provision_ceased_at IS NULL
            LEFT JOIN company evidence_company_record
              ON evidence_company_record.workspace_id = evidence_company.workspace_id
              AND evidence_company_record.id = evidence_company.company_id
              AND evidence_company_record.archived_at IS NULL
            LEFT JOIN person low_person
              ON low_person.workspace_id = d.workspace_id
              AND low_person.id = d.low_person_id
              AND low_person.archived_at IS NULL
              AND low_person.suspended_at IS NULL
              AND low_person.provision_ceased_at IS NULL
            LEFT JOIN person high_person
              ON high_person.workspace_id = d.workspace_id
              AND high_person.id = d.high_person_id
              AND high_person.archived_at IS NULL
              AND high_person.suspended_at IS NULL
              AND high_person.provision_ceased_at IS NULL
            LEFT JOIN company low_person_company
              ON low_person_company.id = low_person.company_id
              AND (
                low_person_company.workspace_id = #{workspaceId}
                OR EXISTS (
                  SELECT 1
                  FROM company_share low_company_share
                  JOIN workspace vws
                    ON vws.id = low_company_share.workspace_id
                  JOIN workspace ows
                    ON ows.id = low_person_company.workspace_id
                    AND ows.org_id = vws.org_id
                  WHERE low_company_share.company_id = low_person_company.id
                    AND low_company_share.workspace_id = #{workspaceId}
                )
              )
              AND low_person_company.archived_at IS NULL
            LEFT JOIN company high_person_company
              ON high_person_company.id = high_person.company_id
              AND (
                high_person_company.workspace_id = #{workspaceId}
                OR EXISTS (
                  SELECT 1
                  FROM company_share high_company_share
                  JOIN workspace vws
                    ON vws.id = high_company_share.workspace_id
                  JOIN workspace ows
                    ON ows.id = high_person_company.workspace_id
                    AND ows.org_id = vws.org_id
                  WHERE high_company_share.company_id = high_person_company.id
                    AND high_company_share.workspace_id = #{workspaceId}
                )
              )
              AND high_person_company.archived_at IS NULL
            LEFT JOIN company low_company
              ON low_company.workspace_id = d.workspace_id
              AND low_company.id = d.low_company_id
              AND low_company.archived_at IS NULL
            LEFT JOIN company high_company
              ON high_company.workspace_id = d.workspace_id
              AND high_company.id = d.high_company_id
              AND high_company.archived_at IS NULL
          </sql>

          <sql id="visibleCurrentReviewPredicate">
            d.workspace_id = #{workspaceId}
            AND d.is_current = TRUE
            AND (
              (d.record_type = 'person'
               AND evidence_person.id IS NOT NULL
               AND evidence_person.superseded_at IS NULL
               AND evidence_person_record.id IS NOT NULL)
              OR
              (d.record_type = 'company'
               AND evidence_company.id IS NOT NULL
               AND evidence_company.superseded_at IS NULL
               AND evidence_company_record.id IS NOT NULL)
            )
            AND (
              (d.oversized_marker = 1
               AND d.collision_size &gt; #{maximumPairGroupSize})
              OR
              (d.record_type = 'person'
               AND d.oversized_marker IS NULL
               AND low_person.id IS NOT NULL
               AND high_person.id IS NOT NULL)
              OR
              (d.record_type = 'company'
               AND d.oversized_marker IS NULL
               AND low_company.id IS NOT NULL
               AND high_company.id IS NOT NULL)
            )
          </sql>

          <sql id="visibleReviewPredicate">
            <include refid="visibleCurrentReviewPredicate"/>
            <if test="recordType != null">
              AND d.record_type = #{recordType}
            </if>
            <if test="kind != null">
              AND d.kind = #{kind}
            </if>
            <if test="state != null">
              AND d.state = #{state}
            </if>
          </sql>

          <sql id="reviewItemColumns">
            d.id,
            CASE WHEN d.oversized_marker = 1 THEN 'oversized_group' ELSE 'pair' END AS item_type,
            d.record_type,
            d.kind,
            CASE WHEN d.record_type = 'person' THEN d.low_person_id ELSE d.low_company_id END
              AS low_record_id,
            CASE WHEN d.record_type = 'person' THEN low_person.name ELSE low_company.name END
              AS low_name,
            CASE WHEN d.record_type = 'person' THEN low_person_company.name ELSE NULL END
              AS low_company_name,
            CASE WHEN d.record_type = 'person' THEN low_person.owner_id ELSE low_company.owner_id END
              AS low_owner_id,
            CASE WHEN d.record_type = 'person'
                 THEN low_person.workspace_id = #{workspaceId}
                 ELSE low_company.workspace_id = #{workspaceId}
            END AS low_owned_by_active_workspace,
            CASE WHEN d.record_type = 'person' THEN d.high_person_id ELSE d.high_company_id END
              AS high_record_id,
            CASE WHEN d.record_type = 'person' THEN high_person.name ELSE high_company.name END
              AS high_name,
            CASE WHEN d.record_type = 'person' THEN high_person_company.name ELSE NULL END
              AS high_company_name,
            CASE WHEN d.record_type = 'person' THEN high_person.owner_id ELSE high_company.owner_id END
              AS high_owner_id,
            CASE WHEN d.record_type = 'person'
                 THEN high_person.workspace_id = #{workspaceId}
                 ELSE high_company.workspace_id = #{workspaceId}
            END AS high_owned_by_active_workspace,
            d.collision_size,
            d.detected_at,
            d.state,
            d.evidence_fingerprint,
            d.dismissed_at,
            d.dismissed_by_user_id
          </sql>

          <select id="countVisibleItems" resultType="long" timeout="3">
            SELECT /*+ MAX_EXECUTION_TIME(3000) */ COUNT(*)
            <include refid="visibleReviewJoins"/>
            WHERE <include refid="visibleReviewPredicate"/>
          </select>

          <select id="findVisibleItems" resultMap="reviewItemRow" timeout="3">
            SELECT /*+ MAX_EXECUTION_TIME(3000) */ <include refid="reviewItemColumns"/>
            <include refid="visibleReviewJoins"/>
            WHERE <include refid="visibleReviewPredicate"/>
            ORDER BY d.detected_at DESC, d.id DESC
            LIMIT #{limit} OFFSET #{offset}
          </select>

          <select id="findVisibleItemById" resultMap="reviewItemRow">
            SELECT <include refid="reviewItemColumns"/>
            <include refid="visibleReviewJoins"/>
            WHERE <include refid="visibleCurrentReviewPredicate"/>
              AND d.id = #{id}
          </select>
        </mapper>
        """;
}
