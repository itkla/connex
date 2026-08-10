package ooo.klae.connex.backend.tenant;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Closed lifecycle and export registry for workspace-owned data that remains physically on the
 * control plane.
 */
public final class ControlWorkspaceLifecycleRegistry {
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Map<String, TableLifecycle> DECLARATIONS = Map.of(
        "client_error",
        new TableLifecycle("client_error", "workspace_id", "id", ExportKind.CLIENT_ERROR));

    private ControlWorkspaceLifecycleRegistry() {
    }

    /** Returns the complete immutable registry keyed by table name. */
    public static Map<String, TableLifecycle> declarations() {
        return DECLARATIONS;
    }

    /** Requires the canonical registered declaration instance. */
    public static TableLifecycle requireRegistered(TableLifecycle declaration) {
        if (declaration == null || DECLARATIONS.get(declaration.table()) != declaration) {
            throw new IllegalArgumentException(
                "Unregistered control-workspace lifecycle declaration");
        }
        return declaration;
    }

    /** Safe export projection owned by one registered table. */
    public enum ExportKind {
        CLIENT_ERROR
    }

    /** One control-plane workspace-data lifecycle declaration. */
    public static final class TableLifecycle {
        private final String table;
        private final String workspaceColumn;
        private final String exportKey;
        private final ExportKind exportKind;

        private TableLifecycle(
                String table,
                String workspaceColumn,
                String exportKey,
                ExportKind exportKind) {
            requireIdentifier(table);
            requireIdentifier(workspaceColumn);
            requireIdentifier(exportKey);
            this.table = table;
            this.workspaceColumn = workspaceColumn;
            this.exportKey = exportKey;
            this.exportKind = exportKind;
        }

        public String table() {
            return table;
        }

        public String workspaceColumn() {
            return workspaceColumn;
        }

        public String exportKey() {
            return exportKey;
        }

        public ExportKind exportKind() {
            return exportKind;
        }
    }

    private static void requireIdentifier(String value) {
        if (!SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid lifecycle SQL identifier");
        }
    }
}
