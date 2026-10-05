package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/**
 * Proves on a real MySQL deadlock that a transaction the database rolled back cannot commit the work
 * its code did after swallowing the deadlock (#1947), and that a deadlock inside a
 * {@code REQUIRES_NEW} transaction leaves the transaction around it committable.
 *
 * <p>The deadlock runs through MyBatis mapper statements, the path production deadlocks take. Its
 * victim is deterministic: InnoDB rolls back the lighter transaction, and the survivor writes ten
 * rows before it closes the cycle against the victim's one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RolledBackTransactionGuardIntegrationTest {
    private static final int SURVIVOR_ROWS = 10;

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final CountDownLatch victimHoldsFirst = new CountDownLatch(1);
    private final CountDownLatch survivorHoldsSecond = new CountDownLatch(1);
    private final CountDownLatch victimBlockedOnSecond = new CountDownLatch(1);
    private final AtomicLong victimConnection = new AtomicLong();

    private Organization organization;
    private Workspace workspace;
    private int first;
    private int second;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Rolled back " + unique);
        organization.setSlug("rolled-back-" + unique);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Rolled back " + unique);
        workspace.setSlug("rolled-back-" + unique);
        workspaceMapper.insert(workspace);
        first = company("first");
        second = company("second");
    }

    @AfterEach
    void cleanUp() {
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    /**
     * The victim swallows its deadlock and keeps writing into the implicit transaction that replaced
     * the rolled-back one. Its commit must fail as the deadlock it was, and nothing it wrote, before
     * or after the deadlock, may survive.
     */
    @Test
    void aSwallowedDeadlockFailsTheCommitAndNothingTheVictimWroteSurvives() throws Exception {
        AtomicReference<PessimisticLockingFailureException> swallowed = new AtomicReference<>();

        ExecutionException refused = assertThrows(ExecutionException.class, () -> runDeadlock(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    try {
                        loseTheDeadlock("victim-before");
                    } catch (PessimisticLockingFailureException deadlock) {
                        swallowed.set(deadlock);
                    }
                    company("victim-after");
                })));

        assertInstanceOf(DeadlockLoserDataAccessException.class, refused.getCause());
        assertInstanceOf(DeadlockLoserDataAccessException.class, swallowed.get());
        assertSame(swallowed.get().getCause(), refused.getCause().getCause());
        assertEquals(List.of(), companiesNamed("victim-"));
        assertEquals(SURVIVOR_ROWS, companiesNamed("survivor-").size());
    }

    /**
     * The deadlock poisons only the inner transaction it arrived in, never the one it suspended: the
     * inner one swallows it and still cannot commit, and the outer one that catches that refusal
     * commits everything it wrote.
     */
    @Test
    void aDeadlockInsideRequiresNewPoisonsOnlyThatTransaction() throws Exception {
        AtomicReference<PessimisticLockingFailureException> swallowed = new AtomicReference<>();
        AtomicReference<DeadlockLoserDataAccessException> innerRefused = new AtomicReference<>();
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        runDeadlock(() -> new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            company("outer-before");
            try {
                inner.executeWithoutResult(status -> {
                    try {
                        loseTheDeadlock("inner-before");
                    } catch (PessimisticLockingFailureException deadlock) {
                        swallowed.set(deadlock);
                    }
                    company("inner-after");
                });
            } catch (DeadlockLoserDataAccessException refused) {
                innerRefused.set(refused);
            }
            company("outer-after");
        }));

        assertInstanceOf(DeadlockLoserDataAccessException.class, swallowed.get());
        assertNotNull(innerRefused.get());
        assertSame(swallowed.get().getCause(), innerRefused.get().getCause());
        assertEquals(List.of(), companiesNamed("inner-"));
        assertEquals(List.of("outer-after", "outer-before"), companiesNamed("outer-"));
    }

    /**
     * Runs the victim on one thread and the survivor on another, and closes the cycle only once the
     * victim is really waiting on the survivor's row.
     */
    private void runDeadlock(Runnable victimTransaction) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> victim = executor.submit(victimTransaction);
            await(victimHoldsFirst);
            Future<?> survivor = executor.submit(this::survive);
            await(survivorHoldsSecond);
            MySqlLockWaitProbe.awaitExclusiveRecordLock(
                    jdbcTemplate, victimConnection.get(), "company", String.valueOf(second));
            victimBlockedOnSecond.countDown();
            survivor.get(30, TimeUnit.SECONDS);
            victim.get(30, TimeUnit.SECONDS);
        } finally {
            victimHoldsFirst.countDown();
            survivorHoldsSecond.countDown();
            victimBlockedOnSecond.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    /** Writes one row, holds the first company and then waits on the second, the survivor's. */
    private void loseTheDeadlock(String rowName) {
        victimConnection.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
        company(rowName);
        companyMapper.lockById(workspace.getId(), first);
        victimHoldsFirst.countDown();
        await(survivorHoldsSecond);
        companyMapper.lockById(workspace.getId(), second);
    }

    /** Writes more rows than the victim, holds the second company, then closes the cycle. */
    private void survive() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int row = 0; row < SURVIVOR_ROWS; row++) {
                company("survivor-" + row);
            }
            companyMapper.lockById(workspace.getId(), second);
            survivorHoldsSecond.countDown();
            await(victimBlockedOnSecond);
            companyMapper.lockById(workspace.getId(), first);
        });
    }

    private int company(String name) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName(name);
        companyMapper.insert(company);
        return company.getId();
    }

    private List<String> companiesNamed(String prefix) {
        return jdbcTemplate.queryForList(
                "SELECT name FROM company WHERE workspace_id = ? AND name LIKE ? ORDER BY name",
                String.class, workspace.getId(), prefix + "%");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("The other transaction never reached its lock");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
