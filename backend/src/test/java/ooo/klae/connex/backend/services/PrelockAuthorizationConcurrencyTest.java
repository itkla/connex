package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.Filter;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.webauthn.WebauthnUserEntityRow;

/** HTTP proofs that requests waiting on a workspace lock cannot retain revoked authority. */
@SpringBootTest
@Import(PrelockAuthorizationConcurrencyTest.ProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrelockAuthorizationConcurrencyTest {
    private static final String PASSWORD = "Prelock-Test-Pw1!";
    private static final String EXCLUSIVE_WORKSPACE_LOCK =
        WorkspaceMapper.class.getName() + ".lockWorkspace";
    private static final String SHARED_WORKSPACE_LOCK =
        WorkspaceMapper.class.getName() + ".lockActiveWorkspaceForShare";
    private static final long BARRIER_ARRIVAL_SECONDS = 60;
    private static final long BARRIER_HOLD_SECONDS = 180;
    private static final long BLOCKED_OBSERVATION_SECONDS = 10;
    private static final long RESPONSE_SECONDS = 60;
    private static final long SHUTDOWN_GRACE_SECONDS = 30;

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private ShareMapper shareMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantContext tenantContext;
    @Autowired private StatementProbe statementProbe;
    @Autowired private WebauthnUserEntityMapper userEntityMapper;
    @Autowired private WebauthnCredentialMapper credentialMapper;
    @Autowired private UserCredentialRepository userCredentials;

    private Organization organization;
    private Workspace workspace;
    private Workspace target;
    private User actor;
    private User revoker;
    private WorkspaceRole actorRole;
    private MockHttpSession actorSession;
    private MockHttpSession revokerSession;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain).build();
        organization = new Organization();
        organization.setName("Prelock " + UUID.randomUUID());
        organization.setSlug("prelock-" + UUID.randomUUID());
        organizationMapper.insert(organization);
        target = newWorkspace();
        workspace = newWorkspace();
        actor = newMember();
        revoker = newMember();
        actorRole = grant(actor, List.of("SHARE_MANAGE", "PERSON_UPDATE"));
        grant(revoker, List.of("ROLE_MANAGE"));
        workspaceMapper.addMember(target.getId(), actor.getId(), "member");
        actorSession = login(actor);
        revokerSession = login(revoker);
        enrollPasskey(actor, actorSession);
        enrollPasskey(revoker, revokerSession);
    }

    @AfterEach
    void cleanUp() {
        clearContext();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (target != null) {
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", target.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
        for (User user : new User[] {actor, revoker}) {
            if (user != null) {
                jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", user.getId());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"company", "person", "pipeline"})
    void shareGrantRejectsPermissionRevokedWhileWaitingOnWorkspace(String type) throws Exception {
        int id = newRecord(type);
        assertFalse(shareExists(type, id));
        assertInvisible(type, id);

        revokeWhileWaiting(post("/api/shares/" + type + "/" + id)
            .content("{\"workspaceId\":" + target.getId() + ",\"canEdit\":false}"));

        assertFalse(shareExists(type, id));
        assertInvisible(type, id);
        assertNoAudit("workspace.share");
    }

    @ParameterizedTest
    @ValueSource(strings = {"company", "person", "pipeline"})
    void shareRevocationRejectsPermissionRevokedWhileWaitingOnWorkspace(String type) throws Exception {
        int id = newRecord(type);
        mockMvc.perform(post("/api/shares/" + type + "/" + id)
                .session(actorSession).header("X-Workspace-Id", workspace.getId()).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspaceId\":" + target.getId() + ",\"canEdit\":false}"))
            .andExpect(status().isNoContent());
        assertTrue(shareExists(type, id));

        revokeWhileWaiting(delete("/api/shares/" + type + "/" + id + "/" + target.getId()));

        assertTrue(shareExists(type, id));
        assertNoAudit("workspace.unshare");
    }

    @Test
    void restrictionClearRejectsPermissionRevokedWhileWaitingOnWorkspace() throws Exception {
        int id = newRecord("person");
        personMapper.updateProcessingRestrictions(workspace.getId(), id, true, true);
        Person before = personMapper.getPersonById(workspace.getId(), id);
        assertNotNull(before);
        assertNotNull(before.getSuspendedAt());
        assertNotNull(before.getProvisionCeasedAt());

        revokeWhileWaiting(put("/api/persons/" + id + "/restrictions")
            .content("{\"suspended\":false,\"provisionCeased\":false}"));

        Person after = personMapper.getPersonById(workspace.getId(), id);
        assertNotNull(after);
        assertEquals(before.getSuspendedAt(), after.getSuspendedAt());
        assertEquals(before.getProvisionCeasedAt(), after.getProvisionCeasedAt());
        assertNoAudit("person.restrictions");
    }

    @Test
    void personUpdateRejectsPermissionRevokedWhileWaitingOnWorkspace() throws Exception {
        int id = newRecord("person");
        Person before = personMapper.getPersonById(workspace.getId(), id);
        assertNotNull(before);

        revokeWhileWaiting(put("/api/persons/" + id).content("{\"name\":\"Unauthorized replacement\"}"));

        Person after = personMapper.getPersonById(workspace.getId(), id);
        assertNotNull(after);
        assertEquals(before.getName(), after.getName());
        assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
        assertNoAudit("person.update");
    }

    private void revokeWhileWaiting(MockHttpServletRequestBuilder mutation) throws Exception {
        CountDownLatch workspaceLocked = new CountDownLatch(1);
        CountDownLatch releaseRevocation = new CountDownLatch(1);
        CountDownLatch workspaceRequested = new CountDownLatch(1);
        CountDownLatch workspaceGranted = new CountDownLatch(1);
        ProbePlan revocationProbe = ProbePlan.after(EXCLUSIVE_WORKSPACE_LOCK, (invocation, result) -> {
            if (hasParameter(invocation, "workspaceId", workspace.getId())) {
                workspaceLocked.countDown();
                assertTrue(
                    releaseRevocation.await(BARRIER_HOLD_SECONDS, TimeUnit.SECONDS),
                    "The revocation was never released while it held the workspace lock");
            }
        });
        ProbePlan mutationProbe = new ProbePlan(
            SHARED_WORKSPACE_LOCK,
            (invocation, executor) -> {
                if (hasParameter(invocation, "workspaceId", workspace.getId())) {
                    workspaceRequested.countDown();
                }
            },
            (invocation, result) -> {
                if (hasParameter(invocation, "workspaceId", workspace.getId())) {
                    workspaceGranted.countDown();
                }
            });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var revocation = executor.submit(() -> withProbe(revocationProbe, () ->
                mockMvc.perform(put("/api/workspaces/" + workspace.getId() + "/roles/" + actorRole.getId())
                    .session(revokerSession).header("X-Workspace-Id", workspace.getId()).with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Revoked role\",\"permissions\":[]}"))
                    .andReturn()));
            if (!workspaceLocked.await(BARRIER_ARRIVAL_SECONDS, TimeUnit.SECONDS)) {
                fail("The revocation never took the exclusive workspace lock: "
                    + describe(revocation.get(RESPONSE_SECONDS, TimeUnit.SECONDS)));
            }
            var request = executor.submit(() -> withProbe(mutationProbe, () ->
                mockMvc.perform(mutation.session(actorSession)
                    .header("X-Workspace-Id", workspace.getId()).with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)).andReturn()));
            if (!workspaceRequested.await(BARRIER_ARRIVAL_SECONDS, TimeUnit.SECONDS)) {
                releaseRevocation.countDown();
                fail("The waiting request never asked for the shared workspace root: "
                    + describe(request.get(RESPONSE_SECONDS, TimeUnit.SECONDS)));
            }
            assertFalse(
                workspaceGranted.await(BLOCKED_OBSERVATION_SECONDS, TimeUnit.SECONDS),
                "The waiting request took the shared workspace root that the revocation still holds");
            releaseRevocation.countDown();
            MvcResult revoked = revocation.get(RESPONSE_SECONDS, TimeUnit.SECONDS);
            assertEquals(200, revoked.getResponse().getStatus(), describe(revoked));
            MvcResult refused = request.get(RESPONSE_SECONDS, TimeUnit.SECONDS);
            assertEquals(403, refused.getResponse().getStatus(), describe(refused));
            assertTrue(roleMapper.findPermissions(workspace.getId(), actorRole.getId()).isEmpty());
        } finally {
            releaseAndShutDown(executor, releaseRevocation);
        }
    }

    /** Status and body of a completed exchange, so a failed drill names the refusing control. */
    private static String describe(MvcResult result) {
        try {
            return result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            return String.valueOf(result.getResponse().getStatus());
        }
    }

    private <T> T withProbe(ProbePlan probe, Callable<T> work) throws Exception {
        try {
            return statementProbe.execute(probe, work);
        } finally {
            clearContext();
        }
    }

    private void assertNoAudit(String action) {
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action = ?",
            Integer.class, workspace.getId(), action));
    }

    private void assertInvisible(String type, int id) throws Exception {
        String collection = switch (type) {
            case "company" -> "companies";
            case "person" -> "persons";
            case "pipeline" -> "pipelines";
            default -> throw new IllegalArgumentException(type);
        };
        mockMvc.perform(get("/api/" + collection + "/" + id)
                .session(actorSession).header("X-Workspace-Id", target.getId()))
            .andExpect(status().isNotFound());
    }

    private boolean shareExists(String type, int id) {
        return switch (type) {
            case "company" -> shareMapper.companyShareExists(id, workspace.getId(), target.getId());
            case "person" -> shareMapper.personShareExists(id, workspace.getId(), target.getId());
            case "pipeline" -> shareMapper.pipelineShareExists(id, workspace.getId(), target.getId());
            default -> throw new IllegalArgumentException(type);
        };
    }

    private int newRecord(String type) {
        return switch (type) {
            case "company" -> {
                Company company = new Company();
                company.setWorkspaceId(workspace.getId());
                company.setName("Prelock company");
                companyMapper.insert(company);
                yield company.getId();
            }
            case "person" -> {
                Person person = new Person();
                person.setWorkspaceId(workspace.getId());
                person.setOwnerId(actor.getId());
                person.setName("Prelock contact");
                personMapper.insert(person);
                yield person.getId();
            }
            case "pipeline" -> {
                Pipeline pipeline = new Pipeline();
                pipeline.setWorkspaceId(workspace.getId());
                pipeline.setName("Prelock pipeline");
                pipelineMapper.insertPipeline(pipeline);
                yield pipeline.getId();
            }
            default -> throw new IllegalArgumentException(type);
        };
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setOrgId(organization.getId());
        created.setName("Prelock " + UUID.randomUUID());
        created.setSlug("prelock-" + UUID.randomUUID());
        workspaceMapper.insert(created);
        return created;
    }

    private User newMember() {
        String unique = UUID.randomUUID().toString();
        User user = new User();
        user.setUsername("prelock-" + unique);
        user.setDisplayName("Prelock " + unique);
        user.setEmail("prelock-" + unique + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    private WorkspaceRole grant(User user, List<String> permissions) {
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Prelock " + user.getId());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), permissions);
        workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
        return role;
    }

    private MockHttpSession login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk()).andReturn();
        return assertInstanceOf(MockHttpSession.class, result.getRequest().getSession(false));
    }

    /** Seeds enrolled credentials and fresh user-bound step-up state without disabling MFA gates. */
    private void enrollPasskey(User user, MockHttpSession session) {
        Bytes handle = Bytes.random();
        WebauthnUserEntityRow entity = new WebauthnUserEntityRow();
        entity.setId(handle.toBase64UrlString());
        entity.setUserId(user.getId());
        entity.setName(user.getUsername());
        entity.setDisplayName(user.getDisplayName());
        userEntityMapper.insert(entity);
        userCredentials.save(ImmutableCredentialRecord.builder()
            .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
            .credentialId(Bytes.random())
            .userEntityUserId(handle)
            .publicKey(new ImmutablePublicKeyCose(new byte[] {9, 9, 9}))
            .signatureCount(0)
            .created(Instant.now())
            .build());
        assertTrue(credentialMapper.existsByUserId(user.getId()));
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, System.currentTimeMillis());
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, user.getId());
    }

    private void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    private static boolean hasParameter(Invocation invocation, String name, Object expected) {
        Object parameter = invocation.getArgs()[1];
        if (!(parameter instanceof Map<?, ?> parameters)) {
            return false;
        }
        return Objects.equals(expected, parameters.get(name));
    }

    private static void releaseAndShutDown(
            ExecutorService executor, CountDownLatch... releases) throws InterruptedException {
        for (CountDownLatch release : releases) {
            release.countDown();
        }
        executor.shutdown();
        if (!executor.awaitTermination(RESPONSE_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertTrue(
                executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS),
                "Concurrent transactions did not terminate after barrier release");
        }
    }

    private record ProbePlan(String statementId, BeforeProbe before, AfterProbe after) {
        static ProbePlan after(String statementId, AfterProbe after) {
            return new ProbePlan(statementId, (invocation, executor) -> {
            }, after);
        }
    }

    @FunctionalInterface
    private interface BeforeProbe {
        void execute(Invocation invocation, Executor executor) throws Throwable;
    }

    @FunctionalInterface
    private interface AfterProbe {
        void execute(Invocation invocation, Object result) throws Throwable;
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "update",
            args = { MappedStatement.class, Object.class }),
        @Signature(type = Executor.class, method = "query",
            args = { MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class })
    })
    static final class StatementProbe implements Interceptor {
        private final ThreadLocal<ProbePlan> armed = new ThreadLocal<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            ProbePlan probe = armed.get();
            if (!(invocation.getArgs()[0] instanceof MappedStatement statement)) {
                throw new AssertionError("A MyBatis probe requires a mapped statement");
            }
            if (probe == null || !probe.statementId().equals(statement.getId())) {
                return invocation.proceed();
            }
            if (!(invocation.getTarget() instanceof Executor executor)) {
                throw new AssertionError("A MyBatis statement probe requires an Executor target");
            }
            probe.before().execute(invocation, executor);
            Object result = invocation.proceed();
            probe.after().execute(invocation, result);
            return result;
        }

        <T> T execute(ProbePlan probe, Callable<T> work) throws Exception {
            if (armed.get() != null) {
                throw new IllegalStateException("A statement probe is already armed on this thread");
            }
            armed.set(probe);
            try {
                return work.call();
            } finally {
                armed.remove();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        StatementProbe statementProbe() {
            return new StatementProbe();
        }
    }
}
