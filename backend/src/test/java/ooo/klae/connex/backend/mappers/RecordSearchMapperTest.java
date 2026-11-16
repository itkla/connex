package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** Pins the exact-id source-read boundary used by independently committed search repair pages. */
class RecordSearchMapperTest {
    @Test
    void sourcePagesRefuseEmptyOrOversizedIdLists() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mappers/RecordSearchMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing mapper " + resource);
            }
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        for (RecordSearchMapper.Kind kind : RecordSearchMapper.Kind.values()) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("workspaceId", 11);
            parameters.put("kind", kind);
            for (int size : List.of(0, 501)) {
                parameters.put("ids", IntStream.rangeClosed(1, size).boxed().toList());
                assertDenied(configuration, parameters);
            }
            parameters.put("ids", null);
            assertDenied(configuration, parameters);
            parameters.put("ids", IntStream.rangeClosed(1, 500).boxed().toList());
            BoundSql allowed = sql(configuration, parameters);
            assertTrue(allowed.getSql().contains("id IN"));
            assertTrue(allowed.getSql().contains("LIMIT 500"));
            assertEquals(501, allowed.getParameterMappings().size());
        }
    }

    private void assertDenied(Configuration configuration, Map<String, Object> parameters) {
        BoundSql denied = sql(configuration, parameters);
        assertTrue(denied.getSql().contains("1 = 0"));
        assertFalse(denied.getSql().contains("id IN"));
    }

    private BoundSql sql(Configuration configuration, Map<String, Object> parameters) {
        return configuration.getMappedStatement(
            "ooo.klae.connex.backend.mappers.RecordSearchMapper.findMissing").getBoundSql(parameters);
    }
}
