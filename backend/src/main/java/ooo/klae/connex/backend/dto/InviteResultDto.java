package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An invitation result independent of recipient account existence or profile. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InviteResultDto {
    private InviteDto invite;
}
