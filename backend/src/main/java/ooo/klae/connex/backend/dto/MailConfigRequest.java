package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.ToString;

/**
 * Upsert of a workspace's SMTP config. Structural constraints only; the
 * "required when enabled" and password-encryption rules live in
 * {@code WorkspaceMailConfigService}. A null/blank {@code password} leaves any
 * stored password unchanged; a blank string is never persisted as the password.
 */
@Data
@ToString(exclude = "password")
public class MailConfigRequest {

    private boolean enabled;

    @Size(max = 255)
    private String host;

    @Min(1)
    @Max(65535)
    private Integer port;

    @Size(max = 255)
    private String username;

    @Size(max = 1024)
    private String password;

    @Email
    @Size(max = 320)
    private String fromAddress;

    @Size(max = 255)
    private String fromName;

    private boolean starttls = true;
    private boolean ssl = false;
    private boolean auth = true;
}
