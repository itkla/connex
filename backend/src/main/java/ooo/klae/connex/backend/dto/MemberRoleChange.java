package ooo.klae.connex.backend.dto;

/** Internal result of a direct role edit or an ownership offer preserving the current member. */
public record MemberRoleChange<T>(T currentMember, OwnershipOfferDto offer) {
    /** Direct role changes retain their existing HTTP response. */
    public static <T> MemberRoleChange<T> direct(T member) {
        return new MemberRoleChange<>(member, null);
    }
}
