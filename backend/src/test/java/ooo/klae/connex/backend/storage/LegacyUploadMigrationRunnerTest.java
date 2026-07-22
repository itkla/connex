package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import ooo.klae.connex.backend.mappers.LegacyControlUploadMigrationMapper;
import ooo.klae.connex.backend.mappers.LegacyTenantUploadMigrationMapper;
import ooo.klae.connex.backend.storage.ObjectStorageProperties.LegacyMigration;
import ooo.klae.connex.backend.storage.ObjectStorageProperties.LegacyMigrationMode;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class LegacyUploadMigrationRunnerTest {
    @Mock private LegacyTenantUploadMigrationMapper tenantMapper;
    @Mock private LegacyControlUploadMigrationMapper controlMapper;
    @Mock private LegacyUploadFileReader fileReader;
    @Mock private LegacyUploadMigrationTransaction transaction;
    @Mock private WorkspaceObjectStorageQuotaService quotaService;
    @Mock private ObjectStorage objectStorage;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private Environment environment;
    @Mock private ConfigurableApplicationContext applicationContext;
    @Mock private ApplicationArguments arguments;

    private ObjectStorageProperties properties;
    private LegacyUploadMigrationRunner runner;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        properties.getLegacyMigration().setUploadsRoot("/legacy");
        properties.getLegacyMigration().setBatchSize(100);
        runner = new LegacyUploadMigrationRunner(
            properties,
            tenantMapper,
            controlMapper,
            fileReader,
            transaction,
            quotaService,
            objectStorage,
            tenantWorkScope,
            environment,
            applicationContext);
        lenient().when(environment.getProperty(
            "spring.main.web-application-type", "")).thenReturn("none");
        lenient().when(environment.getProperty(
            "connex.maintenance.mode", "")).thenReturn("legacy-upload-migration");
        lenient().when(objectStorage.isReady()).thenReturn(true);
        lenient().when(controlMapper.findWorkspaceIds(0, 100)).thenReturn(List.of());
        lenient().when(controlMapper.findUserImages(0, 100)).thenReturn(List.of());
        lenient().when(controlMapper.countUserReferences()).thenReturn(0);
        lenient().when(tenantWorkScope.unrouted(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        lenient().when(tenantWorkScope.inWorkspace(
            anyInt(), org.mockito.ArgumentMatchers.<Supplier<Integer>>any()
        )).thenAnswer(invocation -> {
            Supplier<Integer> work = invocation.getArgument(1);
            return work.get();
        });
        lenient().doAnswer(invocation -> {
            Runnable work = invocation.getArgument(1);
            work.run();
            return null;
        }).when(tenantWorkScope).inWorkspace(anyInt(), any(Runnable.class));
    }

    @Test
    void disabledModeRequiresEveryLegacyReferenceToBeGone() {
        properties.getLegacyMigration().setMode(LegacyMigrationMode.OFF);
        when(controlMapper.findWorkspaceIds(0, 100)).thenReturn(List.of(3));
        when(tenantMapper.countReferences(3)).thenReturn(0);

        runner.run(arguments);

        verify(objectStorage, never()).isReady();
        verify(controlMapper).countUserReferences();
        verify(tenantMapper).countReferences(3);
        verify(applicationContext, never()).close();
    }

    @Test
    void disabledModeFailsStartupWhenTenantLegacyReferencesRemain() {
        properties.getLegacyMigration().setMode(LegacyMigrationMode.OFF);
        when(controlMapper.findWorkspaceIds(0, 100)).thenReturn(List.of(3));
        when(tenantMapper.countReferences(3)).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> runner.run(arguments));

        verify(objectStorage, never()).isReady();
    }

    @Test
    void disabledModeFailsStartupWhenProfileImageReferencesRemain() {
        properties.getLegacyMigration().setMode(LegacyMigrationMode.OFF);
        when(controlMapper.countUserReferences()).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> runner.run(arguments));

        verify(controlMapper).findWorkspaceIds(0, 100);
        verify(objectStorage, never()).isReady();
    }

    @Test
    void disabledModeFailsClosedWhenAWorkspaceCannotBeRouted() {
        properties.getLegacyMigration().setMode(LegacyMigrationMode.OFF);
        when(controlMapper.findWorkspaceIds(0, 100)).thenReturn(List.of(3));
        doThrow(new IllegalStateException("route unavailable"))
            .when(tenantWorkScope)
            .inWorkspace(eq(3), org.mockito.ArgumentMatchers.<Supplier<Integer>>any());

        assertThrows(IllegalStateException.class, () -> runner.run(arguments));

        verify(objectStorage, never()).isReady();
    }

    @Test
    void disabledModeChecksEveryWorkspacePage() {
        properties.getLegacyMigration().setMode(LegacyMigrationMode.OFF);
        properties.getLegacyMigration().setBatchSize(2);
        when(controlMapper.findWorkspaceIds(0, 2)).thenReturn(List.of(1, 2));
        when(controlMapper.findWorkspaceIds(2, 2)).thenReturn(List.of(3));

        runner.run(arguments);

        verify(tenantMapper).countReferences(1);
        verify(tenantMapper).countReferences(2);
        verify(tenantMapper).countReferences(3);
    }

    @Test
    void enabledModeRequiresIsolatedMaintenanceContext() {
        properties.getLegacyMigration().setMode(LegacyMigrationMode.DRY_RUN);
        when(environment.getProperty(
            "connex.maintenance.mode", "")).thenReturn("off");

        assertThrows(IllegalStateException.class, () -> runner.run(arguments));

        verify(objectStorage, never()).isReady();
    }

    @Test
    void dryRunRoutesByWorkspaceAndValidatesWithoutMutating() {
        properties.getLegacyMigration().setMode(LegacyMigrationMode.DRY_RUN);
        LegacyUploadRecord record = record(
            7, 3, "/attachments/person/person-19-1700000000000-legacy.pdf");
        record.setEntityType("person");
        record.setEntityId(19);
        ResolvedLegacyUpload resolved = new ResolvedLegacyUpload(
            "legacy.pdf", new byte[] {1});
        when(controlMapper.findWorkspaceIds(0, 100)).thenReturn(List.of(3));
        when(tenantMapper.findAttachments(3, 0, 100)).thenReturn(List.of(record));
        when(tenantMapper.findPersonImages(3, 0, 100)).thenReturn(List.of());
        when(tenantMapper.findCompanyImages(3, 0, 100)).thenReturn(List.of());
        when(tenantMapper.countReferences(3)).thenReturn(1);
        when(fileReader.read(record.getUrl(), "/attachments/")).thenReturn(resolved);
        when(transaction.validateAttachment(record, resolved)).thenReturn(1L);

        runner.run(arguments);

        verify(tenantWorkScope).inWorkspace(anyInt(), any(Runnable.class));
        verify(fileReader).validateOwnership(record, "/attachments/");
        verify(transaction).validateAttachment(record, resolved);
        verify(transaction, never()).migrateAttachment(any(), any());
        verify(quotaService).validateProjectedAddition(3, 1, 1);
        verify(applicationContext).close();
    }

    @Test
    void migrationFailsWhenAnyLegacyReferenceCannotBeCopied() {
        properties.getLegacyMigration().setMode(LegacyMigrationMode.MIGRATE);
        properties.getLegacyMigration().setApplyConfirmation(
            LegacyMigration.APPLY_CONFIRMATION);
        LegacyUploadRecord record = record(
            9, 4, "/contact-pictures/contact-9-1700000000000-missing.png");
        when(controlMapper.findWorkspaceIds(0, 100)).thenReturn(List.of(4));
        when(tenantMapper.findAttachments(4, 0, 100)).thenReturn(List.of());
        when(tenantMapper.findPersonImages(4, 0, 100)).thenReturn(List.of(record));
        when(tenantMapper.findCompanyImages(4, 0, 100)).thenReturn(List.of());
        when(tenantMapper.countReferences(4)).thenReturn(1);
        when(fileReader.read(record.getUrl(), "/contact-pictures/"))
            .thenThrow(new IllegalStateException("Legacy upload source could not be read"));

        assertThrows(IllegalStateException.class, () -> runner.run(arguments));

        verify(applicationContext, never()).close();
    }

    @Test
    void migrationRefusesWebModeAndMissingApplyConfirmation() {
        properties.getLegacyMigration().setMode(LegacyMigrationMode.MIGRATE);

        assertThrows(IllegalStateException.class, () -> runner.run(arguments));

        properties.getLegacyMigration().setApplyConfirmation(
            LegacyMigration.APPLY_CONFIRMATION);
        when(environment.getProperty(
            "spring.main.web-application-type", "")).thenReturn("servlet");

        assertThrows(IllegalStateException.class, () -> runner.run(arguments));
        verify(objectStorage, never()).isReady();
    }

    private static LegacyUploadRecord record(int id, int workspaceId, String url) {
        LegacyUploadRecord record = new LegacyUploadRecord();
        record.setId(id);
        record.setWorkspaceId(workspaceId);
        record.setUrl(url);
        return record;
    }
}
