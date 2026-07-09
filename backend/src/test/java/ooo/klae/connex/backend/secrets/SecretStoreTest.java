package ooo.klae.connex.backend.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SecretStoreTest {

    @Autowired private SecretStore secretStore;
    @Autowired private SecretValueMapper secretValueMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void putThenGet_roundTripsWithoutPlaintextPersistence() {
        String plaintext = "smtp-secret-value";
        int workspaceId = workspaceId();

        String reference = secretStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, plaintext);

        assertTrue(SecretReference.isReference(reference));
        assertFalse(reference.contains(plaintext));
        assertEquals(plaintext, secretStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, reference));

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
        String firstReference = secretStore.put(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId(), "same-secret");
        StoredSecret first = secretValueMapper.findById(SecretReference.parse(firstReference).id());

        String secondReference = secretStore.put(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId(), "same-secret");
        StoredSecret second = secretValueMapper.findById(SecretReference.parse(secondReference).id());

        assertNotEquals(first.getEncryptedDataKey(), second.getEncryptedDataKey());
        assertNotEquals(first.getCiphertext(), second.getCiphertext());
    }

    @Test
    void put_sameScopeAndPurposeRotatesExistingSlot() {
        int workspaceId = workspaceId();
        String firstReference = secretStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "first-secret");
        StoredSecret first = secretValueMapper.findById(SecretReference.parse(firstReference).id());

        String secondReference = secretStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "second-secret");
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
        assertEquals("second-secret", secretStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD,
                workspaceId, firstReference));
    }

    @Test
    void get_tamperedCiphertextFails() {
        int orgId = orgId();
        String reference = secretStore.put(SecretPurpose.ORG_SSO_SAML_SP_PRIVATE_KEY, orgId, "private-key");
        StoredSecret stored = secretValueMapper.findById(SecretReference.parse(reference).id());
        byte[] blob = Base64.getDecoder().decode(stored.getCiphertext());
        blob[blob.length - 1] ^= 0x01;
        stored.setCiphertext(Base64.getEncoder().encodeToString(blob));
        secretValueMapper.upsert(stored);

        assertThrows(IllegalStateException.class,
                () -> secretStore.get(SecretPurpose.ORG_SSO_SAML_SP_PRIVATE_KEY, orgId, reference));
    }

    @Test
    void get_scopeMismatchFailsClosed() {
        int workspaceId = workspaceId();
        String reference = secretStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "smtp-password");

        assertThrows(IllegalStateException.class,
                () -> secretStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId(), reference));
        assertThrows(IllegalStateException.class,
                () -> secretStore.get(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, workspaceId, reference));
    }

    @Test
    void delete_scopeMismatchDoesNotDeleteSecret() {
        int workspaceId = workspaceId();
        String reference = secretStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "smtp-password");

        secretStore.delete(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId(), reference);

        assertTrue(secretStore.exists(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, reference));
        assertEquals("smtp-password", secretStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, reference));
    }

    @Test
    void existsAndDelete_ignoreMalformedReferences() {
        int workspaceId = workspaceId();

        assertFalse(secretStore.exists(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "secret:v1:not-a-number"));
        assertDoesNotThrow(() -> secretStore.delete(SecretPurpose.WORKSPACE_SMTP_PASSWORD,
                workspaceId, "secret:v1:not-a-number"));
    }

    @Test
    void workspaceDeleteCascadesWorkspaceSecretRows() {
        int workspaceId = workspaceId();
        String reference = secretStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "smtp-password");
        long secretId = SecretReference.parse(reference).id();

        jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);

        assertNull(secretValueMapper.findById(secretId));
    }

    @Test
    void orgDeleteCascadesOrgSecretRows() {
        int orgId = orgId();
        String reference = secretStore.put(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId, "oidc-password");
        long secretId = SecretReference.parse(reference).id();

        jdbcTemplate.update("DELETE FROM organization WHERE id = ?", orgId);

        assertNull(secretValueMapper.findById(secretId));
    }

    @Test
    void missingMasterKeyRefusesEncryption() {
        SecretStoreProperties properties = new SecretStoreProperties();
        SecretStoreCrypto crypto = new SecretStoreCrypto(properties);

        assertFalse(crypto.isAvailable());
        assertThrows(BadRequestException.class, () -> crypto.encrypt("value", "aad"));
    }

    @Test
    void keyringDecryptsStoredSecretsByTheirKeyId() {
        int workspaceId = workspaceId();
        String oldKey = base64Key((byte) 1);
        String newKey = base64Key((byte) 2);
        SecretStore oldStore = new SecretStore(secretValueMapper, crypto("old-v1", oldKey, Map.of()));
        String reference = oldStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "old-key-secret");

        SecretStore rotatedStore = new SecretStore(secretValueMapper, crypto("new-v2", newKey,
                Map.of("old-v1", oldKey)));

        assertTrue(rotatedStore.hasKey("old-v1"));
        assertEquals("old-key-secret", rotatedStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD,
                workspaceId, reference));

        rotatedStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, "new-key-secret");
        StoredSecret rotated = secretValueMapper.findById(SecretReference.parse(reference).id());
        assertEquals("new-v2", rotated.getKeyId());
        assertEquals("new-key-secret", rotatedStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD,
                workspaceId, reference));
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

    private static SecretStoreCrypto crypto(String keyId, String masterKey, Map<String, String> keys) {
        SecretStoreProperties properties = new SecretStoreProperties();
        properties.setKeyId(keyId);
        properties.setMasterKey(masterKey);
        properties.setKeys(keys);
        return new SecretStoreCrypto(properties);
    }

    private static String base64Key(byte fill) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = fill;
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
