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
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.ClientIpResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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
    private static final int ERROR_MAX = 500;
    private static final int MAX_OFFSET = 100_000;
    private static final int EXPORT_MAX_LIMIT = 10_000;

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILURE = "failure";

    private final AuditLogMapper auditLogMapper;
    private final AuditIntegrityService auditIntegrityService;
    private final ObjectMapper objectMapper;
    private final TenantContext tenantContext;
    private final ClientIpResolver clientIpResolver;

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
        write(action, entityType, entityId, targetLabel, OUTCOME_SUCCESS, summary, changes, null, false,
                false, null, null);
    }

    /**
     * Records a single successful audit event and propagates any persistence failure, for
     * operations that must not proceed without a durable access record (e.g. bulk personal-data
     * disclosure). Call inside the operation's transaction so a failed append aborts it.
     *
     * @param action      dotted action name
     * @param entityType  target entity type (null for non-entity events)
     * @param entityId    target entity id (nullable)
     * @param targetLabel human-readable target descriptor
     * @param summary     human-readable one-liner
     * @param changes     field diff or metadata object; may be null
     */
    public void recordStrict(String action, String entityType, Integer entityId,
            String targetLabel, String summary, Object changes) {
        writeUnchecked(action, entityType, entityId, targetLabel, OUTCOME_SUCCESS, summary, changes,
                null, false, false, null, null);
    }

    /**
     * Records a successful audit event with explicit workspace/org scope.
     * @param action action name
     * @param entityType audited entity type
     * @param entityId audited entity id
     * @param workspaceId explicit workspace scope, or null
     * @param orgId explicit organization scope, or null
     * @param targetLabel target descriptor
     * @param summary summary text
     * @param changes sanitized metadata
     */
    public void recordScoped(String action, String entityType, Integer entityId,
            Integer workspaceId, Integer orgId, String targetLabel, String summary, Object changes) {
        write(action, entityType, entityId, targetLabel, OUTCOME_SUCCESS, summary, changes, null, false,
                true, workspaceId, orgId);
    }

    /**
     * Records a successful audit event in its own transaction with explicit scope.
     * @param action action name
     * @param entityType audited entity type
     * @param entityId audited entity id
     * @param workspaceId explicit workspace scope, or null
     * @param orgId explicit organization scope, or null
     * @param targetLabel target descriptor
     * @param summary summary text
     * @param changes sanitized metadata
     */
    public void recordIndependentScoped(String action, String entityType, Integer entityId,
            Integer workspaceId, Integer orgId, String targetLabel, String summary, Object changes) {
        write(action, entityType, entityId, targetLabel, OUTCOME_SUCCESS, summary, changes, null, true,
                true, workspaceId, orgId);
    }

    /**
     * Records a single failed audit event. Never throws.
     * @param action
     * @param entityType
     * @param entityId
     * @param targetLabel
     * @param summary
     * @param errorMessage
     */
    public void recordFailure(String action, String entityType, Integer entityId,
            String targetLabel, String summary, String errorMessage) {
        Object context = errorMessage == null ? null
                : Map.of("error", truncate(errorMessage, ERROR_MAX));
        write(action, entityType, entityId, targetLabel, OUTCOME_FAILURE, summary, null, context, true,
                false, null, null);
    }

    /**
     * Records a failed audit event with explicit workspace/org scope.
     * @param action action name
     * @param entityType audited entity type
     * @param entityId audited entity id
     * @param workspaceId explicit workspace scope, or null
     * @param orgId explicit organization scope, or null
     * @param targetLabel target descriptor
     * @param summary summary text
     * @param errorMessage sanitized error class or reason
     */
    public void recordFailureScoped(String action, String entityType, Integer entityId,
            Integer workspaceId, Integer orgId, String targetLabel, String summary, String errorMessage) {
        Object context = errorMessage == null ? null
                : Map.of("error", truncate(errorMessage, ERROR_MAX));
        write(action, entityType, entityId, targetLabel, OUTCOME_FAILURE, summary, null, context, true,
                true, workspaceId, orgId);
    }

    /**
     * Writes an audit event to the database.
     * @param action
     * @param entityType
     * @param entityId
     * @param targetLabel
     * @param outcome
     * @param summary
     * @param changes
     * @param context
     */
    private void write(String action, String entityType, Integer entityId, String targetLabel,
            String outcome, String summary, Object changes, Object context, boolean independent,
            boolean explicitScope, Integer workspaceId, Integer orgId) {
        try {
            writeUnchecked(action, entityType, entityId, targetLabel, outcome, summary, changes,
                    context, independent, explicitScope, workspaceId, orgId);
        } catch (Exception e) {
            log.error("Failed to record audit event action={} entityType={} entityId={}",
                    action, entityType, entityId, e);
        }
    }

    /**
     * Writes an audit event to the database, propagating any failure to the caller.
     */
    private void writeUnchecked(String action, String entityType, Integer entityId, String targetLabel,
            String outcome, String summary, Object changes, Object context, boolean independent,
            boolean explicitScope, Integer workspaceId, Integer orgId) {
        AuditLog entry = new AuditLog();
        entry.setAction(truncate(action, ACTION_MAX));
        entry.setEntityType(truncate(entityType, ENTITY_TYPE_MAX));
        entry.setEntityId(entityId);
        entry.setTargetLabel(truncate(sanitizeAuditText(targetLabel), LABEL_MAX));
        entry.setOutcome(outcome);
        entry.setSummary(truncate(sanitizeAuditText(summary), SUMMARY_MAX));
        entry.setChanges(toSanitizedJson(changes));
        entry.setContext(toSanitizedJson(context));

        if (explicitScope) {
            entry.setWorkspaceId(workspaceId);
            entry.setOrgId(orgId);
        } else {
            boolean orgLevel = "organization".equals(entityType);
            entry.setWorkspaceId(orgLevel ? null : tenantContext.getWorkspaceId());
            entry.setOrgId(orgLevel ? entityId : tenantContext.getOrgId());
        }
        resolveActor(entry);
        resolveRequest(entry);

        if (independent) {
            auditIntegrityService.appendIndependent(entry);
        } else {
            auditIntegrityService.append(entry);
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
                delta.put("old", sanitizeAuditValue(oldVal).value());
                delta.put("new", sanitizeAuditValue(newVal).value());
                changes.put(field, delta);
            }
        }
        return changes.isEmpty() ? null : changes;
    }

    /**
     * Builds a single-field change payload.
     * @param field
     * @param oldVal
     * @param newVal
     * @return
     */
    public Map<String, Object> singleChange(String field, Object oldVal, Object newVal) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("old", sanitizeAuditValue(oldVal).value());
        delta.put("new", sanitizeAuditValue(newVal).value());
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put(field, delta);
        return changes;
    }

    /**
     * Retrieves the most recent events across all entities, newest first.
     * @param limit the maximum number of events to return, capped per request
     * @param offset the number of events to skip, enabling incremental paging
     * @return the page of events
     */
    public List<AuditLog> recent(int limit, int offset) {
        return redactAuditEntries(
                auditLogMapper.findRecent(tenantContext.getWorkspaceId(), cap(limit), offset(offset)));
    }

    /**
     * Retrieves events for a single target, newest first.
     * @param entityType the entity type to scope to
     * @param entityId the entity id to scope to
     * @param limit the maximum number of events to return, capped per request
     * @param offset the number of events to skip, enabling incremental paging
     * @return the page of events
     */
    public List<AuditLog> forEntity(String entityType, int entityId, int limit, int offset) {
        return redactAuditEntries(auditLogMapper.findByEntity(
                tenantContext.getWorkspaceId(), entityType, entityId, cap(limit), offset(offset)));
    }

    /**
     * The most recent events for an organization, newest first. Org-scoped (not the active
     * workspace); the caller must have gated on org membership.
     * @param orgId the organization to scope to
     * @param limit the maximum number of events to return, capped per request
     * @param offset the number of events to skip
     * @return the page of events
     */
    public List<AuditLog> recentForOrg(int orgId, int limit, int offset) {
        return redactAuditEntries(auditLogMapper.findRecentByOrg(orgId, cap(limit), offset(offset)));
    }

    /**
     * Exports recent workspace audit events as CSV.
     * @param limit the maximum number of events to export, capped per request
     * @param offset the number of events to skip
     * @return CSV text
     */
    public String exportRecent(int limit, int offset) {
        return toCsv(auditLogMapper.findWorkspaceExport(tenantContext.getWorkspaceId(), exportCap(limit), offset(offset)));
    }

    /**
     * Exports workspace audit events for a single target as CSV.
     * @param entityType the entity type to scope to
     * @param entityId the entity id to scope to
     * @param limit the maximum number of events to export, capped per request
     * @param offset the number of events to skip
     * @return CSV text
     */
    public String exportForEntity(String entityType, int entityId, int limit, int offset) {
        return toCsv(auditLogMapper.findByEntity(tenantContext.getWorkspaceId(), entityType, entityId,
                exportCap(limit), offset(offset)));
    }

    /**
     * Exports org-plane audit events as CSV.
     * @param orgId the organization to scope to
     * @param limit the maximum number of events to export, capped per request
     * @param offset the number of events to skip
     * @return CSV text
     */
    public String exportRecentForOrg(int orgId, int limit, int offset) {
        return toCsv(auditLogMapper.findOrgExport(orgId, exportCap(limit), offset(offset)));
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
        return truncate(clientIpResolver.resolve(req), IP_MAX);
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
        return sha256Hex(session.getId());
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
    private String toSanitizedJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            Object jsonValue = value instanceof String string
                    ? objectMapper.readValue(string, Object.class)
                    : value;
            return objectMapper.writeValueAsString(sanitizeAuditValue(jsonValue).value());
        } catch (Exception e) {
            if (value instanceof String string) {
                try {
                    return objectMapper.writeValueAsString(sanitizeAuditText(string));
                } catch (Exception serializationException) {
                    log.warn("Failed to serialize sanitized audit content", serializationException);
                    return null;
                }
            }
            log.warn("Failed to serialize audit content", e);
            return null;
        }
    }

    private SanitizedValue sanitizeAuditValue(Object value) {
        if (value instanceof String string) {
            String sanitized = sanitizeAuditText(string);
            return new SanitizedValue(sanitized, !Objects.equals(string, sanitized));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                SanitizedValue child = sanitizeAuditValue(entry.getValue());
                sanitized.put(String.valueOf(entry.getKey()), child.value());
                changed |= child.changed();
            }
            return new SanitizedValue(sanitized, changed);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            boolean changed = false;
            for (Object item : iterable) {
                SanitizedValue child = sanitizeAuditValue(item);
                sanitized.add(child.value());
                changed |= child.changed();
            }
            return new SanitizedValue(sanitized, changed);
        }
        if (value != null
                && !(value instanceof Number)
                && !(value instanceof Boolean)
                && !(value instanceof Character)
                && !(value instanceof Enum<?>)) {
            try {
                Object converted = objectMapper.convertValue(value, Object.class);
                if (converted != null && !converted.getClass().equals(value.getClass())) {
                    return sanitizeAuditValue(converted);
                }
            } catch (Exception exception) {
                log.warn("Failed to inspect audit content for reference tokens", exception);
            }
        }
        return new SanitizedValue(value, false);
    }

    private List<AuditLog> redactAuditEntries(List<AuditLog> entries) {
        entries.forEach(this::redactAuditEntry);
        return entries;
    }

    private void redactAuditEntry(AuditLog entry) {
        String targetLabel = sanitizeAuditText(entry.getTargetLabel());
        String summary = sanitizeAuditText(entry.getSummary());
        SanitizedJson changes = sanitizeAuditJson(entry.getChanges());
        SanitizedJson context = sanitizeAuditJson(entry.getContext());
        boolean redacted = entry.isContentRedacted()
                || !Objects.equals(entry.getTargetLabel(), targetLabel)
                || !Objects.equals(entry.getSummary(), summary)
                || changes.changed()
                || context.changed();
        entry.setTargetLabel(targetLabel);
        entry.setSummary(summary);
        entry.setChanges(changes.value());
        entry.setContext(context.value());
        entry.setContentRedacted(redacted);
    }

    private SanitizedJson sanitizeAuditJson(String json) {
        if (json == null) {
            return new SanitizedJson(null, false);
        }
        try {
            SanitizedValue sanitized = sanitizeAuditValue(objectMapper.readValue(json, Object.class));
            return sanitized.changed()
                    ? new SanitizedJson(objectMapper.writeValueAsString(sanitized.value()), true)
                    : new SanitizedJson(json, false);
        } catch (Exception exception) {
            String sanitized = sanitizeAuditText(json);
            if (Objects.equals(json, sanitized)) {
                return new SanitizedJson(json, false);
            }
            try {
                return new SanitizedJson(objectMapper.writeValueAsString(sanitized), true);
            } catch (Exception serializationException) {
                log.warn("Failed to serialize a redacted audit projection", serializationException);
                return new SanitizedJson("null", true);
            }
        }
    }

    private static String sanitizeAuditText(String value) {
        return value == null ? null : ReferenceService.toPlainText(value);
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
     * Caps an export limit to a minimum of 1 and a maximum of 10,000.
     * @param limit
     * @return
     */
    private static int exportCap(int limit) {
        return Math.max(1, Math.min(limit, EXPORT_MAX_LIMIT));
    }

    /**
     * Clamps an offset to the range [0, {@value #MAX_OFFSET}], bounding how deep a
     * paged read can scan regardless of the requested value.
     * @param offset
     * @return
     */
    private static int offset(int offset) {
        return Math.min(Math.max(0, offset), MAX_OFFSET);
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

    private String toCsv(List<AuditLog> entries) {
        StringBuilder sb = new StringBuilder();
        writeCsvRow(sb, List.of("id", "workspaceId", "orgId", "action", "entityType", "entityId",
                "actorId", "actorLabel", "currentActorLabel", "targetLabel", "outcome", "summary",
                "changes", "context", "ipAddress", "userAgent", "sessionId", "requestId",
                "chainScopeType", "chainScopeId", "chainIndex", "prevHash", "rowHash", "createdAt",
                "contentRedacted", "integrityPayloadRedacted"));
        for (AuditLog entry : entries) {
            SanitizedJson integrityPayload = sanitizeAuditJson(auditIntegrityService.integrityPayload(entry));
            redactAuditEntry(entry);
            writeCsvRow(sb, List.of(
                    csvCell(entry.getId()),
                    csvCell(entry.getWorkspaceId()),
                    csvCell(entry.getOrgId()),
                    csvCell(entry.getAction()),
                    csvCell(entry.getEntityType()),
                    csvCell(entry.getEntityId()),
                    csvCell(entry.getActorId()),
                    csvCell(entry.getActorLabel()),
                    csvCell(entry.getCurrentActorLabel()),
                    csvCell(entry.getTargetLabel()),
                    csvCell(entry.getOutcome()),
                    csvCell(entry.getSummary()),
                    csvCell(entry.getChanges()),
                    csvCell(entry.getContext()),
                    csvCell(entry.getIpAddress()),
                    csvCell(entry.getUserAgent()),
                    csvCell(entry.getSessionId()),
                    csvCell(entry.getRequestId()),
                    csvCell(entry.getChainScopeType()),
                    csvCell(entry.getChainScopeId()),
                    csvCell(entry.getChainIndex()),
                    csvCell(entry.getPrevHash()),
                    csvCell(entry.getRowHash()),
                    csvCell(entry.getCreatedAt()),
                    csvCell(entry.isContentRedacted() || integrityPayload.changed()),
                    csvCell(integrityPayload.value())));
        }
        return sb.toString();
    }

    private static String csvCell(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void writeCsvRow(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCsv(cells.get(i)));
        }
        sb.append("\r\n");
    }

    private static String escapeCsv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String s = value;
        char first = s.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            s = "'" + s;
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private record SanitizedValue(Object value, boolean changed) {
    }

    private record SanitizedJson(String value, boolean changed) {
    }
}
