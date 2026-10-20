package ooo.klae.connex.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

/** Proves production listener registration and physical commit semantics against real MySQL. */
class RequestTransactionCommitListenerContextTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionManagerCustomizationAutoConfiguration.class))
        .withUserConfiguration(Transactions.class, TenantRoutingConfig.class,
            RequestTransactionCommitListener.class);

    @BeforeEach
    void bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        new RequestTransactionCommitListener().preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"single-database", "catalog-per-placement"})
    void bootRegistersTheListenerOnEveryManagerAndTracksCommitButNotRollback(String mode) {
        configured(mode).run(context -> {
            assertThat(context).hasNotFailed();
            var managers = context.getBeansOfType(PlatformTransactionManager.class);
            assertThat(managers).containsOnlyKeys("transactionManager");
            RequestTransactionCommitListener listener = context.getBean(RequestTransactionCommitListener.class);
            managers.values().forEach(manager -> {
                assertThat(manager).isInstanceOf(JdbcTransactionManager.class);
                JdbcTransactionManager jdbc = (JdbcTransactionManager) manager;
                assertThat(jdbc.getTransactionExecutionListeners()).containsExactly(listener);
                assertSame(context.getBean(DataSource.class), jdbc.getDataSource());
            });
            assertEquals(mode.equals("catalog-per-placement"),
                context.getBean(DataSource.class) instanceof TenantRoutingDataSource);

            TransactionProbe probe = context.getBean(TransactionProbe.class);
            probe.commit();
            assertTrue(RequestTransactionCommitListener.committedDuringRequest());

            bindRequest();
            assertThrows(IllegalStateException.class, probe::rollback);
            assertFalse(RequestTransactionCommitListener.committedDuringRequest());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"single-database", "catalog-per-placement"})
    void participatingAndNestedCompletionsDoNotMarkBeforeTheOuterCommit(String mode) {
        configured(mode).run(context -> {
            assertThat(context).hasNotFailed();
            TransactionProbe probe = context.getBean(TransactionProbe.class);

            assertThrows(IllegalStateException.class, probe::rollbackAfterNested);
            assertFalse(RequestTransactionCommitListener.committedDuringRequest());

            probe.commitAfterNested();
            assertTrue(RequestTransactionCommitListener.committedDuringRequest());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"single-database", "catalog-per-placement"})
    void independentCommitsMarkEvenWhenTheOuterTransactionRollsBack(String mode) {
        configured(mode).run(context -> {
            assertThat(context).hasNotFailed();

            assertThrows(IllegalStateException.class,
                context.getBean(TransactionProbe.class)::rollbackAfterIndependent);

            assertTrue(RequestTransactionCommitListener.committedDuringRequest());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"single-database", "catalog-per-placement"})
    void earlierAndReadOnlyCommitsDoNotMarkTheHandlerWindow(String mode) {
        configured(mode).run(context -> {
            assertThat(context).hasNotFailed();
            MockHttpServletRequest request = new MockHttpServletRequest();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            TransactionProbe probe = context.getBean(TransactionProbe.class);

            probe.commit();
            assertFalse(RequestTransactionCommitListener.committedDuringRequest());
            context.getBean(RequestTransactionCommitListener.class)
                .preHandle(request, new MockHttpServletResponse(), new Object());
            probe.readOnly();
            assertFalse(RequestTransactionCommitListener.committedDuringRequest());
            probe.commit();
            assertTrue(RequestTransactionCommitListener.committedDuringRequest());
        });
    }

    private ApplicationContextRunner configured(String mode) {
        String url = Objects.requireNonNull(System.getenv("CONNEX_DB_URL"), "CONNEX_DB_URL");
        return contextRunner.withPropertyValues(
            "connex.tenancy.routing.mode=" + mode,
            "connex.tenancy.routing.default-catalog=" + TenantRoutingConfig.databaseFromJdbcUrl(url));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableConfigurationProperties(TenantRoutingProperties.class)
    static class Transactions {
        @Bean
        HikariDataSource dataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(Objects.requireNonNull(System.getenv("CONNEX_DB_URL"), "CONNEX_DB_URL"));
            dataSource.setUsername(Objects.requireNonNull(System.getenv("CONNEX_DB_USERNAME"), "CONNEX_DB_USERNAME"));
            dataSource.setPassword(Objects.requireNonNull(System.getenv("CONNEX_DB_PASSWORD"), "CONNEX_DB_PASSWORD"));
            dataSource.setMaximumPoolSize(2);
            return dataSource;
        }

        @Bean
        TenantContext tenantContext() {
            return new TenantContext();
        }

        @Bean
        InnerProbe innerProbe(DataSource dataSource) {
            return new InnerProbe(new JdbcTemplate(dataSource));
        }

        @Bean
        TransactionProbe transactionProbe(InnerProbe inner) {
            return new TransactionProbe(inner);
        }
    }

    static class TransactionProbe {
        private final InnerProbe inner;

        TransactionProbe(InnerProbe inner) {
            this.inner = inner;
        }

        @Transactional
        public void commit() {
            inner.participating();
            assertFalse(RequestTransactionCommitListener.committedDuringRequest());
        }

        @Transactional
        public void rollback() {
            commit();
            throw new IllegalStateException("rollback");
        }

        @Transactional(readOnly = true)
        public void readOnly() {
            inner.participating();
        }

        @Transactional
        public void commitAfterNested() {
            commit();
            inner.nested();
            assertFalse(RequestTransactionCommitListener.committedDuringRequest());
        }

        @Transactional
        public void rollbackAfterNested() {
            commitAfterNested();
            throw new IllegalStateException("rollback");
        }

        @Transactional
        public void rollbackAfterIndependent() {
            inner.independent();
            assertTrue(RequestTransactionCommitListener.committedDuringRequest());
            throw new IllegalStateException("rollback");
        }
    }

    static class InnerProbe {
        private final JdbcTemplate jdbc;

        InnerProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional
        public void participating() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
        }

        @Transactional(propagation = Propagation.NESTED)
        public void nested() {
            participating();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void independent() {
            participating();
        }
    }
}
