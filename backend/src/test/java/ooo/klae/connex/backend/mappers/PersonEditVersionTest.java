package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.PersonDetailDto;
import ooo.klae.connex.backend.dto.PersonLifecycleDto;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.StaleRecordException;

class PersonEditVersionTest {
    @Test
    void conditionalWriteChecksVersionInTheTenantScopedUpdate() throws Exception {
        Configuration configuration = configuration();
        Person edit = new Person();
        edit.setWorkspaceId(7);
        edit.setId(13);
        edit.setExpectedVersion(5L);
        BoundSql bound = configuration.getMappedStatement(PersonMapper.class.getName() + ".update").getBoundSql(edit);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.endsWith("WHERE workspace_id = ? AND id = ? AND version = ?"));
        assertEquals("expectedVersion", bound.getParameterMappings().getLast().getProperty());
        edit.setExpectedVersion(null);
        String legacy = configuration.getMappedStatement(PersonMapper.class.getName() + ".update")
            .getBoundSql(edit).getSql().replaceAll("\\s+", " ").trim();
        assertTrue(legacy.endsWith("WHERE workspace_id = ? AND id = ?"));
        assertFalse(legacy.contains("AND version ="));
    }

    @Test
    void explicitEmptyContactFieldsRemainBoundUpdatesWhileOmittedFieldsStayUntouched() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        PersonDto request = mapper.readValue(
            "{\"name\":\"Contact\",\"email\":\"\",\"phone\":\"\",\"title\":\"\",\"expectedVersion\":5}", PersonDto.class);
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
        Person edit = request.toBean();
        edit.setWorkspaceId(7);
        edit.setId(13);
        Configuration configuration = configuration();
        var statement = configuration.getMappedStatement(PersonMapper.class.getName() + ".update");
        BoundSql bound = statement.getBoundSql(edit);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        for (String field : new String[] {"email", "phone", "title"}) {
            assertTrue(sql.contains(field + " = ?"));
            assertTrue(bound.getParameterMappings().stream().anyMatch(parameter -> field.equals(parameter.getProperty())));
            assertEquals("", configuration.newMetaObject(edit).getValue(field));
        }
        Person omitted = mapper.readValue("{\"name\":\"Contact\"}", PersonDto.class).toBean();
        String omittedSql = statement.getBoundSql(omitted).getSql().replaceAll("\\s+", " ").trim();
        for (String field : new String[] {"email", "phone", "title"}) {
            assertNull(configuration.newMetaObject(omitted).getValue(field));
            assertFalse(omittedSql.contains(field + " = ?"));
        }
    }

    @Test
    void databaseCounterIncludesWritersThatPreserveTheTimestamp() throws Exception {
        String migration = resource("db/migration/tenant/V242__person_edit_version.sql");
        assertTrue(migration.contains("ADD COLUMN version BIGINT NOT NULL DEFAULT 0"));
        assertTrue(migration.contains("ALGORITHM=INSTANT, LOCK=DEFAULT"));
        assertTrue(migration.contains("BEFORE UPDATE ON person"));
        assertTrue(migration.contains("FOR EACH ROW"));
        assertTrue(migration.contains("SET NEW.version = OLD.version + 1"));
        assertFalse(migration.contains("CURRENT_TIMESTAMP"));
        assertTrue(migration.contains("BEFORE DELETE ON company"));
        assertTrue(migration.contains("UPDATE person SET company_id = NULL WHERE company_id = OLD.id"));
    }

    @Test
    void dtoCarriesOnlyTheSubmittedExpectedTokenIntoTheUpdate() {
        PersonDto request = new PersonDto();
        request.setVersion(99L);
        request.setExpectedVersion(5L);
        assertEquals(5L, request.toBean().getExpectedVersion());
        Person current = new Person();
        current.setVersion(8L);
        assertEquals(8L, PersonDto.from(current).getVersion());
        assertEquals(8L, PersonDetailDto.from(current).getVersion());
        assertEquals(8L, PersonLifecycleDto.from(current, true, null).version());
    }

    @Test
    void jsonVersionIsReadOnlyAndExpectedVersionIsOptionalWriteOnly() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        PersonDto request = mapper.readValue("{\"version\":99,\"expectedVersion\":5}", PersonDto.class);
        assertNull(request.getVersion());
        assertEquals(5L, request.toBean().getExpectedVersion());
        Person current = new Person();
        current.setVersion(8L);
        PersonDto responseDto = PersonDto.from(current);
        responseDto.setExpectedVersion(5L);
        var response = mapper.readTree(mapper.writeValueAsString(responseDto));
        assertEquals(8L, response.path("version").asLong());
        assertFalse(response.has("expectedVersion"));
        assertNull(mapper.readValue("{}", PersonDto.class).toBean().getExpectedVersion());
    }

    @Test
    void optionalExpectedVersionIsValidatedAndResponseVersionCannotSupplyIt() {
        PersonDto request = new PersonDto();
        request.setName("Person");
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

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String name : new String[] {"mappers/PersonMapper.xml"}) {
            try (InputStream input = PersonEditVersionTest.class.getClassLoader().getResourceAsStream(name)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, name, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = PersonEditVersionTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
