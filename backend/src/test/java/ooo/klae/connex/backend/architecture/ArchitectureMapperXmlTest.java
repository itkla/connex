package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArchitectureMapperXmlTest {
    @Test
    void includesPreserveSqlAndBranchMetadataWithoutTreatingCommentsAsEvidence() throws Exception {
        ArchitectureMapperXml.Parsed mapper = ArchitectureMapperXml.parse("""
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://unreachable.example/mapper.dtd">
            <mapper namespace="fixture.Mapper">
              <sql id="inner"><![CDATA[SELECT revenue]]><!-- hidden evidence --></sql>
              <sql id="outer"><if test="query.measure == 'revenue'"><include refid="inner"/></if></sql>
              <select id="read"><include refid="outer"/><include refid="missing"/><otherwise>fallback</otherwise></select>
              <insert id="insert">INSERT</insert>
              <update id="update">UPDATE</update>
              <delete id="delete">DELETE</delete>
            </mapper>
            """);

        assertEquals("fixture.Mapper", mapper.namespace());
        assertEquals(4, mapper.statements().size());
        String sql = ArchitectureMapperXml.resolve(
            ArchitectureMapperXml.collectSql(mapper.statements().getFirst()), mapper.fragments(), 0);
        assertEquals("SELECT revenue fallback", sql.replaceAll("\\s+", " ").trim());
        assertFalse(sql.contains("hidden evidence"));
        assertFalse(sql.contains("query.measure"));
        assertEquals("query.measure == 'revenue'", mapper.fragmentElements().get("outer")
            .getElementsByTagName("if").item(0).getAttributes().getNamedItem("test").getNodeValue());
    }

    @Test
    void recursiveIncludesRetainTheExistingDepthBound() throws Exception {
        ArchitectureMapperXml.Parsed mapper = ArchitectureMapperXml.parse("""
            <mapper namespace="fixture.Mapper">
              <sql id="cycle"><include refid="cycle"/></sql>
              <select id="read"><include refid="cycle"/></select>
            </mapper>
            """);
        String sql = ArchitectureMapperXml.resolve(
            ArchitectureMapperXml.collectSql(mapper.statements().getFirst()), mapper.fragments(), 0);

        assertTrue(sql.contains(String.valueOf((char) 1) + "cycle" + (char) 1));
    }
}
