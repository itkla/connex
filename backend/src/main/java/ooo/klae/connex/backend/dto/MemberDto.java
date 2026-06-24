package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace member with their role, for the member-management view.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemberDto {
    private int id;
    private String username;
    private String displayName;
    private String email;
    private String role;
}
