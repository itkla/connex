package ooo.klae.connex.backend.services;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.AuditLogMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private static final String REQUEST_ID_ATTR = "connexAuditRequestId";

    private static final int ACTION_MAX = 48;
    private static final int ENTITY_TYPE_MAX = 32;
    private static final int LABEL_MAX = 255;
    private static final int SUMMARY_MAX = 255;
    private static final int USER_AGENT_MAX = 512;
    private static final int IP_MAX = 45;
    private static final int HASH_LEN = 64;

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * Records a single successful audit event. Never throws.
     * 
     * @param action      dotted action name, e.g. {@code company.update},
     *                    {@code auth.login}
     * @param entityType  target entity type, e.g. {@code company} (null for
     *                    non-entity events)
     * @param entityId    target entity id (nullable)
     * @param targetLabel human-readable target descriptor, snapshotted at event
     *                    time
     * @param summary     human-readable one-liner
     * @param changes     field diff (see {@link #diff}) or any object/JSON string;
     *                    may be null
     */
    public void record(String action, String entityType, Integer entityId,
            String targetLabel, String summary, Object changes) {
        try {
            AuditLog entry = new AuditLog();
            entry.setAction(truncate(action, ACTION_MAX));
            entry.setEntityType(truncate(entityType, ENTITY_TYPE_MAX));
            entry.setEntityId(entityId);
            entry.setTargetLabel(truncate(targetLabel, LABEL_MAX));
            entry.setOutcome("success");
            entry.setSummary(truncate(summary, SUMMARY_MAX));
            entry.setChanges(toJson(changes));

            resolveActor(entry);
            resolveRequest(entry);

            auditLogMapper.insert(entry);
        } catch (Exception e) {
            // Auditing must never break the operation it is observing.
            log.error("Failed to record audit event action={} entityType={} entityId={}",
                    action, entityType, entityId, e);
        }
    }

    /**
     * Builds a field-level diff over an explicit allowlist of bean field names.
     * @param before
     * @param after
     * @param allowedFields
     * @return
     */
    public Map<String, Object> diff(Object before, Object after, Set<String> allowedFields) {
        Map<String, Object> beforeMap = toMap(before);
        Map<String, Object> afterMap = toMap(after);
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String field : allowedFields) {
            Object oldVal = beforeMap.get(field);
            Object newVal = afterMap.get(field);
            if (!Objects.equals(oldVal, newVal)) {
                Map<String, Object> delta = new LinkedHashMap<>();
                delta.put("old", oldVal);
                delta.put("new", newVal);
                changes.put(field, delta);
            }
        }
        return changes.isEmpty() ? null : changes;
    }

    /**
     * Retrieves the most recent events across all entities, newest first.
     * @param limit
     * @return
     */
    public List<AuditLog> recent(int limit) {
        return auditLogMapper.findRecent(cap(limit));
    }

    /**
     * Retrieves events for a single target, newest first.
     * @param entityType
     * @param entityId
     * @param limit
     * @return
     */
    public List<AuditLog> forEntity(String entityType, int entityId, int limit) {
        return auditLogMapper.findByEntity(entityType, entityId, cap(limit));
    }

    /**
     * Resolves the actor details.
     * @param entry
     */
    private void resolveActor(AuditLog entry) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof User user) {
            entry.setActorId(user.getId());
            entry.setActorLabel(truncate(user.getDisplayName(), LABEL_MAX));
        }
    }

    /**
     * Resolves the request details.
     * @param entry
     */
    private void resolveRequest(AuditLog entry) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return;
        }
        HttpServletRequest req = attrs.getRequest();
        entry.setIpAddress(clientIp(req));
        entry.setUserAgent(truncate(req.getHeader("User-Agent"), USER_AGENT_MAX));
        entry.setSessionId(sessionHash(req));
        entry.setRequestId(requestId(attrs));
    }

    /**
     * Retrieves the client IP address.
     * @param req
     * @return
     */
    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return truncate(xff.split(",")[0].trim(), IP_MAX);
        }
        return truncate(req.getRemoteAddr(), IP_MAX);
    }

    /**
     * Generates a session hash.
     * @param req
     * @return
     */
    private String sessionHash(HttpServletRequest req) {
        var session = req.getSession(false);
        if (session == null)
            return null;
        return sha256Hex(session.getId()); // store a hash, never the raw session id
    }

    /**
     * Generates a request ID.
     * @param attrs
     * @return
     */
    private String requestId(ServletRequestAttributes attrs) {
        Object existing = attrs.getAttribute(REQUEST_ID_ATTR, RequestAttributes.SCOPE_REQUEST);
        if (existing instanceof String s)
            return s;
        String id = UUID.randomUUID().toString();
        attrs.setAttribute(REQUEST_ID_ATTR, id, RequestAttributes.SCOPE_REQUEST);
        return id;
    }

    /**
     * Converts an object to a JSON string.
     * @param value
     * @return
     */
    private String toJson(Object value) {
        if (value == null)
            return null;
        if (value instanceof String s)
            return s;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize audit changes", e);
            return null;
        }
    }

    /**
     * Converts a bean to a map.
     * @param bean
     * @return
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object bean) {
        if (bean == null)
            return Map.of();
        try {
            return objectMapper.convertValue(bean, Map.class);
        } catch (Exception e) {
            log.warn("Failed to read audit fields from {}", bean.getClass().getSimpleName(), e);
            return Map.of();
        }
    }

    /**
     * Caps a limit to a minimum of 1 and a maximum of 200.
     * @param limit
     * @return
     */
    private static int cap(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    /**
     * Truncates a string to a maximum length.
     * @param s
     * @param max
     * @return
     */
    private static String truncate(String s, int max) {
        if (s == null)
            return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Hashes an input string using SHA-256.
     * @param input
     * @return
     */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, HASH_LEN);
        } catch (Exception e) {
            return null;
        }
    }
}