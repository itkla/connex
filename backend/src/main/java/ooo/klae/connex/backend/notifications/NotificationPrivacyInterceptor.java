package ooo.klae.connex.backend.notifications;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWorkspacePrivacy;

/** Binds scoped authority before filtering and revalidates materialized results under publication fences. */
@Intercepts({
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
    @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
    @Signature(type = Executor.class, method = "queryCursor",
            args = {MappedStatement.class, Object.class, RowBounds.class})
})
public final class NotificationPrivacyInterceptor implements Interceptor {
    private static final String NAMESPACE = "ooo.klae.connex.backend.mappers.NotificationMapper.";
    private static final Set<String> STATEMENTS = Set.of(
            "findPage", "countPage", "countsByCategory", "countsBySeverity", "countsByWorkspace",
            "getUnreadCounts", "getNextSnoozeExpiry", "getInboxCutoffId", "findById", "findByIdForUpdate",
            "markRead", "markUnread", "dismiss", "restore", "restoreActionableApprovalRequest",
            "restoreResolvedApprovalRequest", "snooze", "unsnooze", "markAllRead",
            "findInaccessibleNotificationRecipientIds", "resolveInaccessibleNotifications");
    private final BiFunction<Integer, Integer, List<AiAssistantWorkspacePrivacy>> authority;

    /** Defers control mapper resolution until after SqlSessionFactory construction. */
    public NotificationPrivacyInterceptor(BiFunction<Integer, Integer, List<AiAssistantWorkspacePrivacy>> authority) {
        this.authority = java.util.Objects.requireNonNull(authority);
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] arguments = invocation.getArgs();
        MappedStatement statement = (MappedStatement) arguments[0];
        String id = statement.getId();
        if (!id.startsWith(NAMESPACE) || !STATEMENTS.contains(id.substring(NAMESPACE.length()))) {
            return invocation.proceed();
        }
        if (!(arguments[1] instanceof Map<?, ?> source)) {
            throw new IllegalStateException("Notification privacy parameters are unavailable");
        }
        Map<String, Object> parameters = new HashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException("Notification privacy parameter identity is invalid");
            }
            parameters.put(key, entry.getValue());
        }
        if (invocation.getMethod().getName().equals("queryCursor")
                || arguments.length >= 4 && arguments[3] != null) {
            throw new IllegalStateException("Notification privacy requires materialized results");
        }
        Integer recipientId = identity(parameters, "recipientId");
        Integer workspaceId = identity(parameters, "workspaceId");
        List<AiAssistantWorkspacePrivacy> captured = authority.apply(recipientId, workspaceId);
        Set<Integer> organizations = captured.stream().map(AiAssistantWorkspacePrivacy::organizationId)
                .collect(Collectors.toSet());
        parameters.put("assistantWorkspaceGenerations", captured.stream().collect(Collectors.toUnmodifiableMap(
                AiAssistantWorkspacePrivacy::workspaceId, AiAssistantWorkspacePrivacy::generation)));
        arguments[1] = parameters;
        if (arguments.length == 6) {
            BoundSql bound = statement.getBoundSql(parameters);
            arguments[5] = bound;
            arguments[4] = ((Executor) invocation.getTarget()).createCacheKey(
                    statement, parameters, (RowBounds) arguments[2], bound);
        }
        Object result = invocation.proceed();
        try (var publication = AiAssistantPrivacyPublicationGuard.retainPublicationReads(organizations)) {
            if (!authority.apply(recipientId, workspaceId).containsAll(captured)) {
                throw new ooo.klae.connex.backend.exceptions.ConflictException("assistant_privacy_unavailable");
            }
            return result;
        }
    }

    private static Integer identity(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Integer id) || id <= 0) {
            throw new IllegalStateException("Notification privacy scope identity is invalid");
        }
        return id;
    }
}
