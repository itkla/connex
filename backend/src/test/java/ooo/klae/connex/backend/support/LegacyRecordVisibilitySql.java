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
        for (String xml : List.of(INTRODUCTION, DUPLICATE_REVIEW, NOTE, REPORT)) {
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
    /** Frozen Report SQL from b004c467b, before the L3 ceiling rewrite. */
    private static final String REPORT = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="ooo.klae.connex.backend.mappers.ReportMapper">
          <sql id="dealFilters">
            <if test="query.pipelineIds != null and !query.pipelineIds.isEmpty()">
              AND d.pipeline_id IN
              <foreach item="id" collection="query.pipelineIds" open="(" separator="," close=")">#{id}</foreach>
            </if>
            <if test="query.ownerIds != null and !query.ownerIds.isEmpty()">
              AND d.owner_id IN
              <foreach item="id" collection="query.ownerIds" open="(" separator="," close=")">#{id}</foreach>
            </if>
            <if test="query.statuses != null and !query.statuses.isEmpty()">
              AND (CASE WHEN d.won = TRUE THEN 'won' WHEN d.won = FALSE THEN 'lost' ELSE 'open' END) IN
              <foreach item="status" collection="query.statuses" open="(" separator="," close=")">#{status}</foreach>
            </if>
            <if test="query.tagIds != null and !query.tagIds.isEmpty()">
              AND EXISTS (
                SELECT 1 FROM deal_tag dt
                JOIN tag filter_tag ON filter_tag.id = dt.tag_id AND filter_tag.workspace_id = #{query.workspaceId}
                WHERE dt.deal_id = d.id AND dt.tag_id IN
                <foreach item="id" collection="query.tagIds" open="(" separator="," close=")">#{id}</foreach>
              )
            </if>
            <if test="query.riskIds != null and !query.riskIds.isEmpty()">
              AND d.id IN
              <foreach item="id" collection="query.riskIds" open="(" separator="," close=")">#{id}</foreach>
            </if>
          </sql>

          <select id="aggregateCoverageGaps" resultType="ooo.klae.connex.backend.dto.ReportAggregateRow">
            SELECT
              <choose>
                <when test="query.measure == 'coverage_gap_open_pipeline_value'">
                  CONCAT(COALESCE(open_deal.currency, ''), ':',
                    <choose>
                      <when test="query.groupBy == 'company'">CAST(gap_company.id AS CHAR)</when>
                      <otherwise>'total'</otherwise>
                    </choose>)
                </when>
                <when test="query.groupBy == 'company'">CAST(gap_company.id AS CHAR)</when>
                <otherwise>'total'</otherwise>
              </choose> AS group_key,
              <choose>
                <when test="query.measure == 'coverage_gap_open_pipeline_value'">
                  CONCAT(COALESCE(open_deal.currency, ''), ' · ',
                    <choose>
                      <when test="query.groupBy == 'company'">gap_company.name</when>
                      <otherwise>'Total'</otherwise>
                    </choose>)
                </when>
                <when test="query.groupBy == 'company'">gap_company.name</when>
                <otherwise>'Total'</otherwise>
              </choose> AS group_label,
              <choose>
                <when test="query.measure == 'coverage_gap_open_pipeline_value'">COALESCE(open_deal.currency, '')</when>
                <otherwise>'count'</otherwise>
              </choose> AS unit,
              <choose>
                <when test="query.measure == 'coverage_gap_open_pipeline_value'">COALESCE(SUM(open_deal.value), 0)</when>
                <otherwise>COUNT(DISTINCT gap_company.id)</otherwise>
              </choose> AS value
            FROM (
              SELECT c.id, c.name
              FROM company c
              LEFT JOIN person gap_person ON gap_person.company_id = c.id
                AND gap_person.created_at &lt; #{query.endUtc}
                AND gap_person.suspended_at IS NULL
                AND gap_person.archived_at IS NULL
                AND (
                  gap_person.workspace_id = #{query.workspaceId}
                  OR EXISTS (
                    SELECT 1
                    FROM person_share gap_person_share
                    JOIN workspace gap_person_viewer
                      ON gap_person_viewer.id = gap_person_share.workspace_id
                      AND gap_person_viewer.id = #{query.workspaceId}
                    JOIN workspace gap_person_owner
                      ON gap_person_owner.id = gap_person.workspace_id
                      AND gap_person_owner.org_id = gap_person_viewer.org_id
                    WHERE gap_person_share.person_id = gap_person.id
                      AND gap_person_share.workspace_id = #{query.workspaceId}
                      AND gap_person_share.created_at &lt; #{query.endUtc}
                  )
                )
              LEFT JOIN (
                SELECT warmth_touch.person_id,
                       SUM(warmth_touch.weight
                         * POW(#{model.decayBase}, -warmth_touch.age_days / #{model.halfLifeDays})) AS raw_weight
                FROM (
                  SELECT a.person_id,
                         CASE LOWER(a.type)
                           WHEN 'meeting' THEN #{model.meetingWeight}
                           WHEN 'call' THEN #{model.callWeight}
                           WHEN 'email' THEN #{model.emailWeight}
                           ELSE #{model.otherActivityWeight}
                         END AS weight,
                         GREATEST(0.0,
                           TIMESTAMPDIFF(MICROSECOND, a.timestamp, #{warmthReference})
                             / #{model.microsecondsPerDay}) AS age_days
                  FROM activity a
                  WHERE a.workspace_id = #{query.workspaceId}
                    AND a.person_id IS NOT NULL
                    AND a.timestamp &lt; #{query.endUtc}
                  UNION ALL
                  SELECT n.person_id, #{model.noteWeight} AS weight,
                         GREATEST(0.0,
                           TIMESTAMPDIFF(MICROSECOND, n.created_at, #{warmthReference})
                             / #{model.microsecondsPerDay}) AS age_days
                  FROM note n
                  WHERE n.workspace_id = #{query.workspaceId}
                    AND n.visibility = 'workspace'
                    AND n.person_id IS NOT NULL
                    AND n.created_at &lt; #{query.endUtc}
                  UNION ALL
                  SELECT t.person_id, #{model.taskWeight} AS weight,
                         GREATEST(0.0,
                           TIMESTAMPDIFF(MICROSECOND, t.created_at, #{warmthReference})
                             / #{model.microsecondsPerDay}) AS age_days
                  FROM task t
                  WHERE t.workspace_id = #{query.workspaceId}
                    AND t.person_id IS NOT NULL
                    AND t.created_at &lt; #{query.endUtc}
                ) warmth_touch
                GROUP BY warmth_touch.person_id
              ) person_warmth ON person_warmth.person_id = gap_person.id
              WHERE c.created_at &lt; #{query.endUtc}
                AND (
                  c.workspace_id = #{query.workspaceId}
                  OR EXISTS (
                    SELECT 1
                    FROM company_share gap_company_share
                    JOIN workspace gap_company_viewer
                      ON gap_company_viewer.id = gap_company_share.workspace_id
                      AND gap_company_viewer.id = #{query.workspaceId}
                    JOIN workspace gap_company_owner
                      ON gap_company_owner.id = c.workspace_id
                      AND gap_company_owner.org_id = gap_company_viewer.org_id
                    WHERE gap_company_share.company_id = c.id
                      AND gap_company_share.workspace_id = #{query.workspaceId}
                      AND gap_company_share.created_at &lt; #{query.endUtc}
                  )
                )
                AND c.archived_at IS NULL
              <if test="query.tagIds != null and !query.tagIds.isEmpty()">
                AND EXISTS (
                  SELECT 1
                  FROM company_tag coverage_company_tag
                  JOIN tag coverage_tag ON coverage_tag.id = coverage_company_tag.tag_id
                    AND coverage_tag.workspace_id = #{query.workspaceId}
                  WHERE coverage_company_tag.company_id = c.id
                    AND coverage_company_tag.tag_id IN
                    <foreach item="id" collection="query.tagIds" open="(" separator="," close=")">#{id}</foreach>
                )
              </if>
              GROUP BY c.id, c.name
              HAVING COUNT(DISTINCT CASE
                WHEN COALESCE(person_warmth.raw_weight, 0.0) &gt;= #{model.warmMinimumRawWeight}
                THEN gap_person.id
                ELSE NULL
              END) &lt;= 1
            ) gap_company
            LEFT JOIN deal open_deal ON open_deal.company_id = gap_company.id
              AND open_deal.workspace_id = #{query.workspaceId}
              AND open_deal.won IS NULL
              <if test="query.pipelineIds != null and !query.pipelineIds.isEmpty()">
                AND open_deal.pipeline_id IN
                <foreach item="id" collection="query.pipelineIds" open="(" separator="," close=")">#{id}</foreach>
              </if>
              <if test="query.ownerIds != null and !query.ownerIds.isEmpty()">
                AND open_deal.owner_id IN
                <foreach item="id" collection="query.ownerIds" open="(" separator="," close=")">#{id}</foreach>
              </if>
              <if test="query.statuses != null and !query.statuses.isEmpty()">
                AND 'open' IN
                <foreach item="status" collection="query.statuses" open="(" separator="," close=")">#{status}</foreach>
              </if>
            <if test="query.measure == 'coverage_gap_open_pipeline_value'
                or query.pipelineIds != null and !query.pipelineIds.isEmpty()
                or query.ownerIds != null and !query.ownerIds.isEmpty()
                or query.statuses != null and !query.statuses.isEmpty()">
              WHERE open_deal.id IS NOT NULL
            </if>
            GROUP BY group_key, group_label, unit
            ORDER BY value DESC, group_label, group_key
          </select>

          <select id="aggregateSingleThreadedDeals" resultType="ooo.klae.connex.backend.dto.ReportAggregateRow">
            SELECT
              <choose>
                <when test="query.measure == 'single_threaded_deal_value'">
                  CONCAT(single_deal.currency, ':',
                    <choose>
                      <when test="query.groupBy == 'deal'">CAST(single_deal.id AS CHAR)</when>
                      <when test="query.groupBy == 'company'">COALESCE(CAST(single_company.id AS CHAR), 'unassigned')</when>
                      <otherwise>'total'</otherwise>
                    </choose>)
                </when>
                <when test="query.groupBy == 'deal'">CAST(single_deal.id AS CHAR)</when>
                <when test="query.groupBy == 'company'">COALESCE(CAST(single_company.id AS CHAR), 'unassigned')</when>
                <otherwise>'total'</otherwise>
              </choose> AS group_key,
              <choose>
                <when test="query.measure == 'single_threaded_deal_value'">
                  CONCAT(single_deal.currency, ' · ',
                    <choose>
                      <when test="query.groupBy == 'deal'">single_deal.name</when>
                      <when test="query.groupBy == 'company'">COALESCE(single_company.name, 'Unassigned')</when>
                      <otherwise>'Total'</otherwise>
                    </choose>)
                </when>
                <when test="query.groupBy == 'deal'">single_deal.name</when>
                <when test="query.groupBy == 'company'">COALESCE(single_company.name, 'Unassigned')</when>
                <otherwise>'Total'</otherwise>
              </choose> AS group_label,
              <choose>
                <when test="query.measure == 'single_threaded_deal_value'">single_deal.currency</when>
                <otherwise>'count'</otherwise>
              </choose> AS unit,
              <choose>
                <when test="query.measure == 'single_threaded_deal_value'">SUM(single_deal.value)</when>
                <otherwise>COUNT(*)</otherwise>
              </choose> AS value
            FROM (
              SELECT d.id, d.name, d.value, d.currency, d.company_id
              FROM deal d
              JOIN deal_person single_link ON single_link.deal_id = d.id
              JOIN person single_person ON single_person.id = single_link.person_id
                AND single_person.suspended_at IS NULL
                AND single_person.archived_at IS NULL
              WHERE d.workspace_id = #{query.workspaceId}
                AND d.won IS NULL
                AND d.risk_excluded = FALSE
              <include refid="dealFilters"/>
              GROUP BY d.id, d.name, d.value, d.currency, d.company_id
              HAVING COUNT(DISTINCT single_link.person_id) = 1
            ) single_deal
            LEFT JOIN company single_company ON single_company.id = single_deal.company_id
              AND (
                single_company.workspace_id = #{query.workspaceId}
                OR EXISTS (
                  SELECT 1
                  FROM company_share single_company_share
                  JOIN workspace single_company_viewer
                    ON single_company_viewer.id = single_company_share.workspace_id
                    AND single_company_viewer.id = #{query.workspaceId}
                  JOIN workspace single_company_owner
                    ON single_company_owner.id = single_company.workspace_id
                    AND single_company_owner.org_id = single_company_viewer.org_id
                  WHERE single_company_share.company_id = single_company.id
                    AND single_company_share.workspace_id = #{query.workspaceId}
                )
              )
              AND single_company.archived_at IS NULL
            GROUP BY group_key, group_label, unit
            ORDER BY value DESC, group_label, group_key
          </select>

          <select id="getNetworkAccountValues"
                  resultType="ooo.klae.connex.backend.dto.ReportNetworkAccountRow">
            SELECT network_company.id AS company_id,
                   network_company.name AS company_name,
                   network_deal.currency AS currency,
                   COALESCE(SUM(GREATEST(network_deal.value, 0)), 0) AS account_value
            FROM deal network_deal
            JOIN company network_company ON network_company.id = network_deal.company_id
              AND (
                network_company.workspace_id = #{query.workspaceId}
                OR EXISTS (
                  SELECT 1
                  FROM company_share network_company_share
                  JOIN workspace vws ON vws.id = network_company_share.workspace_id
                    AND vws.id = #{query.workspaceId}
                  JOIN workspace ows ON ows.id = network_company.workspace_id
                    AND ows.org_id = vws.org_id
                  WHERE network_company_share.company_id = network_company.id
                    AND network_company_share.workspace_id = #{query.workspaceId}
                )
              )
              AND network_company.archived_at IS NULL
            WHERE network_deal.workspace_id = #{query.workspaceId}
              AND network_deal.won IS NULL
            <if test="query.pipelineIds != null and !query.pipelineIds.isEmpty()">
              AND network_deal.pipeline_id IN
              <foreach item="id" collection="query.pipelineIds" open="(" separator="," close=")">#{id}</foreach>
            </if>
            <if test="query.ownerIds != null and !query.ownerIds.isEmpty()">
              AND network_deal.owner_id IN
              <foreach item="id" collection="query.ownerIds" open="(" separator="," close=")">#{id}</foreach>
            </if>
            <if test="query.statuses != null and !query.statuses.isEmpty()">
              AND 'open' IN
              <foreach item="status" collection="query.statuses" open="(" separator="," close=")">#{status}</foreach>
            </if>
            <if test="query.tagIds != null and !query.tagIds.isEmpty()">
              AND EXISTS (
                SELECT 1
                FROM company_tag network_company_tag
                JOIN tag network_tag ON network_tag.id = network_company_tag.tag_id
                  AND network_tag.workspace_id = #{query.workspaceId}
                WHERE network_company_tag.company_id = network_company.id
                  AND network_company_tag.tag_id IN
                  <foreach item="id" collection="query.tagIds" open="(" separator="," close=")">#{id}</foreach>
              )
            </if>
            GROUP BY network_company.id, network_company.name, network_deal.currency
            ORDER BY network_deal.currency, network_company.name, network_company.id
            LIMIT #{limit}
          </select>

          <select id="getVisiblePersonIdsAt" resultType="int">
            SELECT p.id
            FROM person p
            WHERE p.created_at &lt;= #{asOf}
              AND (
                p.workspace_id = #{workspaceId}
                OR EXISTS (
                  SELECT 1
                  FROM person_share ps
                  JOIN workspace viewer_workspace ON viewer_workspace.id = ps.workspace_id
                    AND viewer_workspace.id = #{workspaceId}
                  JOIN workspace owner_workspace ON owner_workspace.id = p.workspace_id
                    AND owner_workspace.org_id = viewer_workspace.org_id
                  WHERE ps.person_id = p.id
                    AND ps.workspace_id = #{workspaceId}
                    AND ps.created_at &lt;= #{asOf}
                )
              )
            ORDER BY p.id
          </select>

          <select id="getVisibleCompanyIdsAt" resultType="int">
            SELECT c.id
            FROM company c
            WHERE c.created_at &lt;= #{asOf}
              AND (
                c.workspace_id = #{workspaceId}
                OR EXISTS (
                  SELECT 1
                  FROM company_share cs
                  JOIN workspace viewer_workspace ON viewer_workspace.id = cs.workspace_id
                    AND viewer_workspace.id = #{workspaceId}
                  JOIN workspace owner_workspace ON owner_workspace.id = c.workspace_id
                    AND owner_workspace.org_id = viewer_workspace.org_id
                  WHERE cs.company_id = c.id
                    AND cs.workspace_id = #{workspaceId}
                    AND cs.created_at &lt;= #{asOf}
                )
              )
            ORDER BY c.id
          </select>
        </mapper>
        """;
}
