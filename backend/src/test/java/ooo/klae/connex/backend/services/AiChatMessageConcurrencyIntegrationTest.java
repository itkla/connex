package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.AiChatMessageCreateRequest;
import ooo.klae.connex.backend.dto.AiChatMessageDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Proves concurrent appends serialize on the session root and allocate gap-free sequences. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiChatMessageConcurrencyIntegrationTest {

    private static final int APPEND_COUNT = 8;

    @Autowired private AiAssistantService assistantService;
    @Autowired private AiChatMapper chatMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private AiChatMapper chatMapperSpy;

    private Organization organization;
    private Workspace workspace;
    private AiChatSession session;
    private List<User> actors;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("AI chat concurrency " + unique);
        organization.setSlug("ai-chat-concurrency-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("AI chat concurrency " + unique);
        workspace.setSlug("ai-chat-concurrency-" + unique);
        workspaceMapper.insert(workspace);

        actors = IntStream.range(0, APPEND_COUNT)
            .mapToObj(index -> user("ai-chat-" + index + "-" + unique))
            .toList();
        for (User actor : actors) {
            workspaceMapper.addMember(workspace.getId(), actor.getId(), "admin");
        }

        session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(actors.getFirst().getId());
        session.setTitle("Concurrent appends");
        session.setVisibility("shared");
        session.setStatus("active");
        chatMapper.insertSession(session);
        for (User actor : actors.subList(1, actors.size())) {
            chatMapper.insertParticipant(workspace.getId(), session.getId(), actor.getId());
        }
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (workspace != null) {
            jdbcTemplate.update(
                "DELETE FROM ai_chat_session WHERE workspace_id = ?",
                workspace.getId());
            jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE workspace_id = ?",
                workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (actors != null) {
            for (User actor : actors) {
                jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", actor.getId());
            }
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    /**
     * The leader is started alone and must hold the session row lock before any contender is
     * submitted. Releasing every thread at once made the {@code lockAttempts} counter unreliable:
     * it increments on method entry, not on lock acquisition, so a thread could claim attempt 1,
     * be descheduled before its {@code SELECT ... FOR UPDATE}, and let attempt 2 acquire an
     * unlocked row and signal that the contender was never blocked.
     */
    @Test
    void concurrentAppendsSerializeOnSessionRootAndYieldExactlyOneThroughN() throws Exception {
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch contenderEntered = new CountDownLatch(1);
        CountDownLatch contenderAcquired = new CountDownLatch(1);
        AtomicInteger lockAttempts = new AtomicInteger();
        AiChatMapper realChatMapper = sqlSessionTemplate.getMapper(AiChatMapper.class);
        doAnswer(invocation -> {
            int attempt = lockAttempts.incrementAndGet();
            if (attempt > 1) {
                contenderEntered.countDown();
            }
            AiChatSession locked = realChatMapper.getSessionByIdForUpdate(
                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            if (attempt > 1) {
                contenderAcquired.countDown();
            }
            if (attempt == 1) {
                firstLockAcquired.countDown();
                assertTrue(releaseFirst.await(30, TimeUnit.SECONDS));
            }
            return locked;
        }).when(chatMapperSpy).getSessionByIdForUpdate(anyInt(), anyInt(), anyInt());

        ExecutorService executor = Executors.newFixedThreadPool(APPEND_COUNT);
        List<Future<AiChatMessageDto>> futures = new ArrayList<>();
        try {
            User leader = actors.get(0);
            futures.add(executor.submit(() -> inContext(leader, () ->
                assistantService.appendMessage(session.getId(), request("message-1")))));
            assertTrue(firstLockAcquired.await(10, TimeUnit.SECONDS));

            for (int index = 1; index < APPEND_COUNT; index++) {
                int messageNumber = index + 1;
                User actor = actors.get(index);
                futures.add(executor.submit(() -> inContext(actor, () ->
                    assistantService.appendMessage(
                        session.getId(), request("message-" + messageNumber)))));
            }
            assertTrue(contenderEntered.await(10, TimeUnit.SECONDS));
            assertFalse(contenderAcquired.await(1, TimeUnit.SECONDS));
            releaseFirst.countDown();
            for (Future<AiChatMessageDto> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        List<AiChatMessage> messages = chatMapper.listMessages(
            workspace.getId(), session.getId(), 100, 0);
        assertEquals(APPEND_COUNT, messages.size());
        assertEquals(
            IntStream.rangeClosed(1, APPEND_COUNT).boxed().toList(),
            messages.stream().map(AiChatMessage::getSeq).toList());
        assertEquals(
            APPEND_COUNT,
            messages.stream().map(AiChatMessage::getSeq).distinct().count());
    }

    private AiChatMessageDto inContext(User actor, Callable<AiChatMessageDto> operation)
            throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        tenantContext.set(
            workspace.getId(), organization.getId(), actor.getId(), "admin", null);
        try {
            return operation.call();
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private AiChatMessageCreateRequest request(String content) {
        AiChatMessageCreateRequest request = new AiChatMessageCreateRequest();
        request.setContent(content);
        return request;
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("hash-" + username);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }
}
