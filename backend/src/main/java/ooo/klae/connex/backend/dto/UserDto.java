package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.User;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    private int id;

    @NotBlank
    @Size(min = 3, max = 64)
    private String username;

    @NotBlank
    @Size(max = 128)
    private String displayName;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    private String createdAt;
    private String updatedAt;

    public static UserDto from(User u) {
        if (u == null) return null;
        UserDto dto = new UserDto();
        dto.id = u.getId();
        dto.username = u.getUsername();
        dto.displayName = u.getDisplayName();
        dto.email = u.getEmail();
        dto.createdAt = u.getCreatedAt();
        dto.updatedAt = u.getUpdatedAt();
        return dto;
    }

    public User toBean() {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setEmail(email);
        u.setCreatedAt(createdAt);
        u.setUpdatedAt(updatedAt);
        return u;
    }
}
