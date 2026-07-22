package ooo.klae.connex.backend.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.SecretStoreDiagnosticsDto;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SecretStoreLifecycleServiceTest {

    @Autowired private SecretValueMapper secretValueMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void diagnosticsReportsStaleRowsAsHealthyWhenOldKeyIsConfigured() {
        int workspaceId = workspaceId();
        String oldKey = base64Key((byte) 11);
        String newKey = base64Key((byte) 12);
        String reference = store("old-v1", oldKey, Map.of(), Set.of())
                .put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "secret");

        SecretStoreDiagnosticsDto diagnostics = lifecycle("new-v2", newKey,
                Map.of("old-v1", oldKey), Set.of()).diagnosticsForWorkspace(workspaceId);

        assertTrue(diagnostics.isHealthy());
        assertEquals(1, diagnostics.getTotalSecrets());
        assertEquals(1, diagnostics.getStaleSecrets());
        assertEquals(0, diagnostics.getMissingKeySecrets());
        assertEquals(0, diagnostics.getMismatchedSecrets());
        assertEquals("old-v1", secretValueMapper.findById(SecretReference.parse(reference).id()).getKeyId());
    }

    @Test
    void diagnosticsReportsMissingStoredKeys() {
        int workspaceId = workspaceId();
        String oldKey = base64Key((byte) 13);
        String newKey = base64Key((byte) 14);
        store("old-v1", oldKey, Map.of(), Set.of())
                .put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "secret");

        SecretStoreDiagnosticsDto diagnostics = lifecycle("new-v2", newKey, Map.of(), Set.of())
                .diagnosticsForWorkspace(workspaceId);

        assertFalse(diagnostics.isHealthy());
        assertEquals(1, diagnostics.getMissingKeySecrets());
        assertEquals("missing", diagnostics.getFailures().getFirst().getStatus());
    }

    @Test
    void diagnosticsReportsDisabledStoredKeys() {
        int workspaceId = workspaceId();
        String oldKey = base64Key((byte) 15);
        String newKey = base64Key((byte) 16);
        store("old-v1", oldKey, Map.of(), Set.of())
                .put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "secret");

        SecretStoreDiagnosticsDto diagnostics = lifecycle("new-v2", newKey,
                Map.of("old-v1", oldKey), Set.of("old-v1")).diagnosticsForWorkspace(workspaceId);

        assertFalse(diagnostics.isHealthy());
        assertEquals(1, diagnostics.getDisabledKeySecrets());
        assertEquals("disabled", diagnostics.getFailures().getFirst().getStatus());
    }

    @Test
    void diagnosticsReportsMismatchedConfiguredKeyMaterial() {
        int workspaceId = workspaceId();
        String oldKey = base64Key((byte) 17);
        String wrongOldKey = base64Key((byte) 18);
        String newKey = base64Key((byte) 19);
        store("old-v1", oldKey, Map.of(), Set.of())
                .put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "secret");

        SecretStoreDiagnosticsDto diagnostics = lifecycle("new-v2", newKey,
                Map.of("old-v1", wrongOldKey), Set.of()).diagnosticsForWorkspace(workspaceId);

        assertFalse(diagnostics.isHealthy());
        assertEquals(1, diagnostics.getMismatchedSecrets());
        assertEquals("mismatched_key", diagnostics.getFailures().getFirst().getStatus());
    }

    @Test
    void diagnosticsReportsUnsupportedAlgorithmsWithoutDecryptingPayloads() {
        int workspaceId = workspaceId();
        String key = base64Key((byte) 20);
        String reference = store("active-v1", key, Map.of(), Set.of())
                .put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "secret");
        jdbcTemplate.update("UPDATE secret_value SET key_algorithm = ? WHERE id = ?",
                "AES-GCM-legacy", SecretReference.parse(reference).id());

        SecretStoreDiagnosticsDto diagnostics = lifecycle("active-v1", key, Map.of(), Set.of())
                .diagnosticsForWorkspace(workspaceId);

        assertFalse(diagnostics.isHealthy());
        assertEquals(1, diagnostics.getUnsupportedAlgorithmSecrets());
        assertEquals("unsupported_algorithm", diagnostics.getFailures().getFirst().getStatus());
    }

    @Test
    void scopedDiagnosticsDoNotExposeUnrelatedKeyringMetadataAndAreAudited() {
        int workspaceId = workspaceId();
        String oldKey = base64Key((byte) 22);
        String newKey = base64Key((byte) 23);
        String unrelatedKey = base64Key((byte) 24);
        store("old-v1", oldKey, Map.of(), Set.of())
                .put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "secret");
        SecretStoreProperties properties = properties("new-v2", newKey,
                Map.of("old-v1", oldKey, "unrelated-v9", unrelatedKey), Set.of("unrelated-v9"));
        Map<String, SecretStoreProperties.KeyMetadata> metadata = new LinkedHashMap<>();
        metadata.put("unrelated-v9", metadata("security", "customer"));
        properties.setMetadata(metadata);
        AuditService auditService = mock(AuditService.class);
        SecretStoreLifecycleService service = new SecretStoreLifecycleService(secretValueMapper,
                new SecretStoreCrypto(properties), properties, auditService);

        SecretStoreDiagnosticsDto diagnostics = service.diagnosticsForWorkspace(workspaceId);

        assertTrue(diagnostics.getKeys().stream().anyMatch(key -> "old-v1".equals(key.getKeyId())));
        assertTrue(diagnostics.getKeys().stream().anyMatch(key -> "new-v2".equals(key.getKeyId())));
        assertTrue(diagnostics.getKeys().stream().noneMatch(key -> "unrelated-v9".equals(key.getKeyId())));
        verify(auditService).recordScoped(eq("secret_store.diagnostics.read"), eq("workspace"), eq(workspaceId),
                eq(workspaceId), isNull(), eq("secret_store"), eq("Secret store diagnostics read"), any());
    }

    private SecretStore store(String keyId, String masterKey, Map<String, String> keys, Set<String> disabledKeyIds) {
        SecretStoreProperties properties = properties(keyId, masterKey, keys, disabledKeyIds);
        return new SecretStore(secretValueMapper, userMapper, workspaceMapper, organizationMapper,
                new SecretStoreCrypto(properties), properties, mock(AuditService.class));
    }

    private SecretStoreLifecycleService lifecycle(String keyId, String masterKey,
            Map<String, String> keys, Set<String> disabledKeyIds) {
        SecretStoreProperties properties = properties(keyId, masterKey, keys, disabledKeyIds);
        return new SecretStoreLifecycleService(secretValueMapper, new SecretStoreCrypto(properties),
                properties, mock(AuditService.class));
    }

    private static SecretStoreProperties properties(String keyId, String masterKey,
            Map<String, String> keys, Set<String> disabledKeyIds) {
        SecretStoreProperties properties = new SecretStoreProperties();
        properties.setKeyId(keyId);
        properties.setMasterKey(masterKey);
        properties.setKeys(keys);
        properties.setDisabledKeyIds(disabledKeyIds);
        return properties;
    }

    private static SecretStoreProperties.KeyMetadata metadata(String owner, String scope) {
        SecretStoreProperties.KeyMetadata metadata = new SecretStoreProperties.KeyMetadata();
        metadata.setOwner(owner);
        metadata.setScope(scope);
        return metadata;
    }

    private int workspaceId() {
        int orgId = orgId();
        Workspace workspace = new Workspace();
        workspace.setOrgId(orgId);
        workspace.setName("Secret Lifecycle Test Workspace");
        workspace.setSlug("secret-lifecycle-test-" + UUID.randomUUID());
        workspaceMapper.insert(workspace);
        return workspace.getId();
    }

    private int orgId() {
        Organization organization = new Organization();
        organization.setName("Secret Lifecycle Test Org");
        organization.setSlug("secret-lifecycle-test-" + UUID.randomUUID());
        organizationMapper.insert(organization);
        return organization.getId();
    }

    private static String base64Key(byte fill) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = fill;
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
