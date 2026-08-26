package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for sending one immutable commercial-document version. */
@Data
@NoArgsConstructor
public class SendDeliveryRequest {
    @NotBlank
    @Pattern(regexp = "[a-z0-9_]{1,32}")
    private String provider = "in_app";

    @Size(max = 2000)
    private String message;

    @Future
    private LocalDateTime expiresAt;

    @Valid
    @Size(min = 1, max = 20)
    private List<SendDeliveryRecipientRequest> recipients = new ArrayList<>();
}
