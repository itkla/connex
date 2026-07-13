package ooo.klae.connex.backend.tenant;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single source of truth for what makes a dedicated-placement database
 * handle servable (#485): a strict identifier shape, never a MySQL system
 * schema, and never the deployment's default catalog (a "dedicated" org
 * aliased onto shared storage). Every consumer of a handle — the request-path
 * resolver, the scheduler catalog fan-out, and the future provisioning write
 * path — must validate through this class so a bad registry row cannot mean
 * different things on different paths.
 */
public final class DatabaseHandles {

    private static final Pattern DATABASE_HANDLE = Pattern.compile("^[A-Za-z0-9_]{1,64}$");
    private static final Set<String> RESERVED_CATALOGS = Set.of(
        "information_schema", "mysql", "performance_schema", "sys");

    private DatabaseHandles() {
    }

    /**
     * Whether a handle may be pinned as a routed catalog.
     *
     * @param handle the registry's {@code database_handle} value
     * @param defaultCatalog the deployment's default catalog, or {@code null}
     * @return {@code true} only for a well-formed, non-reserved handle that is
     *     not the default catalog
     */
    public static boolean servable(String handle, String defaultCatalog) {
        if (handle == null || !DATABASE_HANDLE.matcher(handle).matches()) {
            return false;
        }
        if (RESERVED_CATALOGS.contains(handle.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return defaultCatalog == null || !handle.equalsIgnoreCase(defaultCatalog);
    }
}
