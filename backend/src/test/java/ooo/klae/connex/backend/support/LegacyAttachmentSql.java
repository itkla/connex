package ooo.klae.connex.backend.support;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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

/** Frozen pre-L8 attachment statements; the oracle always requires the migrated default catalog. */
public final class LegacyAttachmentSql {
    private final DataSource dataSource;
    private final String defaultCatalog;
    private final SqlSessionTemplate sessions;

    /** Loads the four original statements independently of production SQL. */
    public LegacyAttachmentSql(DataSource dataSource, String defaultCatalog) {
        this.dataSource = dataSource;
        this.defaultCatalog = defaultCatalog;
        Configuration configuration = new Configuration(new Environment(
            "legacy-attachment", new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        try (var tags = getClass().getClassLoader().getResourceAsStream("mappers/TagMapper.xml")) {
            new XMLMapperBuilder(tags, configuration, "attachment-oracle-tags", configuration.getSqlFragments()).parse();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not load oracle tag mapping", failure);
        }
        new XMLMapperBuilder(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)),
            configuration, "legacy-attachment", configuration.getSqlFragments()).parse();
        sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    }

    /** Executes immutable SQL only after checking its actual connection catalog. */
    public <T> java.util.List<T> selectList(String statement, Map<String, Object> parameters) {
        var connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (!defaultCatalog.equals(connection.getCatalog())) {
                throw new IllegalStateException("Attachment oracle requires the default catalog");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify attachment oracle catalog", failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
        return sessions.selectList("legacy.AttachmentMapper." + statement, parameters);
    }

    /** Immutable original mapper fragments and statements from 57cf7c8e1. */
    public static final String XML = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="legacy.AttachmentMapper">
          <resultMap id="AttachmentResult" type="Attachment">
            <id property="id" column="id"/>
            <result property="workspaceId" column="workspace_id"/>
            <result property="entityType" column="entity_type"/>
            <result property="entityId" column="entity_id"/>
            <result property="entityLabel" column="entity_label"/>
            <result property="fileName" column="file_name"/>
            <result property="url" column="url"/>
            <result property="contentType" column="content_type"/>
            <result property="size" column="size"/>
            <result property="createdAt" column="created_at"/>
            <result property="updatedAt" column="updated_at"/>
            <result property="scanState" column="scan_state"/>
            <result property="scanEngine" column="scan_engine"/>
            <result property="scanDatabaseVersion" column="scan_database_version"/>
            <result property="scanSignature" column="scan_signature"/>
            <result property="scannedAt" column="scanned_at"/>
            <result property="scanExpiresAt" column="scan_expires_at"/>
            <result property="scanAttempts" column="scan_attempts"/>
            <result property="scanOwner" column="scan_owner"/>
            <result property="scanLeaseUntil" column="scan_lease_until"/>
            <result property="scanNextAttemptAt" column="scan_next_attempt_at"/>
            <association property="uploadedBy" javaType="User">
              <id property="id" column="uploaded_by_id"/>
              <result property="displayName" column="uploaded_by_name"/>
            </association>
          </resultMap>

          <resultMap id="AttachmentWithTagsResult" type="Attachment" extends="AttachmentResult">
            <collection property="tags" ofType="Tag" column="{workspaceId=workspace_id,attachmentId=id}"
              select="ooo.klae.connex.backend.mappers.TagMapper.getTagsByAttachmentId"/>
          </resultMap>

          <sql id="attachmentColumns">
            a.id, a.workspace_id, a.entity_type, a.entity_id, a.file_name, a.url, a.content_type, a.size,
            a.uploaded_by_id, a.created_at, a.updated_at, a.scan_state, a.scan_engine,
            a.scan_database_version, a.scan_signature, a.scanned_at, a.scan_expires_at, a.scan_attempts,
            a.scan_owner, a.scan_lease_until, a.scan_next_attempt_at
          </sql>

          <sql id="attachmentColumnsWithUploader">
            <include refid="attachmentColumns"/>, u.display_name AS uploaded_by_name
          </sql>

          <sql id="noteVisibilityPredicate">
            AND (a.entity_type != 'note' OR EXISTS (
              SELECT 1
              FROM note n
              WHERE n.workspace_id = a.workspace_id
                AND n.id = a.entity_id
                AND (n.visibility = 'workspace' OR n.author_id = #{currentUserId})
            ))
          </sql>

          <select id="search" resultMap="AttachmentResult">
            SELECT <include refid="attachmentColumnsWithUploader"/>
            FROM attachment a
            LEFT JOIN app_user u ON u.id = a.uploaded_by_id
            WHERE a.workspace_id = #{workspaceId}
              AND a.entity_type != 'ai_chat_session'
              <include refid="noteVisibilityPredicate"/>
              AND (a.file_name LIKE #{query}
              OR a.content_type LIKE #{query}
              OR u.display_name LIKE #{query})
            ORDER BY a.created_at DESC
            LIMIT 10
          </select>

          <sql id="kindExpr">
            CASE
              WHEN a.content_type LIKE 'image/%'
                OR LOWER(SUBSTRING_INDEX(a.file_name, '.', -1)) IN ('png','jpg','jpeg','gif','webp','svg','avif','bmp','ico','heic','heif','tif','tiff') THEN 'image'
              WHEN a.content_type = 'application/pdf'
                OR LOWER(SUBSTRING_INDEX(a.file_name, '.', -1)) = 'pdf' THEN 'pdf'
              WHEN a.content_type LIKE '%spreadsheet%' OR a.content_type = 'text/csv' OR a.content_type = 'application/vnd.ms-excel'
                OR LOWER(SUBSTRING_INDEX(a.file_name, '.', -1)) IN ('xls','xlsx','csv','tsv','ods') THEN 'spreadsheet'
              WHEN a.content_type LIKE '%presentation%' OR a.content_type = 'application/vnd.ms-powerpoint'
                OR LOWER(SUBSTRING_INDEX(a.file_name, '.', -1)) IN ('ppt','pptx','odp','key') THEN 'presentation'
              WHEN a.content_type LIKE 'audio/%' OR a.content_type LIKE 'video/%'
                OR LOWER(SUBSTRING_INDEX(a.file_name, '.', -1)) IN ('mp3','wav','ogg','flac','m4a','aac','mp4','mov','webm','avi','mkv','m4v') THEN 'media'
              WHEN a.content_type LIKE '%zip%' OR a.content_type LIKE '%compressed%' OR a.content_type LIKE '%x-7z%'
                OR a.content_type LIKE '%x-rar%' OR a.content_type LIKE '%x-tar%' OR a.content_type LIKE '%gzip%' OR a.content_type LIKE '%x-bzip%'
                OR LOWER(SUBSTRING_INDEX(a.file_name, '.', -1)) IN ('zip','rar','7z','tar','gz','tgz','bz2','xz') THEN 'archive'
              WHEN a.content_type = 'application/json' OR a.content_type = 'application/xml' OR a.content_type = 'text/html'
                OR a.content_type LIKE '%javascript%'
                OR LOWER(SUBSTRING_INDEX(a.file_name, '.', -1)) IN ('js','jsx','ts','tsx','json','html','htm','css','scss','py','java','go','rb','rs','php','sh','yml','yaml','xml','sql','c','cpp','cs','kt','swift','toml') THEN 'code'
              WHEN a.content_type LIKE '%word%' OR a.content_type LIKE '%opendocument.text%' OR a.content_type = 'application/rtf'
                OR a.content_type LIKE 'text/%'
                OR LOWER(SUBSTRING_INDEX(a.file_name, '.', -1)) IN ('doc','docx','odt','rtf','txt','md','markdown','pages') THEN 'document'
              ELSE 'other'
            END
          </sql>

          <sql id="tenantAttachJoins">
            FROM attachment a
            LEFT JOIN company  c  ON a.entity_type = 'company' AND c.id  = a.entity_id AND c.workspace_id = a.workspace_id
              AND c.archived_at IS NULL
            LEFT JOIN person   p  ON a.entity_type = 'person'  AND p.id  = a.entity_id AND p.workspace_id = a.workspace_id
              AND p.archived_at IS NULL
            LEFT JOIN deal     d  ON a.entity_type = 'deal'    AND d.id  = a.entity_id AND d.workspace_id = a.workspace_id
          </sql>

          <sql id="attachJoins">
            <include refid="tenantAttachJoins"/>
            LEFT JOIN app_user u  ON u.id  = a.uploaded_by_id
            LEFT JOIN app_user eu ON a.entity_type = 'user'    AND eu.id = a.entity_id
          </sql>

          <sql id="attachPageWhere">
            <where>
              AND a.workspace_id = #{workspaceId}
              AND a.entity_type != 'ai_chat_session'
              <include refid="noteVisibilityPredicate"/>
              <if test="query != null">
                AND (a.file_name LIKE #{query} OR a.content_type LIKE #{query}
                  OR COALESCE(c.name, p.name, d.name, eu.display_name) LIKE #{query}
                  OR u.display_name LIKE #{query})
              </if>
              <if test="types != null and types.size() > 0">
                AND a.entity_type IN <foreach item="ty" collection="types" open="(" separator="," close=")">#{ty}</foreach>
              </if>
              <if test="kinds != null and kinds.size() > 0">
                AND (<include refid="kindExpr"/>) IN <foreach item="kd" collection="kinds" open="(" separator="," close=")">#{kd}</foreach>
              </if>
              <if test="tagIds != null and tagIds.size() > 0">
                AND EXISTS (
                  SELECT 1 FROM attachment_tag att
                  WHERE att.attachment_id = a.id
                  AND att.tag_id IN <foreach item="tg" collection="tagIds" open="(" separator="," close=")">#{tg}</foreach>
                )
              </if>
              <if test="orphaned != null and orphaned">
                AND COALESCE(c.name, p.name, d.name, eu.display_name) IS NULL
              </if>
            </where>
          </sql>

          <sql id="attachPageOrderBy">
            ORDER BY
            <choose>
              <when test="sort == 'oldest'">a.created_at ASC</when>
              <when test="sort == 'name'">a.file_name ASC</when>
              <when test="sort == 'largest'">a.size DESC</when>
              <otherwise>a.created_at DESC</otherwise>
            </choose>
          </sql>

          <select id="getPage" resultMap="AttachmentWithTagsResult">
            SELECT <include refid="attachmentColumnsWithUploader"/>,
              COALESCE(c.name, p.name, d.name, eu.display_name) AS entity_label
            <include refid="attachJoins"/>
            <include refid="attachPageWhere"/>
            <include refid="attachPageOrderBy"/>
            LIMIT #{limit} OFFSET #{offset}
          </select>

          <select id="countPage" resultType="long">
            SELECT COUNT(*)
            <include refid="attachJoins"/>
            <include refid="attachPageWhere"/>
          </select>

          <select id="countOrphaned" resultType="long">
            SELECT COUNT(*)
            <include refid="attachJoins"/>
            WHERE a.workspace_id = #{workspaceId}
              AND a.entity_type != 'ai_chat_session'
              <include refid="noteVisibilityPredicate"/>
              AND COALESCE(c.name, p.name, d.name, eu.display_name) IS NULL
          </select>
        </mapper>
        """;
}
