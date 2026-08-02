package ooo.klae.connex.backend.services;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.observability.CorrelationIds;
import tools.jackson.databind.ObjectMapper;

/**
 * Assembles the redacted support bundle streamed by {@code SupportBundleController}.
 *
 * <p>Every entry is built by constructive allowlisting from a source that was reviewed as safe.
 * Nothing is serialized broadly and scrubbed afterwards, because scrubbing fails open the moment a
 * new field appears upstream. When a source cannot be proven safe or is unavailable, the file is
 * omitted and the omission is recorded in the manifest, so a reader can distinguish "collected and
 * empty" from "not collected".
 *
 * <p>{@code manifest.json} is written last and carries a SHA-256 and byte length for every other
 * entry. A failure part-way therefore produces an archive with no manifest, which the reader
 * refuses rather than partially trusting.
 */
@Service
@RequiredArgsConstructor
public class SupportBundleService {
    private static final String AUDIT_ACTION = "org.support_bundle.download";
    private static final int SCHEMA_VERSION = 1;
    private static final int AUDIT_SLICE_LIMIT = 10_000;
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    private static final Duration MAXIMUM_WINDOW = Duration.ofDays(30);
    private static final Duration BUNDLE_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_CONCURRENT_BUNDLES = 4;
    private static final String MANIFEST_ENTRY = "manifest.json";

    private final OrgMemberService orgMemberService;
    private final SessionSecurityService sessionSecurityService;
    private final SupportBundleReadinessService readinessService;
    private final SupportBundleConfigService configService;
    private final MigrationHistoryService migrationHistoryService;
    private final ProductVersionService productVersionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicInteger activeBundles = new AtomicInteger();

