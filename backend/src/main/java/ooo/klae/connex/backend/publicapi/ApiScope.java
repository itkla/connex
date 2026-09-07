package ooo.klae.connex.backend.publicapi;

import java.util.EnumSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import ooo.klae.connex.backend.tenant.Permission;

/** Permanently additive catalog of public API scopes and their live RBAC ceilings. */
public enum ApiScope {
    CRM_READ("crm.read", Permission.REPORT_READ),
    CRM_WRITE("crm.write",
        Permission.COMPANY_CREATE,
        Permission.COMPANY_UPDATE,
        Permission.COMPANY_DELETE,
        Permission.PERSON_CREATE,
        Permission.PERSON_UPDATE,
        Permission.PERSON_DELETE,
        Permission.DEAL_CREATE,
        Permission.DEAL_UPDATE,
        Permission.DEAL_DELETE),
    ACTIVITIES_READ("activities.read", Permission.REPORT_READ),
    ACTIVITIES_WRITE("activities.write",
        Permission.ACTIVITY_CREATE,
        Permission.ACTIVITY_UPDATE,
        Permission.ACTIVITY_DELETE,
        Permission.NOTE_CREATE,
        Permission.NOTE_UPDATE,
        Permission.NOTE_DELETE,
        Permission.TASK_CREATE,
        Permission.TASK_UPDATE,
        Permission.TASK_DELETE);

    private static final String AUTHORITY_PREFIX = "SCOPE_";

    private final String wireValue;
    private final Set<Permission> permissions;

    ApiScope(String wireValue, Permission first, Permission... rest) {
        this.wireValue = wireValue;
        this.permissions = EnumSet.of(first, rest);
    }

    /** Returns the stable lower-case scope token used on the wire and in storage. */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /** Returns the Spring Security authority representing this scope. */
    public String authority() {
        return AUTHORITY_PREFIX + wireValue;
    }

    /** Returns the existing RBAC permissions whose operations this scope may expose. */
    public Set<Permission> permissions() {
        return Set.copyOf(permissions);
    }

    /** Returns whether at least one operation represented by this scope remains live for the creator. */
    public boolean isAuthorizedBy(Set<Permission> currentPermissions) {
        return currentPermissions != null && permissions.stream().anyMatch(currentPermissions::contains);
    }

    /** Parses one exact public scope token. */
    @JsonCreator
    public static ApiScope fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("API scope is required");
        }
        for (ApiScope scope : values()) {
            if (scope.wireValue.equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unknown API scope");
    }
}
