package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.observability.ReportedError;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * MySQL-to-MVC coverage for CHECK violations and the vendor-code translation boundary.
 */
class CheckConstraintExceptionTranslationIntegrationTest extends AbstractMapperTest {

    @Autowired private IdentityMapper identityMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SQLExceptionTranslator sqlExceptionTranslator;

    @Test
    void requestOwnedCheckViolationReachesIntegrityHandlerAsConflict() throws Exception {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        stage.setSuccess(true);
        stage.setFailure(true);
        DataIntegrityViolationException checkViolation = assertThrows(
            DataIntegrityViolationException.class,
            () -> pipelineMapper.updateStage(stage));
        SQLException sqlException = nestedSqlException(checkViolation);
        assertEquals(3819, sqlException.getErrorCode());
        assertEquals(
            "Check constraint 'chk_stage_terminal' is violated.",
            sqlException.getMessage());
        ErrorReporter errorReporter = mock(ErrorReporter.class);
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new CheckConstraintController(checkViolation))
            .setControllerAdvice(new GlobalExceptionHandler(
                errorReporter,
                new TenantContext()))
            .build();

        mockMvc.perform(post("/test/check-constraint"))
            .andExpect(result -> {
                Exception resolvedException = result.getResolvedException();
                assertNotNull(resolvedException);
                assertInstanceOf(DataIntegrityViolationException.class, resolvedException);
            })
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This record conflicts with existing data"));
    }

    @Test
    void jdbcTemplateUsesRequestOwnedCheckTranslation() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        DataIntegrityViolationException checkViolation = assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update(
                """
                UPDATE stage
                SET is_success = TRUE, is_failure = TRUE
                WHERE workspace_id = ? AND id = ?
                """,
                workspace.getId(),
                stage.getId()));

        SQLException sqlException = nestedSqlException(checkViolation);
        assertEquals(3819, sqlException.getErrorCode());
        assertEquals(
            "Check constraint 'chk_stage_terminal' is violated.",
            sqlException.getMessage());
    }

    @Test
    void serverOwnedCheckViolationReachesInternalErrorAndReporter() throws Exception {
        Company company = newCompany();
        Person person = newPerson(company);
        DataAccessException checkViolation = assertThrows(
            DataAccessException.class,
            () -> identityMapper.upsertPersonEmailIdentity(
                person.getWorkspaceId(),
                person.getId(),
                person.getEmail(),
                person.getEmail(),
                " ",
                null,
                LocalDateTime.now()));
        assertFalse(checkViolation instanceof DataIntegrityViolationException);
        SQLException sqlException = nestedSqlException(checkViolation);
        assertEquals(3819, sqlException.getErrorCode());
        assertEquals(
            "Check constraint 'chk_person_identity_source_system' is violated.",
            sqlException.getMessage());
        ErrorReporter errorReporter = mock(ErrorReporter.class);
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new CheckConstraintController(checkViolation))
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, new TenantContext()))
            .build();

        mockMvc.perform(post("/test/check-constraint"))
            .andExpect(result -> {
                Exception resolvedException = result.getResolvedException();
                assertNotNull(resolvedException);
                assertInstanceOf(DataAccessException.class, resolvedException);
                assertFalse(resolvedException instanceof DataIntegrityViolationException);
            })
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
            .andExpect(jsonPath("$.correlationId").isNotEmpty());
        verify(errorReporter).report(any(ReportedError.class));
    }

    @Test
    void unrelatedMySqlGeneralErrorIsNotClassifiedAsIntegrityViolation() {
        SQLException unrelated = new SQLException("Unrelated MySQL general error", "HY000", 3024);

        assertFalse(sqlExceptionTranslator.translate("query", null, unrelated)
            instanceof DataIntegrityViolationException);
    }

    @Test
    void unrecognizedCheckConstraintIsNotClassifiedAsIntegrityViolation() {
        SQLException unrecognized = new SQLException(
            "Check constraint 'chk_future_server_invariant' is violated.",
            "HY000",
            3819);

        assertFalse(sqlExceptionTranslator.translate("query", null, unrecognized)
            instanceof DataIntegrityViolationException);
    }

    private static SQLException nestedSqlException(Throwable throwable) {
        Throwable current = throwable;
        StringBuilder types = new StringBuilder();
        while (current != null) {
            if (!types.isEmpty()) {
                types.append(" -> ");
            }
            types.append(current.getClass().getName());
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected a nested SQLException in " + types);
    }

    @RestController
    private static final class CheckConstraintController {

        private final RuntimeException checkViolation;

        private CheckConstraintController(RuntimeException checkViolation) {
            this.checkViolation = checkViolation;
        }

        @PostMapping("/test/check-constraint")
        void violateCheckConstraint() {
            throw checkViolation;
        }
    }
}
