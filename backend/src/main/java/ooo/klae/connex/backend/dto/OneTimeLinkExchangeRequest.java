package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** Boundary DTO for the one request allowed to carry an emailed raw token in its body. */
@Data
@NoArgsConstructor
@ToString(exclude = "token")
public class OneTimeLinkExchangeRequest {

    @NotBlank
    @Size(max = 512)
    private String token;
}
