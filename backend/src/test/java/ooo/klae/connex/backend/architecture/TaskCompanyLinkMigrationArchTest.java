package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;

/** Pins the schema and existing lifecycle/export contracts for direct company-linked tasks. */
class TaskCompanyLinkMigrationArchTest {

    @Test
    void migrationAddsNullableIndexedCompanyLinkWithSetNullDeletion() throws IOException {
        String migration = resource("db/migration/tenant/V205__task_company_link.sql")
            .replaceAll("\\s+", " ")
            .toLowerCase();

        assertTrue(migration.contains("alter table task"));
        assertTrue(migration.contains("add column company_id int null"));
        assertTrue(migration.contains("add index idx_task_company (company_id)"));
        assertTrue(migration.contains(
            "foreign key (company_id) references company(id) on delete set null"));
        assertTrue(!migration.contains("workspace_id, company_id"));
    }

    @Test
    void lifecycleDeletesWorkspaceTasksBeforeCompanies() {
        var task = TenantLifecycleRegistry.require("task");
        var company = TenantLifecycleRegistry.require("company");

        assertTrue(task.direct());
        assertEquals("task t0", task.route().fromClause());
        assertEquals("t0.workspace_id", task.route().workspacePredicate());
        assertTrue(task.deleteOrder() < company.deleteOrder());
    }

    @Test
    void genericTenantExportSelectsEveryTaskColumn() throws IOException {
        String mapper = resource("mappers/TenantLifecycleMapper.xml")
            .replaceAll("\\s+", " ");

        assertTrue(mapper.contains("SELECT t0.* FROM ${declaration.route.fromClause}"));
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = TaskCompanyLinkMigrationArchTest.class
                .getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing resource " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
