package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
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
    void redactsCredentialBearingClientPathsWithoutFlatteningDocumentationSlugs() {
        tenantContext.set(7, 8, 9, "member", null);
        service.report(new ClientErrorRequest(
                null, "Render failed", null, "/invite/aBc123defGhi456jklMno"));
        service.report(new ClientErrorRequest(
                null, "Render failed", null, "/docs/using-connex/notifications-and-mentions"));

        ArgumentCaptor<ReportedError> captor = ArgumentCaptor.forClass(ReportedError.class);
        verify(errorReporter, times(2)).report(captor.capture());
        assertEquals("/invite/{token}", captor.getAllValues().getFirst().path());
        assertEquals("/docs/using-connex/notifications-and-mentions",
                captor.getAllValues().getLast().path());
    }

    @Test
    void rejectsUnresolvedTenantBeforeRateLimitingOrReporting() {
        ClientErrorRequest request = new ClientErrorRequest(null, "Render failed", null, null);

        assertThrows(ForbiddenException.class, () -> service.report(request));

        verifyNoInteractions(rateLimiter, errorReporter);
    }

    @Test
    void composesMaximalValidatedFieldsWithinTheReporterDetailCap() {
        tenantContext.set(7, 8, 9, "member", null);
        ClientErrorRequest request = new ClientErrorRequest(
                "d".repeat(128), "Render failed", "s".repeat(8_000), null);

        service.report(request);

        ArgumentCaptor<ReportedError> captor = ArgumentCaptor.forClass(ReportedError.class);
        verify(errorReporter).report(captor.capture());
        assertEquals("Digest: " + "d".repeat(128) + "\nStack:\n" + "s".repeat(8_000),
                captor.getValue().detail());
        assertEquals(true, captor.getValue().detail().length() <= 8_192);
    }
}
