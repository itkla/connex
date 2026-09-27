package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Display-safe control-plane user profile paired with the database collation key of its display
 * name. The key lets callers merge separately fetched batches in exactly the order the database
 * applies to {@code ORDER BY display_name}.
 */
@Data
@NoArgsConstructor
public class UserProfileHydrationRow {
    private Integer id;
    private byte[] displaySortKey;
    private UserDto profile;
}
