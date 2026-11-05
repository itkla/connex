package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.StaleRecordException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.DealOutcomeWriter;
import ooo.klae.connex.backend.services.DealValueService;

class DealEditVersionTest {
    @Test
    void conditionalWriteChecksVersionInTheTenantScopedUpdate() throws Exception {
        Configuration configuration = configuration();
        Deal edit = new Deal();
        edit.setWorkspaceId(7);
        edit.setId(13);
        edit.setExpectedVersion(5L);
        BoundSql bound = configuration.getMappedStatement(DealMapper.class.getName() + ".update").getBoundSql(edit);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.endsWith("WHERE workspace_id = ? AND id = ? AND version = ?"));
        assertEquals("expectedVersion", bound.getParameterMappings().getLast().getProperty());
        edit.setExpectedVersion(null);
        String legacy = configuration.getMappedStatement(DealMapper.class.getName() + ".update")
            .getBoundSql(edit).getSql().replaceAll("\\s+", " ").trim();
        assertTrue(legacy.endsWith("WHERE workspace_id = ? AND id = ?"));
        assertFalse(legacy.contains("AND version ="));
    }

    @Test
    void databaseCounterIncludesWritersThatPreserveTheTimestamp() throws Exception {
        String migration = resource("db/migration/tenant/V241__deal_edit_version.sql");
        assertTrue(migration.contains("ADD COLUMN version BIGINT NOT NULL DEFAULT 0"));
        assertTrue(migration.contains("ALGORITHM=INSTANT, LOCK=DEFAULT"));
        assertTrue(migration.contains("BEFORE UPDATE ON deal"));
        assertTrue(migration.contains("FOR EACH ROW"));
        assertTrue(migration.contains("SET NEW.version = OLD.version + 1"));
        assertFalse(migration.contains("CURRENT_TIMESTAMP"));
    }

    @Test
    void dtoCarriesOnlyTheSubmittedExpectedTokenIntoTheUpdate() {
        DealDto request = new DealDto();
        request.setVersion(99L);
        request.setExpectedVersion(5L);
        request.setValue(BigDecimal.ONE);
        assertEquals(5L, request.toBean().getExpectedVersion());
        Deal current = new Deal();
        current.setVersion(8L);
        assertEquals(8L, DealDto.from(current).getVersion());
    }

    @Test
    void jsonVersionIsReadOnlyAndExpectedVersionIsOptionalWriteOnly() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        DealDto request = mapper.readValue("{\"version\":99,\"expectedVersion\":5}", DealDto.class);
        assertNull(request.getVersion());
        assertEquals(5L, request.toBean().getExpectedVersion());
        Deal current = new Deal();
        current.setVersion(8L);
        DealDto responseDto = DealDto.from(current);
        responseDto.setExpectedVersion(5L);
        var response = mapper.readTree(mapper.writeValueAsString(responseDto));
        assertEquals(8L, response.path("version").asLong());
        assertFalse(response.has("expectedVersion"));
        assertNull(mapper.readValue("{}", DealDto.class).toBean().getExpectedVersion());
    }

    @Test
    void optionalExpectedVersionIsValidatedAndResponseVersionCannotSupplyIt() {
        DealDto request = new DealDto();
        request.setName("Deal");
        request.setCurrency("JPY");
        request.setPipeline(1);
        request.setStage(1);
        request.setValue(BigDecimal.ONE);
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(request).isEmpty());
            request.setExpectedVersion(-1L);
            assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> "expectedVersion".equals(violation.getPropertyPath().toString())));
            request.setExpectedVersion(0L);
            assertTrue(validator.validate(request).isEmpty());
        }
    }

    @Test
    void staleRefusalHasItsOwnCodeAndCurrentVersion() {
        var response = new GlobalExceptionHandler(mock(ooo.klae.connex.backend.observability.ErrorReporter.class), mock(ooo.klae.connex.backend.tenant.TenantContext.class)).staleRecord(new StaleRecordException(9L));
        assertEquals(409, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("STALE_RECORD", response.getBody().get("code"));
        assertEquals(9L, response.getBody().get("currentVersion"));
    }

    @Test
    void zeroRowConditionalWriteRefusesBeforeAnyDependentValueWrite() {
        DealMapper mapper = mock(DealMapper.class);
        DealValueService values = mock(DealValueService.class);
        Deal edit = new Deal();
        edit.setId(13);
        edit.setWorkspaceId(7);
        edit.setExpectedVersion(1L);
        Deal current = new Deal();
        current.setVersion(2L);
        when(mapper.getDealById(7, 13)).thenReturn(current);

        StaleRecordException error = assertThrows(StaleRecordException.class,
            () -> new DealOutcomeWriter(mapper, values).write(7, edit, null, null, "normal"));

        assertEquals(2L, error.getCurrentVersion());
        verifyNoInteractions(values);
    }

    @Test
    void zeroRowWriteForAMissingTenantRecordRemainsNotFound() {
        DealMapper mapper = mock(DealMapper.class);
        DealValueService values = mock(DealValueService.class);
        Deal edit = new Deal();
        edit.setId(13);
        edit.setWorkspaceId(7);
        assertThrows(ResourceNotFoundException.class,
            () -> new DealOutcomeWriter(mapper, values).write(7, edit, null, null, "normal"));
        verifyNoInteractions(values);
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String name : new String[] {"mappers/PersonMapper.xml", "mappers/DealMapper.xml"}) {
            try (InputStream input = DealEditVersionTest.class.getClassLoader().getResourceAsStream(name)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, name, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = DealEditVersionTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
