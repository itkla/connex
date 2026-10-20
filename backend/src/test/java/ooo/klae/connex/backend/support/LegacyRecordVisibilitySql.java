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
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String xml : List.of(INTRODUCTION, DUPLICATE_REVIEW, NOTE, SEGMENT, PIPELINE)) {
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
            Number count = session.selectOne("ooo.klae.connex.backend.mappers." + statement, parameters);
            return count.longValue();
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
    private static final String NOTE = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="ooo.klae.connex.backend.mappers.NoteMapper">

          <resultMap id="NoteResult" type="Note">
            <id     property="id"        column="id"/>
            <result property="workspaceId" column="workspace_id"/>
            <result property="content"   column="content"/>
            <result property="title"     column="title"/>
            <result property="visibility" column="visibility"/>
            <result property="createdAt" column="created_at"/>
            <result property="updatedAt" column="updated_at"/>
            <association property="author" javaType="User">
              <id property="id" column="author_id"/>
            </association>
            <association property="person" javaType="Person">
              <id property="id" column="person_id"/>
            </association>
            <association property="deal" javaType="Deal">
              <id property="id" column="deal_id"/>
            </association>
          </resultMap>

          <resultMap id="SearchNoteResult" type="Note">
            <id property="id" column="id"/>
            <result property="workspaceId" column="workspace_id"/>
            <result property="content" column="content"/>
            <result property="title" column="title"/>
            <result property="visibility" column="visibility"/>
            <result property="createdAt" column="created_at"/>
            <result property="updatedAt" column="updated_at"/>
            <association property="author" javaType="User">
              <id property="id" column="author_id"/>
            </association>
            <association property="person" javaType="Person">
              <id property="id" column="person_id"/>
              <result property="name" column="person_name"/>
            </association>
            <association property="deal" javaType="Deal">
              <id property="id" column="deal_id"/>
              <result property="name" column="deal_name"/>
            </association>
          </resultMap>

          <sql id="noteColumns">
            id, workspace_id, content, title, visibility, author_id, person_id, deal_id, created_at, updated_at
          </sql>

          <sql id="qualifiedNoteColumns">
            n.id, n.workspace_id, n.content, n.title, n.visibility, n.author_id, n.person_id, n.deal_id, n.created_at, n.updated_at
          </sql>

          <sql id="searchNoteColumns">
            <include refid="qualifiedNoteColumns"/>,
            p.name AS person_name,
            d.name AS deal_name
          </sql>

          <sql id="visiblePerson">
            (p.workspace_id = #{workspaceId}
             OR EXISTS (SELECT 1 FROM person_share ps
                JOIN workspace pvws ON pvws.id = ps.workspace_id
                JOIN workspace pows ON pows.id = p.workspace_id AND pows.org_id = pvws.org_id
                WHERE ps.person_id = p.id AND ps.workspace_id = #{workspaceId}))
          </sql>

          <sql id="visibleCompany">
            (c.workspace_id = #{workspaceId}
             OR EXISTS (SELECT 1 FROM company_share cs
                JOIN workspace cvws ON cvws.id = cs.workspace_id
                JOIN workspace cows ON cows.id = c.workspace_id AND cows.org_id = cvws.org_id
                WHERE cs.company_id = c.id AND cs.workspace_id = #{workspaceId}))
          </sql>

          <sql id="internalInlineReferencePattern">
            '\\\\[[^]\\\\r\\\\n]*\\\\][[:space:]]*\\\\([[:space:]]*&lt;?(user|person|deal|company|note|file|task|activity)(:|&amp;colon;|&amp;#0*58;|&amp;#x0*3a;|\\\\\\\\:)[0-9]+&gt;?([[:space:]]+[^)]*)?\\\\)'
          </sql>

          <sql id="internalReferenceTargetPattern">
            '(user|person|deal|company|note|file|task|activity)(:|&amp;colon;|&amp;#0*58;|&amp;#x0*3a;|\\\\\\\\:)[0-9]+'
          </sql>

          <sql id="notePageQuery">
            <if test="query != null">
              AND (
                n.title LIKE #{query}
                <if test="authorIds != null and !authorIds.isEmpty()">
                  OR n.author_id IN
                  <foreach item="authorId" collection="authorIds" open="(" separator="," close=")">
                    #{authorId}
                  </foreach>
                </if>
                OR (
                  NOT REGEXP_LIKE(
                    REGEXP_REPLACE(
                      n.content,
                      <include refid="internalInlineReferencePattern"/>,
                      '', 1, 0, 'i'
                    ),
                    <include refid="internalReferenceTargetPattern"/>,
                    'i'
                  )
                  AND REGEXP_REPLACE(
                    n.content,
                    <include refid="internalInlineReferencePattern"/>,
                    '', 1, 0, 'i'
                  ) LIKE #{query}
                )
                OR EXISTS (
                  SELECT 1
                  FROM person p
                  WHERE p.id = n.person_id
                    AND p.archived_at IS NULL
                    AND <include refid="visiblePerson"/>
                    AND p.name LIKE #{query}
                )
                OR EXISTS (
                  SELECT 1
                  FROM deal d
                  WHERE d.workspace_id = #{workspaceId}
                    AND d.id = n.deal_id
                    AND d.name LIKE #{query}
                )
              )
            </if>
          </sql>

          <sql id="notePageSortDirection">
            <choose>
              <when test="dir == 'asc'">ASC</when>
              <otherwise>DESC</otherwise>
            </choose>
          </sql>

          <sql id="notePageOrder">
            <choose>
              <when test="sort == 'title'">
                ORDER BY (n.title IS NULL OR n.title = '') ASC,
                  n.title <include refid="notePageSortDirection"/>,
                  n.id <include refid="notePageSortDirection"/>
              </when>
              <when test="sort == 'created'">
                ORDER BY n.created_at <include refid="notePageSortDirection"/>,
                  n.id <include refid="notePageSortDirection"/>
              </when>
              <otherwise>
                ORDER BY COALESCE(n.updated_at, n.created_at) <include refid="notePageSortDirection"/>,
                  n.id <include refid="notePageSortDirection"/>
              </otherwise>
            </choose>
          </sql>

          <select id="getVisibleNotesPage" resultMap="NoteResult">
            SELECT <include refid="qualifiedNoteColumns"/> FROM note n
            WHERE n.workspace_id = #{workspaceId}
              AND (n.visibility = 'workspace' OR n.author_id = #{currentUserId})
            <include refid="notePageQuery"/>
            <include refid="notePageOrder"/>
            LIMIT #{limit} OFFSET #{offset}
          </select>

          <select id="countVisibleNotes" resultType="long">
            SELECT COUNT(*) FROM note n
            WHERE n.workspace_id = #{workspaceId}
              AND (n.visibility = 'workspace' OR n.author_id = #{currentUserId})
            <include refid="notePageQuery"/>
          </select>

          <select id="getWorkspaceNotesPage" resultMap="NoteResult">
            SELECT <include refid="qualifiedNoteColumns"/> FROM note n
            WHERE n.workspace_id = #{workspaceId} AND n.visibility = 'workspace'
            <include refid="notePageQuery"/>
            <include refid="notePageOrder"/>
            LIMIT #{limit} OFFSET #{offset}
          </select>

          <select id="countWorkspaceNotes" resultType="long">
            SELECT COUNT(*) FROM note n
            WHERE n.workspace_id = #{workspaceId} AND n.visibility = 'workspace'
            <include refid="notePageQuery"/>
          </select>

          <select id="countOwnPrivateNotesForPersonEvidence" timeout="10" resultType="int">
            SELECT COUNT(*)
            FROM (
              SELECT n.id
              FROM note n
              JOIN person p ON p.id = n.person_id
              WHERE n.workspace_id = #{workspaceId}
                AND n.person_id = #{personId}
                AND n.author_id = #{currentUserId}
                AND n.visibility = 'private'
                AND n.created_at IS NOT NULL
                AND n.created_at &lt;= #{reference}
                AND p.suspended_at IS NULL
                AND <include refid="visiblePerson"/>
              LIMIT #{sourceLimit}
            ) capped
          </select>

          <select id="countOwnPrivateNotesForCompanyEvidence" timeout="10" resultType="int">
            SELECT COUNT(*)
            FROM (
              SELECT n.id
              FROM note n
              WHERE n.workspace_id = #{workspaceId}
                AND n.author_id = #{currentUserId}
                AND n.visibility = 'private'
                AND n.created_at IS NOT NULL
                AND n.created_at &lt;= #{reference}
                AND (
                  EXISTS (
                    SELECT 1
                    FROM person p
                    WHERE p.id = n.person_id
                      AND p.company_id = #{companyId}
                      AND p.suspended_at IS NULL
                      AND <include refid="visiblePerson"/>
                  )
                  OR EXISTS (
                    SELECT 1
                    FROM deal d
                    WHERE d.id = n.deal_id
                      AND d.workspace_id = #{workspaceId}
                      AND d.company_id = #{companyId}
                      AND (n.person_id IS NULL OR EXISTS (
                        SELECT 1
                        FROM person p
                        WHERE p.id = n.person_id
                          AND p.suspended_at IS NULL
                          AND <include refid="visiblePerson"/>
                      ))
                  )
                )
                AND EXISTS (
                  SELECT 1
                  FROM company c
                  WHERE c.id = #{companyId}
                    AND <include refid="visibleCompany"/>
                )
              LIMIT #{sourceLimit}
            ) capped
          </select>

          <select id="searchVisible" resultMap="SearchNoteResult">
            SELECT <include refid="searchNoteColumns"/>
            FROM note n
            LEFT JOIN person p ON p.id = n.person_id AND p.archived_at IS NULL
              AND <include refid="visiblePerson"/>
            LEFT JOIN deal d ON d.workspace_id = #{workspaceId} AND d.id = n.deal_id
            WHERE n.workspace_id = #{workspaceId}
              AND (n.visibility = 'workspace' OR n.author_id = #{currentUserId})
              AND (n.content LIKE #{query}
              OR n.title LIKE #{query}
              OR p.name LIKE #{query}
              OR d.name LIKE #{query})
            ORDER BY n.created_at DESC, n.id DESC
            LIMIT #{limit} OFFSET #{offset}
          </select>
        </mapper>
        """;

    private static final String SEGMENT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="ooo.klae.connex.backend.mappers.SegmentMapper">

            <select id="companyIdsNoActivitySince" resultType="int">
                SELECT c.id
                FROM company c
                WHERE c.workspace_id = #{workspaceId}
                  AND c.archived_at IS NULL
                  <if test="candidateIds != null">
                    AND c.id IN
                    <foreach item="candidateId" collection="candidateIds" open="(" separator="," close=")">
                      #{candidateId}
                    </foreach>
                  </if>
                  AND NOT EXISTS (
                      SELECT 1 FROM activity a
                      WHERE a.workspace_id = #{workspaceId}
                        AND a.timestamp &gt;= DATE_SUB(NOW(), INTERVAL #{days} DAY)
                        AND (a.person_id IS NULL OR EXISTS (
                            SELECT 1 FROM person processable
                            WHERE processable.id = a.person_id
                              AND processable.suspended_at IS NULL
                              AND processable.provision_ceased_at IS NULL
                              AND processable.archived_at IS NULL
                              AND (processable.workspace_id = #{workspaceId}
                                OR EXISTS (
                                  SELECT 1 FROM person_share processable_share
                                  JOIN workspace viewer_workspace
                                    ON viewer_workspace.id = processable_share.workspace_id
                                  JOIN workspace owner_workspace
                                    ON owner_workspace.id = processable.workspace_id
                                   AND owner_workspace.org_id = viewer_workspace.org_id
                                  WHERE processable_share.person_id = processable.id
                                    AND processable_share.workspace_id = #{workspaceId}
                                ))
                        ))
                        AND (
                            a.person_id IN (SELECT p.id FROM person p
                                WHERE p.workspace_id = #{workspaceId} AND p.company_id = c.id
                                  AND p.archived_at IS NULL)
                            OR a.deal_id IN (SELECT d.id FROM deal d WHERE d.workspace_id = #{workspaceId} AND d.company_id = c.id)
                        )
                  )
            </select>

            <select id="companyIdsForPersonsWithoutUserActivity" resultType="int">
                SELECT DISTINCT p.company_id
                FROM person p
                WHERE p.workspace_id = #{workspaceId}
                  AND p.suspended_at IS NULL
                  AND p.provision_ceased_at IS NULL
                  AND p.archived_at IS NULL
                  AND p.company_id IS NOT NULL
                  <if test="candidateIds != null">
                    AND p.company_id IN
                    <foreach item="candidateId" collection="candidateIds" open="(" separator="," close=")">
                      #{candidateId}
                    </foreach>
                  </if>
                  AND p.id IN
                  <foreach item="pid" collection="personIds" open="(" separator="," close=")">#{pid}</foreach>
                  AND NOT EXISTS (
                      SELECT 1 FROM activity a
                      WHERE a.workspace_id = #{workspaceId}
                        AND a.created_by_id = #{userId}
                        AND (a.person_id IS NULL OR EXISTS (
                            SELECT 1 FROM person processable
                            WHERE processable.id = a.person_id
                              AND processable.suspended_at IS NULL
                              AND processable.provision_ceased_at IS NULL
                              AND processable.archived_at IS NULL
                              AND (processable.workspace_id = #{workspaceId}
                                OR EXISTS (
                                  SELECT 1 FROM person_share processable_share
                                  JOIN workspace viewer_workspace
                                    ON viewer_workspace.id = processable_share.workspace_id
                                  JOIN workspace owner_workspace
                                    ON owner_workspace.id = processable.workspace_id
                                   AND owner_workspace.org_id = viewer_workspace.org_id
                                  WHERE processable_share.person_id = processable.id
                                    AND processable_share.workspace_id = #{workspaceId}
                                ))
                        ))
                        AND (
                            a.person_id IN (SELECT p2.id FROM person p2
                                WHERE p2.workspace_id = #{workspaceId}
                                  AND p2.company_id = p.company_id
                                  AND p2.suspended_at IS NULL
                                  AND p2.provision_ceased_at IS NULL
                                  AND p2.archived_at IS NULL)
                            OR a.deal_id IN (SELECT d.id FROM deal d WHERE d.workspace_id = #{workspaceId} AND d.company_id = p.company_id)
                        )
                  )
            </select>

            <select id="companyIdsWithWarmIntro" resultType="int">
                SELECT DISTINCT candidate.company_id
                FROM person_edge edge
                JOIN person candidate
                  ON (candidate.id = edge.source_person_id OR candidate.id = edge.target_person_id)
                 AND candidate.workspace_id = #{workspaceId}
                 AND candidate.suspended_at IS NULL
                 AND candidate.provision_ceased_at IS NULL
                 AND candidate.archived_at IS NULL
                 AND candidate.company_id IN
                  <foreach item="candidateId" collection="candidateIds" open="(" separator="," close=")">
                    #{candidateId}
                  </foreach>
                JOIN person engaged
                  ON engaged.id = IF(candidate.id = edge.source_person_id,
                                     edge.target_person_id, edge.source_person_id)
                 AND engaged.archived_at IS NULL
                 AND engaged.suspended_at IS NULL
                 AND (engaged.workspace_id = #{workspaceId}
                   OR EXISTS (
                     SELECT 1 FROM person_share engaged_share
                     JOIN workspace viewer_workspace
                       ON viewer_workspace.id = engaged_share.workspace_id
                     JOIN workspace owner_workspace
                       ON owner_workspace.id = engaged.workspace_id
                      AND owner_workspace.org_id = viewer_workspace.org_id
                     WHERE engaged_share.person_id = engaged.id
                       AND engaged_share.workspace_id = #{workspaceId}
                   ))
                WHERE edge.workspace_id = #{workspaceId}
                  AND edge.strength &gt;= #{strongEdge}
                  AND edge.source_person_id &lt;&gt; edge.target_person_id
                  AND EXISTS (
                    SELECT 1 FROM activity engaged_activity
                    WHERE engaged_activity.workspace_id = #{workspaceId}
                      AND engaged_activity.person_id = engaged.id
                    UNION ALL
                    SELECT 1 FROM note engaged_note
                    WHERE engaged_note.workspace_id = #{workspaceId}
                      AND engaged_note.visibility = 'workspace'
                      AND engaged_note.person_id = engaged.id
                    UNION ALL
                    SELECT 1 FROM task engaged_task
                    WHERE engaged_task.workspace_id = #{workspaceId}
                      AND engaged_task.person_id = engaged.id
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM activity user_activity
                    WHERE user_activity.workspace_id = #{workspaceId}
                      AND user_activity.created_by_id = #{userId}
                      AND (user_activity.person_id IS NULL OR EXISTS (
                        SELECT 1 FROM person processable
                        WHERE processable.id = user_activity.person_id
                          AND processable.suspended_at IS NULL
                          AND processable.provision_ceased_at IS NULL
                          AND processable.archived_at IS NULL
                          AND (processable.workspace_id = #{workspaceId}
                            OR EXISTS (
                              SELECT 1 FROM person_share processable_share
                              JOIN workspace processable_viewer_workspace
                                ON processable_viewer_workspace.id = processable_share.workspace_id
                              JOIN workspace processable_owner_workspace
                                ON processable_owner_workspace.id = processable.workspace_id
                               AND processable_owner_workspace.org_id = processable_viewer_workspace.org_id
                              WHERE processable_share.person_id = processable.id
                                AND processable_share.workspace_id = #{workspaceId}
                            ))
                      ))
                      AND (
                        user_activity.person_id IN (
                          SELECT company_person.id FROM person company_person
                          WHERE company_person.workspace_id = #{workspaceId}
                            AND company_person.company_id = candidate.company_id
                            AND company_person.suspended_at IS NULL
                            AND company_person.provision_ceased_at IS NULL
                            AND company_person.archived_at IS NULL
                        )
                        OR user_activity.deal_id IN (
                          SELECT company_deal.id FROM deal company_deal
                          WHERE company_deal.workspace_id = #{workspaceId}
                            AND company_deal.company_id = candidate.company_id
                        )
                      )
                  )
            </select>

            <sql id="stringOp">
                <choose>
                    <when test="op == 'equals'">AND ${col} = #{value}</when>
                    <when test="op == 'contains' or op == 'starts_with'">AND ${col} LIKE #{pattern}</when>
                    <when test="op == 'is_set'">AND ${col} IS NOT NULL AND ${col} &lt;&gt; ''</when>
                    <otherwise>AND 1 = 0</otherwise>
                </choose>
            </sql>

            <sql id="numberOp">
                <choose>
                    <when test="op == 'equals'">AND ${col} = #{number}</when>
                    <when test="op == 'gt'">AND ${col} &gt; #{number}</when>
                    <when test="op == 'gte'">AND ${col} &gt;= #{number}</when>
                    <when test="op == 'lt'">AND ${col} &lt; #{number}</when>
                    <when test="op == 'lte'">AND ${col} &lt;= #{number}</when>
                    <otherwise>AND 1 = 0</otherwise>
                </choose>
            </sql>

            <sql id="idOp">
                <choose>
                    <when test="op == 'is'">AND ${col} = #{id}</when>
                    <when test="op == 'in'">
                        AND ${col} IN
                        <foreach item="v" collection="ids" open="(" separator="," close=")">#{v}</foreach>
                    </when>
                    <otherwise>AND 1 = 0</otherwise>
                </choose>
            </sql>

            <sql id="dateOp">
                <choose>
                    <when test="op == 'before'">AND ${col} IS NOT NULL AND ${col} &lt; #{value}</when>
                    <when test="op == 'after'">AND ${col} IS NOT NULL AND ${col} &gt; #{value}</when>
                    <when test="op == 'within_days'">AND ${col} IS NOT NULL AND ${col} &gt;= #{dateFrom} AND ${col} &lt;= #{dateTo}</when>
                    <when test="op == 'is_set'">AND ${col} IS NOT NULL</when>
                    <otherwise>AND 1 = 0</otherwise>
                </choose>
            </sql>

            <sql id="personCompanyOp">
                <include refid="idOp"><property name="col" value="company_id"/></include>
                AND EXISTS (
                    SELECT 1 FROM company visible_company
                    WHERE visible_company.id = person.company_id
                      AND visible_company.archived_at IS NULL
                      AND (visible_company.workspace_id = #{workspaceId}
                        OR EXISTS (
                            SELECT 1 FROM company_share visible_share
                            JOIN workspace viewer_workspace ON viewer_workspace.id = visible_share.workspace_id
                            JOIN workspace owner_workspace
                              ON owner_workspace.id = visible_company.workspace_id
                             AND owner_workspace.org_id = viewer_workspace.org_id
                            WHERE visible_share.company_id = visible_company.id
                              AND visible_share.workspace_id = #{workspaceId}
                        ))
                )
            </sql>

            <select id="personIdsMatching" resultType="int">
                SELECT id FROM person
                WHERE workspace_id = #{workspaceId} AND suspended_at IS NULL
                  AND provision_ceased_at IS NULL AND archived_at IS NULL
                <if test="candidateIds != null">
                  AND id IN
                  <foreach item="candidateId" collection="candidateIds" open="(" separator="," close=")">
                    #{candidateId}
                  </foreach>
                </if>
                <choose>
                    <when test="field == 'name'"><include refid="stringOp"><property name="col" value="name"/></include></when>
                    <when test="field == 'title'"><include refid="stringOp"><property name="col" value="title"/></include></when>
                    <when test="field == 'email'"><include refid="stringOp"><property name="col" value="email"/></include></when>
                    <when test="field == 'phone'"><include refid="stringOp"><property name="col" value="phone"/></include></when>
                    <when test="field == 'company'"><include refid="personCompanyOp"/></when>
                    <when test="field == 'owner'"><include refid="idOp"><property name="col" value="owner_id"/></include></when>
                    <when test="field == 'created'"><include refid="dateOp"><property name="col" value="created_at"/></include></when>
                    <when test="field == 'updated'"><include refid="dateOp"><property name="col" value="updated_at"/></include></when>
                    <when test="field == 'tag'">
                        AND id IN (
                            SELECT person_tag.person_id
                            FROM person_tag
                            JOIN tag segment_tag
                              ON segment_tag.id = person_tag.tag_id
                             AND segment_tag.workspace_id = #{workspaceId}
                            WHERE person_tag.tag_id = #{id}
                        )
                    </when>
                    <otherwise>AND 1 = 0</otherwise>
                </choose>
            </select>

            <select id="personIdsMatchingIncludingRestricted" resultType="int">
                SELECT id FROM person
                WHERE workspace_id = #{workspaceId} AND archived_at IS NULL
                <if test="candidateIds != null">
                  AND id IN
                  <foreach item="candidateId" collection="candidateIds" open="(" separator="," close=")">
                    #{candidateId}
                  </foreach>
                </if>
                <choose>
                    <when test="field == 'name'"><include refid="stringOp"><property name="col" value="name"/></include></when>
                    <when test="field == 'title'"><include refid="stringOp"><property name="col" value="title"/></include></when>
                    <when test="field == 'email'"><include refid="stringOp"><property name="col" value="email"/></include></when>
                    <when test="field == 'phone'"><include refid="stringOp"><property name="col" value="phone"/></include></when>
                    <when test="field == 'company'"><include refid="personCompanyOp"/></when>
                    <when test="field == 'owner'"><include refid="idOp"><property name="col" value="owner_id"/></include></when>
                    <when test="field == 'created'"><include refid="dateOp"><property name="col" value="created_at"/></include></when>
                    <when test="field == 'updated'"><include refid="dateOp"><property name="col" value="updated_at"/></include></when>
                    <when test="field == 'tag'">
                        AND id IN (
                            SELECT person_tag.person_id
                            FROM person_tag
                            JOIN tag segment_tag
                              ON segment_tag.id = person_tag.tag_id
                             AND segment_tag.workspace_id = #{workspaceId}
                            WHERE person_tag.tag_id = #{id}
                        )
                    </when>
                    <otherwise>AND 1 = 0</otherwise>
                </choose>
            </select>
        </mapper>
        """;

    private static final String PIPELINE = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="ooo.klae.connex.backend.mappers.PipelineMapper">

          <resultMap id="StageResult" type="Stage">
            <id property="id" column="id"/>
            <result property="workspaceId" column="workspace_id"/>
            <result property="name" column="name"/>
            <result property="position" column="position"/>
            <result property="success" column="is_success"/>
            <result property="failure" column="is_failure"/>
            <association property="pipeline" javaType="Pipeline">
              <id property="id" column="pipeline_id"/>
            </association>
          </resultMap>

          <resultMap id="PipelineFullResult" type="Pipeline">
            <id property="id" column="id"/>
            <result property="workspaceId" column="workspace_id"/>
            <result property="name" column="name"/>
            <result property="createdAt" column="created_at"/>
            <result property="updatedAt" column="updated_at"/>
            <collection property="stages" ofType="Stage"
                        column="{workspaceId=workspace_id,pipelineId=id}"
                        select="ooo.klae.connex.backend.mappers.PipelineMapper.getStagesByPipelineId"/>
          </resultMap>

          <!-- Owned-or-shared visibility: a pipeline is visible to its owning workspace and any workspace it
               has been shared with (pipeline_share). The share branch carries the same-org ceiling the write
               path enforces (owner and grantee workspaces must share an organization), so a share row that
               spans an org boundary grants no read visibility. Writes stay owned-only. Requires
               `FROM pipeline` unaliased. -->
          <sql id="visible">
            (pipeline.workspace_id = #{workspaceId}
             OR EXISTS (SELECT 1 FROM pipeline_share ps
                JOIN workspace vws ON vws.id = ps.workspace_id
                JOIN workspace ows ON ows.id = pipeline.workspace_id AND ows.org_id = vws.org_id
                WHERE ps.pipeline_id = pipeline.id AND ps.workspace_id = #{workspaceId}))
          </sql>

          <select id="getAllPipelines" resultType="Pipeline">
            SELECT id, workspace_id, name, created_at, updated_at FROM pipeline WHERE <include refid="visible"/>
          </select>

          <select id="getPipelineById" resultMap="PipelineFullResult">
            SELECT id, workspace_id, name, created_at, updated_at FROM pipeline
            WHERE <include refid="visible"/> AND id = #{id}
          </select>

          <select id="getVisiblePipelineByIdForUpdate" resultType="Pipeline">
            SELECT id, workspace_id, name, created_at, updated_at FROM pipeline
            WHERE <include refid="visible"/> AND id = #{id}
            FOR UPDATE
          </select>

          <select id="getOwnedPipelineById" resultMap="PipelineFullResult">
            SELECT id, workspace_id, name, created_at, updated_at FROM pipeline
            WHERE workspace_id = #{workspaceId} AND id = #{id}
          </select>

          <select id="search" resultType="Pipeline">
            SELECT id, workspace_id, name, created_at, updated_at FROM pipeline
            WHERE <include refid="visible"/> AND name LIKE #{query}
            ORDER BY name
            LIMIT 10
          </select>

          <select id="pipelineExists" resultType="boolean">
            SELECT EXISTS(
              SELECT 1 FROM pipeline WHERE <include refid="visible"/> AND id = #{id}
            )
          </select>

          <insert id="insertPipeline" useGeneratedKeys="true" keyProperty="id">
            INSERT INTO pipeline (workspace_id, name) VALUES (#{workspaceId}, #{name})
          </insert>

          <update id="updatePipeline">
            UPDATE pipeline SET name = #{name} WHERE workspace_id = #{workspaceId} AND id = #{id}
          </update>

          <delete id="deletePipeline">DELETE FROM pipeline WHERE workspace_id = #{workspaceId} AND id = #{id}</delete>

          <select id="getStageById" resultMap="StageResult">
            SELECT stage.id, stage.workspace_id, stage.name, stage.pipeline_id, stage.position,
                   stage.is_success, stage.is_failure
            FROM stage
            JOIN pipeline ON pipeline.id = stage.pipeline_id AND pipeline.workspace_id = stage.workspace_id
            WHERE pipeline.workspace_id = #{workspaceId} AND stage.id = #{id}
          </select>

          <select id="getVisibleStageById" resultMap="StageResult">
            SELECT stage.id, stage.workspace_id, stage.name, stage.pipeline_id, stage.position,
                   stage.is_success, stage.is_failure
            FROM stage
            JOIN pipeline ON pipeline.id = stage.pipeline_id AND pipeline.workspace_id = stage.workspace_id
            WHERE stage.id = #{id}
              AND (pipeline.workspace_id = #{workspaceId}
                   OR EXISTS (SELECT 1 FROM pipeline_share ps
                      JOIN workspace vws ON vws.id = ps.workspace_id
                      JOIN workspace ows ON ows.id = pipeline.workspace_id AND ows.org_id = vws.org_id
                      WHERE ps.pipeline_id = pipeline.id AND ps.workspace_id = #{workspaceId}))
          </select>

          <select id="getVisibleStageByIdForUpdate" resultMap="StageResult">
            SELECT stage.id, stage.workspace_id, stage.name, stage.pipeline_id, stage.position,
                   stage.is_success, stage.is_failure
            FROM stage
            JOIN pipeline ON pipeline.id = stage.pipeline_id AND pipeline.workspace_id = stage.workspace_id
            WHERE stage.id = #{id}
              AND (pipeline.workspace_id = #{workspaceId}
                   OR EXISTS (SELECT 1 FROM pipeline_share ps
                      JOIN workspace vws ON vws.id = ps.workspace_id
                      JOIN workspace ows ON ows.id = pipeline.workspace_id AND ows.org_id = vws.org_id
                      WHERE ps.pipeline_id = pipeline.id AND ps.workspace_id = #{workspaceId}))
            FOR UPDATE
          </select>

          <select id="getAllStages" resultMap="StageResult">
            SELECT id, workspace_id, name, pipeline_id, position, is_success, is_failure FROM stage
            WHERE stage.workspace_id = #{workspaceId}
               OR EXISTS (SELECT 1 FROM pipeline_share ps
                  JOIN workspace vws ON vws.id = ps.workspace_id
                  JOIN workspace ows ON ows.id = stage.workspace_id AND ows.org_id = vws.org_id
                  WHERE ps.pipeline_id = stage.pipeline_id AND ps.workspace_id = #{workspaceId})
            ORDER BY pipeline_id, position
          </select>

          <select id="getStagesByPipelineId" resultMap="StageResult">
            SELECT stage.id, stage.workspace_id, stage.name, stage.pipeline_id, stage.position,
                   stage.is_success, stage.is_failure
            FROM stage
            JOIN pipeline ON pipeline.id = stage.pipeline_id AND pipeline.workspace_id = stage.workspace_id
            WHERE pipeline.id = #{pipelineId}
              AND (pipeline.workspace_id = #{workspaceId}
                   OR EXISTS (SELECT 1 FROM pipeline_share ps
                      JOIN workspace vws ON vws.id = ps.workspace_id
                      JOIN workspace ows ON ows.id = pipeline.workspace_id AND ows.org_id = vws.org_id
                      WHERE ps.pipeline_id = pipeline.id AND ps.workspace_id = #{workspaceId}))
            ORDER BY stage.position
          </select>

          <insert id="insertStage" useGeneratedKeys="true" keyProperty="id">
            INSERT INTO stage (workspace_id, name, pipeline_id, position, is_success, is_failure)
            VALUES (#{workspaceId}, #{name}, #{pipeline.id}, #{position}, #{success}, #{failure})
          </insert>

          <update id="updateStage">
            UPDATE stage
            SET name = #{name}, pipeline_id = #{pipeline.id}, position = #{position},
                is_success = #{success}, is_failure = #{failure}
            WHERE workspace_id = #{workspaceId} AND id = #{id}
          </update>

          <delete id="deleteStage">DELETE FROM stage WHERE workspace_id = #{workspaceId} AND id = #{id}</delete>
        </mapper>
        """;
}
