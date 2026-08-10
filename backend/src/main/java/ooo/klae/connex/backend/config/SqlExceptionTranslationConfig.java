package ooo.klae.connex.backend.config;

import javax.sql.DataSource;

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
 * Extends the JDBC and MyBatis exception-translation chains with MySQL CHECK-constraint support.
 */
@Configuration(proxyBeanMethods = false)
public class SqlExceptionTranslationConfig {

    private static final int MYSQL_CHECK_CONSTRAINT_VIOLATION = 3819;

    /** Supplies Boot-managed JDBC clients with the CHECK-aware subclass translation chain. */
    @Bean
    SQLExceptionTranslator sqlExceptionTranslator() {
        return withMySqlCheckConstraintTranslation(new SQLExceptionSubclassTranslator());
    }

    /** Supplies MyBatis with its existing vendor-code chain plus CHECK-constraint translation. */
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
            exception.getErrorCode() == MYSQL_CHECK_CONSTRAINT_VIOLATION
                ? new DataIntegrityViolationException(task, exception)
                : null);
        return translator;
    }
}
