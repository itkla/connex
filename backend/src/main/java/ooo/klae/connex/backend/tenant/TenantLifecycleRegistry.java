package ooo.klae.connex.backend.tenant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Closed registry describing how every org-data table participates in tenant
 * export, teardown, and residual verification. Dynamic SQL consumers may use
 * identifiers and route fragments only after validating declaration object
 * identity through {@link #requireRegistered(TableLifecycle)}. No request value
 * can create or alter a declaration.
 */
public final class TenantLifecycleRegistry {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Map<String, TableLifecycle> DECLARATIONS = buildDeclarations();

    private TenantLifecycleRegistry() {
    }

    /** Returns the immutable lifecycle declaration map keyed by table name. */
    public static Map<String, TableLifecycle> declarations() {
        return DECLARATIONS;
    }

    /** Returns the registered declaration for a table or fails closed. */
    public static TableLifecycle require(String table) {
        TableLifecycle declaration = DECLARATIONS.get(table);
        if (declaration == null) {
            throw new IllegalArgumentException("No tenant lifecycle declaration for " + table);
        }
        return declaration;
    }

    /**
     * Verifies that a declaration is the canonical sealed registry instance
     * before its SQL identifiers are interpolated.
     */
    public static TableLifecycle requireRegistered(TableLifecycle declaration) {
        if (declaration == null
                || DECLARATIONS.get(declaration.table()) != declaration) {
            throw new IllegalArgumentException("Unregistered tenant lifecycle declaration");
        }
        return declaration;
    }

    /** How a table is reached from a workspace root. */
    public sealed interface TenantReach permits Direct, Cascade {
    }

    /** A table whose rows carry the workspace key directly. */
    public static final class Direct implements TenantReach {
        private final String workspaceColumn;

        private Direct(String workspaceColumn) {
            requireIdentifier(workspaceColumn);
            this.workspaceColumn = workspaceColumn;
        }

        public String workspaceColumn() {
            return workspaceColumn;
        }
    }

    /** A table reached through an in-plane cascading foreign key. */
    public static final class Cascade implements TenantReach {
        private final String parentTable;
        private final String constraintName;
        private final List<ColumnLink> columns;

        private Cascade(
                String parentTable,
                String constraintName,
                List<ColumnLink> columns) {
            requireIdentifier(parentTable);
            requireIdentifier(constraintName);
            this.parentTable = parentTable;
            this.constraintName = constraintName;
            this.columns = List.copyOf(columns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("Cascade columns are required");
            }
        }

        public String parentTable() {
            return parentTable;
        }

        public String constraintName() {
            return constraintName;
        }

        public List<ColumnLink> columns() {
            return columns;
        }
    }

    /** One ordered child-to-parent foreign-key column link. */
    public static final class ColumnLink {
        private final String childColumn;
        private final String parentColumn;

        private ColumnLink(String childColumn, String parentColumn) {
            requireIdentifier(childColumn);
            requireIdentifier(parentColumn);
            this.childColumn = childColumn;
            this.parentColumn = parentColumn;
        }

        public String childColumn() {
            return childColumn;
        }

        public String parentColumn() {
            return parentColumn;
        }
    }

    /** Teardown phase in which a direct table is removed. */
    public enum DeleteStage {
        CONTENT,
        STORAGE_FINALIZATION
    }

    /** A registry-declared operation that breaks a restrictive internal edge. */
    public sealed interface Preparation permits NullifyReference {
    }

    /** Nulls a workspace-filtered reference before delete batching begins. */
    public static final class NullifyReference implements Preparation {
        private final String column;

        private NullifyReference(String column) {
            requireIdentifier(column);
            this.column = column;
        }

        public String column() {
            return column;
        }
    }

    /** Registry-derived SQL route shared by export and residual verification. */
    public static final class SqlRoute {
        private final String fromClause;
        private final String workspacePredicate;

        private SqlRoute(String fromClause, String workspacePredicate) {
            if (fromClause.isBlank() || workspacePredicate.isBlank()) {
                throw new IllegalArgumentException("Lifecycle SQL route is incomplete");
            }
            this.fromClause = fromClause;
            this.workspacePredicate = workspacePredicate;
        }

        public String fromClause() {
            return fromClause;
        }

        public String workspacePredicate() {
            return workspacePredicate;
        }
    }

    /**
     * Complete table lifecycle declaration. Delete order applies to direct
     * tables; cascading children are removed by their declared parent.
     */
    public static final class TableLifecycle {
        private final String table;
        private final TenantReach reach;
        private final DeleteStage deleteStage;
        private final int deleteOrder;
        private final List<Preparation> preparations;
        private final SqlRoute route;

        private TableLifecycle(
                String table,
                TenantReach reach,
                DeleteStage deleteStage,
                int deleteOrder,
                List<Preparation> preparations,
                SqlRoute route) {
            requireIdentifier(table);
            this.table = table;
            this.reach = reach;
            this.deleteStage = deleteStage;
            this.deleteOrder = deleteOrder;
            this.preparations = List.copyOf(preparations);
            this.route = route;
        }

        public String table() {
            return table;
        }

        public TenantReach reach() {
            return reach;
        }

        public DeleteStage deleteStage() {
            return deleteStage;
        }

        public int deleteOrder() {
            return deleteOrder;
        }

        public List<Preparation> preparations() {
            return preparations;
        }

        public SqlRoute route() {
            return route;
        }

        /** Whether this declaration is removed by a direct workspace delete. */
        public boolean direct() {
            return reach instanceof Direct;
        }

        /** Direct workspace column, available only for direct declarations. */
        public String workspaceColumn() {
            return ((Direct) reach).workspaceColumn();
        }
    }

    private static Map<String, TableLifecycle> buildDeclarations() {
        List<TableLifecycle> raw = new ArrayList<>();
        raw.add(direct("campaign_delivery_event", 10));
        raw.add(direct("campaign_delivery", 20));
        raw.add(direct("campaign_audience_export", 30));
        raw.add(direct("campaign_audience_member", 40));
        raw.add(direct("campaign_send", 50));
        raw.add(direct("campaign_message_revision", 60));
        raw.add(direct("campaign_audience_snapshot", 70));
        raw.add(direct("campaign_audience", 80));
        raw.add(direct("campaign_message", 90));
        raw.add(direct("campaign", 100, nullify("parent_campaign_id")));
        raw.add(direct("document_approval", 110));
        raw.add(direct("deal_document", 120));
        raw.add(direct("deal_line_item", 130));
        raw.add(direct("deal_stage_history", 140));
        raw.add(direct("deal_collaborator", 150));
        raw.add(direct("deal", 160));
        raw.add(direct("stage", 170));
        raw.add(direct("pipeline_share", 180));
        raw.add(direct("workflow_intervention", 181));
        raw.add(direct("workflow_invocation_record", 182));
        raw.add(direct("workflow_invocation", 183));
        raw.add(direct("workflow_recipe_origin", 184));
        raw.add(direct("pipeline", 190));
        raw.add(direct("workflow_step_attempt", 194));
        raw.add(direct("workflow_step_run", 195));
        raw.add(direct("workflow_run", 196));
        raw.add(direct("workflow_trigger_outbox", 197));
        raw.add(direct("workflow_runtime_workspace", 198));
        raw.add(direct("rule_execution", 200));
        raw.add(direct("job_run", 201));
        raw.add(direct("workflow_version", 210));
        raw.add(direct("workflow", 220, nullify("active_version_id")));
        raw.add(direct("rule", 230));
        raw.add(direct("report_schedule", 240));
        raw.add(direct("report_snapshot", 250));
        raw.add(direct("report_definition", 260));
        raw.add(direct("custom_field_value", 270));
        raw.add(direct("custom_field_definition", 280));
        raw.add(direct("contact_channel_consent_event", 290));
        raw.add(direct("contact_channel_consent", 300));
        raw.add(direct("saved_view_default", 310));
        raw.add(direct("saved_view_pin", 320));
        raw.add(direct("saved_view", 330));
        raw.add(direct("person_edge", 340));
        raw.add(direct("person_employment", 350));
        raw.add(direct("person_share", 360));
        raw.add(direct("introduction", 370));
        raw.add(direct("warm_path_dismissal", 380));
        raw.add(direct("suppression_entry", 390));
        raw.add(direct("provider_capture_workspace_policy", 391));
        raw.add(direct("provider_capture_user_policy", 392));
        raw.add(direct("provider_capture_sync_state", 393));
        raw.add(direct("provider_participant_decision", 394));
        raw.add(direct("identity_collision", 395));
        raw.add(direct("provider_captured_interaction", 396));
        raw.add(direct("activity", 400));
        raw.add(direct("note", 410));
        raw.add(direct("task", 420));
        raw.add(direct("person", 430));
        raw.add(direct("company_share", 440));
        raw.add(direct("company", 450));
        raw.add(direct("attachment", 460));
        raw.add(direct("ai_output_cache", 470));
        raw.add(direct("approval_policy", 480));
        raw.add(direct("business_card_import_request", 490));
        raw.add(direct("connector_config", 500));
        raw.add(direct("delivery_provider_config", 510));
        raw.add(direct("document_template", 520));
        raw.add(direct("entity_reference", 530));
        raw.add(direct("historical_notification_baseline", 535));
        raw.add(direct("notification", 540));
        raw.add(direct("product", 550));
        raw.add(direct("report_goal", 560));
        raw.add(direct("tag", 570));
        raw.add(direct("task_board_lock", 580));
        raw.add(direct("user_dashboard", 590));
        raw.add(storage("managed_object_usage", 600));
        raw.add(storage("object_deletion_queue", 610));
        raw.add(storage("object_storage_quota", 620));
        raw.add(cascade("attachment_tag", "attachment",
            "fk_attachment_tag_attachment", link("attachment_id", "id")));
        raw.add(cascade("company_tag", "company",
            "fk_company_tag_company", link("company_id", "id")));
        raw.add(cascade("deal_person", "deal",
            "fk_deal_person_deal", link("deal_id", "id")));
        raw.add(cascade("deal_tag", "deal",
            "fk_deal_tag_deal", link("deal_id", "id")));
        raw.add(cascade("person_tag", "person",
            "fk_person_tag_person", link("person_id", "id")));
        raw.add(cascade("provider_activity_projection", "provider_captured_interaction",
            "fk_provider_activity_projection_interaction",
            link("workspace_id", "workspace_id"), link("interaction_id", "id")));
        raw.add(cascade("provider_captured_participant", "provider_captured_interaction",
            "fk_provider_captured_participant_interaction",
            link("workspace_id", "workspace_id"), link("interaction_id", "id")));
        raw.add(cascade("person_identity", "person",
            "fk_person_identity_person",
            link("workspace_id", "workspace_id"), link("person_id", "id")));
        raw.add(cascade("company_identity", "company",
            "fk_company_identity_company",
            link("workspace_id", "workspace_id"), link("company_id", "id")));

        Map<String, TableLifecycle> byTable = new LinkedHashMap<>();
        for (TableLifecycle declaration : raw) {
            if (byTable.put(declaration.table(), declaration) != null) {
                throw new IllegalStateException("Duplicate tenant lifecycle declaration for "
                    + declaration.table());
            }
        }
        Map<String, TableLifecycle> routed = new LinkedHashMap<>();
        for (TableLifecycle declaration : byTable.values()) {
            routed.put(declaration.table(), withRoute(declaration, byTable));
        }
        if (!routed.keySet().equals(TablePlaneRegistry.ORG_DATA_TABLES)) {
            Set<String> missing = new java.util.HashSet<>(TablePlaneRegistry.ORG_DATA_TABLES);
            missing.removeAll(routed.keySet());
            Set<String> stale = new java.util.HashSet<>(routed.keySet());
            stale.removeAll(TablePlaneRegistry.ORG_DATA_TABLES);
            throw new IllegalStateException("Tenant lifecycle registry mismatch; missing="
                + missing + ", stale=" + stale);
        }
        return Collections.unmodifiableMap(routed);
    }

    private static TableLifecycle withRoute(
            TableLifecycle declaration,
            Map<String, TableLifecycle> declarations) {
        StringBuilder from = new StringBuilder(declaration.table()).append(" t0");
        TableLifecycle current = declaration;
        String alias = "t0";
        int depth = 0;
        Set<String> visited = new java.util.HashSet<>();
        while (current.reach() instanceof Cascade cascade) {
            if (!visited.add(current.table())) {
                throw new IllegalStateException("Tenant lifecycle cascade cycle at " + current.table());
            }
            TableLifecycle parent = declarations.get(cascade.parentTable());
            if (parent == null) {
                throw new IllegalStateException("Unknown lifecycle parent " + cascade.parentTable());
            }
            String parentAlias = "t" + (++depth);
            from.append(" JOIN ").append(parent.table()).append(' ').append(parentAlias)
                .append(" ON ");
            for (int i = 0; i < cascade.columns().size(); i++) {
                if (i > 0) {
                    from.append(" AND ");
                }
                ColumnLink link = cascade.columns().get(i);
                from.append(alias).append('.').append(link.childColumn())
                    .append(" = ").append(parentAlias).append('.').append(link.parentColumn());
            }
            current = parent;
            alias = parentAlias;
        }
        Direct direct = (Direct) current.reach();
        SqlRoute route = new SqlRoute(from.toString(), alias + "." + direct.workspaceColumn());
        return new TableLifecycle(
            declaration.table(),
            declaration.reach(),
            declaration.deleteStage(),
            declaration.deleteOrder(),
            declaration.preparations(),
            route);
    }

    private static TableLifecycle direct(String table, int order, Preparation... preparations) {
        return new TableLifecycle(
            table,
            new Direct("workspace_id"),
            DeleteStage.CONTENT,
            order,
            List.of(preparations),
            new SqlRoute(table + " t0", "t0.workspace_id"));
    }

    private static TableLifecycle storage(String table, int order) {
        return new TableLifecycle(
            table,
            new Direct("workspace_id"),
            DeleteStage.STORAGE_FINALIZATION,
            order,
            List.of(),
            new SqlRoute(table + " t0", "t0.workspace_id"));
    }

    private static TableLifecycle cascade(
            String table,
            String parent,
            String constraint,
            ColumnLink... links) {
        return new TableLifecycle(
            table,
            new Cascade(parent, constraint, List.of(links)),
            DeleteStage.CONTENT,
            Integer.MIN_VALUE,
            List.of(),
            new SqlRoute(table + " t0", "t0.workspace_id"));
    }

    private static ColumnLink link(String child, String parent) {
        return new ColumnLink(child, parent);
    }

    private static NullifyReference nullify(String column) {
        return new NullifyReference(column);
    }

    private static void requireIdentifier(String identifier) {
        if (identifier == null || !SQL_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe SQL identifier");
        }
    }
}
