package ooo.klae.connex.backend.beans;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import lombok.Data;
import lombok.EqualsAndHashCode;
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

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id", scope = User.class)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
public class User implements org.springframework.security.core.userdetails.UserDetails {
    /**
     * Pinned so a later field addition fails a test rather than production: this principal is
     * serialized into {@code SPRING_SESSION_ATTRIBUTES}, and an implicit id changes with the class.
     * The value is the one the current shape computes, so pinning it changes nothing today.
     */
    private static final long serialVersionUID = -5201556527847944016L;

    private int id;
    private String username;
    private String displayName;
    private String email;
    private boolean emailVerified;
    private String timezone;
    private String locale = "en";
    @JsonIgnore
    private String passwordHash; // can be null
    /**
     * The account's session epoch as read alongside the credential this principal was verified
     * against. Carried from that row read to the session stamp and nowhere else, which is why it is
     * transient: the stamp in the session is the persisted source of truth, and a boxed null is the
     * fail-closed value that a primitive zero would silently make indistinguishable from epoch 0.
     */
    @JsonIgnore
    private transient Integer sessionEpoch;
    private String createdAt;
    private String updatedAt;
    private String lastLoginAt;
    private String profilePictureUrl;
    private String department;
    private String title;
    private String employeeId;
    private String phoneNumber;
    // private String address;
    // private Note[] notes;

    public User(int id, String username, String displayName, String email, String passwordHash, String createdAt, String updatedAt, String lastLoginAt, String profilePictureUrl) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastLoginAt = lastLoginAt;
        this.profilePictureUrl = profilePictureUrl;
    }

    // Getters and setters

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
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

    public String getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(String lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getProfilePictureUrl() {
        return profilePictureUrl;
    }

    public void setProfilePictureUrl(String profilePictureUrl) {
        this.profilePictureUrl = profilePictureUrl;
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
