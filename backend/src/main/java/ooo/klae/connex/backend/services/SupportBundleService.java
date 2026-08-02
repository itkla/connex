package ooo.klae.connex.backend.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.observability.CorrelationIds;
import tools.jackson.databind.ObjectMapper;

/**
 * Assembles the redacted support bundle served by {@code SupportBundleController}.
 *
 * <p>Every entry is built by constructive allowlisting from a source that was reviewed as safe.
 * Nothing is serialized broadly and scrubbed afterwards, because scrubbing fails open the moment a
 * new field appears upstream. When a source cannot be proven safe or is unavailable, the file is
 * omitted and the omission is recorded in the manifest, so a reader can distinguish "collected and
 * empty" from "not collected".
 *
 * <p>{@code manifest.json} is written last and carries a SHA-256 and byte length for every other
 * entry, so a reader can prove the archive is intact and complete.
 *
 * <p><strong>Why this is assembled in full rather than streamed.</strong> A support bundle is
 * bounded metadata — capability booleans, allowlisted configuration, migration rows, and an audit
 * slice capped at {@link #AUDIT_SLICE_LIMIT} rows — so it is kilobytes in practice and bounded in
 * megabytes. The tenant export streams because it carries an entire workspace's data and cannot be
 * held in memory; that difference is the only reason it needs an async writer, a monotonic
 * deadline, and a cancellation state machine. Assembling here keeps the work on the request
 * thread, where the tenant routing and security context the mappers depend on are actually
 * installed. An async writer would run without them, could keep writing after the container had
 * completed the response, and would hold a thread in the shared managed-content pool for the whole
 * read.
 */
@Service
@RequiredArgsConstructor
public class SupportBundleService {
    private static final Logger log = LoggerFactory.getLogger(SupportBundleService.class);

    private static final String AUDIT_ACTION = "org.support_bundle.download";
    private static final String AUDIT_OUTCOME_ACTION = "org.support_bundle.completed";
    private static final int SCHEMA_VERSION = 1;
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    private static final Duration MAXIMUM_WINDOW = Duration.ofDays(30);
    private static final String MANIFEST_ENTRY = "manifest.json";

    /** The maximum number of audit rows a slice may carry. */
    static final int AUDIT_SLICE_LIMIT = 10_000;

    /**
     * The uncompressed ceiling for one bundle. The audit slice dominates it: {@link
     * #AUDIT_SLICE_LIMIT} rows of bounded columns is roughly three megabytes, so this leaves ample
     * headroom while still refusing to build something unbounded in memory.
     */
    static final long MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;

    /**
     * The wall-clock budget for assembling one bundle. Assembly is synchronous and holds a request
     * thread, so a pathological query must fail rather than pin the thread indefinitely.
     */
    static final Duration ASSEMBLY_BUDGET = Duration.ofSeconds(30);

    /**
     * Bounds concurrent assembly so a burst cannot run many {@link #AUDIT_SLICE_LIMIT}-row audit
     * scans at once. This is a per-JVM bound, not a cluster-wide one.
     */
    static final int MAX_CONCURRENT_BUNDLES = 4;

    private final OrgMemberService orgMemberService;
    private final SessionSecurityService sessionSecurityService;
    private final SupportBundleReadinessService readinessService;
    private final SupportBundleConfigService configService;
    private final MigrationHistoryService migrationHistoryService;
    private final ProductVersionService productVersionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Semaphore admission = new Semaphore(MAX_CONCURRENT_BUNDLES);

