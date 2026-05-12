package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.User;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    private Integer id;

    @NotBlank
    @Size(min = 3, max = 64)
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Username must contain only letters, numbers, dots, underscores, and hyphens")
    private String username;

    @NotBlank
    @Size(max = 128)
    private String displayName;

    @NotBlank
    @Email
    @Size(max = 255)
    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "Email must be a valid email address")
    private String email;

    private String createdAt;
    private String updatedAt;
    private String lastLoginAt;

    @Size(max = 2048)
    private String profilePictureUrl;

    public static UserDto from(User u) {
        if (u == null) return null;
        UserDto dto = new UserDto();
        dto.id = u.getId();
        dto.username = u.getUsername();
        dto.displayName = u.getDisplayName();
        dto.email = u.getEmail();
        dto.createdAt = u.getCreatedAt();
        dto.updatedAt = u.getUpdatedAt();
        dto.lastLoginAt = u.getLastLoginAt();
        dto.profilePictureUrl = u.getProfilePictureUrl();
        return dto;
    }

    public User toBean() {
        User u = new User();
        // u.setId(id);
        if (id != null) u.setId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setEmail(email);
        u.setCreatedAt(createdAt);
        u.setUpdatedAt(updatedAt);
        u.setLastLoginAt(lastLoginAt);
        u.setProfilePictureUrl(profilePictureUrl);
        return u;
    }
}
