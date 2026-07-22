package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import ooo.klae.connex.backend.beans.AiProviderConfig;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.AiProviderConfigRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.AiProviderConfigMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Verifies AI provider config mutation serialization against real MySQL transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiProviderConfigConcurrencyIntegrationTest {

    @Autowired private AiProviderConfigService service;
    @Autowired private AiProviderConfigMapper aiProviderConfigMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private AiProviderSecretCipher aiProviderSecretCipher;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean private OrganizationMapper organizationMapper;
    @MockitoBean private AuditService auditService;
    @MockitoBean private SessionSecurityService sessionSecurityService;

    private Organization organization;
    private Workspace firstWorkspace;
    private Workspace secondWorkspace;
    private User actor;
    private String credentialRef;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("AI Mutation " + unique);
        organization.setSlug("ai-mutation-" + unique);
        organizationMapper.insert(organization);

        firstWorkspace = workspace("ai-mutation-a-" + unique);
        secondWorkspace = workspace("ai-mutation-b-" + unique);

        actor = new User();
        actor.setUsername("ai_mutation_" + unique);
        actor.setDisplayName("AI Mutation " + unique);
        actor.setEmail(unique + "@example.com");
        actor.setPasswordHash("hash_" + unique);
        actor.setTimezone("UTC");
        userMapper.insert(actor);
        workspaceMapper.addMember(firstWorkspace.getId(), actor.getId(), "admin");
        workspaceMapper.addMember(secondWorkspace.getId(), actor.getId(), "admin");
        orgMemberMapper.addMember(organization.getId(), actor.getId(), "owner");

        credentialRef = aiProviderSecretCipher.encryptCredential(
                organization.getId(),
                "{\"accessKeyId\":\"AKIATEST12345678\",\"secretAccessKey\":\"abcd1234wxyz\"}");
        aiProviderConfigMapper.upsert(readyConfig(credentialRef));
    }

    @AfterEach
    void cleanUp() {
        aiProviderConfigMapper.deleteByOrg(organization.getId());
        jdbcTemplate.update("DELETE FROM secret_value WHERE org_id = ?", organization.getId());
        orgMemberMapper.removeMember(organization.getId(), actor.getId());
        workspaceMapper.removeMember(firstWorkspace.getId(), actor.getId());
        workspaceMapper.removeMember(secondWorkspace.getId(), actor.getId());
        jdbcTemplate.update("DELETE FROM workspace WHERE id IN (?, ?)",
                firstWorkspace.getId(), secondWorkspace.getId());
        userMapper.delete(actor.getId());
        jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
    }

    @Test
    void revokeFirstPreventsCredentialPreservingSaveFromResurrectingDeletedSecret() throws Exception {
        LockBarrier barrier = holdFirstOrganizationLock();
        AiProviderConfigRequest preservingRequest = validRequest(null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> revoke = executor.submit(
                    () -> service.revoke(firstWorkspace.getId(), actor.getId()));
            assertTrue(barrier.firstLocked().await(10, TimeUnit.SECONDS));

            Future<BadRequestException> save = executor.submit(() -> assertThrows(
                    BadRequestException.class,
                    () -> service.save(secondWorkspace.getId(), actor.getId(), preservingRequest)));
            assertTrue(barrier.secondAttempted().await(10, TimeUnit.SECONDS));
            barrier.releaseFirst().countDown();

            revoke.get(20, TimeUnit.SECONDS);
            assertEquals(
                    "Stored provider credentials are required before enabling AI",
                    save.get(20, TimeUnit.SECONDS).getMessage());
        } finally {
            barrier.releaseFirst().countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertNull(aiProviderConfigMapper.findByOrg(organization.getId()));
        assertEquals(0, credentialCount());
    }

    @Test
    void saveFirstLeavesOrganizationRevokedAfterWaitingRevokeCommits() throws Exception {
        LockBarrier barrier = holdFirstOrganizationLock();
        AiProviderConfigRequest preservingRequest = validRequest(null);
        preservingRequest.setRegion("eu-west-1");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> save = executor.submit(
                    () -> service.save(firstWorkspace.getId(), actor.getId(), preservingRequest));
            assertTrue(barrier.firstLocked().await(10, TimeUnit.SECONDS));

            Future<?> revoke = executor.submit(
                    () -> service.revoke(secondWorkspace.getId(), actor.getId()));
            assertTrue(barrier.secondAttempted().await(10, TimeUnit.SECONDS));
            barrier.releaseFirst().countDown();

            assertNotNull(save.get(20, TimeUnit.SECONDS));
            revoke.get(20, TimeUnit.SECONDS);
        } finally {
            barrier.releaseFirst().countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertNull(aiProviderConfigMapper.findByOrg(organization.getId()));
        assertEquals(0, credentialCount());
    }

    private LockBarrier holdFirstOrganizationLock() {
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger lockCalls = new AtomicInteger();
        OrganizationMapper realOrganizationMapper = sqlSessionTemplate.getMapper(OrganizationMapper.class);
        doAnswer(invocation -> {
            int call = lockCalls.incrementAndGet();
            if (call == 1) {
                Integer locked = realOrganizationMapper.lockById(organization.getId());
                firstLocked.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("First AI config mutation lock was not released");
                }
                return locked;
            }
            secondAttempted.countDown();
            return realOrganizationMapper.lockById(organization.getId());
        }).when(organizationMapper).lockById(organization.getId());
        return new LockBarrier(firstLocked, secondAttempted, releaseFirst);
    }

    private Workspace workspace(String slug) {
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        return workspace;
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
        config.setAttestedAt(LocalDateTime.now());
        config.setEnabled(true);
        return config;
    }

    private static AiProviderConfigRequest validRequest(String secretAccessKey) {
        AiProviderConfigRequest request = new AiProviderConfigRequest();
        request.setProvider("bedrock");
        request.setRegion("ap-northeast-1");
        request.setModelId("anthropic.claude-3-5-sonnet-20240620-v1:0");
        request.setAccessKeyId(secretAccessKey == null ? null : "AKIATEST12345678");
        request.setSecretAccessKey(secretAccessKey);
        request.setNoTrainingAttested(true);
        request.setEnabled(true);
        return request;
    }

    private int credentialCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM secret_value WHERE org_id = ? AND purpose = 'org.ai.provider_credential'",
                Integer.class,
                organization.getId());
        return count == null ? 0 : count;
    }

    private record LockBarrier(
            CountDownLatch firstLocked,
            CountDownLatch secondAttempted,
            CountDownLatch releaseFirst) {
    }
}
