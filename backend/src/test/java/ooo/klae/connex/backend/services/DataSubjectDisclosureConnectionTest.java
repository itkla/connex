package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.mappers.DataSubjectDisclosureMapper;
import tools.jackson.databind.ObjectMapper;

/** Exercises real Spring/MyBatis connection retention while substituting JDBC query results. */
class DataSubjectDisclosureConnectionTest {
    @Test
    void acceptanceCannotLeaveProgressReadsInAnImplicitTransaction() throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(call -> connection());
        var transactions = new DataSourceTransactionManager(dataSource);
        var outer = new TransactionTemplate(transactions);
        outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var committed = new TransactionTemplate(transactions);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var queries = new QueryResults();
        var configuration = new Configuration(new Environment(
            "reservation", new SpringManagedTransactionFactory(), dataSource));
        configuration.addInterceptor(queries);
        configuration.addMapper(DataSubjectDisclosureMapper.class);
        String namespace = DataSubjectDisclosureMapper.class.getName();
        for (String id : List.of(namespace + ".subjectPersonExists",
                namespace + ".lockSubjectPersonForShare", "progressAutoCommit")) {
            configuration.addMappedStatement(new MappedStatement.Builder(configuration, id,
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT)
                .flushCacheRequired(true).useCache(false).build());
        }
        var factory = new SqlSessionFactoryBuilder().build(configuration);
        var sessions = new SqlSessionTemplate(factory);
        var reader = new DataSubjectDisclosureReadTransaction(
            sessions.getMapper(DataSubjectDisclosureMapper.class), factory, new ObjectMapper());

        outer.executeWithoutResult(status -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
            reader.withLockedSubjectPerson(4, 5,
                work -> committed.execute(control -> work.get()), () -> "accepted");

            assertTrue(sessions.<Boolean>selectOne("progressAutoCommit"),
                "The pre-T1 completion read must not open an implicit snapshot");
            assertSame(queries.reserved, queries.progress);
            committed.executeWithoutResult(tenant ->
                assertFalse(sessions.<Boolean>selectOne("progressAutoCommit")));
            assertTrue(sessions.<Boolean>selectOne("progressAutoCommit"),
                "The post-T1 projection must see committed invalidation work");
            assertSame(queries.reserved, queries.progress);
        });

        assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
        assertFalse(TransactionSynchronizationManager.hasResource(factory));
    }

    private static Connection connection() throws Exception {
        var connection = mock(Connection.class);
        var autoCommit = new AtomicBoolean(true);
        when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        doAnswer(call -> {
            autoCommit.set(call.getArgument(0));
            return null;
        }).when(connection).setAutoCommit(anyBoolean());
        return connection;
    }

    /** Keeps the actual session/transaction lifecycle and observes the connection each query uses. */
    @Intercepts(@Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    private static final class QueryResults implements Interceptor {
        private Connection reserved;
        private Connection progress;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            var connection = ((Executor) invocation.getTarget()).getTransaction().getConnection();
            if (statement.getId().endsWith(".subjectPersonExists")) {
                return List.of(true);
            }
            if (statement.getId().endsWith(".lockSubjectPersonForShare")) {
                reserved = connection;
                assertFalse(connection.getAutoCommit());
                return List.of(5);
            }
            progress = connection;
            return List.of(connection.getAutoCommit());
        }
    }
}
