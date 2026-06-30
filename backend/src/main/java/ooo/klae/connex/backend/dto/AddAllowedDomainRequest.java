package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * Request to add an email domain to a workspace's join allowlist. The value is normalized and
 * shape-checked in {@code AllowedDomainService} (lowercased, leading {@code @} stripped, must look
 * like a domain).
 */
@Data
public class AddAllowedDomainRequest {

    @NotBlank
    @Size(max = 255)
    private String domain;
}
