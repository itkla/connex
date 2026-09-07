package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.session.web.http.SessionRepositoryFilter;

class CspReportSecurityConfigTest {

    @Test
    void derivesTheOrderImmediatelyAheadOfSpringSession() {
        assertEquals(SessionRepositoryFilter.DEFAULT_ORDER - 1,
                CspReportSecurityConfig.immediatelyAheadOf(SessionRepositoryFilter.DEFAULT_ORDER));
        assertEquals(Integer.MIN_VALUE,
                CspReportSecurityConfig.immediatelyAheadOf(Integer.MIN_VALUE + 1));
    }

    /**
     * Subtracting one from {@link Integer#MIN_VALUE} wraps to {@link Integer#MAX_VALUE}, which
     * would register the cookie filter behind every other filter — the exact inversion of the
     * guarantee — while looking like a successful startup. The configuration has to be refused.
     */
    @Test
    void refusesAnOrderNothingCanPrecede() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CspReportSecurityConfig.immediatelyAheadOf(Integer.MIN_VALUE));

        assertTrue(failure.getMessage().contains("spring.session.servlet.filter-order"));
    }
}
