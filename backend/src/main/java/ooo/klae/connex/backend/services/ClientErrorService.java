package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ClientErrorRequest;
import ooo.klae.connex.backend.dto.ClientErrorSupportRowDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.ClientErrorMapper;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.observability.ReportedError;
import ooo.klae.connex.backend.observability.ReportedError.Source;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Validates tenant context and reports bounded client error metadata.
 */
@Service
@RequiredArgsConstructor
public class ClientErrorService {
    private static final Pattern SAFE_FRAMEWORK_DIGEST =
        Pattern.compile("^[0-9]{1,10}(?:@E[0-9]{1,6})?$");

    private final ErrorReporter errorReporter;
    private final ClientErrorRateLimiter rateLimiter;
    private final TenantContext tenantContext;
    private final ClientErrorMapper clientErrorMapper;

    /**
     * Reports one error for the resolved workspace member.
     *
     * @param request the validated client error
     */
    public void report(ClientErrorRequest request) {
        Integer workspaceId = tenantContext.getWorkspaceId();
        Integer userId = tenantContext.getUserId();
        if (!tenantContext.isResolved()
                || workspaceId == null
                || workspaceId <= 0
                || userId == null
                || userId <= 0) {
            throw new ForbiddenException("A resolved workspace membership is required");
        }
        rateLimiter.acquire(userId);
        ReportedError reportedError = new ReportedError(
                Source.CLIENT,
                CorrelationIds.current(),
                workspaceId,
                userId,
                request.message(),
                detail(request),
                request.path());
        errorReporter.report(reportedError);
        clientErrorMapper.insert(
            workspaceId,
            reportedError.correlationId(),
            safeFrameworkDigest(request.digest()),
            reportedError.path());
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
        List<ClientErrorSupportRowDto> rows = clientErrorMapper.findOrgSupportSlice(
            orgId, since, until, correlationId, limit + 1);
        boolean truncated = rows.size() > limit;
        List<ClientErrorSupportRowDto> bounded = truncated
            ? List.copyOf(rows.subList(0, limit))
            : List.copyOf(rows);
        List<ClientErrorSupportRowDto> disclosed = bounded.stream()
            .map(ClientErrorService::sanitizeSupportRow)
            .toList();
        return new ClientErrorSlice(disclosed, disclosed.size(), truncated);
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

    private static ClientErrorSupportRowDto sanitizeSupportRow(ClientErrorSupportRowDto row) {
        return new ClientErrorSupportRowDto(
            row.id(),
            row.workspaceId(),
            row.correlationId(),
            safeFrameworkDigest(row.digest()),
            row.pagePath(),
            row.reportedAt());
    }

    private static String safeFrameworkDigest(String digest) {
        return digest != null && SAFE_FRAMEWORK_DIGEST.matcher(digest).matches() ? digest : null;
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
