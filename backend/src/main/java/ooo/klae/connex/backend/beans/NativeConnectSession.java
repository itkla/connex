package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** Single-use server-side session for a managed native/PKCE provider authorization. */
@Data
@NoArgsConstructor
@ToString(exclude = {
    "pairingCodeHash", "handoffTicketHash", "stateHash", "verifierRef"
})
public class NativeConnectSession {
    private int id;
    private int userId;
    private String provider;
    private String status;
    private byte[] pairingCodeHash;
    private byte[] handoffTicketHash;
    private byte[] stateHash;
    private String verifierRef;
    private String redirectUri;
    private Integer expectedConnectionId;
    private Long expectedCredentialGeneration;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;
}
