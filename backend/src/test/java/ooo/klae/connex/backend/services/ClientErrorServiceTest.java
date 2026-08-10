package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import ooo.klae.connex.backend.beans.ClientErrorMetadataRow;
import ooo.klae.connex.backend.config.AuditIntegrityProperties;
import ooo.klae.connex.backend.dto.ClientErrorRequest;
import ooo.klae.connex.backend.dto.ClientErrorSupportRowDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.ClientErrorMapper;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
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
    private ClientAssertedCorrelationPseudonymizer correlationPseudonymizer;

    @BeforeEach
    void setUp() {
        tenantContext = new TenantContext();
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        properties.setHmacSecret("test-correlation-hmac-secret-change-me");
        correlationPseudonymizer = new ClientAssertedCorrelationPseudonymizer(properties);
        service = new ClientErrorService(
            errorReporter,
            rateLimiter,
            tenantContext,
            clientErrorMapper,
            correlationPseudonymizer);
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
            7, correlationPseudonymizer.forStorage(8, "request_id_123"), "/dashboard");
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
        assertEquals("/docs/{...slug}",
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
            correlationPseudonymizer.forStorage(8, "request_id_123"),
            "unknown");
    }

    @Test
    void clientAssertedDigestNeverReachesTheMetadataMapper() {
        tenantContext.set(7, 8, 9, "member", null);
        MDC.put(CorrelationIds.MDC_KEY, "request_id_123");
        ClientErrorRequest request = new ClientErrorRequest(
            "private@example.com",
            "Render failed",
            null,
            "/dashboard");

        service.report(request);

        verify(clientErrorMapper).insert(
            7, correlationPseudonymizer.forStorage(8, "request_id_123"), "/dashboard");
    }

    @Test
    void supportSliceReappliesPathVocabularyAndDisclosureHmacToLegacyRows() {
        Instant reportedAt = Instant.parse("2026-07-31T04:05:05Z");
        when(clientErrorMapper.findOrgSupportSlice(
                3,
                Instant.parse("2026-07-30T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                null,
                null,
                11))
            .thenReturn(List.of(new ClientErrorMetadataRow(
                71L,
                7,
                3,
                "privateCRMRecordEncoded123",
                "/records/private@example.com",
                reportedAt)));

        ClientErrorService.ClientErrorSlice slice = service.supportSliceForOrg(
            3,
            Instant.parse("2026-07-30T00:00:00Z"),
            Instant.parse("2026-08-01T00:00:00Z"),
            null,
            10);

        ClientErrorSupportRowDto row = slice.rows().getFirst();
        assertEquals("unknown", row.pagePath());
        assertEquals(
            correlationPseudonymizer.forDisclosure(3, "privateCRMRecordEncoded123"),
            row.untrustedClientAssertedCorrelationHmac());
        assertNotEquals(
            "privateCRMRecordEncoded123",
            row.untrustedClientAssertedCorrelationHmac());
    }

    @Test
    void correlationLookupMatchesCurrentHmacAndLegacyRawStorageWithoutDisclosingEither() {
        String clientValue = "client-correlation-123";
        String storageHmac = correlationPseudonymizer.forStorage(3, clientValue);
        Instant since = Instant.parse("2026-07-30T00:00:00Z");
        Instant until = Instant.parse("2026-08-01T00:00:00Z");
        Instant reportedAt = Instant.parse("2026-07-31T04:05:05Z");
        when(clientErrorMapper.findOrgSupportSlice(
                3,
                since,
                until,
                storageHmac,
                clientValue,
                11))
            .thenReturn(List.of(
                new ClientErrorMetadataRow(
                    71L, 7, 3, storageHmac, "/dashboard", reportedAt),
                new ClientErrorMetadataRow(
                    72L, 7, 3, clientValue, "/dashboard", reportedAt)));

        ClientErrorService.ClientErrorSlice slice =
            service.supportSliceForOrg(3, since, until, clientValue, 10);

        verify(clientErrorMapper).findOrgSupportSlice(
            3,
            since,
            until,
            storageHmac,
            clientValue,
            11);
        String expectedDisclosure = correlationPseudonymizer.forDisclosure(3, storageHmac);
        assertEquals(
            List.of(expectedDisclosure, expectedDisclosure),
            slice.rows().stream()
                .map(ClientErrorSupportRowDto::untrustedClientAssertedCorrelationHmac)
                .toList());
    }

    @Test
    void workspaceExportReappliesTheSafeProjectionToLegacyRows() {
        Instant reportedAt = Instant.parse("2026-07-31T04:05:05Z");
        when(clientErrorMapper.findWorkspaceExportPage(7, 0, 500))
            .thenReturn(List.of(new ClientErrorMetadataRow(
                71L,
                7,
                3,
                "privateCRMRecordEncoded123",
                "/records/private@example.com",
                reportedAt)));

        ClientErrorSupportRowDto row = service.workspaceExportPage(7, 0, 500).getFirst();

        assertEquals("unknown", row.pagePath());
        assertEquals(
            correlationPseudonymizer.forDisclosure(3, "privateCRMRecordEncoded123"),
            row.untrustedClientAssertedCorrelationHmac());
    }

    @Test
    void purgesMetadataBeyondTheThirtyDayMapperHorizon() {
        when(clientErrorMapper.deleteExpired()).thenReturn(17);

        assertEquals(17, service.purgeExpired());

        verify(clientErrorMapper).deleteExpired();
    }
}
