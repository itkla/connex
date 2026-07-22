package ooo.klae.connex.backend.secrets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;

@ExtendWith(MockitoExtension.class)
class SecretStoreLockOrderTest {
    @Mock private SecretValueMapper secretValueMapper;
    @Mock private UserMapper userMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private SecretStoreCrypto crypto;
    @Mock private SecretStoreProperties properties;
    @Mock private AuditService auditService;

    @InjectMocks private SecretStore secretStore;

    @Test
    void batchRewrapLocksSortedScopeParentsBeforeDecryptingCandidates() {
        List<StoredSecret> candidates = List.of(
                secret(1, "organization", 7),
                secret(2, "workspace", 5),
                secret(3, "user", 9),
                secret(4, "organization", 3),
                secret(5, "workspace", 2),
                secret(6, "user", 4));
        when(crypto.activeKeyId()).thenReturn("new-v2");
        when(secretValueMapper.listRewrapCandidates("new-v2", 10))
                .thenReturn(candidates);
        when(userMapper.lockByIdForShare(4)).thenReturn(4);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspaceForShare(2)).thenReturn(2);
        when(workspaceMapper.lockWorkspaceForShare(5)).thenReturn(5);
        when(organizationMapper.lockByIdForShare(3)).thenReturn(3);
        when(organizationMapper.lockByIdForShare(7)).thenReturn(7);
        when(crypto.decrypt(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("stop after parent locks"));

        assertThrows(IllegalStateException.class, () -> secretStore.rewrapBatchToActiveKey(10));

        InOrder order = inOrder(userMapper, workspaceMapper, organizationMapper, crypto);
        order.verify(userMapper).lockByIdForShare(4);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(workspaceMapper).lockWorkspaceForShare(2);
        order.verify(workspaceMapper).lockWorkspaceForShare(5);
        order.verify(organizationMapper).lockByIdForShare(3);
        order.verify(organizationMapper).lockByIdForShare(7);
        order.verify(crypto).decrypt(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void failedUseDefersIndependentAuditUntilScopeLocksAreReleased() {
        StoredSecret secret = secret(1, "organization", 7);
        secret.setPurpose(SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL.value());
        when(organizationMapper.lockByIdForShare(7)).thenReturn(7);
        when(secretValueMapper.findById(1)).thenReturn(secret);
        when(crypto.decrypt(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("decrypt failed"));
        List<TransactionSynchronization> synchronizations;
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(IllegalStateException.class, () -> secretStore.get(
                    SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL, 7, "secret:v1:1"));
            synchronizations = TransactionSynchronizationManager.getSynchronizations();
            verifyNoInteractions(auditService);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(auditService).recordFailureScoped(
                "secret_store.secret.use_failed", "organization", 7, null, 7,
                SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL.value(), "Secret use failed", "IllegalStateException");
    }

    @Test
    void successfulUseDefersDurableAuditUntilAfterRollbackReleasesScopeLocks() {
        StoredSecret secret = secret(1, "organization", 7);
        secret.setPurpose(SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL.value());
        when(organizationMapper.lockByIdForShare(7)).thenReturn(7);
        when(secretValueMapper.findById(1)).thenReturn(secret);
        when(crypto.decrypt(anyString(), anyString(), anyString(), anyString())).thenReturn("plaintext");
        List<TransactionSynchronization> synchronizations;
        TransactionSynchronizationManager.initSynchronization();
        try {
            secretStore.get(SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL, 7, "secret:v1:1");
            synchronizations = TransactionSynchronizationManager.getSynchronizations();
            verifyNoInteractions(auditService);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(auditService).recordIndependentScoped(
                "secret_store.secret.use", "organization", 7, null, 7,
                SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL.value(), "Secret used",
                java.util.Map.of("secretId", 1L, "purpose", SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL.value(),
                        "keyId", "old-v1", "rewrapped", false));
    }

    @Test
    void failedBatchRewrapDefersIndependentAuditUntilScopeLocksAreReleased() {
        StoredSecret secret = secret(1, "organization", 7);
        when(crypto.activeKeyId()).thenReturn("new-v2");
        when(secretValueMapper.listRewrapCandidates("new-v2", 10)).thenReturn(List.of(secret));
        when(organizationMapper.lockByIdForShare(7)).thenReturn(7);
        when(crypto.decrypt(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("decrypt failed"));
        List<TransactionSynchronization> synchronizations;
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(IllegalStateException.class, () -> secretStore.rewrapBatchToActiveKey(10));
            synchronizations = TransactionSynchronizationManager.getSynchronizations();
            verifyNoInteractions(auditService);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(auditService).recordFailureScoped(
                "secret_store.secret.rewrap_failed", "organization", 7, null, 7,
                "test", "Secret rewrap failed", "IllegalStateException");
    }

    private static StoredSecret secret(long id, String scopeType, int scopeId) {
        StoredSecret secret = new StoredSecret();
        secret.setId(id);
        secret.setScopeType(scopeType);
        secret.setScopeId(scopeId);
        secret.setPurpose("test");
        secret.setKeyId("old-v1");
        secret.setKeyAlgorithm(SecretStoreCrypto.KEY_ALGORITHM);
        secret.setDataAlgorithm(SecretStoreCrypto.DATA_ALGORITHM);
        secret.setEncryptedDataKey("encrypted-key");
        secret.setCiphertext("ciphertext");
        return secret;
    }
}
