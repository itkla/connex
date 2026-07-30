package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ImportCommitDeletionConcurrencyIntegrationTest extends AbstractServiceTest {

    @Autowired private ImportService importService;
    @Autowired private CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean private PersonMapper personMapperSpy;
    @MockitoSpyBean private CompanyMapper companyMapperSpy;

    private String referencedCompanyName;
    private String tagName;
    private String customFieldKey;

    @AfterEach
    void cleanUpCommittedFixtures() {
        if (workspace != null) {
            if (customFieldKey != null) {
                jdbcTemplate.update(
                    "DELETE FROM custom_field_definition "
                        + "WHERE workspace_id = ? AND field_key = ?",
                    workspace.getId(),
                    customFieldKey);
            }
            if (tagName != null) {
                jdbcTemplate.update(
                    "DELETE FROM tag WHERE workspace_id = ? AND name = ?",
                    workspace.getId(),
                    tagName);
            }
            if (referencedCompanyName != null) {
                jdbcTemplate.update(
                    "DELETE FROM company WHERE workspace_id = ? AND name = ?",
                    workspace.getId(),
                    referencedCompanyName);
            }
        }
        if (workspace != null && currentUser != null) {
            jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                workspace.getId(),
                currentUser.getId());
            jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?",
                currentUser.getId());
        }
    }

    @Test
    void concurrentPersonDeletionLeavesNoDependencyOrAuditSideEffects()
            throws Exception {
        int workspaceId = workspace.getId();
        Person target = new Person();
        target.setWorkspaceId(workspaceId);
        target.setOwnerId(currentUser.getId());
        target.setName("Deletion race " + unique());
        target.setEmail(unique() + "@example.test");
        personMapper.insert(target);
        referencedCompanyName = "Orphan company " + unique();
        tagName = "orphan_tag_" + unique();
        customFieldKey = "orphan_field_" + unique();
        int auditCountBefore = rowCount(
            "SELECT COUNT(*) FROM audit_log "
                + "WHERE workspace_id = ? AND action LIKE 'import.%'",
            workspaceId);
        CountDownLatch lockRequested = new CountDownLatch(1);
        CountDownLatch deletionCommitted = new CountDownLatch(1);
        PersonMapper realPersonMapper =
            sqlSessionTemplate.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            lockRequested.countDown();
            assertTrue(deletionCommitted.await(20, TimeUnit.SECONDS));
            return realPersonMapper.getOwnedPersonByIdForUpdate(
                workspaceId, target.getId());
        }).when(personMapperSpy).getOwnedPersonByIdForUpdate(
            workspaceId, target.getId());
        ImportRequest request = personRequest(target.getId());
        request.setDuplicateReviewProof(
            importService.previewPersons(request).getDuplicateReviewProof());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<ImportResult> commit = executor.submit(
                () -> commitAsCurrentUser(
                    workspaceId,
                    () -> importService.commitPersons(
                        request)));
            assertTrue(lockRequested.await(10, TimeUnit.SECONDS));
            assertEquals(
                1,
                jdbcTemplate.update(
                    "DELETE FROM person WHERE workspace_id = ? AND id = ?",
                    workspaceId,
                    target.getId()));
            deletionCommitted.countDown();

            ImportResult result = commit.get(20, TimeUnit.SECONDS);
            assertEquals(0, result.getCreated());
            assertEquals(0, result.getUpdated());
            assertEquals(1, result.getFailed().size());
        } finally {
            deletionCommitted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertFalse(companyMapper.getCompaniesForDedup(workspaceId).stream()
            .anyMatch(company ->
                referencedCompanyName.equals(company.getName())));
        assertNull(tagMapper.getTagByName(workspaceId, tagName));
        assertNull(customFieldDefinitionMapper.getByKey(
            workspaceId, "person", customFieldKey));
        assertEquals(
            auditCountBefore,
            rowCount(
                "SELECT COUNT(*) FROM audit_log "
                    + "WHERE workspace_id = ? AND action LIKE 'import.%'",
                workspaceId));
    }

    @Test
    void concurrentCompanyDeletionLeavesNoDependencyOrAuditSideEffects()
            throws Exception {
        int workspaceId = workspace.getId();
        Company target = new Company();
        target.setWorkspaceId(workspaceId);
        target.setOwnerId(currentUser.getId());
        target.setName("Company deletion race " + unique());
        companyMapper.insert(target);
        tagName = "orphan_company_tag_" + unique();
        customFieldKey = "orphan_company_field_" + unique();
        int auditCountBefore = rowCount(
            "SELECT COUNT(*) FROM audit_log "
                + "WHERE workspace_id = ? AND action LIKE 'import.%'",
            workspaceId);
        CountDownLatch lockRequested = new CountDownLatch(1);
        CountDownLatch deletionCommitted = new CountDownLatch(1);
        CompanyMapper realCompanyMapper =
            sqlSessionTemplate.getMapper(CompanyMapper.class);
        doAnswer(invocation -> {
            lockRequested.countDown();
            assertTrue(deletionCommitted.await(20, TimeUnit.SECONDS));
            return realCompanyMapper.getOwnedCompanyByIdForUpdate(
                workspaceId, target.getId());
        }).when(companyMapperSpy).getOwnedCompanyByIdForUpdate(
            workspaceId, target.getId());
        ImportRequest request = companyRequest(target.getId());
        request.setDuplicateReviewProof(
            importService.previewCompanies(request).getDuplicateReviewProof());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<ImportResult> commit = executor.submit(
                () -> commitAsCurrentUser(
                    workspaceId,
                    () -> importService.commitCompanies(
                        request)));
            assertTrue(lockRequested.await(10, TimeUnit.SECONDS));
            assertEquals(
                1,
                jdbcTemplate.update(
                    "DELETE FROM company WHERE workspace_id = ? AND id = ?",
                    workspaceId,
                    target.getId()));
            deletionCommitted.countDown();

            ImportResult result = commit.get(20, TimeUnit.SECONDS);
            assertEquals(0, result.getCreated());
            assertEquals(0, result.getUpdated());
            assertEquals(1, result.getFailed().size());
        } finally {
            deletionCommitted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertNull(tagMapper.getTagByName(workspaceId, tagName));
        assertNull(customFieldDefinitionMapper.getByKey(
            workspaceId, "company", customFieldKey));
        assertEquals(
            auditCountBefore,
            rowCount(
                "SELECT COUNT(*) FROM audit_log "
                    + "WHERE workspace_id = ? AND action LIKE 'import.%'",
                workspaceId));
    }

    private ImportResult commitAsCurrentUser(
            int workspaceId,
            Supplier<ImportResult> commit) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                currentUser, null, currentUser.getAuthorities()));
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        TenantContext context = tenantContext;
        context.set(
            workspaceId,
            orgId == null ? workspaceId : orgId,
            currentUser.getId(),
            "owner",
            null);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        long now = System.currentTimeMillis();
        servletRequest.getSession().setAttribute(
            SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        servletRequest.getSession().setAttribute(
            SessionSecurityService.AUTHENTICATED_USER_ATTR,
            currentUser.getId());
        RequestContextHolder.setRequestAttributes(
            new ServletRequestAttributes(servletRequest));
        try {
            return commit.get();
        } finally {
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
            context.clear();
        }
    }

    private ImportRequest companyRequest(int companyId) {
        return new ImportRequest(
            List.of(Map.of(
                "Name", "Vanished company update",
                "Tags", tagName,
                "Custom", "orphan value")),
            List.of(
                new ColumnMapping("Name", "name", null, null, null),
                new ColumnMapping("Tags", "tags", null, null, null),
                new ColumnMapping(
                    "Custom", null, true, "text", customFieldKey)),
            "overwrite",
            Map.of(0, companyId));
    }

    private ImportRequest personRequest(int personId) {
        return new ImportRequest(
            List.of(Map.of(
                "Name", "Vanished update",
                "Company", referencedCompanyName,
                "Tags", tagName,
                "Custom", "orphan value")),
            List.of(
                new ColumnMapping("Name", "name", null, null, null),
                new ColumnMapping("Company", "company", null, null, null),
                new ColumnMapping("Tags", "tags", null, null, null),
                new ColumnMapping(
                    "Custom", null, true, "text", customFieldKey)),
            "overwrite",
            Map.of(0, personId));
    }

    private int rowCount(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }
}
