package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.OneTimeLinkFlowClaimService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowClaimService.Claim;
import ooo.klae.connex.backend.services.OneTimeLinkFlowScheduler;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.IssuedGrant;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Proves flow claims serialize independent request wrappers and cannot be stolen by elapsed time. */
@SpringBootTest
class OneTimeLinkFlowConcurrencyIntegrationTest {

    @Autowired private OneTimeLinkFlowService flowService;
    @Autowired private OneTimeLinkFlowClaimService claimService;
    @Autowired private OneTimeLinkFlowScheduler flowScheduler;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentFinalRequestsExecuteTheDomainOperationExactlyOnce() throws Exception {
        String binding = OneTimeTokenDigest.generate();
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest firstRequest = request(binding, session);
        MockHttpServletRequest secondRequest = request(binding, session);
        flowService.establishBrowserBinding(firstRequest, binding);
        IssuedGrant grant = flowService.issue(
            firstRequest,
            Purpose.PASSWORD_RESET,
            OneTimeTokenDigest.sha256(OneTimeTokenDigest.generate()));
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch operationMayFinish = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> flowService.consume(
                firstRequest,
                Purpose.PASSWORD_RESET,
                grant.value(),
                sourceTokenHash -> {
                    executions.incrementAndGet();
                    operationEntered.countDown();
                    await(operationMayFinish);
                }));
            assertTrue(operationEntered.await(5, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> flowService.consume(
                secondRequest,
                Purpose.PASSWORD_RESET,
                grant.value(),
                sourceTokenHash -> executions.incrementAndGet()));

            operationMayFinish.countDown();
            first.get(5, TimeUnit.SECONDS);
            ExecutionException rejected = assertThrows(
                ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertInstanceOf(BadRequestException.class, rejected.getCause());
        }

        assertEquals(1, executions.get());
        assertThrows(BadRequestException.class, () -> flowService.require(
            secondRequest, Purpose.PASSWORD_RESET, grant.value()));
    }

    @Test
    void activeClaimIsNotStolenAndExpiredOwnerRetryRecoversIt() {
        String binding = OneTimeTokenDigest.generate();
        MockHttpServletRequest request = request(binding, new MockHttpSession());
        flowService.establishBrowserBinding(request, binding);
        String sourceHash = OneTimeTokenDigest.sha256(OneTimeTokenDigest.generate());
        IssuedGrant grant = flowService.issue(request, Purpose.PASSWORD_RESET, sourceHash);
        String grantHash = OneTimeTokenDigest.sha256(grant.value());
        String ownerHash = flowService.exchangeOwnerHash(request);
        Claim claim = claimService.claim(grantHash, ownerHash, Purpose.PASSWORD_RESET);

        jdbcTemplate.update(
            "UPDATE one_time_link_flow SET consume_claimed_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY) WHERE grant_hash = ?",
            grantHash);
        assertThrows(BadRequestException.class, () -> claimService.claim(
            grantHash, ownerHash, Purpose.PASSWORD_RESET));
        LocalDateTime activeExpiry = jdbcTemplate.queryForObject(
            "SELECT expires_at FROM one_time_link_flow WHERE grant_hash = ?",
            LocalDateTime.class,
            grantHash);

        IssuedGrant earlyRetry = flowService.issue(request, Purpose.PASSWORD_RESET, sourceHash);

        assertEquals(grant.value(), earlyRetry.value());
        assertEquals(activeExpiry, jdbcTemplate.queryForObject(
            "SELECT expires_at FROM one_time_link_flow WHERE grant_hash = ?",
            LocalDateTime.class,
            grantHash));
        assertThrows(BadRequestException.class, () -> flowService.consume(
            request,
            Purpose.PASSWORD_RESET,
            earlyRetry.value(),
            resolvedSourceHash -> { }));

        jdbcTemplate.update(
            "UPDATE one_time_link_flow SET expires_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 MINUTE), consume_claimed_at = UTC_TIMESTAMP() WHERE grant_hash = ?",
            grantHash);
        flowScheduler.purgeExpired();
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM one_time_link_flow WHERE grant_hash = ?",
            Integer.class,
            grantHash));

        IssuedGrant renewed = flowService.issue(request, Purpose.PASSWORD_RESET, sourceHash);
        assertEquals(grant.value(), renewed.value());
        flowService.consume(
            request,
            Purpose.PASSWORD_RESET,
            renewed.value(),
            resolvedSourceHash -> assertEquals(sourceHash, resolvedSourceHash));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM one_time_link_flow WHERE grant_hash = ?",
            Integer.class,
            claim.grantHash()));
    }

    private static MockHttpServletRequest request(String binding, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        request.setCookies(new Cookie(OneTimeLinkFlowService.BROWSER_BINDING_COOKIE, binding));
        return request;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("One-time-link operation wait timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("One-time-link operation wait interrupted", exception);
        }
    }
}
