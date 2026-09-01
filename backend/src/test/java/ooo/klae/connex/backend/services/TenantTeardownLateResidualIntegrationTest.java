package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "connex.tenant-lifecycle.teardown-settle-delay=0s")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantTeardownLateResidualIntegrationTest extends AbstractServiceTest {

    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private TenantTeardownService teardownService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private TenantLifecycleAccess lifecycleAccessSpy;
    @MockitoSpyBean private TenantLifecycleMapper lifecycleMapperSpy;

    private Organization organization;
    private Workspace lifecycleWorkspace;

    @BeforeEach
    void createLifecycleRoots() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Late template residual " + suffix);
        organization.setSlug("late-template-residual-" + suffix);
        organizationMapper.insert(organization);
        orgMemberService.addFoundingOwner(organization.getId(), currentUser.getId());
        lifecycleWorkspace = new Workspace();
        lifecycleWorkspace.setOrgId(organization.getId());
        lifecycleWorkspace.setName("Late template residual " + suffix);
        lifecycleWorkspace.setSlug("late-template-residual-" + suffix);
        workspaceMapper.insert(lifecycleWorkspace);
        workspaceMapper.addMember(lifecycleWorkspace.getId(), currentUser.getId(), "owner");
        workspace = lifecycleWorkspace;
        authenticateAs(currentUser, lifecycleWorkspace.getId());
    }

    @AfterEach
    void cleanCommittedRoots() {
        if (lifecycleWorkspace != null) {
            int workspaceId = lifecycleWorkspace.getId();
            jdbcTemplate.update(
                "UPDATE record_creation_template SET current_version_id = NULL WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update(
                "DELETE FROM record_creation_template_version WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update(
                "DELETE FROM record_creation_template WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update(
                "DELETE FROM record_creation_template_set WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update("DELETE FROM tenant_operation_lease WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM tenant_cleanup_tombstone WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM workspace_member WHERE user_id = ?", currentUser.getId());
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
    }

    @Test
    void lateLinkedTemplateAggregateIsPreparedAndDeletedByTheSecondRealSweep() {
        int workspaceId = lifecycleWorkspace.getId();
        AtomicInteger routedCalls = new AtomicInteger();
        AtomicInteger rootId = new AtomicInteger();
        AtomicBoolean seeded = new AtomicBoolean();
        AtomicBoolean pointerPrepared = new AtomicBoolean();
        List<String> events = new CopyOnWriteArrayList<>();
        TenantLifecycleMapper realLifecycleMapper =
            sqlSessionTemplate.getMapper(TenantLifecycleMapper.class);
        doAnswer(invocation -> {
            if (routedCalls.incrementAndGet() == 3) {
                rootId.set(seedLateAggregate(workspaceId));
                seeded.set(true);
            }
            return invocation.callRealMethod();
        }).when(lifecycleAccessSpy).withRoute(any(), anyInt(), any());
        doAnswer(invocation -> {
            int result = realLifecycleMapper.nullifyReference(
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2));
            TableLifecycle declaration = invocation.getArgument(1);
            NullifyReference preparation = invocation.getArgument(2);
            if (seeded.get()
                    && declaration.table().equals("record_creation_template")
                    && preparation.column().equals("current_version_id")) {
                events.add("prepare-root");
                pointerPrepared.set(jdbcTemplate.queryForObject(
                    "SELECT current_version_id FROM record_creation_template WHERE id = ?",
                    Long.class,
                    rootId.get()) == null);
            }
            return result;
        }).when(lifecycleMapperSpy).nullifyReference(anyInt(), any(), any());
        doAnswer(invocation -> {
            int result = realLifecycleMapper.deleteDirectBatch(
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2));
            TableLifecycle declaration = invocation.getArgument(1);
            if (seeded.get() && result > 0 && declaration.table().startsWith("record_creation_template")) {
                events.add("delete-" + declaration.table());
            }
            return result;
        }).when(lifecycleMapperSpy).deleteDirectBatch(anyInt(), any(), anyInt());

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> teardownService.teardownWorkspace(
                organization.getId(),
                workspaceId,
                currentUser.getId(),
                lifecycleWorkspace.getSlug()));

        assertTrue(exception.getMessage().contains("trusted cleanup clean=true"));
        assertTrue(seeded.get());
        assertTrue(pointerPrepared.get());
        assertEquals(List.of(
            "prepare-root",
            "delete-record_creation_template_version",
            "delete-record_creation_template",
            "delete-record_creation_template_set"), events);
        assertEquals(0, rowCount("record_creation_template_version"));
        assertEquals(0, rowCount("record_creation_template"));
        assertEquals(0, rowCount("record_creation_template_set"));
    }

    private int seedLateAggregate(int workspaceId) {
        AtomicInteger rootId = new AtomicInteger();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update(
                "INSERT INTO record_creation_template_set"
                    + " (workspace_id, record_type, revision, default_template_id)"
                    + " VALUES (?, 'person', 1, NULL)",
                workspaceId);
            jdbcTemplate.update(
                "INSERT INTO record_creation_template"
                    + " (workspace_id, record_type, status, position, revision,"
                    + " current_version_id, created_by_id, updated_by_id)"
                    + " VALUES (?, 'person', 'enabled', 0, 1, NULL, ?, ?)",
                workspaceId,
                currentUser.getId(),
                currentUser.getId());
            rootId.set(jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Integer.class));
            String definition = "{\"schemaVersion\":1,\"groups\":[]}";
            jdbcTemplate.update(
                "INSERT INTO record_creation_template_version"
                    + " (workspace_id, template_id, version_number, name_en, name_ja,"
                    + " definition_json, definition_hash, created_by_id)"
                    + " VALUES (?, ?, 1, 'Late template', '遅延テンプレート', ?,"
                    + " UNHEX(SHA2(?, 256)), ?)",
                workspaceId,
                rootId.get(),
                definition,
                definition,
                currentUser.getId());
            long versionId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbcTemplate.update(
                "UPDATE record_creation_template SET current_version_id = ?"
                    + " WHERE workspace_id = ? AND id = ?",
                versionId,
                workspaceId,
                rootId.get());
        });
        assertTrue(rootId.get() > 0);
        assertEquals(1, rowCount("record_creation_template_set"));
        assertEquals(1, rowCount("record_creation_template"));
        assertEquals(1, rowCount("record_creation_template_version"));
        assertTrue(jdbcTemplate.queryForObject(
            "SELECT current_version_id FROM record_creation_template WHERE id = ?",
            Long.class,
            rootId.get()) > 0);
        return rootId.get();
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?",
            Integer.class,
            lifecycleWorkspace.getId());
    }
}
