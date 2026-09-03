package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;

/**
 * A requested lead-lifecycle transition for a contact.
 *
 * <p>{@code stage} is required: withdrawing a contact from the lifecycle is a separate delete on the
 * lifecycle sub-resource, so a client that omits the field cannot silently erase the contact's
 * lifecycle state. A supplied {@code reason} must already be canonical uppercase ASCII matching
 * {@code ^[A-Z][A-Z0-9_]{1,31}$}; the API rejects rather than normalizes lower-case, padded, or
 * accented values.
 */
@Data
@NoArgsConstructor
public class PersonLifecycleRequest {
    @NotNull
    private PersonLifecycleStage stage;

    @Size(max = 32)
    @Pattern(regexp = PersonDisqualificationReason.CODE_PATTERN)
    private String reason;

    @Size(max = 2000)
    private String note;
}