    /**
     * Authorizes the request, validates the filters, and returns a single-use streaming writer.
     *
     * <p>Authorization runs here, before any byte reaches the response, so an unauthorized caller
     * never receives a partial archive.
     *
     * @param request the validated bundle request
     * @param actorId the authenticated caller
     * @return the prepared download
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SupportBundleDownload prepare(SupportBundleRequest request, int actorId) {
        orgMemberService.requireOrgAdmin(request.orgId(), actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);

        Instant generatedAt = clock.instant();
        Instant since = resolveSince(request.since(), generatedAt);
        SupportBundleFilters filters = new SupportBundleFilters(
            request.correlationId(),
            request.entityType(),
            request.entityId(),
            request.workspaceId(),
            since,
            generatedAt);

        auditService.recordStrictIndependentScoped(
            AUDIT_ACTION,
            "organization",
            request.orgId(),
            null,
            request.orgId(),
            "organization:" + request.orgId(),
            "Support bundle authorized and streaming started",
            Map.of(
                "since", since.toString(),
                "until", generatedAt.toString(),
                "correlationFiltered", request.correlationId() != null,
                "entityFiltered", request.entityType() != null));

        return new SupportBundleDownload(request.orgId(), filters);
    }

    private Instant resolveSince(Instant requested, Instant generatedAt) {
        if (requested == null) {
            return generatedAt.minus(DEFAULT_WINDOW);
        }
        if (requested.isAfter(generatedAt)) {
            throw new BadRequestException("since must not be in the future");
        }
        if (requested.isBefore(generatedAt.minus(MAXIMUM_WINDOW))) {
            throw new BadRequestException("since must be within the last 30 days");
        }
        return requested;
    }

    /**
     * Validates a correlation id against the shape the observability layer issues.
     *
     * @param correlationId the requested correlation id, or null
     * @return the validated correlation id, or null
     */
    public static String validateCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return null;
        }
        if (!CorrelationIds.isValid(correlationId)) {
            throw new BadRequestException("correlationId is malformed");
        }
        return correlationId;
    }

    /** A single-use writer that streams one bundle and releases its admission slot on every exit. */
    public final class SupportBundleDownload {
        private final int orgId;
        private final SupportBundleFilters filters;
        private final List<ManifestEntry> inventory = new ArrayList<>();
        private final Map<String, String> omissions = new LinkedHashMap<>();
        private boolean admitted;
        private boolean written;

        private SupportBundleDownload(int orgId, SupportBundleFilters filters) {
            this.orgId = orgId;
            this.filters = filters;
            admit();
        }

        private void admit() {
            if (activeBundles.incrementAndGet() > MAX_CONCURRENT_BUNDLES) {
                activeBundles.decrementAndGet();
                throw new SupportBundleBusyException(
                    "Too many support bundles are being generated; retry shortly");
            }
            admitted = true;
        }

        /** Trusted response filename. */
        public String filename() {
            return "connex-org-" + orgId + "-support-bundle.zip";
        }

        /** Remaining servlet timeout for the streaming response. */
        public long remainingTimeoutMillis() {
            return BUNDLE_TIMEOUT.toMillis();
        }

        /** Releases the admission slot. Idempotent, so every async handler may call it. */
        public void cancel() {
            if (admitted) {
                admitted = false;
                activeBundles.decrementAndGet();
            }
        }

        /** Writes the ZIP once and releases the admission slot on every exit. */
        public void writeTo(OutputStream output) throws IOException {
            if (written) {
                throw new IllegalStateException("Support bundle download is single-use");
            }
            written = true;
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                writeJsonEntry(zip, "readiness.json", readinessService.readiness(orgId));
                writeJsonEntry(zip, "config.json", configService.safeConfiguration());
                writeJsonEntry(zip, "migrations.json", migrationHistoryService.history());
                writeTextEntry(zip, "audit-slice.csv", auditSlice());
                omissions.put("client-errors.json", "no_persisted_source");
                omissions.put("job-runs.json", "job_run_not_available");
                writeManifest(zip);
            } finally {
                cancel();
            }
        }

        private String auditSlice() {
            String requestId = filters.correlationId();
            if (filters.entityType() != null && filters.workspaceId() != null) {
                return auditService.supportSliceForEntity(
                    filters.workspaceId(),
                    filters.entityType(),
                    filters.entityId(),
                    filters.since(),
                    filters.until(),
                    requestId,
                    AUDIT_SLICE_LIMIT);
            }
            return auditService.supportSliceForOrg(
                orgId,
                filters.since(),
                filters.until(),
                requestId,
                AUDIT_SLICE_LIMIT);
        }

        private void writeJsonEntry(ZipOutputStream zip, String path, Object value)
                throws IOException {
            writeEntry(zip, path, "application/json",
                objectMapper.writeValueAsBytes(value));
        }

        private void writeTextEntry(ZipOutputStream zip, String path, String value)
                throws IOException {
            writeEntry(zip, path, "text/csv", value.getBytes(StandardCharsets.UTF_8));
        }

        private void writeEntry(ZipOutputStream zip, String path, String mediaType, byte[] content)
                throws IOException {
            ZipEntry entry = new ZipEntry(path);
            entry.setTime(filters.until().toEpochMilli());
            zip.putNextEntry(entry);
            zip.write(content);
            zip.closeEntry();
            inventory.add(new ManifestEntry(path, mediaType, content.length, sha256(content)));
        }

        private void writeManifest(ZipOutputStream zip) throws IOException {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", SCHEMA_VERSION);
            manifest.put("productVersion", productVersionService.version());
            manifest.put("generatedAt", filters.until().toString());
            manifest.put("orgId", orgId);
            manifest.put("filters", manifestFilters());
            inventory.sort(Comparator.comparing(ManifestEntry::path));
            manifest.put("files", inventory);
            manifest.put("omissions", omissions);
            ZipEntry entry = new ZipEntry(MANIFEST_ENTRY);
            entry.setTime(filters.until().toEpochMilli());
            zip.putNextEntry(entry);
            zip.write(objectMapper.writeValueAsBytes(manifest));
            zip.closeEntry();
        }

        private Map<String, Object> manifestFilters() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("correlationId", filters.correlationId());
            values.put("entityType", filters.entityType());
            values.put("entityId", filters.entityId());
            values.put("resolvedWorkspaceId", filters.workspaceId());
            values.put("since", filters.since().toString());
            values.put("until", filters.until().toString());
            return values;
        }
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for the support bundle manifest",
                exception);
        }
    }

    /**
     * The validated inputs of one support bundle request.
     *
     * @param orgId          the organization to collect for
     * @param correlationId  the correlation id filter, or null
     * @param entityType     the record type filter, or null
     * @param entityId       the record id filter, or null
     * @param workspaceId    the resolved workspace backing the entity filter, or null
     * @param since          the requested window start, or null for the default
     */
    public record SupportBundleRequest(
        int orgId,
        String correlationId,
        String entityType,
        Integer entityId,
        Integer workspaceId,
        Instant since) {
    }

    /**
     * The effective filters recorded in the manifest.
     *
     * @param correlationId the correlation id filter, or null
     * @param entityType    the record type filter, or null
     * @param entityId      the record id filter, or null
     * @param workspaceId   the resolved workspace, or null
     * @param since         the inclusive window start
     * @param until         the generation instant and inclusive window end
     */
    public record SupportBundleFilters(
        String correlationId,
        String entityType,
        Integer entityId,
        Integer workspaceId,
        Instant since,
        Instant until) {
    }

    /**
     * One inventory row in the manifest.
     *
     * @param path       the archive entry name
     * @param mediaType  the entry media type
     * @param byteLength the exact uncompressed length
     * @param sha256     the hex SHA-256 of the uncompressed bytes
     */
    public record ManifestEntry(String path, String mediaType, int byteLength, String sha256) {
    }

    /** Signals that the per-instance support bundle admission limit is saturated. */
    public static class SupportBundleBusyException extends RuntimeException {
        /**
         * Creates the exception.
         *
         * @param message the operator-facing message
         */
        public SupportBundleBusyException(String message) {
            super(message);
        }
    }
}
