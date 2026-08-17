package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.beans.User;

/**
 * One member the current approver may delegate a specific active step to.
 *
 * @param id          application user id
 * @param username    account username
 * @param displayName current display name
 * @param email       current email address, used to disambiguate equal display names
 */
public record ApprovalDelegateDto(
    int id,
    String username,
    String displayName,
    String email
) {
    public static ApprovalDelegateDto from(User user) {
        return new ApprovalDelegateDto(
            user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail());
    }
}
