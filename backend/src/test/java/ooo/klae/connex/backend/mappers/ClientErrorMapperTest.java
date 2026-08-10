package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.ClientErrorMetadataRow;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.AuditIntegrityProperties;
import ooo.klae.connex.backend.dto.ClientErrorRequest;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.ClientErrorRateLimiter;
import ooo.klae.connex.backend.services.ClientErrorService;
import ooo.klae.connex.backend.tenant.ControlWorkspaceLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantContext;

class ClientErrorMapperTest extends AbstractMapperTest {
    @Autowired private ClientErrorMapper clientErrorMapper;
    @Autowired private ControlWorkspaceLifecycleMapper controlWorkspaceLifecycleMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void reportingBoundaryPersistsOnlyTheClosedRouteVocabulary() {
        Organization organization = newOrganization();
        Workspace workspace = newWorkspace(organization.getId());
        TenantContext tenantContext = new TenantContext();
        tenantContext.set(workspace.getId(), organization.getId(), 91, "member", null);
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        properties.setHmacSecret("test-correlation-hmac-secret-change-me");
        ClientErrorService service = new ClientErrorService(
            mock(ErrorReporter.class),
            mock(ClientErrorRateLimiter.class),
            tenantContext,
            clientErrorMapper,
            new ClientAssertedCorrelationPseudonymizer(properties));
        MDC.put(CorrelationIds.MDC_KEY, "request_id_123");

        try {
            service.report(new ClientErrorRequest(
                null,
                "Render failed",
                null,
                "/records/private@example.com?message=customer-data"));

            assertEquals(
                "unknown",
                jdbcTemplate.queryForObject(
                    "SELECT page_path FROM client_error WHERE workspace_id = ?",
                    String.class,
                    workspace.getId()));
        } finally {
            MDC.clear();
            tenantContext.clear();
        }
    }

    @Test
    void organizationSliceMatchesCurrentAndLegacyStorageWithoutCorrelationOrOrgLeakage() {
        Organization firstOrg = newOrganization();
        Organization secondOrg = newOrganization();
        Workspace currentWorkspace = newWorkspace(firstOrg.getId());
        Workspace legacyWorkspace = newWorkspace(firstOrg.getId());
        Workspace unrelatedWorkspace = newWorkspace(firstOrg.getId());
        Workspace foreignWorkspace = newWorkspace(secondOrg.getId());
        String rawCorrelationId = "client-correlation-123";
        String correlationHmac = "pseudonymized-client-correlation";
        clientErrorMapper.insert(
            currentWorkspace.getId(), correlationHmac, "/records/contacts/1");
        clientErrorMapper.insert(
            legacyWorkspace.getId(), rawCorrelationId, "/records/contacts/2");
        clientErrorMapper.insert(
            unrelatedWorkspace.getId(), "different-correlation-456", "/records/contacts/3");
        clientErrorMapper.insert(
            foreignWorkspace.getId(), correlationHmac, "/records/contacts/4");

        Instant now = Instant.now();
        List<ClientErrorMetadataRow> rows = clientErrorMapper.findOrgSupportSlice(
            firstOrg.getId(),
            now.minus(1, ChronoUnit.DAYS),
            now.plus(1, ChronoUnit.DAYS),
            correlationHmac,
            rawCorrelationId,
            10);

        assertEquals(
            List.of(correlationHmac, rawCorrelationId),
            rows.stream().map(ClientErrorMetadataRow::storedCorrelationValue).toList());
        assertEquals(
            List.of(currentWorkspace.getId(), legacyWorkspace.getId()),
            rows.stream().map(ClientErrorMetadataRow::workspaceId).toList());
    }

    @Test
    void workspaceExportAndLifecycleDeletionStayScopedToTheExactWorkspace() {
        Organization organization = newOrganization();
        Workspace target = newWorkspace(organization.getId());
        Workspace other = newWorkspace(organization.getId());
        clientErrorMapper.insert(target.getId(), "target-correlation", "/dashboard");
        clientErrorMapper.insert(other.getId(), "other-correlation", "/dashboard");
        ControlWorkspaceLifecycleRegistry.TableLifecycle declaration =
            ControlWorkspaceLifecycleRegistry.requireRegistered(
                ControlWorkspaceLifecycleRegistry.declarations().get("client_error"));

        List<ClientErrorMetadataRow> exported =
            clientErrorMapper.findWorkspaceExportPage(target.getId(), 0, 10);

        assertEquals(
            List.of(target.getId()),
            exported.stream().map(ClientErrorMetadataRow::workspaceId).toList());
        assertEquals(
            1L,
            controlWorkspaceLifecycleMapper.countRows(target.getId(), declaration));
        assertEquals(
            1,
            controlWorkspaceLifecycleMapper.deleteBatch(target.getId(), declaration, 10));
        assertEquals(
            0L,
            controlWorkspaceLifecycleMapper.countRows(target.getId(), declaration));
        assertEquals(
            1L,
            controlWorkspaceLifecycleMapper.countRows(other.getId(), declaration));
    }

    @Test
    void retentionDeletesOnlyRowsOlderThanThirtyDaysUsingTheLiveSql() {
        Workspace target = newWorkspace(newOrganization().getId());
        String expiredCorrelation = "expired-correlation-" + unique();
        String retainedCorrelation = "retained-correlation-" + unique();
        clientErrorMapper.insert(target.getId(), expiredCorrelation, "/dashboard");
        clientErrorMapper.insert(target.getId(), retainedCorrelation, "/dashboard");
        jdbcTemplate.update(
            "UPDATE client_error SET reported_at = "
                + "DATE_SUB(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 DAY), INTERVAL 1 HOUR)"
                + " WHERE correlation_id = ?",
            expiredCorrelation);
        jdbcTemplate.update(
            "UPDATE client_error SET reported_at = "
                + "DATE_ADD(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 DAY), INTERVAL 1 HOUR)"
                + " WHERE correlation_id = ?",
            retainedCorrelation);

        assertEquals(1, clientErrorMapper.deleteExpired());
        assertEquals(0, countByCorrelation(expiredCorrelation));
        assertEquals(1, countByCorrelation(retainedCorrelation));
    }

    private int countByCorrelation(String correlationId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM client_error WHERE correlation_id = ?",
            Integer.class,
            correlationId);
    }

    private Organization newOrganization() {
        String suffix = unique();
        Organization organization = new Organization();
        organization.setName("Organization " + suffix);
        organization.setSlug("org-" + suffix);
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspace(int orgId) {
        String suffix = unique();
        Workspace created = new Workspace();
        created.setName("Workspace " + suffix);
        created.setSlug("workspace-" + suffix);
        created.setOrgId(orgId);
        workspaceMapper.insert(created);
        return created;
    }
}
