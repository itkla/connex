package ooo.klae.connex.backend.services;

import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.AuditIntegrityHead;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.config.AuditIntegrityProperties;
import ooo.klae.connex.backend.mappers.AuditIntegrityMapper;
import ooo.klae.connex.backend.mappers.AuditLogMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Appends audit rows with per-scope HMAC hash-chain metadata.
 */
@Service
@RequiredArgsConstructor
public class AuditIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityService.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final DateTimeFormatter CREATED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AuditLogMapper auditLogMapper;
    private final AuditIntegrityMapper auditIntegrityMapper;
    private final AuditIntegrityProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Appends an audit row to its integrity scope.
     */
    @Transactional(propagation = Propagation.NESTED)
    public void append(AuditLog entry) {
        appendChained(entry);
    }

    /**
     * Appends an audit row in an independent transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendIndependent(AuditLog entry) {
        appendChained(entry);
    }

    private void appendChained(AuditLog entry) {
        AuditScope scope = scopeFor(entry);
        auditIntegrityMapper.ensureHead(scope.type(), scope.id(), GENESIS_HASH);
        AuditIntegrityHead head = auditIntegrityMapper.lockHead(scope.type(), scope.id());
        if (head == null) {
            throw new IllegalStateException("Audit integrity head was not initialized");
        }

        long chainIndex = head.getNextChainIndex();
        entry.setChainScopeType(scope.type());
        entry.setChainScopeId(scope.id());
        entry.setChainIndex(chainIndex);
        entry.setPrevHash(head.getCurrentHash());
        entry.setCreatedAt(now());
        entry.setRowHash(hmacHex(canonicalPayload(entry)));

        auditLogMapper.insert(entry);
        int updated = auditIntegrityMapper.advanceHead(scope.type(), scope.id(), chainIndex, chainIndex + 1, entry.getRowHash());
        if (updated != 1) {
            throw new IllegalStateException("Audit integrity head update failed");
        }
        log.info("AUDIT_INTEGRITY_CHECKPOINT scopeType={} scopeId={} chainIndex={} auditLogId={} prevHash={} rowHash={}",
                scope.type(), scope.id(), chainIndex, entry.getId(), entry.getPrevHash(), entry.getRowHash());
    }

    /**
     * Builds the exact canonical payload used as the input to the row HMAC.
     * @param entry the audit event row
     * @return the canonical integrity payload
     */
    public String integrityPayload(AuditLog entry) {
        return canonicalPayload(entry);
    }

    private AuditScope scopeFor(AuditLog entry) {
        if (entry.getWorkspaceId() != null) {
            return new AuditScope("workspace", entry.getWorkspaceId());
        }
        if (entry.getOrgId() != null) {
            return new AuditScope("organization", entry.getOrgId());
        }
        return new AuditScope("system", 0);
    }

    private String now() {
        return CREATED_AT_FORMATTER.format(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }

    private String canonicalPayload(AuditLog entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chainScopeType", entry.getChainScopeType());
        payload.put("chainScopeId", entry.getChainScopeId());
        payload.put("chainIndex", entry.getChainIndex());
        payload.put("prevHash", entry.getPrevHash());
        payload.put("workspaceId", entry.getWorkspaceId());
        payload.put("orgId", entry.getOrgId());
        payload.put("action", entry.getAction());
        payload.put("entityType", entry.getEntityType());
        payload.put("entityId", entry.getEntityId());
        payload.put("actorId", entry.getActorId());
        payload.put("actorLabel", entry.getActorLabel());
        payload.put("targetLabel", entry.getTargetLabel());
        payload.put("outcome", entry.getOutcome());
        payload.put("summary", entry.getSummary());
        payload.put("changes", canonicalJson(entry.getChanges()));
        payload.put("context", canonicalJson(entry.getContext()));
        payload.put("ipAddress", entry.getIpAddress());
        payload.put("userAgent", entry.getUserAgent());
        payload.put("sessionId", entry.getSessionId());
        payload.put("requestId", entry.getRequestId());
        payload.put("createdAt", entry.getCreatedAt());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalize audit row", e);
        }
    }

    private Object canonicalJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return canonicalJsonValue(objectMapper.readValue(json, Object.class));
        } catch (Exception e) {
            return json;
        }
    }

    private Object canonicalJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonicalJsonValue(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalJsonValue).toList();
        }
        return value;
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.hmacSecretBytes(), HMAC_ALGORITHM));
            return hex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash audit row", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private record AuditScope(String type, int id) {
    }
}
