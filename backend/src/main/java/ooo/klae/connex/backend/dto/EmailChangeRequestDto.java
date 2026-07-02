package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Request body to initiate a verified email change: the desired new address and
 * the caller's current password (step-up). The password is excluded from
 * {@code toString} so it never lands in logs.
 */
@Data
@NoArgsConstructor
@ToString(exclude = {"currentPassword"})
public class EmailChangeRequestDto {

    @NotBlank
    @Email
    @Size(max = 255)
    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "Email must be a valid email address")
    private String newEmail;

    @NotBlank
    private String currentPassword;
}
