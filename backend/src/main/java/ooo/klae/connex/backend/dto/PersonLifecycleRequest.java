package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
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
 * lifecycle state.
 */
@Data
@NoArgsConstructor
public class PersonLifecycleRequest {
    @NotNull
    private PersonLifecycleStage stage;

    private PersonDisqualificationReason reason;

    @Size(max = 2000)
    private String note;
}
