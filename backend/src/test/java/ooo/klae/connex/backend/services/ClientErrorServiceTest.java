package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import ooo.klae.connex.backend.dto.ClientErrorRequest;
import ooo.klae.connex.backend.dto.ClientErrorSupportRowDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.ClientErrorMapper;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.observability.ReportedError;
import ooo.klae.connex.backend.observability.ReportedError.Source;
import ooo.klae.connex.backend.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class ClientErrorServiceTest {
    @Mock private ErrorReporter errorReporter;
    @Mock private ClientErrorRateLimiter rateLimiter;
    @Mock private ClientErrorMapper clientErrorMapper;

    private TenantContext tenantContext;
    private ClientErrorService service;

    @BeforeEach
    void setUp() {
        tenantContext = new TenantContext();
        service = new ClientErrorService(
            errorReporter, rateLimiter, tenantContext, clientErrorMapper);
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
                new ClientErrorRequest(
                    "3819274061@E394", "Render failed", "at Component", "/dashboard");

        service.report(request);

        ArgumentCaptor<ReportedError> captor = ArgumentCaptor.forClass(ReportedError.class);
        verify(rateLimiter).acquire(9);
        verify(errorReporter).report(captor.capture());
        verify(clientErrorMapper).insert(
            7, "request_id_123", "3819274061@E394", "/dashboard");
        assertEquals(new ReportedError(
                Source.CLIENT,
                "request_id_123",
                7,
                9,
                "Render failed",
                "Digest: 3819274061@E394\nStack:\nat Component",
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

        verifyNoInteractions(rateLimiter, errorReporter, clientErrorMapper);
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

    @Test
    void messageDetailAndStackNeverReachTheMetadataMapper() {
        tenantContext.set(7, 8, 9, "member", null);
        MDC.put(CorrelationIds.MDC_KEY, "request_id_123");
        ClientErrorRequest request = new ClientErrorRequest(
            "3819274061",
            "private@example.com vanished",
            "Stack includes private record value",
            "/records/people/42?email=private@example.com");

        service.report(request);

        verify(clientErrorMapper).insert(
            7,
            "request_id_123",
            "3819274061",
            "/records/people/42");
    }

    @Test
    void unsafeDigestNeverReachesTheMetadataMapper() {
        tenantContext.set(7, 8, 9, "member", null);
        MDC.put(CorrelationIds.MDC_KEY, "request_id_123");
        ClientErrorRequest request = new ClientErrorRequest(
            "private@example.com",
            "Render failed",
            null,
            "/dashboard");

        service.report(request);

        verify(clientErrorMapper).insert(7, "request_id_123", null, "/dashboard");
    }

    @Test
    void supportSliceOmitsAnUnsafeStoredDigest() {
        Instant reportedAt = Instant.parse("2026-07-31T04:05:05Z");
        when(clientErrorMapper.findOrgSupportSlice(
                3,
                Instant.parse("2026-07-30T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                null,
                11))
            .thenReturn(List.of(new ClientErrorSupportRowDto(
                71L,
                7,
                "request_id_123",
                "private@example.com",
                "/records/people/42",
                reportedAt)));

        ClientErrorService.ClientErrorSlice slice = service.supportSliceForOrg(
            3,
            Instant.parse("2026-07-30T00:00:00Z"),
            Instant.parse("2026-08-01T00:00:00Z"),
            null,
            10);

        assertNull(slice.rows().getFirst().digest());
    }

    @Test
    void purgesMetadataBeyondTheThirtyDayMapperHorizon() {
        when(clientErrorMapper.deleteExpired()).thenReturn(17);

        assertEquals(17, service.purgeExpired());

        verify(clientErrorMapper).deleteExpired();
    }
}
