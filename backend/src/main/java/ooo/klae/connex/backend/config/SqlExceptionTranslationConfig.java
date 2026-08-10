package ooo.klae.connex.backend.config;

import javax.sql.DataSource;

import java.sql.SQLException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.MyBatisExceptionTranslator;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.boot.autoconfigure.MybatisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * Extends the JDBC and MyBatis exception-translation chains for request-owned MySQL CHECK
 * constraints.
 */
@Configuration(proxyBeanMethods = false)
public class SqlExceptionTranslationConfig {

    private static final int MYSQL_CHECK_CONSTRAINT_VIOLATION = 3819;
    private static final Pattern MYSQL_CHECK_CONSTRAINT_MESSAGE = Pattern.compile(
        "\\ACheck constraint '([A-Za-z0-9_]+)' is violated\\.\\z");
    private static final Set<String> REQUEST_OWNED_CHECK_CONSTRAINTS = Set.of(
        "chk_stage_terminal");

    /**
     * Supplies the unique translator bean that Spring Boot associates with its auto-configured
     * JDBC clients.
     */
    @Bean
    SQLExceptionTranslator sqlExceptionTranslator() {
        return withMySqlCheckConstraintTranslation(new SQLExceptionSubclassTranslator());
    }

    /** Supplies MyBatis with its existing vendor-code chain plus request-owned CHECK translation. */
    @Bean
    SqlSessionTemplate sqlSessionTemplate(
            SqlSessionFactory sqlSessionFactory,
            MybatisProperties properties,
            DataSource dataSource) {
        ExecutorType executorType = properties.getExecutorType();
        if (executorType == null) {
            executorType = sqlSessionFactory.getConfiguration().getDefaultExecutorType();
        }
        SQLExceptionTranslator translator = withMySqlCheckConstraintTranslation(
            new SQLErrorCodeSQLExceptionTranslator(dataSource));
        return new SqlSessionTemplate(
            sqlSessionFactory,
            executorType,
            new MyBatisExceptionTranslator(() -> translator, true));
    }

    private static <T extends AbstractFallbackSQLExceptionTranslator> T
            withMySqlCheckConstraintTranslation(T translator) {
        translator.setCustomTranslator((task, sql, exception) ->
            isRequestOwnedCheckConstraintViolation(exception)
                ? new DataIntegrityViolationException(task, exception)
                : null);
        return translator;
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
