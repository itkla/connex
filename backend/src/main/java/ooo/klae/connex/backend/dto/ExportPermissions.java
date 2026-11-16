package ooo.klae.connex.backend.dto;

/** Export-only policy for built-in roles; organization policy has no member role. */
public record ExportPermissions(boolean owner, boolean admin, boolean member) {
    /** Whether the named built-in role may export within this policy's scope. */
    public boolean allows(String role) {
        return switch (role) {
            case "owner" -> owner;
            case "admin" -> admin;
            case "member" -> member;
            default -> false;
        };
    }
}
