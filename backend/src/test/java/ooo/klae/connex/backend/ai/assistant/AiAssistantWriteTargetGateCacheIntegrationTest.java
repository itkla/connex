package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * The write framework's post-lock target gate reads committed state, not the first-level cache.
 *
 * <p>A tool may read its target through the scoped getter before the lock — the stage tool reads
 * its deal to resolve the stage — and the framework reads it again through the same getter after
 * the lock, as the owner-scope gate. Inside one transaction MyBatis answers an identical select
 * from its session cache, so the gate would replay the pre-lock row unless the lock statement in
 * between clears that cache. Each case here fills the cache, commits a change from another
 * transaction, shows the unlocked re-read still returns the cached row, takes the framework's
 * target lock and then asserts the getter's statement returns the committed change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiAssistantWriteTargetGateCacheIntegrationTest {
    @Autowired private CompanyMapper companyMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Organization organization;
    private Workspace workspace;
    private Person person;
    private Company company;
    private Deal deal;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Assistant gate cache " + unique);
        organization.setSlug("assistant-gate-cache-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Assistant gate cache " + unique);
        workspace.setSlug("assistant-gate-cache-" + unique);
        workspaceMapper.insert(workspace);

        person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName("Gate person " + unique);
        personMapper.insert(person);

        company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName("Gate company " + unique);
        companyMapper.insert(company);

        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Gate pipeline " + unique);
        pipelineMapper.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("Gate stage " + unique);
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);

        deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setName("Gate deal " + unique);
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("USD");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);
    }

    @AfterEach
    void cleanUp() {
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void theStageChangeLockMakesTheDealGateReadCommittedState() {
        assertGateRereadsCommittedState(
                "deal",
                deal.getId(),
                id -> dealMapper.getDealById(workspace.getId(), id).getName(),
                id -> dealMapper.getDealByIdForUpdate(workspace.getId(), id).getId());
    }

    @Test
    void theSharedPersonLockMakesThePersonGateReadCommittedState() {
        assertGateRereadsCommittedState(
                "person",
                person.getId(),
                id -> personMapper.getPersonById(workspace.getId(), id).getName(),
                id -> personMapper.getVisiblePersonByIdForShare(workspace.getId(), id).getId());
    }

    @Test
    void theExclusivePersonLockMakesThePersonGateReadCommittedState() {
        assertGateRereadsCommittedState(
                "person",
                person.getId(),
                id -> personMapper.getPersonById(workspace.getId(), id).getName(),
                id -> personMapper.getVisiblePersonByIdForUpdate(workspace.getId(), id).getId());
    }

    @Test
    void theCompanyLockMakesTheCompanyGateReadCommittedState() {
        assertGateRereadsCommittedState(
                "company",
                company.getId(),
                id -> companyMapper.getCompanyById(workspace.getId(), id).getName(),
                id -> companyMapper.getOwnedCompanyByIdForUpdate(workspace.getId(), id).getId());
    }

    /**
     * Reads the target through the gate's getter, commits a rename from another transaction,
     * proves the unlocked re-read is still served from the cache, then takes the lock and returns
     * what the gate's getter reads after it.
     */
    private void assertGateRereadsCommittedState(
            String table, int id, IntFunction<String> gateRead, IntUnaryOperator lock) {
        String renamed = "Renamed " + UUID.randomUUID().toString().substring(0, 8);
        TransactionTemplate decision = new TransactionTemplate(transactionManager);
        decision.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionTemplate concurrent = new TransactionTemplate(transactionManager);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String[] reads = decision.execute(status -> {
            String beforeLock = gateRead.apply(id);
            concurrent.executeWithoutResult(inner -> assertEquals(1, jdbcTemplate.update(
                    "UPDATE " + table + " SET name = ? WHERE id = ?", renamed, id)));
            String cachedBeforeLock = gateRead.apply(id);
            assertEquals(id, lock.applyAsInt(id));
            return new String[] {beforeLock, cachedBeforeLock, gateRead.apply(id)};
        });

        assertNotNull(reads);
        assertEquals(reads[0], reads[1], "the unlocked re-read is answered from the session cache");
        assertEquals(renamed, reads[2], "the gate after the " + table + " lock read the cached row");
    }
}
