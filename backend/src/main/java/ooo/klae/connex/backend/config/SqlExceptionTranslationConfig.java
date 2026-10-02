package ooo.klae.connex.backend.config;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.MyBatisExceptionTranslator;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.boot.autoconfigure.MybatisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * Extends the JDBC and MyBatis exception-translation chains for request-owned MySQL CHECK
 * constraints, and reports every failure either chain translates to the
 * {@link RolledBackTransactionGuard}, so a transaction the database already rolled back fails its
 * commit (#1947).
 */
@Configuration(proxyBeanMethods = false)
public class SqlExceptionTranslationConfig {

    private static final Logger log = LoggerFactory.getLogger(SqlExceptionTranslationConfig.class);

    private static final int MYSQL_CHECK_CONSTRAINT_VIOLATION = 3819;
    private static final Pattern MYSQL_CHECK_CONSTRAINT_MESSAGE = Pattern.compile(
        "\\ACheck constraint '([A-Za-z0-9_]+)' is violated\\.\\z");
    private static final Set<String> REQUEST_OWNED_CHECK_CONSTRAINTS = Set.of(
        "chk_stage_terminal");

    /**
     * Supplies the guard both translation chains report to. Whether the server rolls back the whole
     * transaction on a lock-wait timeout is read once here; when it cannot be read, a timeout is
     * treated as rolling it back, which can only fail a commit, never let one through.
     */
    @Bean
    RolledBackTransactionGuard rolledBackTransactionGuard(DataSource dataSource) {
        return new RolledBackTransactionGuard(dataSource, serverRollsBackOnTimeout(dataSource));
    }

    /**
     * Supplies the unique translator bean that Spring Boot associates with its auto-configured
     * JDBC clients.
     */
    @Bean
    SQLExceptionTranslator sqlExceptionTranslator(RolledBackTransactionGuard rolledBackTransactionGuard) {
        return withConnexTranslation(new SQLExceptionSubclassTranslator(), rolledBackTransactionGuard);
    }

    /**
     * Supplies MyBatis with its existing vendor-code chain plus request-owned CHECK translation, and
     * reports every failure it translates to the rolled-back-transaction guard.
     */
    @Bean
    SqlSessionTemplate sqlSessionTemplate(
            SqlSessionFactory sqlSessionFactory,
            MybatisProperties properties,
            DataSource dataSource,
            RolledBackTransactionGuard rolledBackTransactionGuard) {
        ExecutorType executorType = properties.getExecutorType();
        if (executorType == null) {
            executorType = sqlSessionFactory.getConfiguration().getDefaultExecutorType();
        }
        SQLExceptionTranslator translator = withConnexTranslation(
            new SQLErrorCodeSQLExceptionTranslator(dataSource), rolledBackTransactionGuard);
        return new SqlSessionTemplate(
            sqlSessionFactory,
            executorType,
            new MyBatisExceptionTranslator(() -> translator, true));
    }

    private static <T extends AbstractFallbackSQLExceptionTranslator> T withConnexTranslation(
            T translator, RolledBackTransactionGuard rolledBackTransactionGuard) {
        translator.setCustomTranslator((task, sql, exception) -> {
            rolledBackTransactionGuard.observe(exception);
            return isRequestOwnedCheckConstraintViolation(exception)
                ? new DataIntegrityViolationException(task, exception)
                : null;
        });
        return translator;
    }

    private static boolean serverRollsBackOnTimeout(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT @@GLOBAL.innodb_rollback_on_timeout")) {
            return !result.next() || result.getBoolean(1);
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("Could not read innodb_rollback_on_timeout; lock-wait timeouts will fail their "
                + "transaction's commit exceptionClass={}", unreadable.getClass().getSimpleName());
            return true;
        }
    }

    private static boolean isRequestOwnedCheckConstraintViolation(SQLException exception) {
        if (exception.getErrorCode() != MYSQL_CHECK_CONSTRAINT_VIOLATION) {
            return false;
        }
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        Matcher matcher = MYSQL_CHECK_CONSTRAINT_MESSAGE.matcher(message);
        return matcher.matches() && REQUEST_OWNED_CHECK_CONSTRAINTS.contains(matcher.group(1));
    }
}
