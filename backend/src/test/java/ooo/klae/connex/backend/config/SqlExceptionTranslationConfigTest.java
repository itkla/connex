package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.SQLExceptionTranslator;

class SqlExceptionTranslationConfigTest {
    private final SQLExceptionTranslator sqlExceptionTranslator =
        new SqlExceptionTranslationConfig().sqlExceptionTranslator();

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
}
