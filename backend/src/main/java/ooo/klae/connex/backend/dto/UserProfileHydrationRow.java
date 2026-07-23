package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** API-safe user profile paired with its database collation key. */
@Data
@NoArgsConstructor
public class UserProfileHydrationRow {
    private Integer id;
    private UserDto profile;
    private byte[] displaySortKey;
}
