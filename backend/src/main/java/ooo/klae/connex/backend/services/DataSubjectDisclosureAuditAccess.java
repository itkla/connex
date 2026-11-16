package ooo.klae.connex.backend.services;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.DataSubjectDisclosureJsonWriter.Fingerprint;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Suspends the tenant snapshot before routing an independently committed strict control audit. */
@Component
@RequiredArgsConstructor
public class DataSubjectDisclosureAuditAccess {
    private final TenantWorkScope tenantWorkScope;
    private final AuditService auditService;

    /** Restores the tenant catalog before Spring resumes the same tenant transaction resources. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void record(int orgId, long requestId, int personId, int workspaceId, Fingerprint fingerprint) {
        tenantWorkScope.unrouted(() -> {
            auditService.recordStrictIndependentScoped("appi.subject_request.disclosure", "organization",
                orgId, null, orgId, "Subject request " + requestId, "Subject-scoped disclosure export assembled",
                Map.of("requestId", requestId, "subjectPersonId", personId, "subjectWorkspaceId", workspaceId,
                    "fingerprintVersion", 1, "noteCount", fingerprint.noteCount(),
                    "notesSha256", fingerprint.notesSha256(), "jsonByteLength", fingerprint.byteLength(),
                    "jsonSha256", fingerprint.sha256()));
            return null;
        });
    }
}
