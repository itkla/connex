package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ClientErrorRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
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
    private static final int MAX_DETAIL_LENGTH = 8_192;

    private final ErrorReporter errorReporter;
    private final ClientErrorRateLimiter rateLimiter;
    private final TenantContext tenantContext;

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
        errorReporter.report(new ReportedError(
                Source.CLIENT,
                CorrelationIds.current(),
                workspaceId,
                userId,
                request.message(),
                detail(request),
                request.path()));
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
        if (detail.length() <= MAX_DETAIL_LENGTH) {
            return detail.toString();
        }
        int end = MAX_DETAIL_LENGTH;
        if (Character.isHighSurrogate(detail.charAt(end - 1))) {
            end--;
        }
        return detail.substring(0, end);
    }
}
