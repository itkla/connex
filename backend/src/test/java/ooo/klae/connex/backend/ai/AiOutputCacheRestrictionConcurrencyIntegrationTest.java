package ooo.klae.connex.backend.ai;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Verifies contact restrictions and person-aware AI cache writes serialize in real MySQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiOutputCacheRestrictionConcurrencyIntegrationTest {
    private static final String FEATURE = "intro.rationale:en";
    private static final String DEAL_BRIEF_FEATURE = "deal.brief:en";
    private static final String CONTENT_HASH = "a".repeat(64);
    private static final String GENERATED_AT = "2026-07-22T00:00:00Z";

    @Autowired private AiOutputCacheStore cacheStore;
    @Autowired private PersonService personService;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private AiOutputCacheMapper aiOutputCacheMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private ShareMapper shareMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private PersonMapper personMapperSpy;
    @MockitoBean private AuditService auditService;

    private Organization organization;
    private Workspace ownerWorkspace;
    private Workspace granteeWorkspace;
    private User owner;
    private Person person;
    private Company company;
    private Pipeline pipeline;
    private Stage stage;
    private Deal deal;
    private Activity activity;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("AI cache restriction " + unique);
        organization.setSlug("ai-cache-restriction-" + unique);
        organizationMapper.insert(organization);

        ownerWorkspace = workspace("AI cache owner " + unique, "ai-cache-owner-" + unique);
        granteeWorkspace = workspace("AI cache grantee " + unique, "ai-cache-grantee-" + unique);

        owner = new User();
        owner.setUsername("ai-cache-restriction-" + unique);
        owner.setDisplayName("AI cache restriction " + unique);
        owner.setEmail("ai-cache-restriction-" + unique + "@example.com");
        owner.setPasswordHash("hash-" + unique);
        owner.setTimezone("UTC");
        userMapper.insert(owner);
        workspaceMapper.addMember(ownerWorkspace.getId(), owner.getId(), "owner");
        workspaceMapper.addMember(granteeWorkspace.getId(), owner.getId(), "member");

        person = new Person();
        person.setWorkspaceId(ownerWorkspace.getId());
        person.setName("Restricted contact " + unique);
        person.setEmail("restricted-" + unique + "@example.com");
        personMapper.insert(person);
        assertTrue(shareMapper.sharePerson(
                person.getId(), ownerWorkspace.getId(), granteeWorkspace.getId(),
                owner.getId(), false) > 0);

        company = new Company();
        company.setWorkspaceId(granteeWorkspace.getId());
        company.setName("AI cache company " + unique);
        companyMapper.insert(company);
        pipeline = new Pipeline();
        pipeline.setWorkspaceId(granteeWorkspace.getId());
        pipeline.setName("AI cache pipeline " + unique);
        pipelineMapper.insertPipeline(pipeline);
        stage = new Stage();
        stage.setWorkspaceId(granteeWorkspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("AI cache stage " + unique);
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);
        deal = new Deal();
        deal.setWorkspaceId(granteeWorkspace.getId());
        deal.setName("AI cache deal " + unique);
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("USD");
        deal.setCompanyId(company.getId());
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        dealMapper.insert(deal);
        activity = new Activity();
        activity.setWorkspaceId(granteeWorkspace.getId());
        activity.setType("call");
        activity.setSubject("Current direct contributor");
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(owner);
        activity.setTimestamp("2026-07-22 09:00:00");
        activityMapper.insert(activity);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (deal != null) {
            jdbcTemplate.update("DELETE FROM ai_output_cache WHERE subject_a_id = ?", deal.getId());
            jdbcTemplate.update("DELETE FROM activity WHERE deal_id = ?", deal.getId());
            jdbcTemplate.update("DELETE FROM deal_stage_history WHERE deal_id = ?", deal.getId());
            jdbcTemplate.update("DELETE FROM deal WHERE id = ?", deal.getId());
        }
        if (stage != null) {
            jdbcTemplate.update("DELETE FROM stage WHERE id = ?", stage.getId());
        }
        if (pipeline != null) {
            jdbcTemplate.update("DELETE FROM pipeline WHERE id = ?", pipeline.getId());
        }
        if (company != null) {
            jdbcTemplate.update("DELETE FROM company WHERE id = ?", company.getId());
        }
        if (person != null) {
            jdbcTemplate.update("DELETE FROM ai_output_cache WHERE subject_a_id = ?", person.getId());
            jdbcTemplate.update("DELETE FROM person_share WHERE person_id = ?", person.getId());
            jdbcTemplate.update("DELETE FROM person WHERE id = ?", person.getId());
        }
        if (ownerWorkspace != null && granteeWorkspace != null) {
            jdbcTemplate.update(
                    "DELETE FROM workspace_member WHERE workspace_id IN (?, ?)",
                    ownerWorkspace.getId(), granteeWorkspace.getId());
            jdbcTemplate.update(
                    "DELETE FROM workspace WHERE id IN (?, ?)",
                    ownerWorkspace.getId(), granteeWorkspace.getId());
        }
        if (owner != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", owner.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void restrictionCommitsBeforeWaitingGranteeSaveRejectsPersistence() throws Exception {
        int personId = person.getId();
        int ownerWorkspaceId = ownerWorkspace.getId();
        CountDownLatch restrictionLocked = new CountDownLatch(1);
        CountDownLatch releaseRestriction = new CountDownLatch(1);
        CountDownLatch saveLockAttempted = new CountDownLatch(1);
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            Person locked = realPersonMapper.getOwnedPersonByIdForUpdate(ownerWorkspaceId, personId);
            restrictionLocked.countDown();
            assertTrue(releaseRestriction.await(30, TimeUnit.SECONDS));
            return locked;
        }).when(personMapperSpy).getOwnedPersonByIdForUpdate(ownerWorkspaceId, personId);
        doAnswer(invocation -> {
            saveLockAttempted.countDown();
            return realPersonMapper.getVisiblePersonByIdForUpdate(
                    granteeWorkspace.getId(), personId);
        }).when(personMapperSpy).getVisiblePersonByIdForUpdate(
                granteeWorkspace.getId(), personId);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Person> restriction = executor.submit(() -> restrict(personId));
            assertTrue(restrictionLocked.await(10, TimeUnit.SECONDS));
            Future<Boolean> save = executor.submit(() -> saveFromGrantee(personId));
            assertTrue(saveLockAttempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> save.get(1, TimeUnit.SECONDS));
            releaseRestriction.countDown();

            restriction.get(20, TimeUnit.SECONDS);
            assertFalse(save.get(20, TimeUnit.SECONDS));
        } finally {
            releaseRestriction.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertNull(aiOutputCacheMapper.getBySubject(
                granteeWorkspace.getId(), FEATURE, personId, AiOutputCacheStore.NO_SUBJECT));
    }

    @Test
    void granteeSaveCommitsBeforeWaitingRestrictionPurgesPersistence() throws Exception {
        int personId = person.getId();
        int ownerWorkspaceId = ownerWorkspace.getId();
        int granteeWorkspaceId = granteeWorkspace.getId();
        CountDownLatch saveLocked = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        CountDownLatch restrictionLockAttempted = new CountDownLatch(1);
        PersonMapper realPersonMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            Person locked = realPersonMapper.getVisiblePersonByIdForUpdate(granteeWorkspaceId, personId);
            saveLocked.countDown();
            assertTrue(releaseSave.await(30, TimeUnit.SECONDS));
            return locked;
        }).when(personMapperSpy).getVisiblePersonByIdForUpdate(granteeWorkspaceId, personId);
        doAnswer(invocation -> {
            restrictionLockAttempted.countDown();
            return realPersonMapper.getOwnedPersonByIdForUpdate(ownerWorkspaceId, personId);
        }).when(personMapperSpy).getOwnedPersonByIdForUpdate(ownerWorkspaceId, personId);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> save = executor.submit(() -> saveDealBriefFromGrantee(personId));
            assertTrue(saveLocked.await(10, TimeUnit.SECONDS));
            Future<Person> restriction = executor.submit(() -> restrict(personId));
            assertTrue(restrictionLockAttempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> restriction.get(1, TimeUnit.SECONDS));
            releaseSave.countDown();

            assertTrue(save.get(20, TimeUnit.SECONDS));
            restriction.get(20, TimeUnit.SECONDS);
        } finally {
            releaseSave.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertNull(aiOutputCacheMapper.getBySubject(
                granteeWorkspaceId, DEAL_BRIEF_FEATURE, deal.getId(), AiOutputCacheStore.NO_SUBJECT));
    }

    private Person restrict(int personId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));
        tenantContext.set(
                ownerWorkspace.getId(), organization.getId(), owner.getId(), "owner", null);
        try {
            return personService.updateProcessingRestrictions(personId, true, false);
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private boolean saveFromGrantee(int personId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));
        tenantContext.set(
                granteeWorkspace.getId(), organization.getId(), owner.getId(), "member", null);
        try {
            return cacheStore.saveForPersons(
                    granteeWorkspace.getId(), FEATURE, personId, AiOutputCacheStore.NO_SUBJECT,
                    CONTENT_HASH, Map.of("rationale", "safe"), 0, GENERATED_AT, List.of(personId));
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private boolean saveDealBriefFromGrantee(int personId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));
        tenantContext.set(
                granteeWorkspace.getId(), organization.getId(), owner.getId(), "member", null);
        try {
            return cacheStore.saveForPersons(
                    granteeWorkspace.getId(), DEAL_BRIEF_FEATURE, deal.getId(),
                    AiOutputCacheStore.NO_SUBJECT, CONTENT_HASH, Map.of("sections", List.of()),
                    0, GENERATED_AT, List.of(personId));
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private Workspace workspace(String name, String slug) {
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName(name);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        return workspace;
    }
}
