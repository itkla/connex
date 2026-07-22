package ooo.klae.connex.backend.storage;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.LegacyControlUploadMigrationMapper;
import ooo.klae.connex.backend.mappers.LegacyTenantUploadMigrationMapper;
import ooo.klae.connex.backend.storage.ObjectStorageProperties.LegacyMigration;
import ooo.klae.connex.backend.storage.ObjectStorageProperties.LegacyMigrationMode;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * One-shot, operator-invoked migration of retired frontend-local upload paths.
 */
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
public class LegacyUploadMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyUploadMigrationRunner.class);
    private static final String ATTACHMENT_PREFIX = "/attachments/";
    private static final String PERSON_IMAGE_PREFIX = "/contact-pictures/";
    private static final String COMPANY_IMAGE_PREFIX = "/company-logos/";
    private static final String USER_IMAGE_PREFIX = "/profile-pictures/";

    private final ObjectStorageProperties properties;
    private final LegacyTenantUploadMigrationMapper tenantMapper;
    private final LegacyControlUploadMigrationMapper controlMapper;
    private final LegacyUploadFileReader fileReader;
    private final LegacyUploadMigrationTransaction migrationTransaction;
    private final WorkspaceObjectStorageQuotaService quotaService;
    private final ObjectStorage objectStorage;
    private final TenantWorkScope tenantWorkScope;
    private final Environment environment;
    private final ConfigurableApplicationContext applicationContext;

    @Override
    public void run(ApplicationArguments arguments) {
        LegacyMigrationMode mode = properties.getLegacyMigration().getMode();
        if (mode == LegacyMigrationMode.OFF) {
            requireNoLegacyReferences();
            return;
        }
        requireMaintenanceInvocation(mode);
        fileReader.validateConfiguration();
        if (!objectStorage.isReady()) {
            throw new IllegalStateException(
                "Private object storage is not ready for legacy upload migration");
        }
        MigrationSummary summary = new MigrationSummary();
        sweepWorkspaces(mode, summary);
        tenantWorkScope.unrouted(() -> {
            sweep(
                "user image",
                USER_IMAGE_PREFIX,
                mode,
                summary,
                controlMapper::findUserImages,
                migrationTransaction::validateUserImage,
                migrationTransaction::migrateUserImage,
                null);
            summary.remaining += controlMapper.countUserReferences();
            return null;
        });
        log.info(
            "Legacy upload migration {}: discovered={}, migrated={}, valid={}, failed={}, remaining={}",
            mode, summary.discovered, summary.migrated, summary.valid, summary.failed, summary.remaining);
        if (summary.failed > 0 || (mode == LegacyMigrationMode.MIGRATE && summary.remaining > 0)) {
            throw new IllegalStateException(
                "Legacy upload migration did not complete; correct the reported records and rerun it");
        }
        applicationContext.close();
    }

    private void requireNoLegacyReferences() {
        int remaining = tenantWorkScope.unrouted(controlMapper::countUserReferences);
        int afterId = 0;
        int batchSize = properties.getLegacyMigration().getBatchSize();
        while (true) {
            int cursor = afterId;
            List<Integer> workspaceIds = tenantWorkScope.unrouted(
                () -> controlMapper.findWorkspaceIds(cursor, batchSize));
            if (workspaceIds.isEmpty()) {
                break;
            }
            for (int workspaceId : workspaceIds) {
                if (workspaceId <= afterId) {
                    throw new IllegalStateException(
                        "Legacy upload workspace enumeration did not advance");
                }
                afterId = workspaceId;
                int references = tenantWorkScope.inWorkspace(
                    workspaceId, () -> tenantMapper.countReferences(workspaceId));
                remaining = Math.addExact(remaining, references);
            }
            if (workspaceIds.size() < batchSize) {
                break;
            }
        }
        if (remaining > 0) {
            throw new IllegalStateException(
                "Legacy public upload references remain; run the documented legacy upload migration before starting this version");
        }
    }

    private void requireMaintenanceInvocation(LegacyMigrationMode mode) {
        String maintenanceMode = environment.getProperty(
            "connex.maintenance.mode", "");
        if (!"legacy-upload-migration".equalsIgnoreCase(maintenanceMode)) {
            throw new IllegalStateException(
                "Legacy upload migration requires connex.maintenance.mode=legacy-upload-migration");
        }
        String webApplicationType = environment.getProperty(
            "spring.main.web-application-type", "");
        if (!"none".equalsIgnoreCase(webApplicationType)) {
            throw new IllegalStateException(
                "Legacy upload migration requires spring.main.web-application-type=none");
        }
        if (mode == LegacyMigrationMode.MIGRATE
                && !LegacyMigration.APPLY_CONFIRMATION.equals(
                    properties.getLegacyMigration().getApplyConfirmation())) {
            throw new IllegalStateException(
                "Legacy upload migration apply confirmation is missing");
        }
    }

    private void sweepWorkspaces(LegacyMigrationMode mode, MigrationSummary summary) {
        int afterId = 0;
        int batchSize = properties.getLegacyMigration().getBatchSize();
        while (true) {
            int cursor = afterId;
            List<Integer> workspaceIds = tenantWorkScope.unrouted(
                () -> controlMapper.findWorkspaceIds(cursor, batchSize));
            if (workspaceIds.isEmpty()) {
                return;
            }
            for (int workspaceId : workspaceIds) {
                if (workspaceId <= afterId) {
                    throw new IllegalStateException(
                        "Legacy upload workspace enumeration did not advance");
                }
                afterId = workspaceId;
                try {
                    tenantWorkScope.inWorkspace(
                        workspaceId, () -> sweepWorkspace(workspaceId, mode, summary));
                } catch (RuntimeException exception) {
                    summary.failed++;
                    log.warn(
                        "Legacy upload migration could not process workspace {}",
                        workspaceId);
                }
            }
            if (workspaceIds.size() < batchSize) {
                return;
            }
        }
    }

    private void sweepWorkspace(
            int workspaceId,
            LegacyMigrationMode mode,
            MigrationSummary summary) {
        WorkspaceProjection projection = mode == LegacyMigrationMode.DRY_RUN
            ? new WorkspaceProjection()
            : null;
        sweep(
            "attachment",
            ATTACHMENT_PREFIX,
            mode,
            summary,
            (afterId, limit) -> tenantMapper.findAttachments(
                workspaceId, afterId, limit),
            migrationTransaction::validateAttachment,
            migrationTransaction::migrateAttachment,
            projection);
        sweep(
            "contact image",
            PERSON_IMAGE_PREFIX,
            mode,
            summary,
            (afterId, limit) -> tenantMapper.findPersonImages(
                workspaceId, afterId, limit),
            migrationTransaction::validatePersonImage,
            migrationTransaction::migratePersonImage,
            projection);
        sweep(
            "company image",
            COMPANY_IMAGE_PREFIX,
            mode,
            summary,
            (afterId, limit) -> tenantMapper.findCompanyImages(
                workspaceId, afterId, limit),
            migrationTransaction::validateCompanyImage,
            migrationTransaction::migrateCompanyImage,
            projection);
        if (projection != null) {
            quotaService.validateProjectedAddition(
                workspaceId, projection.bytes, projection.objects);
        }
        summary.remaining += tenantMapper.countReferences(workspaceId);
    }

    private void sweep(
            String type,
            String prefix,
            LegacyMigrationMode mode,
            MigrationSummary summary,
            BatchFinder finder,
            RecordValidator validator,
            RecordAction migrator,
            WorkspaceProjection projection) {
        int afterId = 0;
        int batchSize = properties.getLegacyMigration().getBatchSize();
        while (true) {
            List<LegacyUploadRecord> records = finder.find(afterId, batchSize);
            if (records.isEmpty()) {
                return;
            }
            for (LegacyUploadRecord record : records) {
                afterId = Math.max(afterId, record.getId());
                summary.discovered++;
                try {
                    fileReader.validateOwnership(record, prefix);
                    ResolvedLegacyUpload resolved = fileReader.read(record.getUrl(), prefix);
                    if (mode == LegacyMigrationMode.DRY_RUN) {
                        long storedSize = validator.apply(record, resolved);
                        if (projection != null) {
                            projection.add(storedSize);
                        }
                        summary.valid++;
                    } else {
                        migrator.apply(record, resolved);
                        summary.migrated++;
                    }
                } catch (RuntimeException exception) {
                    summary.failed++;
                    log.warn(
                        "Legacy {} record {} could not be migrated: {}",
                        type, record.getId(), exception.getMessage());
                }
            }
            if (records.size() < batchSize) {
                return;
            }
        }
    }

    @FunctionalInterface
    private interface BatchFinder {
        List<LegacyUploadRecord> find(int afterId, int limit);
    }

    @FunctionalInterface
    private interface RecordAction {
        void apply(LegacyUploadRecord record, ResolvedLegacyUpload resolved);
    }

    @FunctionalInterface
    private interface RecordValidator {
        long apply(LegacyUploadRecord record, ResolvedLegacyUpload resolved);
    }

    private static final class WorkspaceProjection {
        private long bytes;
        private int objects;

        private void add(long size) {
            bytes = Math.addExact(bytes, size);
            objects = Math.addExact(objects, 1);
        }
    }

    private static final class MigrationSummary {
        private int discovered;
        private int migrated;
        private int valid;
        private int failed;
        private int remaining;
    }
}