    /**
     * Authorizes the request, validates the filters, and returns the assembled bundle.
     *
     * <p>Authorization is evaluated before any content is read, and the admission permit is taken
     * before the start audit is written, so a rejected or saturated request never leaves a durable
     * record claiming a bundle was produced.
     *
     * @param request the validated bundle request
     * @param actorId the authenticated caller
     * @return the assembled bundle
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SupportBundle generate(SupportBundleRequest request, int actorId) {
        orgMemberService.requireOrgAdmin(request.orgId(), actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);

        Instant generatedAt = clock.instant();
        SupportBundleFilters filters = new SupportBundleFilters(
            request.correlationId(),
            request.entityType(),
            request.entityId(),
            request.workspaceId(),
            resolveSince(request.since(), generatedAt),
            generatedAt);

        if (!admission.tryAcquire()) {
            auditOutcome(request.orgId(), "refused_busy", 0);
            throw new TooManyRequestsException(
                "Too many support bundles are being assembled; retry shortly");
        }
        try {
            auditStart(request.orgId(), filters);
            SupportBundle bundle = assemble(request.orgId(), filters);
            auditOutcome(request.orgId(), "success", bundle.content().length);
            return bundle;
        } catch (RuntimeException | Error failure) {
            try {
                auditOutcome(request.orgId(), "failed", 0);
            } catch (RuntimeException | Error auditFailure) {
                // The original failure is what the caller needs; an audit problem must not replace
                // it, but it must not vanish either.
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        } finally {
            admission.release();
        }
    }

    private void auditStart(int orgId, SupportBundleFilters filters) {
        auditService.recordStrictIndependentScoped(
            AUDIT_ACTION,
            "organization",
            orgId,
            null,
            orgId,
            "organization:" + orgId,
            "Support bundle authorized and assembly started",
            Map.of(
                "since", filters.since().toString(),
                "until", filters.until().toString(),
                "correlationFiltered", filters.correlationId() != null,
                "entityFiltered", filters.entityType() != null));
    }

    /**
     * Records the terminal outcome, so a failed bundle is distinguishable in the audit log from
     * one that was delivered whole.
     */
    private void auditOutcome(int orgId, String outcome, int byteLength) {
        auditService.recordStrictIndependentScoped(
            AUDIT_OUTCOME_ACTION,
            "organization",
            orgId,
            null,
            orgId,
            "organization:" + orgId,
            "Support bundle assembly " + outcome,
            Map.of("outcome", outcome, "byteLength", byteLength));
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

    private SupportBundle assemble(int orgId, SupportBundleFilters filters) {
        Assembly assembly = new Assembly(filters, clock.instant().plus(ASSEMBLY_BUDGET));
        AtomicReference<AuditService.AuditSlice> slice = new AtomicReference<>();
        try (ZipOutputStream zip = new ZipOutputStream(assembly.buffer, StandardCharsets.UTF_8)) {
            // Every source is read inside collect(), never before it. Reading eagerly would put
            // the expensive work ahead of the budget check and, worse, would make a failing source
            // abort the whole bundle instead of taking the declared-omission path this method
            // promises.
            collect(assembly, zip, "readiness.json", "application/json",
                () -> objectMapper.writeValueAsBytes(readinessService.readiness(orgId)));
            collect(assembly, zip, "config.json", "application/json", () -> {
                SupportBundleConfigService.SafeConfiguration configuration =
                    configService.safeConfiguration();
                configuration.omissions().forEach(assembly::omit);
                return objectMapper.writeValueAsBytes(configuration.values());
            });
            collect(assembly, zip, "migrations.json", "application/json",
                () -> objectMapper.writeValueAsBytes(migrationHistoryService.history()));
            collect(assembly, zip, "audit-slice.csv", "text/csv", () -> {
                AuditService.AuditSlice collected = auditSlice(orgId, filters);
                slice.set(collected);
                return collected.csv().getBytes(StandardCharsets.UTF_8);
            });

            assembly.omit("client-errors.json", "no_persisted_source");
            assembly.omit("job-runs.json", "job_run_not_available");
            writeManifest(zip, orgId, filters, assembly, slice.get());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to assemble the support bundle", exception);
        }
        return new SupportBundle(
            "connex-org-" + orgId + "-support-bundle.zip",
            assembly.buffer.toByteArray());
    }

    /**
     * Emits one bundle entry, or records exactly one omission explaining why it is absent.
     *
     * <p>This is the only place an entry may be added, so an entry and its omission cannot drift
     * apart: every source produces exactly one of the two outcomes and there is no third path in
     * which a file silently vanishes from an otherwise successful bundle. A source that fails is
     * not fatal — a bundle missing one section is far more useful to support than no bundle — but
     * the failure is always declared, and the reason is a fixed code rather than an exception
     * message, which could carry the very content the bundle must not disclose.
     */
    private void collect(
            Assembly assembly,
            ZipOutputStream zip,
            String path,
            String mediaType,
            EntrySupplier supplier) throws IOException {
        assembly.requireWithinBudget(clock.instant());
        byte[] content;
        try {
            content = supplier.get();
        } catch (RuntimeException exception) {
            log.warn("Support bundle source {} failed; declaring the omission", path, exception);
            assembly.omit(path, "source_failed");
            return;
        }
        assembly.requireWithinCap(path, content.length);
        writeEntry(zip, assembly.inventory, assembly.filters, path, mediaType, content);
    }

    /** Produces the bytes of one bundle entry. */
    @FunctionalInterface
    private interface EntrySupplier {
        byte[] get();
    }

    /** Mutable assembly state: the buffer, the inventory, and the declared omissions. */
    private static final class Assembly {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final List<ManifestEntry> inventory = new ArrayList<>();
        private final Map<String, Object> omissions = new LinkedHashMap<>();
        private final SupportBundleFilters filters;
        private final Instant deadline;
        private long uncompressedBytes;

        private Assembly(SupportBundleFilters filters, Instant deadline) {
            this.filters = filters;
            this.deadline = deadline;
        }

        private void omit(String path, Object reason) {
            omissions.put(path, reason);
        }

        /**
         * Fails closed if an entry would push the bundle past its ceiling.
         *
         * <p>The audit slice is capped at {@link #AUDIT_SLICE_LIMIT} rows of bounded columns, so a
         * real bundle is a few megabytes at most. Exceeding the cap means an assumption about
         * field sizes is wrong, and truncating silently would hand support an archive that looks
         * complete but is not.
         */
        private void requireWithinCap(String path, long length) {
            if (uncompressedBytes + length > MAX_UNCOMPRESSED_BYTES) {
                throw new SupportBundleTooLargeException(
                    "Support bundle exceeded its uncompressed ceiling while adding " + path
                        + "; narrow the window with since or add an entity filter");
            }
            uncompressedBytes += length;
        }

        private void requireWithinBudget(Instant now) {
            if (now.isAfter(deadline)) {
                throw new SupportBundleTooLargeException(
                    "Support bundle assembly exceeded its time budget; narrow the window with "
                        + "since or add an entity filter");
            }
        }
    }

    private AuditService.AuditSlice auditSlice(int orgId, SupportBundleFilters filters) {
        String requestId = filters.correlationId();
        if (filters.entityType() != null && filters.workspaceId() == null) {
            throw new IllegalStateException(
                "An entity-filtered bundle requires a resolved workspace; refusing to widen to the "
                    + "organization slice the manifest does not advertise");
        }
        if (filters.entityType() != null) {
            return auditService.supportSliceForEntity(
                filters.workspaceId(),
                orgId,
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

    private void writeEntry(
            ZipOutputStream zip,
            List<ManifestEntry> inventory,
            SupportBundleFilters filters,
            String path,
            String mediaType,
            byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(filters.until().toEpochMilli());
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
        inventory.add(new ManifestEntry(path, mediaType, content.length, sha256(content)));
    }

    private void writeManifest(
            ZipOutputStream zip,
            int orgId,
            SupportBundleFilters filters,
            Assembly assembly,
            AuditService.AuditSlice slice) throws IOException {
        List<ManifestEntry> inventory = assembly.inventory;
        Map<String, Object> omissions = assembly.omissions;
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("productVersion", productVersionService.version());
        manifest.put("generatedAt", filters.until().toString());
        manifest.put("orgId", orgId);
        manifest.put("filters", manifestFilters(filters));
        inventory.sort(Comparator.comparing(ManifestEntry::path));
        manifest.put("files", inventory);
        manifest.put("auditSliceRowCount", slice == null ? null : slice.rowCount());
        manifest.put("auditSliceTruncated", slice == null ? null : slice.truncated());
        manifest.put("auditSliceLimit", AUDIT_SLICE_LIMIT);
        // A correlation filter matches the server-minted request id, which a user cannot quote, so
        // an empty result under that filter is reported as inconclusive rather than as evidence
        // that nothing happened.
        manifest.put("auditSliceInconclusive",
            filters.correlationId() != null && slice != null && slice.rowCount() == 0);
        manifest.put("omissions", omissions);
        ZipEntry entry = new ZipEntry(MANIFEST_ENTRY);
        entry.setTime(filters.until().toEpochMilli());
        zip.putNextEntry(entry);
        zip.write(objectMapper.writeValueAsBytes(manifest));
        zip.closeEntry();
    }

    private static Map<String, Object> manifestFilters(SupportBundleFilters filters) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("correlationId", filters.correlationId());
        values.put("entityType", filters.entityType());
        values.put("entityId", filters.entityId());
        values.put("resolvedWorkspaceId", filters.workspaceId());
        values.put("since", filters.since().toString());
        values.put("until", filters.until().toString());
        return values;
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
     * @param orgId         the organization to collect for
     * @param correlationId the correlation id filter, or null
     * @param entityType    the record type filter, or null
     * @param entityId      the record id filter, or null
     * @param workspaceId   the resolved workspace backing the entity filter, or null
     * @param since         the requested window start, or null for the default
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

    /** Signals that a bundle would exceed its size or time ceiling. */
    public static class SupportBundleTooLargeException extends RuntimeException {
        /**
         * Creates the exception.
         *
         * @param message the operator-facing message, including how to narrow the request
         */
        public SupportBundleTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * A fully assembled bundle ready to be returned to the caller.
     *
     * @param filename the trusted response filename
     * @param content  the archive bytes
     */
    public record SupportBundle(String filename, byte[] content) {
    }
}
