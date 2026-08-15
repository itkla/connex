package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.NativeConnectSession;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.NativeConnectSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NativeConnectClaimConcurrencyIntegrationTest {
    @Autowired private NativeConnectSessionMapper sessionMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private PlatformTransactionManager transactionManager;

    private User user;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user = new User();
        user.setUsername("native_claim_" + suffix);
        user.setDisplayName("Native Claim " + suffix);
        user.setEmail("native_claim_" + suffix + "@example.test");
        user.setPasswordHash("hash_" + suffix);
        user.setTimezone("UTC");
        inTransaction(() -> userMapper.insert(user));
    }

    @AfterEach
    void tearDown() {
        if (user != null && user.getId() > 0) {
            inTransaction(() -> userMapper.delete(user.getId()));
        }
    }

    @Test
    void pairingAndHandoffClaimsAreSingleWinnerAcrossCommittedTransactions()
            throws Exception {
        byte[] pairingHash = NativeConnectPkce.hash(NativeConnectPkce.randomSecret());
        byte[] handoffHash = NativeConnectPkce.hash(NativeConnectPkce.randomSecret());
        byte[] stateHash = NativeConnectPkce.hash(NativeConnectPkce.randomSecret());
        NativeConnectSession session = new NativeConnectSession();
        session.setUserId(user.getId());
        session.setProvider("google");
        session.setStatus("pending");
        session.setPairingCodeHash(pairingHash);
        session.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        assertEquals(1, inTransaction(() -> sessionMapper.insert(session)));

        List<Integer> prepareResults = invokeConcurrently(() -> sessionMapper.prepare(
            session.getId(),
            pairingHash,
            handoffHash,
            stateHash,
            "verifier-ref",
            "http://127.0.0.1:49152/callback"));
        assertEquals(List.of(0, 1), prepareResults.stream().sorted().toList());

        List<Integer> claimResults = invokeConcurrently(() ->
            sessionMapper.claimForExchange(session.getId(), handoffHash));
        assertEquals(List.of(0, 1), claimResults.stream().sorted().toList());
    }

    private List<Integer> invokeConcurrently(Supplier<Integer> operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return inTransaction(operation);
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(task);
            Future<Integer> second = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
    }

    private int inTransaction(Supplier<Integer> work) {
        Integer result = tenantWorkScope.unrouted(
            () -> transactionTemplate.execute(status -> work.get()));
        assertNotNull(result);
        return result;
    }
}
