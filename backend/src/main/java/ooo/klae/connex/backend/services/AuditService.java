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
import ooo.klae.connex.backend.dto.AuditSupportRowDto;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.ClientIpResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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

    private static final Set<String> SECRET_PURPOSES = Set.of(
            "workspace.smtp.password",
            "workspace.delivery.provider_credential",
            "workspace.delivery.webhook_secret",
            "workspace.connector.credential",
            "org.sso.oidc_client_secret",
            "org.sso.saml_sp_private_key",
            "org.ai.provider_credential",
            "user.provider.google_token",
            "user.provider.microsoft_token");
    private static final Set<String> SECRET_ACTIONS = Set.of(
            "secret_store.secret.use",
            "secret_store.secret.use_failed",
            "secret_store.secret.rewrap",
            "secret_store.secret.rewrap_failed",
            "secret_store.diagnostics.read");
    private static final Set<String> SECRET_ENTITY_TYPES = Set.of("organization", "workspace", "user");
    private static final Set<String> AI_PROVIDERS = Set.of(
            "azure_openai", "bedrock", "openai_compatible", "vertex", "unresolved");
    private static final Set<String> AI_FEATURES = Set.of(
            "deal.brief", "deal.risk_rationale", "intro.rationale", "report.narrative",
            "business_card.scan");
    private static final Set<String> AI_OUTCOMES = Set.of("attempt", "success", "failure", "blocked");
    private static final Set<String> AI_REASONS = Set.of(
            "gate", "media_admission", "provider", "provider_capability", "serialization", "leak",
            "provider_exception");
    private static final Set<String> AI_PARSE_OUTCOMES = Set.of("parsed", "truncated", "malformed_output");
    private static final Set<String> AI_STOP_REASONS = Set.of(
            "stop", "length", "content_filter", "tool_calls", "function_call", "end_turn",
            "max_tokens", "stop_sequence", "tool_use", "pause_turn", "refusal", "safety",
            "recitation", "blocklist", "prohibited_content", "spii", "malformed_function_call",
            "language", "other");
    private static final Set<String> AI_MEDIA_TYPES = Set.of("image/jpeg");
    private static final Set<String> AI_MODEL_MARKERS = Set.of(
            "anthropic", "claude", "gemini", "gemma", "gpt", "model", "o1", "o3", "o4", "llama",
            "mistral", "mixtral", "deepseek", "qwen", "command", "cohere", "nova", "titan");
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@-]{0,127}");
    private static final Pattern SAFE_REGION = Pattern.compile(
            "(?:global|unresolved|[a-z]{2}(?:-gov)?-[a-z]+-[0-9]|[a-z]+-[a-z]+[0-9]|"
                    + "(?:east|west|north|south|central)[a-z]*[0-9]?|"
                    + "[a-z]+(?:east|west|north|south|central)[0-9]?)");
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
    private static final Pattern SAFE_ERROR_TOKEN = Pattern.compile(
            "[A-Za-z][A-Za-z0-9.$_]{0,119}(?:Exception|Error)");

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
     * Records a successful audit event in its own transaction with explicit scope and propagates
     * persistence failures.
     * @param action action name
     * @param entityType audited entity type
     * @param entityId audited entity id
     * @param workspaceId explicit workspace scope, or null
     * @param orgId explicit organization scope, or null
     * @param targetLabel target descriptor
     * @param summary summary text
     * @param changes sanitized metadata
     */
    public void recordStrictIndependentScoped(String action, String entityType, Integer entityId,
            Integer workspaceId, Integer orgId, String targetLabel, String summary, Object changes) {
        writeUnchecked(action, entityType, entityId, targetLabel, OUTCOME_SUCCESS, summary, changes,
                null, true, true, workspaceId, orgId);
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
    /**
     * Returns the per-request identifier recorded on audit rows.
     *
     * <p>This is deliberately server-minted rather than the inbound {@code X-Correlation-Id}. That
     * header is client-settable and is kept as-is for log and response correlation, so adopting it
     * here would let any authenticated caller make unrelated requests share one identifier, or
     * inject rows into an investigator's filtered slice. The audit identifier must not be
     * attacker-influenced.
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
        SensitiveAuditFamily family = sensitiveAuditFamily(entry);
        if (family != SensitiveAuditFamily.NONE) {
            redactSensitiveAuditEntry(entry, family);
            return;
        }
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

    private void redactSensitiveAuditEntry(AuditLog entry, SensitiveAuditFamily family) {
        String action = sensitiveAction(entry.getAction(), family);
        Map<String, Object> metadata = projectSensitiveMetadata(entry.getChanges(), family);
        String outcome = sensitiveOutcome(entry, family, metadata);
        entry.setAction(action);
        entry.setEntityType(sensitiveEntityType(entry.getEntityType(), family));
        entry.setTargetLabel(sensitiveTarget(entry.getTargetLabel(), family, metadata));
        entry.setOutcome(outcome);
        entry.setSummary(sensitiveSummary(action, family, outcome));
        entry.setChanges(toProjectedJson(metadata));
        entry.setContext(projectSensitiveContext(entry.getContext()));
        entry.setContentRedacted(true);
    }

    private Map<String, Object> projectSensitiveMetadata(String json, SensitiveAuditFamily family) {
        Map<String, Object> projected = new LinkedHashMap<>();
        Object parsed = readAuditJson(json);
        if (!(parsed instanceof Map<?, ?> source)) {
            return projected;
        }
        if (family == SensitiveAuditFamily.SECRET) {
            copyNumber(source, projected, "secretId");
            copyKnownString(source, projected, "purpose", SECRET_PURPOSES);
            copyIdentifier(source, projected, "keyId");
            copyIdentifier(source, projected, "previousKeyId");
            copyIdentifier(source, projected, "newKeyId");
            copyBoolean(source, projected, "rewrapped");
            copyBoolean(source, projected, "healthy");
            copyBoolean(source, projected, "available");
            copyNumber(source, projected, "totalSecrets");
            copyNumber(source, projected, "staleSecrets");
            copyNumber(source, projected, "missingKeySecrets");
            copyNumber(source, projected, "disabledKeySecrets");
            copyNumber(source, projected, "mismatchedSecrets");
            copyNumber(source, projected, "unsupportedAlgorithmSecrets");
            return projected;
        }
        copyKnownString(source, projected, "provider", AI_PROVIDERS);
        copyRegion(source, projected, "region");
        copyModel(source, projected, "model");
        copyKnownString(source, projected, "feature", AI_FEATURES);
        copyKnownString(source, projected, "outcome", AI_OUTCOMES);
        copyCorrelationId(source, projected, "correlationId");
        copyNumber(source, projected, "messageCount");
        copyNumber(source, projected, "mediaCount");
        copyNumber(source, projected, "mediaBytes");
        copyKnownStringList(source, projected, "mediaTypes", AI_MEDIA_TYPES);
        copyBoolean(source, projected, "structured");
        copyNumber(source, projected, "inputTokens");
        copyNumber(source, projected, "outputTokens");
        copyKnownString(source, projected, "stopReason", AI_STOP_REASONS);
        copyNumber(source, projected, "demaskWarnings");
        copyKnownString(source, projected, "parseOutcome", AI_PARSE_OUTCOMES);
        copyKnownString(source, projected, "reason", AI_REASONS);
        return projected;
    }

    private Object readAuditJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private String toProjectedJson(Map<String, Object> metadata) {
        if (metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            log.warn("Failed to serialize projected audit metadata", exception);
            return null;
        }
    }

    private String projectSensitiveContext(String json) {
        Object parsed = readAuditJson(json);
        if (!(parsed instanceof Map<?, ?> context)) {
            return null;
        }
        Object error = metadataValue(context.get("error"));
        if (!(error instanceof String string) || !SAFE_ERROR_TOKEN.matcher(string).matches()) {
            return null;
        }
        return toProjectedJson(Map.of("error", string));
    }

    private static SensitiveAuditFamily sensitiveAuditFamily(AuditLog entry) {
        if (entry.getAction() != null && entry.getAction().startsWith("secret_store.")) {
            return SensitiveAuditFamily.SECRET;
        }
        if ("ai_call".equals(entry.getEntityType()) || "ai.llm.call".equals(entry.getAction())) {
            return SensitiveAuditFamily.AI;
        }
        return SensitiveAuditFamily.NONE;
    }

    private static String sensitiveOutcome(
            AuditLog entry, SensitiveAuditFamily family, Map<String, Object> metadata) {
        if (family == SensitiveAuditFamily.AI && metadata.get("outcome") instanceof String outcome) {
            return outcome;
        }
        Set<String> allowed = family == SensitiveAuditFamily.AI
                ? AI_OUTCOMES
                : Set.of(OUTCOME_SUCCESS, OUTCOME_FAILURE);
        String outcome = entry.getOutcome();
        return outcome != null && allowed.contains(outcome) ? outcome : null;
    }

    private static String sensitiveAction(String action, SensitiveAuditFamily family) {
        if (family == SensitiveAuditFamily.AI) {
            return "ai.llm.call";
        }
        return SECRET_ACTIONS.contains(action) ? action : "secret_store.operation";
    }

    private static String sensitiveEntityType(String entityType, SensitiveAuditFamily family) {
        if (family == SensitiveAuditFamily.AI) {
            return "ai_call";
        }
        return entityType != null && SECRET_ENTITY_TYPES.contains(entityType) ? entityType : null;
    }

    private static String sensitiveTarget(
            String targetLabel, SensitiveAuditFamily family, Map<String, Object> metadata) {
        if (family == SensitiveAuditFamily.SECRET) {
            if (metadata.get("purpose") instanceof String purpose) {
                return purpose;
            }
            return targetLabel != null && SECRET_PURPOSES.contains(targetLabel) ? targetLabel : "secret_store";
        }
        String provider = metadata.get("provider") instanceof String value ? value : "ai_call";
        return metadata.get("region") instanceof String region ? provider + "/" + region : provider;
    }

    private static String sensitiveSummary(String action, SensitiveAuditFamily family, String outcome) {
        if (family == SensitiveAuditFamily.AI) {
            return outcome == null ? "AI call" : "AI call " + outcome;
        }
        return switch (action == null ? "" : action) {
            case "secret_store.secret.use" -> "Secret used";
            case "secret_store.secret.use_failed" -> "Secret use failed";
            case "secret_store.secret.rewrap" -> "Secret rewrapped";
            case "secret_store.secret.rewrap_failed" -> "Secret rewrap failed";
            case "secret_store.diagnostics.read" -> "Secret store diagnostics read";
            default -> "Secret store operation";
        };
    }

    private static void copyKnownString(
            Map<?, ?> source, Map<String, Object> target, String key, Set<String> allowed) {
        Object value = metadataValue(source.get(key));
        if (value instanceof String string && allowed.contains(string)) {
            target.put(key, string);
        }
    }

    private static void copyIdentifier(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = metadataValue(source.get(key));
        if (value instanceof String string
                && SAFE_IDENTIFIER.matcher(string).matches()
                && !looksCredentialShaped(string)) {
            target.put(key, string);
        }
    }

    private static void copyRegion(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = metadataValue(source.get(key));
        if (value instanceof String string && SAFE_REGION.matcher(string).matches()) {
            target.put(key, string);
        }
    }

    private static void copyModel(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = metadataValue(source.get(key));
        if (!(value instanceof String string)
                || !SAFE_IDENTIFIER.matcher(string).matches()
                || looksCredentialShaped(string)) {
            return;
        }
        String normalized = string.toLowerCase(Locale.ROOT);
        if (AI_MODEL_MARKERS.stream().anyMatch(normalized::contains)) {
            target.put(key, string);
        }
    }

    private static void copyCorrelationId(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = metadataValue(source.get(key));
        if (value instanceof String string && SAFE_CORRELATION_ID.matcher(string).matches()) {
            target.put(key, string);
        }
    }

    private static void copyNumber(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = metadataValue(source.get(key));
        if ((value instanceof Integer integer && integer >= 0)
                || (value instanceof Long longValue && longValue >= 0)) {
            target.put(key, value);
        }
    }

    private static void copyBoolean(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = metadataValue(source.get(key));
        if (value instanceof Boolean) {
            target.put(key, value);
        }
    }

    private static void copyKnownStringList(
            Map<?, ?> source, Map<String, Object> target, String key, Set<String> allowed) {
        Object value = metadataValue(source.get(key));
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 16) {
            return;
        }
        List<String> tokens = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String string) || !allowed.contains(string)) {
                return;
            }
            tokens.add(string);
        }
        target.put(key, tokens);
    }

    private static boolean looksCredentialShaped(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.startsWith("sk-")
                || normalized.startsWith("akia")
                || normalized.startsWith("aiza")
                || normalized.startsWith("ghp_")
                || normalized.startsWith("xoxb-")
                || normalized.startsWith("eyj")
                || normalized.contains("raw-secret")
                || normalized.contains("secret-token")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("api-key")
                || normalized.contains("bearer");
    }

    private static Object metadataValue(Object value) {
        if (!(value instanceof Map<?, ?> change)) {
            return value;
        }
        if (change.containsKey("new")) {
            return change.get("new");
        }
        return change.get("old");
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

    /**
     * Returns the organization-plane audit slice a support bundle may carry.
     *
     * @param orgId     the organization to read
     * @param since     the inclusive window start
     * @param until     the inclusive window end
     * @param requestId the request identifier to match, or null for the whole window
     * @param limit     the maximum number of rows to disclose
     * @return the support slice
     */
    public AuditSlice supportSliceForOrg(int orgId, Instant since, Instant until, String requestId, int limit) {
        return toSupportSlice(
            auditLogMapper.findOrgSupportSlice(orgId, since, until, requestId, limit + 1), limit);
    }

    /**
     * Returns the workspace record-event slice for one entity.
     *
     * <p>The caller is responsible for proving the workspace belongs to the requested organization
     * and that the actor holds {@code AUDIT_READ} in it; this method does not widen that gate.
     *
     * @param workspaceId the workspace the entity belongs to
     * @param orgId       the organization the workspace belongs to
     * @param entityType  the record type
     * @param entityId    the record id
     * @param since       the inclusive window start
     * @param until       the inclusive window end
     * @param requestId   the request identifier to match, or null for the whole window
     * @param limit       the maximum number of rows to disclose
     * @return the support slice
     */
    public AuditSlice supportSliceForEntity(int workspaceId, int orgId, String entityType,
            int entityId, Instant since, Instant until, String requestId, int limit) {
        return toSupportSlice(auditLogMapper.findEntitySupportSlice(
                workspaceId, orgId, entityType, entityId, since, until, requestId, limit + 1),
            limit);
    }

    /**
     * Formats the projected rows as CSV and reports whether the window was truncated.
     *
     * <p>The query asks for one row more than it may disclose, so a saturated slice is detectable
     * rather than silently indistinguishable from a complete one. The extra row is never emitted.
     */
    private AuditSlice toSupportSlice(List<AuditSupportRowDto> rows, int limit) {
        boolean truncated = rows.size() > limit;
        List<AuditSupportRowDto> disclosed = truncated ? rows.subList(0, limit) : rows;
        StringBuilder sb = new StringBuilder();
        writeCsvRow(sb, List.of("auditId", "scope", "workspaceId", "orgId", "action", "entityType",
                "entityId", "actorId", "outcome", "requestId", "createdAt", "contentFieldsOmitted"));
        for (AuditSupportRowDto row : disclosed) {
            writeCsvRow(sb, List.of(
                    csvCell(row.auditId()),
                    csvCell(row.workspaceId() == null ? "organization" : "workspace"),
                    csvCell(row.workspaceId()),
                    csvCell(row.orgId()),
                    csvCell(row.action()),
                    csvCell(row.entityType()),
                    csvCell(row.entityId()),
                    csvCell(row.actorId()),
                    csvCell(row.outcome()),
                    csvCell(row.requestId()),
                    csvCell(row.createdAt()),
                    csvCell(true)));
        }
        return new AuditSlice(sb.toString(), disclosed.size(), truncated);
    }

    /**
     * A support bundle audit slice.
     *
     * @param csv       the rendered CSV
     * @param rowCount  the number of rows disclosed
     * @param truncated whether more rows matched than were disclosed
     */
    public record AuditSlice(String csv, int rowCount, boolean truncated) {
    }

    private String toCsv(List<AuditLog> entries) {
        StringBuilder sb = new StringBuilder();
        writeCsvRow(sb, List.of("id", "workspaceId", "orgId", "action", "entityType", "entityId",
                "actorId", "actorLabel", "currentActorLabel", "targetLabel", "outcome", "summary",
                "changes", "context", "ipAddress", "userAgent", "sessionId", "requestId",
                "chainScopeType", "chainScopeId", "chainIndex", "prevHash", "rowHash", "createdAt",
                "contentRedacted", "integrityPayloadRedacted", "integrityPayload"));
        for (AuditLog entry : entries) {
            boolean sensitive = sensitiveAuditFamily(entry) != SensitiveAuditFamily.NONE;
            SanitizedJson integrityPayload = sensitive
                    ? new SanitizedJson(null, true)
                    : sanitizeAuditJson(auditIntegrityService.integrityPayload(entry));
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
                    csvCell(entry.isContentRedacted()),
                    csvCell(integrityPayload.changed()),
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

    private enum SensitiveAuditFamily {
        NONE,
        SECRET,
        AI
    }
}
