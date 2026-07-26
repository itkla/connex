package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import ooo.klae.connex.backend.dto.ClientErrorRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.observability.ReportedError;
import ooo.klae.connex.backend.observability.ReportedError.Source;
import ooo.klae.connex.backend.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class ClientErrorServiceTest {
    @Mock private ErrorReporter errorReporter;
    @Mock private ClientErrorRateLimiter rateLimiter;

    private TenantContext tenantContext;
    private ClientErrorService service;

    @BeforeEach
    void setUp() {
        tenantContext = new TenantContext();
        service = new ClientErrorService(errorReporter, rateLimiter, tenantContext);
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        MDC.clear();
    }

    @Test
    void propagatesResolvedTenantUserAndCorrelationMetadata() {
        tenantContext.set(7, 8, 9, "member", null);
        MDC.put(CorrelationIds.MDC_KEY, "request_id_123");
        ClientErrorRequest request =
                new ClientErrorRequest("digest-7", "Render failed", "at Component", "/dashboard");

        service.report(request);

        ArgumentCaptor<ReportedError> captor = ArgumentCaptor.forClass(ReportedError.class);
        verify(rateLimiter).acquire(9);
        verify(errorReporter).report(captor.capture());
        assertEquals(new ReportedError(
                Source.CLIENT,
                "request_id_123",
                7,
                9,
                "Render failed",
                "Digest: digest-7\nStack:\nat Component",
                "/dashboard"), captor.getValue());
    }

    @Test
    void rejectsUnresolvedTenantBeforeRateLimitingOrReporting() {
        ClientErrorRequest request = new ClientErrorRequest(null, "Render failed", null, null);

        assertThrows(ForbiddenException.class, () -> service.report(request));

        verifyNoInteractions(rateLimiter, errorReporter);
    }

    @Test
    void boundsCombinedDetailWithoutSplittingSurrogatePairs() {
        tenantContext.set(7, 8, 9, "member", null);
        String stack = "a".repeat(8_100) + "\uD83D\uDE00";
        ClientErrorRequest request = new ClientErrorRequest("d".repeat(128), "Render failed", stack, null);

        service.report(request);

        ArgumentCaptor<ReportedError> captor = ArgumentCaptor.forClass(ReportedError.class);
        verify(errorReporter).report(captor.capture());
        assertEquals(8_192, captor.getValue().detail().length());
        assertEquals(false, captor.getValue().detail().endsWith("\uD83D"));
    }
}
