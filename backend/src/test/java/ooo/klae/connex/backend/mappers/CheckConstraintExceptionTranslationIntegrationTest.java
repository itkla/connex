package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * MySQL-to-MVC coverage for CHECK violations and the vendor-code translation boundary.
 */
class CheckConstraintExceptionTranslationIntegrationTest extends AbstractMapperTest {

    @Autowired private IdentityMapper identityMapper;
    @Autowired private SQLExceptionTranslator sqlExceptionTranslator;

    @Test
    void myBatisCheckViolationReachesIntegrityHandlerAsConflict() throws Exception {
        Company company = newCompany();
        Person person = newPerson(company);
        DataIntegrityViolationException checkViolation = assertThrows(
            DataIntegrityViolationException.class,
            () -> identityMapper.upsertPersonEmailIdentity(
                person.getWorkspaceId(),
                person.getId(),
                person.getEmail(),
                person.getEmail(),
                " ",
                null,
                LocalDateTime.now()));
        assertEquals(3819, nestedSqlException(checkViolation).getErrorCode());
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new CheckConstraintController(checkViolation))
            .setControllerAdvice(new GlobalExceptionHandler(
                mock(ErrorReporter.class),
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
    void unrelatedMySqlGeneralErrorIsNotClassifiedAsIntegrityViolation() {
        SQLException unrelated = new SQLException("Unrelated MySQL general error", "HY000", 3024);

        assertFalse(sqlExceptionTranslator.translate("query", null, unrelated)
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

        private final DataIntegrityViolationException checkViolation;

        private CheckConstraintController(DataIntegrityViolationException checkViolation) {
            this.checkViolation = checkViolation;
        }

        @PostMapping("/test/check-constraint")
        void violateCheckConstraint() {
            throw checkViolation;
        }
    }
}
