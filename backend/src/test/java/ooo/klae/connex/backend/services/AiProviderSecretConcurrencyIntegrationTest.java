package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.ai.AiProviderSecretCipher;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.beans.AiProviderConfig;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AiProviderConfigMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretStore;
import ooo.klae.connex.backend.secrets.SecretStoreCrypto;
import ooo.klae.connex.backend.secrets.SecretStoreProperties;

/** Verifies provider secret reads and config revocation share one parent-first lock hierarchy. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "connex.secret-store.key-id=new-v2",
            "connex.secret-store.master-key=AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
            "connex.secret-store.keys.old-v1=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
            "connex.secret-store.lazy-rewrap-enabled=true"
        })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiProviderSecretConcurrencyIntegrationTest {

    @Autowired private AiProviderConfigService aiProviderConfigService;
    @Autowired private AiProviderConfigMapper aiProviderConfigMapper;
    @Autowired private AiProviderSecretCipher aiProviderSecretCipher;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private AuditService auditService;
    @MockitoSpyBean private UserMapper userMapper;
    @MockitoSpyBean private OrganizationMapper organizationMapper;
    @MockitoSpyBean private SecretValueMapper secretValueMapper;
    @MockitoBean private SessionSecurityService sessionSecurityService;

    private Organization organization;
    private Workspace workspace;
    private User actor;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("AI Secret " + unique);
        organization.setSlug("ai-secret-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("AI Secret " + unique);
        workspace.setSlug("ai-secret-workspace-" + unique);
        workspaceMapper.insert(workspace);

        actor = new User();
        actor.setUsername("ai_secret_" + unique);
        actor.setDisplayName("AI Secret " + unique);
        actor.setEmail("ai-secret-" + unique + "@example.com");
        actor.setPasswordHash("hash_" + unique);
        actor.setTimezone("UTC");
        userMapper.insert(actor);
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "admin");
        orgMemberMapper.addMember(organization.getId(), actor.getId(), "owner");

        String credentialRef = aiProviderSecretCipher.encryptCredential(
                organization.getId(),
                "{\"accessKeyId\":\"AKIATEST12345678\",\"secretAccessKey\":\"abcd1234wxyz\"}");
        aiProviderConfigMapper.upsert(readyConfig(credentialRef));
    }

    @AfterEach
    void cleanUp() {
        aiProviderConfigMapper.deleteByOrg(organization.getId());
        jdbcTemplate.update("DELETE FROM secret_value WHERE org_id = ?", organization.getId());
        orgMemberMapper.removeMember(organization.getId(), actor.getId());
        workspaceMapper.removeMember(workspace.getId(), actor.getId());
        jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        userMapper.delete(actor.getId());
        jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
    }

    @Test
    void activeKeyResolutionMakesRevokeWaitBeforeDownstreamLocks() throws Exception {
        CountDownLatch readerRootsLocked = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        CountDownLatch revokeOrganizationLockAttempted = new CountDownLatch(1);
        AtomicInteger organizationShareCalls = new AtomicInteger();
        OrganizationMapper realOrganizationMapper = sqlSessionTemplate.getMapper(OrganizationMapper.class);
        doAnswer(invocation -> {
            Integer locked = realOrganizationMapper.lockByIdForShare(organization.getId());
            if (organizationShareCalls.incrementAndGet() == 1) {
                readerRootsLocked.countDown();
                if (!releaseReader.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Provider resolution did not resume");
                }
            }
            return locked;
        }).when(organizationMapper).lockByIdForShare(organization.getId());
        doAnswer(invocation -> {
            revokeOrganizationLockAttempted.countDown();
            return realOrganizationMapper.lockById(organization.getId());
        }).when(organizationMapper).lockById(organization.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResolvedAiProvider> resolution = executor.submit(
                    () -> aiProviderConfigService.resolveForOrg(organization.getId(), actor.getId()));
            assertTrue(readerRootsLocked.await(10, TimeUnit.SECONDS));

            Future<?> revoke = executor.submit(
                    () -> aiProviderConfigService.revoke(workspace.getId(), actor.getId()));
            assertTrue(revokeOrganizationLockAttempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> revoke.get(500, TimeUnit.MILLISECONDS));
            releaseReader.countDown();

            assertNotNull(resolution.get(20, TimeUnit.SECONDS));
            revoke.get(20, TimeUnit.SECONDS);
        } finally {
            releaseReader.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertNull(aiProviderConfigMapper.findByOrg(organization.getId()));
        assertEquals(0, credentialCount());
        assertEquals(1, auditCount("secret_store.secret.use"));
    }

    @Test
    void lazyRewrapResolutionCompletesSingleAuditBeforeWaitingRevoke() throws Exception {
        replaceCredentialWithOldKey();
        CountDownLatch secretRewrapped = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        CountDownLatch revokeOrganizationLockAttempted = new CountDownLatch(1);
        OrganizationMapper realOrganizationMapper = sqlSessionTemplate.getMapper(OrganizationMapper.class);
        SecretValueMapper realSecretValueMapper = sqlSessionTemplate.getMapper(SecretValueMapper.class);
        doAnswer(invocation -> {
            int updated = realSecretValueMapper.updateRewrapped(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3));
            if (updated == 1) {
                secretRewrapped.countDown();
                if (!releaseReader.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Lazy rewrap did not resume");
                }
            }
            return updated;
        }).when(secretValueMapper).updateRewrapped(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        doAnswer(invocation -> {
            revokeOrganizationLockAttempted.countDown();
            return realOrganizationMapper.lockById(organization.getId());
        }).when(organizationMapper).lockById(organization.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResolvedAiProvider> resolution = executor.submit(
                    () -> aiProviderConfigService.resolveForOrg(organization.getId(), actor.getId()));
            assertTrue(secretRewrapped.await(10, TimeUnit.SECONDS));

            Future<?> revoke = executor.submit(
                    () -> aiProviderConfigService.revoke(workspace.getId(), actor.getId()));
            assertTrue(revokeOrganizationLockAttempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> revoke.get(500, TimeUnit.MILLISECONDS));
            releaseReader.countDown();

            assertNotNull(resolution.get(20, TimeUnit.SECONDS));
            revoke.get(20, TimeUnit.SECONDS);
        } finally {
            releaseReader.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertNull(aiProviderConfigMapper.findByOrg(organization.getId()));
        assertEquals(0, credentialCount());
        assertEquals(1, auditCount("secret_store.secret.use"));
        assertEquals(0, auditCount("secret_store.secret.rewrap"));
    }

    private void replaceCredentialWithOldKey() {
        aiProviderConfigMapper.deleteByOrg(organization.getId());
        jdbcTemplate.update("DELETE FROM secret_value WHERE org_id = ?", organization.getId());
        SecretStoreProperties properties = new SecretStoreProperties();
        properties.setKeyId("old-v1");
        properties.setMasterKey("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        properties.setKeys(Map.of());
        properties.setDisabledKeyIds(Set.of());
        properties.setLazyRewrapEnabled(true);
        SecretStore oldStore = new SecretStore(
                secretValueMapper,
                userMapper,
                workspaceMapper,
                organizationMapper,
                new SecretStoreCrypto(properties),
                properties,
                auditService);
        String credentialRef = oldStore.put(
                SecretPurpose.ORG_AI_PROVIDER_CREDENTIAL,
                organization.getId(),
                "{\"accessKeyId\":\"AKIATEST12345678\",\"secretAccessKey\":\"abcd1234wxyz\"}");
        aiProviderConfigMapper.upsert(readyConfig(credentialRef));
    }

    private AiProviderConfig readyConfig(String reference) {
        AiProviderConfig config = new AiProviderConfig();
        config.setOrgId(organization.getId());
        config.setProvider("bedrock");
        config.setRegion("ap-northeast-1");
        config.setModelId("anthropic.claude-3-5-sonnet-20240620-v1:0");
        config.setCredentialRef(reference);
        config.setCredentialLast4("wxyz");
        config.setNoTrainingAttested(true);
        config.setEnabled(true);
        return config;
    }

    private int credentialCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM secret_value WHERE org_id = ? AND purpose = 'org.ai.provider_credential'",
                Integer.class,
                organization.getId());
        return count == null ? 0 : count;
    }

    private int auditCount(String action) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE org_id = ? AND action = ?",
                Integer.class,
                organization.getId(),
                action);
        return count == null ? 0 : count;
    }
}
