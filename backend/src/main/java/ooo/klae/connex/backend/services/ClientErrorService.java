package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.ClientErrorMetadataRow;
import ooo.klae.connex.backend.dto.ClientErrorRequest;
import ooo.klae.connex.backend.dto.ClientErrorSupportRowDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.ClientErrorMapper;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.observability.ReportedError;
import ooo.klae.connex.backend.observability.ReportedError.Source;
import ooo.klae.connex.backend.observability.RequestPathRedactor;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Validates tenant context and reports bounded client error metadata.
 */
@Service
@RequiredArgsConstructor
public class ClientErrorService {
    private final ErrorReporter errorReporter;
    private final ClientErrorRateLimiter rateLimiter;
    private final TenantContext tenantContext;
    private final ClientErrorMapper clientErrorMapper;
    private final ClientAssertedCorrelationPseudonymizer correlationPseudonymizer;

    /**
     * Reports one error for the resolved workspace member.
     *
     * @param request the validated client error
     */
    public void report(ClientErrorRequest request) {
        Integer workspaceId = tenantContext.getWorkspaceId();
        Integer orgId = tenantContext.getOrgId();
        Integer userId = tenantContext.getUserId();
        if (!tenantContext.isResolved()
                || workspaceId == null
                || workspaceId <= 0
                || orgId == null
                || orgId <= 0
                || userId == null
                || userId <= 0) {
            throw new ForbiddenException("A resolved workspace membership is required");
        }
        rateLimiter.acquire(userId);
        String pagePath = RequestPathRedactor.redact(request.path());
        ReportedError reportedError = new ReportedError(
                Source.CLIENT,
                CorrelationIds.current(),
                workspaceId,
                userId,
                request.message(),
                detail(request),
                pagePath);
        errorReporter.report(reportedError);
        clientErrorMapper.insert(
            workspaceId,
            correlationPseudonymizer.forStorage(orgId, reportedError.correlationId()),
            pagePath);
    }

    /**
     * Returns the bounded metadata-only client-error slice for an organization.
     *
     * @param orgId the organization to read
     * @param since the inclusive window start
     * @param until the inclusive window end
     * @param correlationId the optional user-visible correlation identifier
     * @param limit the maximum rows to disclose
     * @return the bounded support slice
     */
    public ClientErrorSlice supportSliceForOrg(
            int orgId,
            Instant since,
            Instant until,
            String correlationId,
            int limit) {
        String correlationHmac = correlationId == null
            ? null
            : correlationPseudonymizer.forStorage(orgId, correlationId);
        List<ClientErrorMetadataRow> rows = clientErrorMapper.findOrgSupportSlice(
            orgId, since, until, correlationHmac, correlationId, limit + 1);
        boolean truncated = rows.size() > limit;
        List<ClientErrorMetadataRow> bounded = truncated
            ? List.copyOf(rows.subList(0, limit))
            : List.copyOf(rows);
        List<ClientErrorSupportRowDto> disclosed = bounded.stream()
            .map(row -> sanitizeSupportRow(row, orgId, correlationId, correlationHmac))
            .toList();
        return new ClientErrorSlice(disclosed, disclosed.size(), truncated);
    }

    /**
     * Returns one safe keyset page for the complete workspace export.
     *
     * @param workspaceId the workspace being exported
     * @param afterId the exclusive row-id cursor
     * @param limit the maximum rows in the page
     * @return the safe client-error projection
     */
    public List<ClientErrorSupportRowDto> workspaceExportPage(
            int workspaceId,
            long afterId,
            int limit) {
        return clientErrorMapper.findWorkspaceExportPage(workspaceId, afterId, limit).stream()
            .map(row -> sanitizeSupportRow(row, requireOrgId(row), null, null))
            .toList();
    }

    /** Removes metadata older than the maximum 30-day support window. */
    public int purgeExpired() {
        return clientErrorMapper.deleteExpired();
    }

    private static String detail(ClientErrorRequest request) {
        StringBuilder detail = new StringBuilder();
        if (request.digest() != null && !request.digest().isBlank()) {
            detail.append("Digest: ").append(request.digest());
        }
        if (request.stack() != null && !request.stack().isBlank()) {
            if (!detail.isEmpty()) {
                detail.append('\n');
            }
            detail.append("Stack:\n").append(request.stack());
        }
        return detail.toString();
    }

    private ClientErrorSupportRowDto sanitizeSupportRow(
            ClientErrorMetadataRow row,
            int orgId,
            String legacyRawCorrelationId,
            String normalizedStorageHmac) {
        String disclosureSource = legacyRawCorrelationId != null
                && Objects.equals(row.storedCorrelationValue(), legacyRawCorrelationId)
            ? normalizedStorageHmac
            : row.storedCorrelationValue();
        return new ClientErrorSupportRowDto(
            row.id(),
            row.workspaceId(),
            correlationPseudonymizer.forDisclosure(orgId, disclosureSource),
            RequestPathRedactor.redact(row.pagePath()),
            row.reportedAt());
    }

    private static int requireOrgId(ClientErrorMetadataRow row) {
        if (row.orgId() == null || row.orgId() <= 0) {
            throw new IllegalStateException("Client-error export row has no organization scope");
        }
        return row.orgId();
    }

    /**
     * A bounded client-error support slice.
     *
     * @param rows the disclosed metadata rows
     * @param rowCount the number of disclosed rows
     * @param truncated whether additional rows matched the window
     */
    public record ClientErrorSlice(
        List<ClientErrorSupportRowDto> rows,
        int rowCount,
        boolean truncated) {
    }
}
