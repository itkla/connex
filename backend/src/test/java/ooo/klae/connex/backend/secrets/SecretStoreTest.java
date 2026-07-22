package ooo.klae.connex.backend.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Base64;
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
import ooo.klae.connex.backend.exceptions.SecretUnavailableException;
import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SecretStoreTest {

    @Autowired private SecretValueMapper secretValueMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void putThenGet_roundTripsWithoutPlaintextPersistence() {
        String plaintext = "smtp-secret-value";
        int workspaceId = workspaceId();
        SecretStore testStore = store();

        String reference = testStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, plaintext);

        assertTrue(SecretReference.isReference(reference));
        assertFalse(reference.contains(plaintext));
        assertEquals(plaintext, testStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, reference));

        StoredSecret stored = secretValueMapper.findById(SecretReference.parse(reference).id());
        assertNotNull(stored);
        assertEquals("workspace", stored.getScopeType());
        assertEquals(workspaceId, stored.getScopeId());
        assertEquals("workspace.smtp.password", stored.getPurpose());
        assertEquals(SecretStoreCrypto.KEY_ALGORITHM, stored.getKeyAlgorithm());
        assertEquals(SecretStoreCrypto.DATA_ALGORITHM, stored.getDataAlgorithm());
        assertFalse(stored.getEncryptedDataKey().contains(plaintext));
        assertFalse(stored.getCiphertext().contains(plaintext));
    }

    @Test
    void put_isNonDeterministicForSamePlaintext() {
        SecretStore testStore = store();
        String firstReference = testStore.put(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId(), "same-secret");
        StoredSecret first = secretValueMapper.findById(SecretReference.parse(firstReference).id());

        String secondReference = testStore.put(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId(), "same-secret");
        StoredSecret second = secretValueMapper.findById(SecretReference.parse(secondReference).id());

        assertNotEquals(first.getEncryptedDataKey(), second.getEncryptedDataKey());
        assertNotEquals(first.getCiphertext(), second.getCiphertext());
    }

    @Test
    void put_sameScopeAndPurposeRotatesExistingSlot() {
        int workspaceId = workspaceId();
        SecretStore testStore = store();
        String firstReference = testStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "first-secret");
        StoredSecret first = secretValueMapper.findById(SecretReference.parse(firstReference).id());

        String secondReference = testStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "second-secret");
        StoredSecret second = secretValueMapper.findById(SecretReference.parse(secondReference).id());
        Long rowCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM secret_value
                WHERE scope_type = 'workspace'
                  AND scope_id = ?
                  AND purpose = 'workspace.smtp.password'
                """, Long.class, workspaceId);

        assertEquals(firstReference, secondReference);
        assertEquals(1L, rowCount);
        assertNotEquals(first.getEncryptedDataKey(), second.getEncryptedDataKey());
        assertNotEquals(first.getCiphertext(), second.getCiphertext());
        assertEquals("second-secret", testStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD,
                workspaceId, firstReference));
    }

    @Test
    void get_tamperedCiphertextFails() {
        int orgId = orgId();
        SecretStore testStore = store("tamper-v1", base64Key((byte) 21), Map.of(), Set.of(), true);
        String reference = testStore.put(SecretPurpose.ORG_SSO_SAML_SP_PRIVATE_KEY, orgId, "private-key");
        StoredSecret stored = secretValueMapper.findById(SecretReference.parse(reference).id());
        byte[] blob = Base64.getDecoder().decode(stored.getCiphertext());
        blob[blob.length - 1] ^= 0x01;
        stored.setCiphertext(Base64.getEncoder().encodeToString(blob));
        secretValueMapper.upsert(stored);

        assertThrows(IllegalStateException.class,
                () -> testStore.get(SecretPurpose.ORG_SSO_SAML_SP_PRIVATE_KEY, orgId, reference));
    }

    @Test
    void get_scopeMismatchFailsClosed() {
        int workspaceId = workspaceId();
        SecretStore testStore = store();
        String reference = testStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "smtp-password");

        assertThrows(IllegalStateException.class,
                () -> testStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId(), reference));
        assertThrows(IllegalStateException.class,
                () -> testStore.get(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, workspaceId, reference));
    }

    @Test
    void delete_scopeMismatchDoesNotDeleteSecret() {
        int workspaceId = workspaceId();
        SecretStore testStore = store();
        String reference = testStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "smtp-password");

        testStore.delete(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId(), reference);

        assertTrue(testStore.exists(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, reference));
        assertEquals("smtp-password", testStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, reference));
    }

    @Test
    void existsAndDelete_ignoreMalformedReferences() {
        int workspaceId = workspaceId();
        SecretStore testStore = store();

        assertFalse(testStore.exists(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "secret:v1:not-a-number"));
        assertDoesNotThrow(() -> testStore.delete(SecretPurpose.WORKSPACE_SMTP_PASSWORD,
                workspaceId, "secret:v1:not-a-number"));
    }

    @Test
    void workspaceDeleteCascadesWorkspaceSecretRows() {
        int workspaceId = workspaceId();
        String reference = store().put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "smtp-password");
        long secretId = SecretReference.parse(reference).id();

        jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);

        assertNull(secretValueMapper.findById(secretId));
    }

    @Test
    void orgDeleteCascadesOrgSecretRows() {
        int orgId = orgId();
        String reference = store().put(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId, "oidc-password");
        long secretId = SecretReference.parse(reference).id();

        jdbcTemplate.update("DELETE FROM organization WHERE id = ?", orgId);

        assertNull(secretValueMapper.findById(secretId));
    }

    @Test
    void missingMasterKeyRefusesEncryption() {
        SecretStoreProperties properties = new SecretStoreProperties();
        SecretStoreCrypto crypto = new SecretStoreCrypto(properties);

        assertFalse(crypto.isAvailable());
        assertThrows(SecretUnavailableException.class, () -> crypto.encrypt("value", "aad"));
    }

    @Test
    void lazyRewrapMovesStoredSecretsToActiveKeyAfterRead() {
        int workspaceId = workspaceId();
        String oldKey = base64Key((byte) 1);
        String newKey = base64Key((byte) 2);
        SecretStore oldStore = store("old-v1", oldKey, Map.of(), Set.of(), true);
        String reference = oldStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "old-key-secret");

        SecretStore rotatedStore = store("new-v2", newKey, Map.of("old-v1", oldKey), Set.of(), true);

        assertTrue(rotatedStore.hasKey("old-v1"));
        assertEquals("old-key-secret", rotatedStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD,
                workspaceId, reference));
        StoredSecret rewrapped = secretValueMapper.findById(SecretReference.parse(reference).id());
        assertEquals("new-v2", rewrapped.getKeyId());
    }

    @Test
    void batchRewrapMovesStoredSecretsToActiveKeyWithoutLazyReads() {
        jdbcTemplate.update("DELETE FROM secret_value");
        int workspaceId = workspaceId();
        String oldKey = base64Key((byte) 3);
        String newKey = base64Key((byte) 4);
        SecretStore oldStore = store("old-v1", oldKey, Map.of(), Set.of(), true);
        String reference = oldStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "old-key-secret");

        SecretStore rotatedStore = store("new-v2", newKey, Map.of("old-v1", oldKey), Set.of(), false);

        assertEquals(1, rotatedStore.rewrapBatchToActiveKey(10));
        StoredSecret rewrapped = secretValueMapper.findById(SecretReference.parse(reference).id());
        assertEquals("new-v2", rewrapped.getKeyId());
        assertEquals("old-key-secret", rotatedStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD,
                workspaceId, reference));
    }

    @Test
    void disabledStoredKeyFailsClosed() {
        int workspaceId = workspaceId();
        String oldKey = base64Key((byte) 5);
        String newKey = base64Key((byte) 6);
        SecretStore oldStore = store("old-v1", oldKey, Map.of(), Set.of(), true);
        String reference = oldStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "old-key-secret");

        SecretStore revokedStore = store("new-v2", newKey, Map.of("old-v1", oldKey), Set.of("old-v1"), true);

        assertThrows(SecretUnavailableException.class, () -> revokedStore.get(
                SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, reference));
    }

    @Test
    void unsupportedStoredAlgorithmFailsClosedForUseAndBatchRewrap() {
        jdbcTemplate.update("DELETE FROM secret_value");
        int workspaceId = workspaceId();
        String oldKey = base64Key((byte) 7);
        String newKey = base64Key((byte) 8);
        SecretStore oldStore = store("old-v1", oldKey, Map.of(), Set.of(), true);
        String reference = oldStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "old-key-secret");
        jdbcTemplate.update("UPDATE secret_value SET data_algorithm = ? WHERE id = ?",
                "AES-256-GCM-legacy", SecretReference.parse(reference).id());
        SecretStore rotatedStore = store("new-v2", newKey, Map.of("old-v1", oldKey), Set.of(), true);

        assertThrows(SecretUnavailableException.class, () -> rotatedStore.get(
                SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, reference));
        assertThrows(SecretUnavailableException.class, () -> rotatedStore.rewrapBatchToActiveKey(10));
    }

    @Test
    void optimisticRewrapUsesBinaryKeyIdPredicate() {
        int workspaceId = workspaceId();
        String oldKey = base64Key((byte) 9);
        SecretStore oldStore = store("old-v1", oldKey, Map.of(), Set.of(), true);
        String reference = oldStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "old-key-secret");
        StoredSecret stored = secretValueMapper.findById(SecretReference.parse(reference).id());
        StoredSecret rewrapped = new StoredSecret();
        rewrapped.setId(stored.getId());
        rewrapped.setKeyId("new-v2");
        rewrapped.setKeyAlgorithm(SecretStoreCrypto.KEY_ALGORITHM);
        rewrapped.setDataAlgorithm(SecretStoreCrypto.DATA_ALGORITHM);
        rewrapped.setEncryptedDataKey(stored.getEncryptedDataKey());
        rewrapped.setCiphertext(stored.getCiphertext());

        int updated = secretValueMapper.updateRewrapped(rewrapped, "OLD-V1",
                stored.getEncryptedDataKey(), stored.getCiphertext());

        assertEquals(0, updated);
        assertEquals("old-v1", secretValueMapper.findById(stored.getId()).getKeyId());
    }

    @Test
    void successfulUseAuditIsIndependentAndScoped() {
        int workspaceId = workspaceId();
        AuditService auditService = mock(AuditService.class);
        SecretStore auditedStore = store("audit-v1", base64Key((byte) 10),
                Map.of(), Set.of(), true, auditService);
        String reference = auditedStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "smtp-password");

        assertEquals("smtp-password", auditedStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, reference));

        verify(auditService).recordIndependentScoped(eq("secret_store.secret.use"), eq("workspace"),
                eq(workspaceId), eq(workspaceId), isNull(), eq("workspace.smtp.password"),
                eq("Secret used"), any());
    }

    private int workspaceId() {
        int orgId = orgId();
        Workspace workspace = new Workspace();
        workspace.setOrgId(orgId);
        workspace.setName("Secret Store Test Workspace");
        workspace.setSlug("secret-store-test-" + UUID.randomUUID());
        workspaceMapper.insert(workspace);
        return workspace.getId();
    }

    private int orgId() {
        Organization organization = new Organization();
        organization.setName("Secret Store Test Org");
        organization.setSlug("secret-store-test-" + UUID.randomUUID());
        organizationMapper.insert(organization);
        return organization.getId();
    }

    private SecretStore store() {
        return store("test-v1", base64Key((byte) 42), Map.of(), Set.of(), true);
    }

    private SecretStore store(String keyId, String masterKey, Map<String, String> keys,
            Set<String> disabledKeyIds, boolean lazyRewrapEnabled) {
        return store(keyId, masterKey, keys, disabledKeyIds, lazyRewrapEnabled, mock(AuditService.class));
    }

    private SecretStore store(String keyId, String masterKey, Map<String, String> keys,
            Set<String> disabledKeyIds, boolean lazyRewrapEnabled, AuditService auditService) {
        SecretStoreProperties properties = new SecretStoreProperties();
        properties.setKeyId(keyId);
        properties.setMasterKey(masterKey);
        properties.setKeys(keys);
        properties.setDisabledKeyIds(disabledKeyIds);
        properties.setLazyRewrapEnabled(lazyRewrapEnabled);
        return new SecretStore(secretValueMapper, new SecretStoreCrypto(properties),
                properties, auditService);
    }

    private static String base64Key(byte fill) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = fill;
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
