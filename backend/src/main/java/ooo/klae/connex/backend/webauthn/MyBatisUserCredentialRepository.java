package ooo.klae.connex.backend.webauthn;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Repository;

import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;

import lombok.RequiredArgsConstructor;

/**
 * MyBatis-backed {@link UserCredentialRepository}. Persists enrolled authenticators and, on each
 * successful assertion, the updated signature counter and state that Spring Security writes back
 * via {@link #save}. Translates between Spring's {@code CredentialRecord} and the
 * {@link WebauthnCredentialRow} persistence row.
 */
@Repository
@RequiredArgsConstructor
public class MyBatisUserCredentialRepository implements UserCredentialRepository {

    private final WebauthnCredentialMapper mapper;

    @Override
    public void save(CredentialRecord credentialRecord) {
        WebauthnCredentialRow row = toRow(credentialRecord);
        if (mapper.findByCredentialId(row.getCredentialId()) != null) {
            mapper.updateMutable(row);
        } else {
            mapper.insert(row);
        }
    }

    @Override
    public void delete(Bytes credentialId) {
        mapper.delete(credentialId.getBytes());
    }

    @Override
    public CredentialRecord findByCredentialId(Bytes credentialId) {
        return toRecord(mapper.findByCredentialId(credentialId.getBytes()));
    }

    @Override
    public List<CredentialRecord> findByUserId(Bytes userId) {
        return mapper.findByUserEntityUserId(userId.toBase64UrlString()).stream()
            .map(this::toRecord)
            .collect(Collectors.toList());
    }

    private WebauthnCredentialRow toRow(CredentialRecord record) {
        WebauthnCredentialRow row = new WebauthnCredentialRow();
        row.setCredentialId(record.getCredentialId().getBytes());
        row.setUserEntityUserId(record.getUserEntityUserId().toBase64UrlString());
        row.setCredentialType(record.getCredentialType() == null ? null : record.getCredentialType().getValue());
        row.setPublicKey(record.getPublicKey().getBytes());
        row.setSignatureCount(record.getSignatureCount());
        row.setUvInitialized(record.isUvInitialized());
        row.setBackupEligible(record.isBackupEligible());
        row.setBackupState(record.isBackupState());
        row.setTransports(serializeTransports(record.getTransports()));
        row.setAttestationObject(record.getAttestationObject() == null ? null : record.getAttestationObject().getBytes());
        row.setAttestationClientDataJson(record.getAttestationClientDataJSON() == null ? null : record.getAttestationClientDataJSON().getBytes());
        row.setLabel(record.getLabel());
        row.setCreatedAt(record.getCreated() != null ? record.getCreated() : Instant.now());
        row.setLastUsedAt(record.getLastUsed());
        return row;
    }

    private CredentialRecord toRecord(WebauthnCredentialRow row) {
        if (row == null) {
            return null;
        }
        return ImmutableCredentialRecord.builder()
            .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
            .credentialId(new Bytes(row.getCredentialId()))
            .userEntityUserId(Bytes.fromBase64(row.getUserEntityUserId()))
            .publicKey(new ImmutablePublicKeyCose(row.getPublicKey()))
            .signatureCount(row.getSignatureCount())
            .uvInitialized(row.isUvInitialized())
            .backupEligible(row.isBackupEligible())
            .backupState(row.isBackupState())
            .transports(parseTransports(row.getTransports()))
            .attestationObject(row.getAttestationObject() == null ? null : new Bytes(row.getAttestationObject()))
            .attestationClientDataJSON(row.getAttestationClientDataJson() == null ? null : new Bytes(row.getAttestationClientDataJson()))
            .label(row.getLabel())
            .created(row.getCreatedAt())
            .lastUsed(row.getLastUsedAt())
            .build();
    }

    private String serializeTransports(Set<AuthenticatorTransport> transports) {
        if (transports == null || transports.isEmpty()) {
            return null;
        }
        return transports.stream().map(AuthenticatorTransport::getValue).collect(Collectors.joining(","));
    }

    private Set<AuthenticatorTransport> parseTransports(String transports) {
        Set<AuthenticatorTransport> result = new LinkedHashSet<>();
        if (transports == null || transports.isBlank()) {
            return result;
        }
        for (String value : transports.split(",")) {
            String trimmed = value.trim();
            for (AuthenticatorTransport transport : AuthenticatorTransport.values()) {
                if (transport.getValue().equals(trimmed)) {
                    result.add(transport);
                    break;
                }
            }
        }
        return result;
    }
}
