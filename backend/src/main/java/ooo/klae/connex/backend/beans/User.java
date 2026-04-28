package ooo.klae.connex.backend.beans;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
// import org.springframework.security.core.authority.SimpleGrantedAuthority;

import jakarta.annotation.Nullable;

/**
 * Represents an authenticated app user (a team member using Connex).
 * Distinct from {@link Person}: a Person is a CRM contact, a User is a Connex account holder.
 * Loaded by Spring Security during WebAuthn authentication via {@code UserService}.
 * Mapped via {@code UserMapper} / {@code UserMapper.xml}.
 */

@Data
@NoArgsConstructor
public class User implements org.springframework.security.core.userdetails.UserDetails {
    private int id;
    private String username;
    private String displayName;
    private String email;
    @JsonIgnore
    private String passwordHash; // can be null
    private String createdAt;
    private String updatedAt;

    public User(int id, String username, String displayName, String email, String passwordHash, String createdAt, String updatedAt) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and setters

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    // commenting out these two functions because we're extending spring security

    // public String getPasswordHash() {
    //     return passwordHash;
    // }

    // public void setPasswordHash(String passwordHash) {
    //     this.passwordHash = passwordHash;
    // }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // return List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return List.of();
    }

    @Override
    @JsonIgnore
    public @Nullable String getPassword() {
        return this.passwordHash;
    }

}
