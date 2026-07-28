package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One bounded keyset page from a fresh current-visibility repeatable-read snapshot. Continuation
 * is weakly consistent across requests: hidden members are never retained or replayed, while
 * concurrent identity or restriction changes may cause affected visible rows to be skipped or
 * repeated.
 * @param items visible members from the request's repeatable-read snapshot
 * @param hasMore whether that snapshot contains another member after {@code items}
 * @param nextAfterRecordId nullable record ID cursor for the next request when {@code hasMore}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record IdentityCollisionMemberPageDto(
        List<IdentityCollisionMemberDto> items,
        boolean hasMore,
        Integer nextAfterRecordId) {

    public IdentityCollisionMemberPageDto {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (hasMore != (nextAfterRecordId != null)) {
            throw new IllegalArgumentException(
                "Identity collision member cursor must match hasMore");
        }
    }
}
