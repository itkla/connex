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
        for (String xml : List.of(INTRODUCTION, DUPLICATE_REVIEW, NOTE, REPORT, SEGMENT, PIPELINE, COMPANY, DEAL)) {
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

    /** Runs original junction DML and captures its result before rolling back the isolated session. */
    public WriteResult rollbackDealWrite(String statement, Map<String, Object> parameters) {
        try (SqlSession session = sessions.openSession()) {
            requireMigratedDefaultCatalog(session);
            try {
                int rows = session.update("ooo.klae.connex.backend.mappers.DealMapper." + statement, parameters);
                return new WriteResult(rows, dealLinks(session.getConnection(), (Integer) parameters.get("dealId")));
            } finally {
                session.rollback();
            }
        }
    }

    /** Reads the exact junction state on the DML connection, including nullable roles. */
    public static List<DealLink> dealLinks(java.sql.Connection connection, int dealId) {
        try (var query = connection.prepareStatement(
                "SELECT person_id, role FROM deal_person WHERE deal_id = ? ORDER BY person_id")) {
            query.setInt(1, dealId);
            try (var rows = query.executeQuery()) {
                List<DealLink> links = new java.util.ArrayList<>();
                while (rows.next()) {
                    links.add(new DealLink(rows.getInt(1), rows.getString(2)));
                }
                return List.copyOf(links);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read Deal junction state", failure);
        }
    }

    /** Affected rows and the full junction state produced before transaction rollback. */
    public record WriteResult(int rows, List<DealLink> links) { }

    /** A junction row retains the distinction between a SQL NULL role and any literal text. */
    public record DealLink(int personId, String role) { }

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
    /** Frozen Report SQL from origin/main at 37497574b, including #2161's unit grouping fix. */
    private static final String REPORT = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
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

          <sql id="coverageGapUnit">
            <choose>
              <when test="query.measure == 'coverage_gap_open_pipeline_value'">COALESCE(open_deal.currency, '')</when>
              <otherwise>'count'</otherwise>
            </choose>
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
              <include refid="coverageGapUnit"/> AS unit,
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
            GROUP BY group_key, group_label, <include refid="coverageGapUnit"/>
            ORDER BY value DESC, group_label, group_key
          </select>

          <sql id="singleThreadedDealUnit">
            <choose>
              <when test="query.measure == 'single_threaded_deal_value'">single_deal.currency</when>
              <otherwise>'count'</otherwise>
            </choose>
          </sql>

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
              <include refid="singleThreadedDealUnit"/> AS unit,
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
            GROUP BY group_key, group_label, <include refid="singleThreadedDealUnit"/>
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

    private static final String COMPANY = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="ooo.klae.connex.backend.mappers.CompanyMapper">

          <sql id="companyColumns">
            id, workspace_id, owner_id, name, website, industry, phone, address, created_at, updated_at,
            logo_url, archived_at
          </sql>

          <sql id="qualifiedCompanyColumns">
            c.id, c.workspace_id, c.owner_id, c.name, c.website, c.industry, c.phone, c.address,
            c.created_at, c.updated_at, c.logo_url, c.archived_at
          </sql>

          <sql id="visibleAny">
            (c.workspace_id = #{workspaceId}
             OR EXISTS (SELECT 1 FROM company_share cs
                JOIN workspace vws ON vws.id = cs.workspace_id
                JOIN workspace ows ON ows.id = c.workspace_id AND ows.org_id = vws.org_id
                WHERE cs.company_id = c.id AND cs.workspace_id = #{workspaceId}))
          </sql>

          <sql id="visible">
            <include refid="visibleAny"/> AND c.archived_at IS NULL
          </sql>

          <sql id="visiblePerson">
            (p.workspace_id = #{workspaceId}
             OR EXISTS (SELECT 1 FROM person_share ps
                JOIN workspace pvws ON pvws.id = ps.workspace_id
                JOIN workspace pows ON pows.id = p.workspace_id AND pows.org_id = pvws.org_id
                WHERE ps.person_id = p.id AND ps.workspace_id = #{workspaceId}))
            AND p.archived_at IS NULL
          </sql>

          <!-- Normalized interaction type and model weight for one activity row, shared by the
               workspace-wide aggregate and the subject-scoped evidence union so both read the same
               attribution vocabulary. Requires the table aliased `a`. -->
          <sql id="activityInteractionType">
            CASE LOWER(a.type)
              WHEN 'meeting' THEN 'meeting'
              WHEN 'call' THEN 'call'
              WHEN 'email' THEN 'email'
              ELSE 'other'
            END
          </sql>

          <sql id="activityBaseWeight">
            CASE LOWER(a.type)
              WHEN 'meeting' THEN #{model.meetingWeight}
              WHEN 'call' THEN #{model.callWeight}
              WHEN 'email' THEN #{model.emailWeight}
              ELSE #{model.otherActivityWeight}
            END
          </sql>

          <!-- A contact whose touches may be attributed to its company. Requires the table aliased `p`. -->
          <sql id="attributablePerson">
            p.suspended_at IS NULL AND <include refid="visiblePerson"/>
          </sql>

          <sql id="relationshipTouchSources">
            SELECT 'activity' AS source_type,
                   a.id AS source_id,
                   p.company_id,
                   <include refid="activityInteractionType"/> AS interaction_type,
                   a.timestamp AS touched_at,
                   <include refid="activityBaseWeight"/> AS weight
            FROM activity a
            JOIN person p ON p.id = a.person_id
            WHERE a.workspace_id = #{workspaceId} AND p.company_id IS NOT NULL
              AND <include refid="attributablePerson"/>
            UNION ALL
            SELECT 'activity' AS source_type,
                   a.id AS source_id,
                   d.company_id,
                   <include refid="activityInteractionType"/> AS interaction_type,
                   a.timestamp AS touched_at,
                   <include refid="activityBaseWeight"/> AS weight
            FROM activity a
            JOIN deal d ON d.id = a.deal_id
            WHERE a.workspace_id = #{workspaceId} AND d.workspace_id = #{workspaceId}
              AND d.company_id IS NOT NULL
              AND (a.person_id IS NULL OR EXISTS (
                SELECT 1 FROM person p
                WHERE p.id = a.person_id AND <include refid="attributablePerson"/>
              ))
            UNION ALL
            SELECT 'note' AS source_type,
                   n.id AS source_id,
                   p.company_id,
                   'workspace-note' AS interaction_type,
                   n.created_at AS touched_at,
                   #{model.noteWeight} AS weight
            FROM note n
            JOIN person p ON p.id = n.person_id
            WHERE n.workspace_id = #{workspaceId} AND n.visibility = 'workspace'
              AND p.company_id IS NOT NULL
              AND <include refid="attributablePerson"/>
            UNION ALL
            SELECT 'note' AS source_type,
                   n.id AS source_id,
                   d.company_id,
                   'workspace-note' AS interaction_type,
                   n.created_at AS touched_at,
                   #{model.noteWeight} AS weight
            FROM note n
            JOIN deal d ON d.id = n.deal_id
            WHERE n.workspace_id = #{workspaceId} AND n.visibility = 'workspace'
              AND d.workspace_id = #{workspaceId} AND d.company_id IS NOT NULL
              AND (n.person_id IS NULL OR EXISTS (
                SELECT 1 FROM person p
                WHERE p.id = n.person_id AND <include refid="attributablePerson"/>
              ))
            UNION ALL
            SELECT 'task' AS source_type,
                   t.id AS source_id,
                   p.company_id,
                   'task' AS interaction_type,
                   t.created_at AS touched_at,
                   #{model.taskWeight} AS weight
            FROM task t
            JOIN person p ON p.id = t.person_id
            WHERE t.workspace_id = #{workspaceId} AND p.company_id IS NOT NULL
              AND <include refid="attributablePerson"/>
            UNION ALL
            SELECT 'task' AS source_type,
                   t.id AS source_id,
                   d.company_id,
                   'task' AS interaction_type,
                   t.created_at AS touched_at,
                   #{model.taskWeight} AS weight
            FROM task t
            JOIN deal d ON d.id = t.deal_id
            WHERE t.workspace_id = #{workspaceId} AND d.workspace_id = #{workspaceId}
              AND d.company_id IS NOT NULL
              AND (t.person_id IS NULL OR EXISTS (
                SELECT 1 FROM person p
                WHERE p.id = t.person_id AND <include refid="attributablePerson"/>
              ))
          </sql>

          <!-- Sources attributed to one company, filtered inside every branch so the union stays
               index-driven on that company and can be capped by row count. The deal branches drop a source
               whose contact already attributes it to the same company, so dual-attributed sources appear
               once without a deduplication pass over the whole union. -->
          <sql id="companyEvidenceSources">
            SELECT 'activity' AS source_type,
                   a.id AS source_id,
                   <include refid="activityInteractionType"/> AS interaction_type,
                   a.timestamp AS touched_at,
                   <include refid="activityBaseWeight"/> AS weight
            FROM activity a
            JOIN person p ON p.id = a.person_id
            WHERE a.workspace_id = #{workspaceId} AND p.company_id = #{companyId}
              AND <include refid="attributablePerson"/>
              AND a.timestamp IS NOT NULL AND a.timestamp &lt;= #{reference}
            UNION ALL
            SELECT 'activity' AS source_type,
                   a.id AS source_id,
                   <include refid="activityInteractionType"/> AS interaction_type,
                   a.timestamp AS touched_at,
                   <include refid="activityBaseWeight"/> AS weight
            FROM activity a
            JOIN deal d ON d.id = a.deal_id
            WHERE a.workspace_id = #{workspaceId} AND d.workspace_id = #{workspaceId}
              AND d.company_id = #{companyId}
              AND (a.person_id IS NULL OR EXISTS (
                SELECT 1 FROM person p
                WHERE p.id = a.person_id AND <include refid="attributablePerson"/>
              ))
              AND NOT EXISTS (
                SELECT 1 FROM person p
                WHERE p.id = a.person_id AND p.company_id = d.company_id
                  AND <include refid="attributablePerson"/>
              )
              AND a.timestamp IS NOT NULL AND a.timestamp &lt;= #{reference}
            UNION ALL
            SELECT 'note' AS source_type,
                   n.id AS source_id,
                   'workspace-note' AS interaction_type,
                   n.created_at AS touched_at,
                   #{model.noteWeight} AS weight
            FROM note n
            JOIN person p ON p.id = n.person_id
            WHERE n.workspace_id = #{workspaceId} AND n.visibility = 'workspace'
              AND p.company_id = #{companyId}
              AND <include refid="attributablePerson"/>
              AND n.created_at IS NOT NULL AND n.created_at &lt;= #{reference}
            UNION ALL
            SELECT 'note' AS source_type,
                   n.id AS source_id,
                   'workspace-note' AS interaction_type,
                   n.created_at AS touched_at,
                   #{model.noteWeight} AS weight
            FROM note n
            JOIN deal d ON d.id = n.deal_id
            WHERE n.workspace_id = #{workspaceId} AND n.visibility = 'workspace'
              AND d.workspace_id = #{workspaceId} AND d.company_id = #{companyId}
              AND (n.person_id IS NULL OR EXISTS (
                SELECT 1 FROM person p
                WHERE p.id = n.person_id AND <include refid="attributablePerson"/>
              ))
              AND NOT EXISTS (
                SELECT 1 FROM person p
                WHERE p.id = n.person_id AND p.company_id = d.company_id
                  AND <include refid="attributablePerson"/>
              )
              AND n.created_at IS NOT NULL AND n.created_at &lt;= #{reference}
            UNION ALL
            SELECT 'task' AS source_type,
                   t.id AS source_id,
                   'task' AS interaction_type,
                   t.created_at AS touched_at,
                   #{model.taskWeight} AS weight
            FROM task t
            JOIN person p ON p.id = t.person_id
            WHERE t.workspace_id = #{workspaceId} AND p.company_id = #{companyId}
              AND <include refid="attributablePerson"/>
              AND t.created_at IS NOT NULL AND t.created_at &lt;= #{reference}
            UNION ALL
            SELECT 'task' AS source_type,
                   t.id AS source_id,
                   'task' AS interaction_type,
                   t.created_at AS touched_at,
                   #{model.taskWeight} AS weight
            FROM task t
            JOIN deal d ON d.id = t.deal_id
            WHERE t.workspace_id = #{workspaceId} AND d.workspace_id = #{workspaceId}
              AND d.company_id = #{companyId}
              AND (t.person_id IS NULL OR EXISTS (
                SELECT 1 FROM person p
                WHERE p.id = t.person_id AND <include refid="attributablePerson"/>
              ))
              AND NOT EXISTS (
                SELECT 1 FROM person p
                WHERE p.id = t.person_id AND p.company_id = d.company_id
                  AND <include refid="attributablePerson"/>
              )
              AND t.created_at IS NOT NULL AND t.created_at &lt;= #{reference}
          </sql>

          <!-- Company evidence reads the capped source set twice: once collapsed to totals, once ranked for
               the returned contributors. Both bind the same #{sourceLimit}, so neither statement can scan or
               rank more rows than the service allows. -->
          <sql id="companyEvidenceScope">
            FROM (
              <include refid="companyEvidenceSources"/>
              LIMIT #{sourceLimit}
            ) source
            WHERE EXISTS (
              SELECT 1
              FROM company c
              WHERE c.id = #{companyId}
                AND <include refid="visible"/>
            )
          </sql>

          <select id="getAllCompanies" resultType="Company">
            SELECT <include refid="qualifiedCompanyColumns"/>
            FROM company c
            WHERE <include refid="visible"/>
            ORDER BY c.name, c.id
          </select>

          <select id="findVisibleNameCandidates" resultType="Company">
            SELECT c.id, c.workspace_id, c.name
            FROM company c
            WHERE <include refid="visible"/>
              AND c.name LIKE #{pattern}
            ORDER BY CASE WHEN LOWER(c.name) = #{normalizedName} THEN 0 ELSE 1 END,
              CHAR_LENGTH(c.name), c.id
            LIMIT #{limit}
          </select>

          <select id="getRelationshipScoreAggregates"
                  resultType="ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto">
            SELECT c.id,
                   COALESCE(SUM(touch.weight
                     * POW(#{model.decayBase}, -touch.age_days / #{model.halfLifeDays})), 0.0) AS raw_weight,
                   COALESCE(SUM(CASE WHEN touch.age_days &lt;= #{model.recentWindowDays}
                                     THEN touch.weight ELSE 0.0 END), 0.0)
                     AS recent_weight,
                   COALESCE(SUM(CASE WHEN touch.age_days &gt; #{model.recentWindowDays}
                                       AND touch.age_days &lt;= #{model.priorWindowDays}
                                     THEN touch.weight ELSE 0.0 END), 0.0) AS prior_weight,
                   DATE_FORMAT(MAX(touch.touched_at), '%Y-%m-%d %H:%i:%s') AS last_touch_at,
                   SUM(CASE WHEN touch.age_days &lt;= #{model.recentWindowDays}
                            THEN 1 ELSE 0 END) AS recent_touch_count
            FROM company c
            LEFT JOIN (
              SELECT attributed.company_id, attributed.touched_at, attributed.weight,
                     GREATEST(0.0,
                       TIMESTAMPDIFF(MICROSECOND, attributed.touched_at, #{reference})
                         / #{model.microsecondsPerDay}) AS age_days
              FROM (
                SELECT source.source_type,
                       source.source_id,
                       source.company_id,
                       source.interaction_type,
                       source.touched_at,
                       source.weight
                FROM (
                  <include refid="relationshipTouchSources"/>
                ) source
                WHERE source.touched_at IS NOT NULL AND source.touched_at &lt;= #{reference}
                GROUP BY source.source_type,
                         source.source_id,
                         source.company_id,
                         source.interaction_type,
                         source.touched_at,
                         source.weight
              ) attributed
            ) touch ON touch.company_id = c.id
            WHERE <include refid="visible"/>
            GROUP BY c.id
            ORDER BY c.id
          </select>

          <select id="getRelationshipScoreAggregatesByIds"
                  resultType="ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto">
            SELECT c.id,
                   COALESCE(SUM(touch.weight
                     * POW(#{model.decayBase}, -touch.age_days / #{model.halfLifeDays})), 0.0) AS raw_weight,
                   COALESCE(SUM(CASE WHEN touch.age_days &lt;= #{model.recentWindowDays}
                                     THEN touch.weight ELSE 0.0 END), 0.0)
                     AS recent_weight,
                   COALESCE(SUM(CASE WHEN touch.age_days &gt; #{model.recentWindowDays}
                                       AND touch.age_days &lt;= #{model.priorWindowDays}
                                     THEN touch.weight ELSE 0.0 END), 0.0) AS prior_weight,
                   DATE_FORMAT(MAX(touch.touched_at), '%Y-%m-%d %H:%i:%s') AS last_touch_at,
                   SUM(CASE WHEN touch.age_days &lt;= #{model.recentWindowDays}
                            THEN 1 ELSE 0 END) AS recent_touch_count
            FROM company c
            LEFT JOIN (
              SELECT attributed.company_id, attributed.touched_at, attributed.weight,
                     GREATEST(0.0,
                       TIMESTAMPDIFF(MICROSECOND, attributed.touched_at, #{reference})
                         / #{model.microsecondsPerDay}) AS age_days
              FROM (
                SELECT source.source_type,
                       source.source_id,
                       source.company_id,
                       source.interaction_type,
                       source.touched_at,
                       source.weight
                FROM (
                  <include refid="relationshipTouchSources"/>
                ) source
                WHERE source.touched_at IS NOT NULL AND source.touched_at &lt;= #{reference}
                AND source.company_id IN
                <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
                GROUP BY source.source_type,
                         source.source_id,
                         source.company_id,
                         source.interaction_type,
                         source.touched_at,
                         source.weight
              ) attributed
            ) touch ON touch.company_id = c.id
            WHERE <include refid="visible"/>
              AND c.id IN
              <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY c.id
            ORDER BY c.id
          </select>

          <select id="getRelationshipEvidenceTotals" timeout="10"
                  resultType="ooo.klae.connex.backend.dto.RelationshipEvidenceTotalsDto">
            SELECT COUNT(*) AS contributor_count,
                   COALESCE(SUM(capped.base_weight
                     * POW(#{model.decayBase}, -capped.age_days / #{model.halfLifeDays})), 0.0)
                     AS total_decayed_contribution,
                   COALESCE(SUM(CASE WHEN capped.source_type = 'activity' THEN 1 ELSE 0 END), 0)
                     AS activity_count,
                   COALESCE(SUM(CASE WHEN capped.source_type = 'note' THEN 1 ELSE 0 END), 0)
                     AS note_count,
                   COALESCE(SUM(CASE WHEN capped.source_type = 'task' THEN 1 ELSE 0 END), 0)
                     AS task_count,
                   COALESCE(SUM(CASE WHEN capped.age_days &lt;= #{model.recentWindowDays}
                                     THEN capped.base_weight ELSE 0.0 END), 0.0) AS recent_weight,
                   COALESCE(SUM(CASE WHEN capped.age_days &gt; #{model.recentWindowDays}
                                       AND capped.age_days &lt;= #{model.priorWindowDays}
                                     THEN capped.base_weight ELSE 0.0 END), 0.0) AS prior_weight,
                   DATE_FORMAT(MAX(capped.touched_at), '%Y-%m-%d %H:%i:%s') AS last_touch_at,
                   COALESCE(SUM(CASE WHEN capped.age_days &lt;= #{model.recentWindowDays}
                                     THEN 1 ELSE 0 END), 0) AS recent_touch_count
            FROM (
              SELECT source.source_type,
                     source.touched_at,
                     source.weight AS base_weight,
                     GREATEST(0.0,
                       TIMESTAMPDIFF(MICROSECOND, source.touched_at, #{reference})
                         / #{model.microsecondsPerDay}) AS age_days
              <include refid="companyEvidenceScope"/>
            ) capped
          </select>

          <select id="getRelationshipEvidenceContributors" timeout="10"
                  resultType="ooo.klae.connex.backend.dto.RelationshipEvidenceRowDto">
            SELECT aged.source_type,
                   aged.source_id,
                   aged.interaction_type,
                   DATE_FORMAT(aged.touched_at, '%Y-%m-%d %H:%i:%s') AS occurred_at,
                   aged.base_weight,
                   aged.base_weight
                     * POW(#{model.decayBase}, -aged.age_days / #{model.halfLifeDays})
                     AS decayed_contribution,
                   provider_activity.provider_name,
                   provider_activity.provider_stream,
                   provider_activity.provider_source_id,
                   DATE_FORMAT(provider_activity.provider_captured_at, '%Y-%m-%d %H:%i:%s')
                     AS provider_captured_at,
                   CASE WHEN provider_activity.provider_owned
                     THEN DATE_FORMAT(#{reference}, '%Y-%m-%d %H:%i:%s') END AS capture_as_of,
                   provider_activity.provider_visibility,
                   provider_activity.provider_admitted_fields_json,
                   provider_activity.provider_material_exclusions_json
            FROM (
              SELECT source.source_type,
                     source.source_id,
                     source.interaction_type,
                     source.touched_at,
                     source.weight AS base_weight,
                     GREATEST(0.0,
                       TIMESTAMPDIFF(MICROSECOND, source.touched_at, #{reference})
                         / #{model.microsecondsPerDay}) AS age_days
              <include refid="companyEvidenceScope"/>
            ) aged
            LEFT JOIN activity provider_activity
              ON aged.source_type = 'activity'
              AND provider_activity.workspace_id = #{workspaceId}
              AND provider_activity.id = aged.source_id
            ORDER BY decayed_contribution DESC,
                     aged.touched_at DESC,
                     aged.source_type,
                     aged.source_id
            LIMIT #{limit}
          </select>

          <!-- Decayed relationship warmth for the browser's sort, band facet, and decay horizon. This
               reuses the same attributed touch union and model parameters as getRelationshipScoreAggregates,
               including its de-duplicating GROUP BY, so the browser and the smart-segment warmth predicates
               can never read two different scores. The join is emitted only when a warmth sort or filter was
               requested. Binding `model` and `reference` here lets the shared touch-source fragment resolve
               its parameters unchanged. -->
          <sql id="warmthAggregate">
              <bind name="model" value="warmth.model"/>
              <bind name="reference" value="warmth.reference"/>
              LEFT JOIN (
                SELECT aged.company_id,
                       SUM(aged.weight
                         * POW(#{model.decayBase}, -aged.age_days / #{model.halfLifeDays})) AS raw_weight,
                       MAX(aged.touched_at) AS last_touch_at
                FROM (
                  SELECT attributed.company_id, attributed.touched_at, attributed.weight,
                         GREATEST(0.0,
                           TIMESTAMPDIFF(MICROSECOND, attributed.touched_at, #{reference})
                             / #{model.microsecondsPerDay}) AS age_days
                  FROM (
                    SELECT source.source_type,
                           source.source_id,
                           source.company_id,
                           source.interaction_type,
                           source.touched_at,
                           source.weight
                    FROM (
                      <include refid="relationshipTouchSources"/>
                    ) source
                    WHERE source.touched_at IS NOT NULL AND source.touched_at &lt;= #{reference}
                    GROUP BY source.source_type,
                             source.source_id,
                             source.company_id,
                             source.interaction_type,
                             source.touched_at,
                             source.weight
                  ) attributed
                ) aged
                GROUP BY aged.company_id
              ) w ON w.company_id = c.id
          </sql>

          <!-- Emitted wherever the aggregate column itself is projected or ordered on. -->
          <sql id="warmthJoin">
            <if test="warmth != null">
              <include refid="warmthAggregate"/>
            </if>
          </sql>

          <!-- A warmth sort cannot change which rows match, so counts, id selections, and exports join the
               aggregate only when a band, the no-history bucket, or a decay horizon actually narrows them. -->
          <sql id="warmthFilterJoin">
            <if test="warmth != null and warmth.restrictsRows()">
              <include refid="warmthAggregate"/>
            </if>
          </sql>

          <!-- One bucket per company: the model's band, or __none__ when nothing has ever been logged.
               The score is strictly increasing in raw weight, so comparing raw weight against the model's
               published boundaries selects exactly the rows the Java band classifier would. -->
          <sql id="warmthBandKey">
            CASE
              WHEN w.last_touch_at IS NULL THEN '__none__'
              WHEN w.raw_weight &gt;= #{model.hotMinimumRawWeight} THEN 'hot'
              WHEN w.raw_weight &gt;= #{model.warmMinimumRawWeight} THEN 'warm'
              WHEN w.raw_weight &gt;= #{model.coolMinimumRawWeight} THEN 'cool'
              ELSE 'cold'
            END
          </sql>

          <sql id="warmthWhere">
            <if test="warmth != null">
              <if test="warmth.bands.size() > 0 or warmth.noWarmth">
                AND (
                  <trim prefixOverrides="OR ">
                    <if test="warmth.bands.size() > 0">
                      <include refid="warmthBandKey"/> IN
                      <foreach item="band" collection="warmth.bands" open="(" separator="," close=")">#{band}</foreach>
                    </if>
                    <if test="warmth.noWarmth">OR w.last_touch_at IS NULL</if>
                  </trim>
                )
              </if>
              <if test="warmth.goesColdWithinDays != null">
                AND w.raw_weight &gt; #{model.coldRawWeight}
                AND ROUND(#{model.halfLifeDays}
                  * LOG(#{model.decayBase}, w.raw_weight / #{model.coldRawWeight}))
                  &lt;= #{warmth.goesColdWithinDays}
              </if>
            </if>
          </sql>

          <sql id="pageWhere">
            <where>
              <choose>
                <when test="archived">AND c.workspace_id = #{workspaceId} AND c.archived_at IS NOT NULL</when>
                <otherwise>AND <include refid="visible"/></otherwise>
              </choose>
              <include refid="warmthWhere"/>
              <if test="query != null">
                AND (c.name LIKE #{query} OR c.website LIKE #{query} OR c.industry LIKE #{query}
                  OR c.phone LIKE #{query} OR c.address LIKE #{query})
              </if>
              <if test="(industry != null and industry.size() > 0) or noIndustry">
                AND (
                  <trim prefixOverrides="OR ">
                    <if test="industry != null and industry.size() > 0">
                      c.industry IN
                      <foreach item="value" collection="industry" open="(" separator="," close=")">#{value}</foreach>
                    </if>
                    <if test="noIndustry">OR (c.industry IS NULL OR c.industry = '')</if>
                  </trim>
                )
              </if>
              <if test="ids != null and ids.size() > 0">
                AND c.id IN
                <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
              </if>
            </where>
          </sql>

          <sql id="memberScopePredicate">
            <choose>
              <when test="memberScope.mode.name() == 'ME'">
                AND c.owner_id = #{memberScope.userId}
              </when>
              <when test="memberScope.mode.name() == 'MEMBERS'">
                AND c.owner_id IN
                <foreach item="id" collection="memberScope.memberIds" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <when test="memberScope.mode.name() == 'UNASSIGNED'">
                AND c.owner_id IS NULL
              </when>
            </choose>
          </sql>

          <sql id="pageOrderBy">
            ORDER BY
            <choose>
              <when test="sort == 'name'">c.name</when>
              <when test="sort == 'website'">c.website</when>
              <when test="sort == 'industry'">c.industry</when>
              <when test="sort == 'phone'">c.phone</when>
              <when test="sort == 'address'">c.address</when>
              <when test="sort == 'createdAt'">c.created_at</when>
              <when test="sort == 'updatedAt'">c.updated_at</when>
              <when test="sort == 'warmth' and warmth != null">COALESCE(w.raw_weight, 0.0)</when>
              <otherwise>c.name</otherwise>
            </choose>
            <choose><when test="dir == 'desc'">DESC</when><otherwise>ASC</otherwise></choose>
            , c.id ASC
          </sql>

          <!-- The smart-segment statements apply the browser's complete filter contract to the evaluated
               segment ids: the same warmth predicate as pageWhere, plus memberScopePredicate after it. -->
          <sql id="segmentPageWhere">
            <where>
              AND <include refid="visible"/>
              <include refid="warmthWhere"/>
              <if test="query != null">
                AND (c.name LIKE #{query} OR c.website LIKE #{query} OR c.industry LIKE #{query}
                  OR c.phone LIKE #{query} OR c.address LIKE #{query})
              </if>
              <if test="(industry != null and industry.size() > 0) or noIndustry">
                AND (
                  <trim prefixOverrides="OR ">
                    <if test="industry != null and industry.size() > 0">
                      c.industry IN
                      <foreach item="value" collection="industry" open="(" separator="," close=")">#{value}</foreach>
                    </if>
                    <if test="noIndustry">OR (c.industry IS NULL OR c.industry = '')</if>
                  </trim>
                )
              </if>
            </where>
          </sql>

          <select id="getCompaniesPage" resultType="Company">
            SELECT <include refid="qualifiedCompanyColumns"/>
            FROM company c
            <include refid="warmthJoin"/>
            <include refid="pageWhere"/>
            <include refid="memberScopePredicate"/>
            <include refid="pageOrderBy"/>
            LIMIT #{limit} OFFSET #{offset}
          </select>

          <select id="countCompanies" resultType="long">
            SELECT COUNT(*)
            FROM company c
            <include refid="warmthFilterJoin"/>
            <include refid="pageWhere"/>
            <include refid="memberScopePredicate"/>
          </select>

          <select id="getCompanyEngagementCounts"
                  resultType="ooo.klae.connex.backend.dto.CompanyEngagementCountsDto">
            SELECT
              (SELECT COUNT(*) FROM person p
               WHERE p.workspace_id = #{workspaceId} AND p.company_id = #{companyId}
                 AND p.archived_at IS NULL) AS person_count,
              (SELECT COUNT(*) FROM deal d
               WHERE d.workspace_id = #{workspaceId} AND d.company_id = #{companyId}) AS num_deals,
              (SELECT COUNT(*) FROM activity a
               WHERE a.workspace_id = #{workspaceId}
                 AND (EXISTS (SELECT 1 FROM person p
                     WHERE p.workspace_id = #{workspaceId} AND p.id = a.person_id AND p.company_id = #{companyId}
                       AND p.archived_at IS NULL)
                   OR EXISTS (SELECT 1 FROM deal d
                       WHERE d.workspace_id = #{workspaceId} AND d.id = a.deal_id AND d.company_id = #{companyId})))
                AS num_activities,
              (SELECT COUNT(*) FROM task t
               WHERE t.workspace_id = #{workspaceId}
                 AND (EXISTS (SELECT 1 FROM person p
                     WHERE p.workspace_id = #{workspaceId} AND p.id = t.person_id AND p.company_id = #{companyId}
                       AND p.archived_at IS NULL)
                   OR EXISTS (SELECT 1 FROM deal d
                       WHERE d.workspace_id = #{workspaceId} AND d.id = t.deal_id AND d.company_id = #{companyId})))
                AS num_tasks,
              (SELECT COUNT(*) FROM task t
               WHERE t.workspace_id = #{workspaceId} AND t.completed = FALSE
                 AND (EXISTS (SELECT 1 FROM person p
                     WHERE p.workspace_id = #{workspaceId} AND p.id = t.person_id AND p.company_id = #{companyId}
                       AND p.archived_at IS NULL)
                   OR EXISTS (SELECT 1 FROM deal d
                       WHERE d.workspace_id = #{workspaceId} AND d.id = t.deal_id AND d.company_id = #{companyId})))
                AS open_tasks,
              (SELECT COUNT(*) FROM note n
               WHERE n.workspace_id = #{workspaceId} AND n.visibility = 'workspace'
                 AND (EXISTS (SELECT 1 FROM person p
                     WHERE p.workspace_id = #{workspaceId} AND p.id = n.person_id AND p.company_id = #{companyId}
                       AND p.archived_at IS NULL)
                   OR EXISTS (SELECT 1 FROM deal d
                       WHERE d.workspace_id = #{workspaceId} AND d.id = n.deal_id AND d.company_id = #{companyId})))
                AS num_notes
          </select>

          <select id="getCompanyEngagementUsers"
                  resultType="ooo.klae.connex.backend.dto.CompanyEngagementUserDto">
            SELECT related.user_id, COUNT(*) OVER() AS total_users
            FROM (
              SELECT touch.user_id, MAX(touch.touched_at) AS last_touched_at
              FROM (
                SELECT a.created_by_id AS user_id, a.timestamp AS touched_at
                FROM activity a
                WHERE a.workspace_id = #{workspaceId}
                  AND (EXISTS (SELECT 1 FROM person p
                      WHERE p.workspace_id = #{workspaceId} AND p.id = a.person_id AND p.company_id = #{companyId}
                        AND p.archived_at IS NULL)
                    OR EXISTS (SELECT 1 FROM deal d
                        WHERE d.workspace_id = #{workspaceId} AND d.id = a.deal_id AND d.company_id = #{companyId}))
                UNION ALL
                SELECT t.assigned_to_id AS user_id, t.created_at AS touched_at
                FROM task t
                WHERE t.workspace_id = #{workspaceId}
                  AND (EXISTS (SELECT 1 FROM person p
                      WHERE p.workspace_id = #{workspaceId} AND p.id = t.person_id AND p.company_id = #{companyId}
                        AND p.archived_at IS NULL)
                    OR EXISTS (SELECT 1 FROM deal d
                        WHERE d.workspace_id = #{workspaceId} AND d.id = t.deal_id AND d.company_id = #{companyId}))
                UNION ALL
                SELECT n.author_id AS user_id, n.created_at AS touched_at
                FROM note n
                WHERE n.workspace_id = #{workspaceId} AND n.visibility = 'workspace'
                  AND (EXISTS (SELECT 1 FROM person p
                      WHERE p.workspace_id = #{workspaceId} AND p.id = n.person_id AND p.company_id = #{companyId}
                        AND p.archived_at IS NULL)
                    OR EXISTS (SELECT 1 FROM deal d
                        WHERE d.workspace_id = #{workspaceId} AND d.id = n.deal_id AND d.company_id = #{companyId}))
              ) touch
              WHERE touch.user_id IS NOT NULL
              GROUP BY touch.user_id
            ) related
            ORDER BY related.last_touched_at DESC, related.user_id
            LIMIT #{limit}
          </select>

          <select id="getCompanyEngagementWeeks"
                  resultType="ooo.klae.connex.backend.dto.CompanyEngagementWeekBucketDto">
            SELECT FLOOR(TIMESTAMPDIFF(SECOND, #{windowStart}, touch.touched_at) / 604800) AS bucket_index,
                   SUM(touch.kind = 'activities') AS activities,
                   SUM(touch.kind = 'tasks') AS tasks,
                   SUM(touch.kind = 'notes') AS notes
            FROM (
              SELECT 'activities' AS kind, a.timestamp AS touched_at
              FROM activity a
              WHERE a.workspace_id = #{workspaceId}
                AND a.timestamp &gt;= #{windowStart} AND a.timestamp &lt;= #{windowEnd}
                AND (EXISTS (SELECT 1 FROM person p
                    WHERE p.workspace_id = #{workspaceId} AND p.id = a.person_id AND p.company_id = #{companyId}
                      AND p.archived_at IS NULL)
                  OR EXISTS (SELECT 1 FROM deal d
                      WHERE d.workspace_id = #{workspaceId} AND d.id = a.deal_id AND d.company_id = #{companyId}))
              UNION ALL
              SELECT 'tasks' AS kind, t.created_at AS touched_at
              FROM task t
              WHERE t.workspace_id = #{workspaceId}
                AND t.created_at &gt;= #{windowStart} AND t.created_at &lt;= #{windowEnd}
                AND (EXISTS (SELECT 1 FROM person p
                    WHERE p.workspace_id = #{workspaceId} AND p.id = t.person_id AND p.company_id = #{companyId}
                      AND p.archived_at IS NULL)
                  OR EXISTS (SELECT 1 FROM deal d
                      WHERE d.workspace_id = #{workspaceId} AND d.id = t.deal_id AND d.company_id = #{companyId}))
              UNION ALL
              SELECT 'notes' AS kind, n.created_at AS touched_at
              FROM note n
              WHERE n.workspace_id = #{workspaceId} AND n.visibility = 'workspace'
                AND n.created_at &gt;= #{windowStart} AND n.created_at &lt;= #{windowEnd}
                AND (EXISTS (SELECT 1 FROM person p
                    WHERE p.workspace_id = #{workspaceId} AND p.id = n.person_id AND p.company_id = #{companyId}
                      AND p.archived_at IS NULL)
                  OR EXISTS (SELECT 1 FROM deal d
                      WHERE d.workspace_id = #{workspaceId} AND d.id = n.deal_id AND d.company_id = #{companyId}))
            ) touch
            GROUP BY bucket_index
            ORDER BY bucket_index
          </select>

          <select id="getCompanyRevenueByCurrency"
                  resultType="ooo.klae.connex.backend.dto.CompanyRevenueCurrencyDto">
            SELECT COALESCE(NULLIF(d.currency, ''), 'USD') AS currency,
                   COUNT(*) AS deal_count,
                   COALESCE(SUM(CASE WHEN d.won = TRUE THEN d.actual_value ELSE 0 END), 0) AS past_revenue,
                   COALESCE(SUM(CASE WHEN d.won IS NULL THEN d.value ELSE 0 END), 0) AS projected_revenue
            FROM deal d
            WHERE d.workspace_id = #{workspaceId} AND d.company_id = #{companyId}
            GROUP BY COALESCE(NULLIF(d.currency, ''), 'USD')
            ORDER BY deal_count DESC, currency
          </select>

          <select id="getSegmentCompaniesPage" resultType="Company">
            SELECT <include refid="qualifiedCompanyColumns"/>
            FROM company c
            JOIN JSON_TABLE(#{segmentIdsJson}, '$[*]' COLUMNS(id INT PATH '$')) segment_ids
              ON segment_ids.id = c.id
            <include refid="warmthJoin"/>
            <include refid="segmentPageWhere"/>
            <include refid="memberScopePredicate"/>
            <include refid="pageOrderBy"/>
            LIMIT #{limit} OFFSET #{offset}
          </select>

          <select id="countSegmentCompanies" resultType="long">
            SELECT COUNT(*)
            FROM company c
            JOIN JSON_TABLE(#{segmentIdsJson}, '$[*]' COLUMNS(id INT PATH '$')) segment_ids
              ON segment_ids.id = c.id
            <include refid="warmthFilterJoin"/>
            <include refid="segmentPageWhere"/>
            <include refid="memberScopePredicate"/>
          </select>

          <select id="getSegmentCompanyIdsFiltered" resultType="int">
            SELECT c.id
            FROM company c
            JOIN JSON_TABLE(#{segmentIdsJson}, '$[*]' COLUMNS(id INT PATH '$')) segment_ids
              ON segment_ids.id = c.id
            <include refid="warmthFilterJoin"/>
            <include refid="segmentPageWhere"/>
            <include refid="memberScopePredicate"/>
            ORDER BY c.id
            LIMIT #{limit}
          </select>

          <select id="getCompanyIdsFiltered" resultType="int">
            SELECT c.id
            FROM company c
            <include refid="warmthFilterJoin"/>
            <include refid="pageWhere"/>
            <include refid="memberScopePredicate"/>
            ORDER BY c.id
            LIMIT #{limit} OFFSET #{offset}
          </select>

          <select id="getCompaniesFiltered" resultType="Company">
            SELECT <include refid="qualifiedCompanyColumns"/>
            FROM company c
            <include refid="warmthFilterJoin"/>
            <include refid="pageWhere"/>
            <include refid="memberScopePredicate"/>
            ORDER BY c.name, c.id
          </select>

          <!-- Aggregates every visible company's attributed decayed touch history, so it costs a full
               workspace scan of activity, note, and task rows. It is opt-in on the facets endpoint. -->
          <select id="countsByWarmthBand" timeout="10"
                  resultType="ooo.klae.connex.backend.dto.FacetCount">
            SELECT <include refid="warmthBandKey"/> AS `key`,
                   COUNT(*) AS count, NULL AS label
            FROM company c
            <include refid="warmthJoin"/>
            WHERE <include refid="visible"/>
            GROUP BY `key`
          </select>

          <select id="distinctIndustries" resultType="string">
            SELECT DISTINCT c.industry
            FROM company c
            WHERE <include refid="visible"/> AND c.industry IS NOT NULL AND c.industry &lt;&gt; ''
            ORDER BY c.industry
          </select>

          <select id="hasCompanyWithoutIndustry" resultType="boolean">
            SELECT EXISTS(
              SELECT 1
              FROM company c
              WHERE <include refid="visible"/> AND (c.industry IS NULL OR c.industry = '')
            )
          </select>

          <select id="countsByOwner" resultType="ooo.klae.connex.backend.dto.FacetCount">
            SELECT COALESCE(CAST(c.owner_id AS CHAR), '__empty__') AS `key`,
                   COUNT(*) AS count, NULL AS label
            FROM company c
            WHERE c.workspace_id = #{workspaceId} AND c.archived_at IS NULL
            GROUP BY c.owner_id
          </select>

          <select id="countArchivedCompanies" resultType="long">
            SELECT COUNT(*) FROM company c
            WHERE c.workspace_id = #{workspaceId} AND c.archived_at IS NOT NULL
          </select>

          <select id="getCompaniesByTagId" resultType="Company">
            SELECT
              c.id, c.workspace_id, c.owner_id, c.name, c.website, c.industry, c.phone, c.address,
              c.created_at, c.updated_at, c.logo_url, c.archived_at
            FROM company c
            JOIN company_tag ct ON ct.company_id = c.id
            WHERE (c.workspace_id = #{workspaceId}
                   OR EXISTS (SELECT 1 FROM company_share cs
                      JOIN workspace vws ON vws.id = cs.workspace_id
                      JOIN workspace ows ON ows.id = c.workspace_id AND ows.org_id = vws.org_id
                      WHERE cs.company_id = c.id AND cs.workspace_id = #{workspaceId}))
              AND ct.tag_id = #{tagId}
              AND c.archived_at IS NULL
            ORDER BY c.name, c.id
          </select>

          <select id="getCompanyById" resultType="Company">
            SELECT <include refid="qualifiedCompanyColumns"/>
            FROM company c WHERE <include refid="visible"/> AND c.id = #{id}
          </select>

          <select id="getVisibleCompanyByIdForUpdate" resultType="Company">
            SELECT <include refid="qualifiedCompanyColumns"/>
            FROM company c WHERE <include refid="visible"/> AND c.id = #{id}
            FOR UPDATE
          </select>

          <!-- flushCache is required: a caller may read this company through getCompanyById before the
               lock and re-check it after, and a cached pre-lock answer would defeat that recheck. -->
          <select id="getOwnedCompanyByIdForUpdate" resultType="Company" flushCache="true">
            SELECT <include refid="qualifiedCompanyColumns"/>
            FROM company c
            WHERE c.workspace_id = #{workspaceId} AND c.id = #{id}
            FOR UPDATE
          </select>

          <select id="getOwnedArchivedCompanyById" resultType="Company">
            SELECT <include refid="qualifiedCompanyColumns"/>
            FROM company c
            WHERE c.workspace_id = #{workspaceId} AND c.id = #{id}
              AND c.archived_at IS NOT NULL
          </select>

          <select id="getCompaniesWithWebsite" resultType="Company">
            SELECT id, website FROM company
            WHERE workspace_id = #{workspaceId} AND website IS NOT NULL AND website != ''
              AND archived_at IS NULL
          </select>

          <select id="getCompaniesForDedup" resultType="Company">
            SELECT id, name, website FROM company
            WHERE workspace_id = #{workspaceId} AND archived_at IS NULL
          </select>

          <select id="getByIds" resultType="Company">
            SELECT <include refid="qualifiedCompanyColumns"/> FROM company c
            WHERE <include refid="visible"/> AND c.id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY c.name, c.id
          </select>

          <select id="exists" resultType="boolean">
            SELECT EXISTS(
              SELECT 1 FROM company c WHERE <include refid="visible"/> AND c.id = #{id}
            )
          </select>

          <select id="getVisibleIdsIn" resultType="int">
            SELECT c.id
            FROM company c
            WHERE <include refid="visible"/>
              AND c.id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY c.id
          </select>
          <select id="existsOwned" resultType="boolean">
            SELECT EXISTS(SELECT 1 FROM company
              WHERE workspace_id = #{workspaceId} AND id = #{id} AND archived_at IS NULL)
          </select>
          <select id="existsOwnedArchived" resultType="boolean">
            SELECT EXISTS(SELECT 1 FROM company
              WHERE workspace_id = #{workspaceId} AND id = #{id} AND archived_at IS NOT NULL)
          </select>

          <select id="search" resultType="Company">
            SELECT <include refid="qualifiedCompanyColumns"/> FROM company c
            WHERE <include refid="visible"/>
              AND (c.name LIKE #{query} OR c.website LIKE #{query} OR c.industry LIKE #{query} OR c.phone LIKE #{query} OR c.address LIKE #{query})
            ORDER BY c.name, c.id
            LIMIT 10
          </select>

          <insert id="insert" useGeneratedKeys="true" keyProperty="id">
            INSERT INTO company (workspace_id, owner_id, name, ai_identifier_literal_key, ai_identifier_label_key,
              ai_identifier_source_name, ai_identifier_key_version, website, industry, phone, address, logo_url, created_at)
            VALUES (#{workspaceId}, #{ownerId,jdbcType=INTEGER}, CONVERT(#{name,jdbcType=VARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierUtf8TextTypeHandler} USING utf8mb4),
              CONVERT(#{name,jdbcType=LONGVARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierLiteralKeyTypeHandler} USING utf8mb4),
              CONVERT(#{name,jdbcType=LONGVARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierLabelKeyTypeHandler} USING utf8mb4),
              CONVERT(#{name,jdbcType=VARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierUtf8TextTypeHandler} USING utf8mb4), 3, #{website}, #{industry},
              #{phone}, #{address}, #{logoUrl}, COALESCE(#{createdAt}, CURRENT_TIMESTAMP))
          </insert>

          <insert id="insertBatch" useGeneratedKeys="true" keyProperty="id">
            INSERT INTO company (workspace_id, owner_id, name, ai_identifier_literal_key, ai_identifier_label_key,
              ai_identifier_source_name, ai_identifier_key_version, website, industry, phone, address, logo_url)
            VALUES
            <foreach collection="list" item="c" separator=",">
              (#{c.workspaceId}, #{c.ownerId,jdbcType=INTEGER}, CONVERT(#{c.name,jdbcType=VARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierUtf8TextTypeHandler} USING utf8mb4),
              CONVERT(#{c.name,jdbcType=LONGVARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierLiteralKeyTypeHandler} USING utf8mb4),
              CONVERT(#{c.name,jdbcType=LONGVARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierLabelKeyTypeHandler} USING utf8mb4),
              CONVERT(#{c.name,jdbcType=VARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierUtf8TextTypeHandler} USING utf8mb4), 3, #{c.website}, #{c.industry},
               #{c.phone}, #{c.address}, #{c.logoUrl})
            </foreach>
          </insert>

          <update id="update">
            UPDATE company
            SET name = CONVERT(#{name,jdbcType=VARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierUtf8TextTypeHandler} USING utf8mb4),
                ai_identifier_literal_key = CONVERT(#{name,jdbcType=LONGVARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierLiteralKeyTypeHandler} USING utf8mb4),
                ai_identifier_label_key = CONVERT(#{name,jdbcType=LONGVARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierLabelKeyTypeHandler} USING utf8mb4),
                ai_identifier_source_name = CONVERT(#{name,jdbcType=VARCHAR,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierUtf8TextTypeHandler} USING utf8mb4),
                ai_identifier_key_version = 3,
                website = #{website},
                industry = #{industry},
                phone = #{phone},
                address = #{address}
            WHERE workspace_id = #{workspaceId} AND id = #{id}
          </update>

          <update id="updateOwner">
            UPDATE company
            SET owner_id = #{ownerId,jdbcType=INTEGER}
            WHERE workspace_id = #{workspaceId} AND id = #{id}
          </update>

          <select id="lockById" resultType="java.lang.Integer">
            SELECT id FROM company
            WHERE workspace_id = #{workspaceId} AND id = #{id}
            FOR UPDATE
          </select>

          <update id="updateLogoUrlIfCurrent">
            UPDATE company SET logo_url = #{logoUrl}
            WHERE workspace_id = #{workspaceId} AND id = #{id}
              AND logo_url &lt;=&gt; #{currentLogoUrl}
          </update>

          <update id="clearMemberOwnership">
            UPDATE company SET owner_id = NULL
            WHERE workspace_id = #{workspaceId} AND owner_id = #{userId}
          </update>

          <update id="clearOwnershipAnywhere">
            UPDATE company SET owner_id = NULL WHERE owner_id = #{userId}
          </update>

          <update id="archive">
            UPDATE company SET archived_at = CURRENT_TIMESTAMP
            WHERE workspace_id = #{workspaceId} AND id = #{id} AND archived_at IS NULL
          </update>

          <update id="restore">
            UPDATE company SET archived_at = NULL
            WHERE workspace_id = #{workspaceId} AND id = #{id} AND archived_at IS NOT NULL
          </update>

          <insert id="addTag">
            INSERT IGNORE INTO company_tag (company_id, tag_id)
            SELECT c.id, #{tagId}
            FROM company c
            WHERE c.workspace_id = #{workspaceId} AND c.id = #{companyId}
          </insert>

          <delete id="removeTag">
            DELETE ct
            FROM company_tag ct
            JOIN company c ON c.id = ct.company_id
            WHERE c.workspace_id = #{workspaceId} AND ct.company_id = #{companyId} AND ct.tag_id = #{tagId}
          </delete>

          <delete id="clearTags">
            DELETE ct
            FROM company_tag ct
            JOIN company c ON c.id = ct.company_id
            WHERE c.workspace_id = #{workspaceId} AND ct.company_id = #{companyId}
          </delete>

          <insert id="insertTags">
            INSERT IGNORE INTO company_tag (company_id, tag_id)
            SELECT c.id, t.id
            FROM company c
            JOIN tag t ON t.id IN
            <foreach collection="tagIds" item="tagId" open="(" separator="," close=")">
              #{tagId}
            </foreach>
            WHERE c.workspace_id = #{workspaceId} AND c.id = #{companyId} AND t.workspace_id = #{workspaceId}
          </insert>
        </mapper>
        """;
    private static final String DEAL = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="ooo.klae.connex.backend.mappers.DealMapper">
          <resultMap id="DealResult" type="Deal">
            <id property="id" column="id"/>
            <result property="workspaceId" column="workspace_id"/>
            <result property="ownerId" column="owner_id"/>
            <result property="name" column="name"/>
            <result property="value" column="value" javaType="java.math.BigDecimal" jdbcType="DECIMAL"/>
            <result property="actualValue" column="actual_value" javaType="java.math.BigDecimal" jdbcType="DECIMAL"/>
            <result property="valueSource" column="value_source"/>
            <result property="currency" column="currency"/>
            <result property="pipelineId" column="pipeline_id"/>
            <result property="stageId" column="stage_id"/>
            <result property="position" column="position"/>
            <result property="companyId" column="company_id"/>
            <result property="expectedCloseDate" column="expected_close_date"/>
            <result property="closedAt" column="closed_at"/>
            <result property="closedReason" column="closed_reason"/>
            <result property="won" column="won"/>
            <result property="riskExcluded" column="risk_excluded"/>
            <result property="createdAt" column="created_at"/>
            <result property="updatedAt" column="updated_at"/>
          </resultMap>

          <resultMap id="DealPersonResult" type="DealPerson">
            <id column="deal_person_pid"/>
            <result property="role" column="role"/>
            <association property="person" javaType="Person"
                         resultMap="FrozenDealPersonResult"/>
          </resultMap>

          <resultMap id="DealStakeholderResult" type="DealStakeholder">
            <result property="dealId" column="deal_id"/>
            <result property="personId" column="person_id"/>
            <result property="personLabel" column="person_label"/>
            <result property="role" column="role"/>
            <result property="riskExcluded" column="person_risk_excluded"/>
          </resultMap>

          <resultMap id="FrozenDealPersonResult" type="Person">
            <id     property="id"        column="id"/>
            <result property="workspaceId" column="workspace_id"/>
            <result property="ownerId" column="owner_id"/>
            <result property="name"      column="name"/>
            <result property="email"     column="email"/>
            <result property="phone"     column="phone"/>
            <result property="title"     column="title"/>
            <result property="createdAt" column="created_at"/>
            <result property="updatedAt" column="updated_at"/>
            <result property="imageUrl" column="image_url"/>
            <result property="riskExcluded" column="risk_excluded"/>
            <result property="introExcluded" column="intro_excluded"/>
            <result property="suspendedAt" column="suspended_at"/>
            <result property="provisionCeasedAt" column="provision_ceased_at"/>
            <result property="archivedAt" column="archived_at"/>
            <result property="lifecycleStage" column="lifecycle_stage"/>
            <result property="lifecycleChangedAt" column="lifecycle_changed_at"/>
            <result property="disqualifiedReason" column="disqualified_reason" javaType="java.lang.String"/>
            <result property="qualificationNotes" column="qualification_notes"/>
            <result property="leadSource" column="lead_source"/>
            <result property="leadSourceDetail" column="lead_source_detail"/>
            <result property="referrerPersonId" column="referrer_person_id"/>
            <result property="firstResponseDueAt" column="first_response_due_at"/>
            <result property="firstRespondedAt" column="first_responded_at"/>
            <result property="firstResponseBreachedAt" column="first_response_breached_at"/>
            <association property="company" javaType="Company" notNullColumn="company_id">
              <id property="id" column="company_id"/>
              <result property="name" column="company_name"/>
              <result property="logoUrl" column="company_logo_url"/>
            </association>
          </resultMap>

          <sql id="qualifiedDealColumns">
            d.id, d.workspace_id, d.owner_id, d.name, d.value, d.actual_value, d.value_source, d.currency,
            d.pipeline_id, d.stage_id, d.position, d.company_id, d.expected_close_date, d.closed_at,
            d.closed_reason, d.won, d.risk_excluded, d.created_at, d.updated_at
          </sql>

          <sql id="visibleCompanyJoin">
            (c.workspace_id = #{workspaceId}
             OR EXISTS (SELECT 1 FROM company_share cs
                JOIN workspace vws ON vws.id = cs.workspace_id
                JOIN workspace ows ON ows.id = c.workspace_id AND ows.org_id = vws.org_id
                WHERE cs.company_id = c.id AND cs.workspace_id = #{workspaceId}))
            AND c.archived_at IS NULL
          </sql>

          <sql id="visiblePersonJoin">
            (p.workspace_id = #{workspaceId}
             OR EXISTS (SELECT 1 FROM person_share ps
                JOIN workspace vws ON vws.id = ps.workspace_id
                JOIN workspace ows ON ows.id = p.workspace_id AND ows.org_id = vws.org_id
                WHERE ps.person_id = p.id AND ps.workspace_id = #{workspaceId}))
            AND p.archived_at IS NULL
            AND p.suspended_at IS NULL
            AND p.provision_ceased_at IS NULL
          </sql>

          <sql id="visibleFilteredPersonJoin">
            (filtered_person.workspace_id = #{workspaceId}
             OR EXISTS (SELECT 1 FROM person_share filtered_ps
                JOIN workspace vws ON vws.id = filtered_ps.workspace_id
                JOIN workspace ows
                  ON ows.id = filtered_person.workspace_id
                 AND ows.org_id = vws.org_id
                WHERE filtered_ps.person_id = filtered_person.id
                  AND filtered_ps.workspace_id = #{workspaceId}))
            AND filtered_person.archived_at IS NULL
            AND filtered_person.suspended_at IS NULL
            AND filtered_person.provision_ceased_at IS NULL
          </sql>

          <sql id="visiblePipelineJoin">
            (p.workspace_id = #{workspaceId}
             OR EXISTS (SELECT 1 FROM pipeline_share ps
                JOIN workspace vws ON vws.id = ps.workspace_id
                JOIN workspace ows ON ows.id = p.workspace_id AND ows.org_id = vws.org_id
                WHERE ps.pipeline_id = p.id AND ps.workspace_id = #{workspaceId}))
          </sql>

          <sql id="visibleStageJoin">
            EXISTS (SELECT 1
              FROM pipeline sp
              WHERE sp.id = s.pipeline_id AND sp.workspace_id = s.workspace_id
                AND (sp.workspace_id = #{workspaceId}
                  OR EXISTS (SELECT 1 FROM pipeline_share ps
                    JOIN workspace vws ON vws.id = ps.workspace_id
                    JOIN workspace ows ON ows.id = sp.workspace_id AND ows.org_id = vws.org_id
                    WHERE ps.pipeline_id = sp.id AND ps.workspace_id = #{workspaceId})))
          </sql>

          <sql id="memberScopePredicate">
            <choose>
              <when test="memberScope.mode.name() == 'ME'">
                AND d.owner_id = #{memberScope.userId}
              </when>
              <when test="memberScope.mode.name() == 'MEMBERS'">
                AND d.owner_id IN
                <foreach item="id" collection="memberScope.memberIds" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <when test="memberScope.mode.name() == 'UNASSIGNED'">
                AND d.owner_id IS NULL
              </when>
            </choose>
          </sql>

          <sql id="dealListFilterWhere">
            <where>
              AND d.workspace_id = #{workspaceId}
              <include refid="memberScopePredicate"/>
              <if test="query != null">
                AND (d.name LIKE #{query}
                  OR d.currency LIKE #{query}
                  OR c.name LIKE #{query}
                  OR p.name LIKE #{query}
                  OR s.name LIKE #{query})
              </if>
              <if test="currency != null">
                AND d.currency = #{currency}
              </if>
              <if test="pipelineIds != null and pipelineIds.size() > 0">
                AND d.pipeline_id IN
                <foreach item="id" collection="pipelineIds" open="(" separator="," close=")">#{id}</foreach>
              </if>
              <if test="stageIds != null and stageIds.size() > 0">
                AND d.stage_id IN
                <foreach item="id" collection="stageIds" open="(" separator="," close=")">#{id}</foreach>
              </if>
              <if test="(companyIds != null and companyIds.size() > 0) or noCompany">
                AND (
                  <if test="companyIds != null and companyIds.size() > 0">
                    d.company_id IN
                    <foreach item="id" collection="companyIds" open="(" separator="," close=")">#{id}</foreach>
                  </if>
                  <if test="noCompany">
                    <if test="companyIds != null and companyIds.size() > 0">OR</if>
                    c.id IS NULL
                  </if>
                )
              </if>
              <if test="personIds != null and personIds.size() > 0">
                AND EXISTS (
                  SELECT 1
                  FROM deal_person filtered_dp
                  JOIN person filtered_person ON filtered_person.id = filtered_dp.person_id
                    AND <include refid="visibleFilteredPersonJoin"/>
                  WHERE filtered_dp.deal_id = d.id
                    AND filtered_dp.person_id IN
                    <foreach item="id" collection="personIds" open="(" separator="," close=")">#{id}</foreach>
                )
              </if>
              <if test="statuses != null and statuses.size() > 0">
                AND CASE WHEN d.won IS NULL THEN 'open' WHEN d.won THEN 'won' ELSE 'lost' END IN
                <foreach item="value" collection="statuses" open="(" separator="," close=")">#{value}</foreach>
              </if>
              <if test="riskIds != null and riskIds.size() > 0">
                AND d.id IN
                <foreach item="id" collection="riskIds" open="(" separator="," close=")">#{id}</foreach>
              </if>
            </where>
          </sql>

          <sql id="segmentIdsJoin">
            <if test="segmentIdsJson != null">
              JOIN JSON_TABLE(#{segmentIdsJson}, '$[*]' COLUMNS(id INT PATH '$')) segment_ids
                ON segment_ids.id = d.id
            </if>
          </sql>

          <sql id="dealSortDirection">
            <choose>
              <when test="dir == 'desc'">DESC</when>
              <otherwise>ASC</otherwise>
            </choose>
          </sql>

          <sql id="dealPageOrderBy">
            <choose>
              <when test="sort == 'name'">ORDER BY d.name <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'value'">ORDER BY d.value <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'actual_value'">ORDER BY d.actual_value <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'expected_close_date'">ORDER BY d.expected_close_date <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'closed_at'">ORDER BY d.closed_at <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'created_at'">ORDER BY d.created_at <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'updated_at'">ORDER BY d.updated_at <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'stage_id'">ORDER BY d.stage_id <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'position'">ORDER BY d.position <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'company'">ORDER BY c.name <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'pipeline'">ORDER BY p.name <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'stage'">ORDER BY s.name <include refid="dealSortDirection"/>, d.id ASC</when>
              <when test="sort == 'status'">ORDER BY (d.won IS NOT NULL) <include refid="dealSortDirection"/>, d.id ASC</when>
              <otherwise>ORDER BY d.stage_id, d.position, d.id</otherwise>
            </choose>
          </sql>

          <select id="getDealPeopleByDealId" resultMap="DealPersonResult">
            SELECT dp.person_id AS deal_person_pid,
                   p.id, p.workspace_id, p.owner_id, p.name, p.email, p.phone, p.title, p.created_at, p.updated_at,
                   p.image_url, p.risk_excluded, p.intro_excluded, p.suspended_at, p.provision_ceased_at,
                   c.id AS company_id, c.name AS company_name, c.logo_url AS company_logo_url,
                   dp.role
            FROM deal_person dp
            JOIN deal d ON d.id = dp.deal_id
            JOIN person p ON p.id = dp.person_id AND <include refid="visiblePersonJoin"/>
            LEFT JOIN company c ON c.id = p.company_id AND <include refid="visibleCompanyJoin"/>
            WHERE d.workspace_id = #{workspaceId} AND dp.deal_id = #{dealId}
            ORDER BY p.name
          </select>

          <select id="getPrimaryContactsByDealIds"
                  resultType="ooo.klae.connex.backend.dto.DealPrimaryContactDto">
            SELECT ranked.deal_id, ranked.person_id, ranked.name, ranked.image_url
            FROM (
              SELECT dp.deal_id, p.id AS person_id, p.name, p.image_url,
                     ROW_NUMBER() OVER (PARTITION BY dp.deal_id ORDER BY p.name, p.id) AS position
              FROM deal_person dp
              JOIN deal d ON d.id = dp.deal_id AND d.workspace_id = #{workspaceId}
              JOIN person p ON p.id = dp.person_id AND <include refid="visiblePersonJoin"/>
              WHERE dp.deal_id IN
              <foreach item="id" collection="dealIds" open="(" separator="," close=")">#{id}</foreach>
            ) ranked
            WHERE ranked.position = 1
            ORDER BY ranked.deal_id
          </select>

          <select id="getAllDealStakeholders" resultMap="DealStakeholderResult">
            SELECT dp.deal_id AS deal_id,
                   p.id AS person_id,
                   p.name AS person_label,
                   dp.role AS role,
                   p.risk_excluded AS person_risk_excluded
            FROM deal_person dp
            JOIN deal d ON d.id = dp.deal_id AND d.workspace_id = #{workspaceId}
            JOIN person p ON p.id = dp.person_id AND <include refid="visiblePersonJoin"/>
            WHERE d.workspace_id = #{workspaceId}
            ORDER BY dp.deal_id, p.name
          </select>

          <select id="getDealStakeholdersByDealId" resultMap="DealStakeholderResult">
            SELECT dp.deal_id AS deal_id,
                   p.id AS person_id,
                   p.name AS person_label,
                   dp.role AS role,
                   p.risk_excluded AS person_risk_excluded
            FROM deal_person dp
            JOIN deal d ON d.id = dp.deal_id AND d.workspace_id = #{workspaceId}
            JOIN person p ON p.id = dp.person_id AND <include refid="visiblePersonJoin"/>
            WHERE d.workspace_id = #{workspaceId} AND dp.deal_id = #{dealId}
            ORDER BY p.name
          </select>

          <select id="getDealStakeholdersByDealIds" resultMap="DealStakeholderResult">
            SELECT dp.deal_id AS deal_id,
                   p.id AS person_id,
                   p.name AS person_label,
                   dp.role AS role,
                   p.risk_excluded AS person_risk_excluded
            FROM deal_person dp
            JOIN deal d ON d.id = dp.deal_id AND d.workspace_id = #{workspaceId}
            JOIN person p ON p.id = dp.person_id AND <include refid="visiblePersonJoin"/>
            WHERE d.workspace_id = #{workspaceId}
              AND dp.deal_id IN
              <foreach item="id" collection="dealIds" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY dp.deal_id, p.name
          </select>

          <select id="getDealsPageFiltered" resultMap="DealResult">
            SELECT <include refid="qualifiedDealColumns"/>
            FROM deal d
            <include refid="segmentIdsJoin"/>
            LEFT JOIN company c ON c.id = d.company_id AND <include refid="visibleCompanyJoin"/>
            LEFT JOIN pipeline p ON p.id = d.pipeline_id AND <include refid="visiblePipelineJoin"/>
            LEFT JOIN stage s ON s.id = d.stage_id AND s.pipeline_id = d.pipeline_id
              AND <include refid="visibleStageJoin"/>
            <include refid="dealListFilterWhere"/>
            <include refid="dealPageOrderBy"/>
            LIMIT #{limit} OFFSET #{offset}
          </select>

          <select id="getDealsFiltered" resultMap="DealResult">
            SELECT <include refid="qualifiedDealColumns"/>
            FROM deal d
            <include refid="segmentIdsJoin"/>
            LEFT JOIN company c ON c.id = d.company_id AND <include refid="visibleCompanyJoin"/>
            LEFT JOIN pipeline p ON p.id = d.pipeline_id AND <include refid="visiblePipelineJoin"/>
            LEFT JOIN stage s ON s.id = d.stage_id AND s.pipeline_id = d.pipeline_id
              AND <include refid="visibleStageJoin"/>
            <include refid="dealListFilterWhere"/>
            ORDER BY d.id
          </select>

          <select id="countDealsFiltered" resultType="long">
            SELECT COUNT(*)
            FROM deal d
            <include refid="segmentIdsJoin"/>
            LEFT JOIN company c ON c.id = d.company_id AND <include refid="visibleCompanyJoin"/>
            LEFT JOIN pipeline p ON p.id = d.pipeline_id AND <include refid="visiblePipelineJoin"/>
            LEFT JOIN stage s ON s.id = d.stage_id AND s.pipeline_id = d.pipeline_id
              AND <include refid="visibleStageJoin"/>
            <include refid="dealListFilterWhere"/>
          </select>

          <select id="dealMetricsFiltered" resultType="ooo.klae.connex.backend.dto.DealCurrencyMetricsDto">
            SELECT d.currency AS currency,
                   COUNT(CASE WHEN d.won IS NULL THEN 1 END) AS open_count,
                   COALESCE(SUM(CASE WHEN d.won IS NULL THEN d.value END), 0) AS open_value,
                   COUNT(CASE WHEN d.won IS NOT NULL THEN 1 END) AS closed_count,
                   COALESCE(SUM(CASE WHEN d.won IS NOT NULL THEN d.value END), 0) AS closed_forecast,
                   COALESCE(SUM(CASE WHEN d.won IS NOT NULL THEN d.actual_value END), 0) AS closed_revenue,
                   COUNT(CASE WHEN d.won = TRUE THEN 1 END) AS won_count,
                   COUNT(CASE WHEN d.won = FALSE THEN 1 END) AS lost_count
            FROM deal d
            <include refid="segmentIdsJoin"/>
            LEFT JOIN company c ON c.id = d.company_id AND <include refid="visibleCompanyJoin"/>
            LEFT JOIN pipeline p ON p.id = d.pipeline_id AND <include refid="visiblePipelineJoin"/>
            LEFT JOIN stage s ON s.id = d.stage_id AND s.pipeline_id = d.pipeline_id
              AND <include refid="visibleStageJoin"/>
            <include refid="dealListFilterWhere"/>
            GROUP BY d.currency
            ORDER BY d.currency
          </select>

          <select id="getFilteredDealIds" resultType="int">
            SELECT d.id
            FROM deal d
            <include refid="segmentIdsJoin"/>
            LEFT JOIN company c ON c.id = d.company_id AND <include refid="visibleCompanyJoin"/>
            LEFT JOIN pipeline p ON p.id = d.pipeline_id AND <include refid="visiblePipelineJoin"/>
            LEFT JOIN stage s ON s.id = d.stage_id AND s.pipeline_id = d.pipeline_id
              AND <include refid="visibleStageJoin"/>
            <include refid="dealListFilterWhere"/>
            ORDER BY d.id
            LIMIT #{limit}
          </select>

          <select id="countsByCompany" resultType="ooo.klae.connex.backend.dto.FacetCount">
            SELECT CAST(d.company_id AS CHAR) AS `key`, COUNT(*) AS count, c.name AS label
            FROM deal d
            JOIN company c ON c.id = d.company_id AND <include refid="visibleCompanyJoin"/>
            WHERE d.workspace_id = #{workspaceId}
              AND d.company_id IS NOT NULL
            GROUP BY d.company_id, c.name
            UNION ALL
            SELECT '__empty__' AS `key`, COUNT(*) AS count, NULL AS label
            FROM deal d
            LEFT JOIN company c ON c.id = d.company_id AND <include refid="visibleCompanyJoin"/>
            WHERE d.workspace_id = #{workspaceId}
              AND c.id IS NULL
            HAVING COUNT(*) &gt; 0
          </select>

          <select id="countsByPerson" resultType="ooo.klae.connex.backend.dto.FacetCount">
            SELECT CAST(dp.person_id AS CHAR) AS `key`, COUNT(DISTINCT dp.deal_id) AS count,
                   filtered_person.name AS label
            FROM deal_person dp
            JOIN deal d ON d.id = dp.deal_id AND d.workspace_id = #{workspaceId}
            JOIN person filtered_person ON filtered_person.id = dp.person_id
              AND <include refid="visibleFilteredPersonJoin"/>
            GROUP BY dp.person_id, filtered_person.name
          </select>

          <select id="getDealsByPersonId" resultMap="DealResult">
            SELECT <include refid="qualifiedDealColumns"/>
            FROM deal d
            JOIN deal_person dp ON dp.deal_id = d.id
            JOIN person p ON p.id = dp.person_id AND <include refid="visiblePersonJoin"/>
            WHERE d.workspace_id = #{workspaceId} AND dp.person_id = #{personId}
          </select>

          <select id="search" resultMap="DealResult">
            SELECT <include refid="qualifiedDealColumns"/>
            FROM deal d
            LEFT JOIN company c ON c.id = d.company_id AND <include refid="visibleCompanyJoin"/>
            WHERE d.workspace_id = #{workspaceId}
              AND (d.name LIKE #{query} OR d.currency LIKE #{query} OR c.name LIKE #{query})
            ORDER BY d.name
            LIMIT 10
          </select>

          <select id="getStageOutcome" resultType="string">
            SELECT CASE WHEN is_success THEN 'won' WHEN is_failure THEN 'lost' ELSE 'normal' END
            FROM stage s
            WHERE s.id = #{stageId} AND <include refid="visibleStageJoin"/>
          </select>

          <select id="getLastNormalStageId" resultType="java.lang.Integer">
            SELECT s.id FROM stage s
            WHERE s.pipeline_id = #{pipelineId} AND <include refid="visibleStageJoin"/>
              AND s.is_success = FALSE AND s.is_failure = FALSE
            ORDER BY s.position DESC
            LIMIT 1
          </select>

          <insert id="addPerson">
            INSERT INTO deal_person (deal_id, person_id, role)
            SELECT d.id, p.id, #{role}
            FROM deal d
            JOIN person p ON p.id = #{personId} AND <include refid="visiblePersonJoin"/>
            WHERE d.workspace_id = #{workspaceId} AND d.id = #{dealId}
            ON DUPLICATE KEY UPDATE role = #{role}
          </insert>

          <insert id="addPersonIfAbsent">
            INSERT IGNORE INTO deal_person (deal_id, person_id, role)
            SELECT d.id, p.id, NULL
            FROM deal d
            JOIN person p ON p.id = #{personId} AND <include refid="visiblePersonJoin"/>
            WHERE d.workspace_id = #{workspaceId} AND d.id = #{dealId}
          </insert>

          <update id="updatePersonRole">
            UPDATE deal_person dp
            JOIN deal d ON d.id = dp.deal_id
            JOIN person p ON p.id = dp.person_id AND <include refid="visiblePersonJoin"/>
            SET dp.role = #{role}
            WHERE d.workspace_id = #{workspaceId}
              AND dp.deal_id = #{dealId}
              AND dp.person_id = #{personId}
          </update>
        </mapper>
        """;
}
