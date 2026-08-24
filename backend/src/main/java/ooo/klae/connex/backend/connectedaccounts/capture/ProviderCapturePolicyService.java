package ooo.klae.connex.backend.connectedaccounts.capture;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.ProviderCaptureSyncState;
import ooo.klae.connex.backend.beans.ProviderCaptureUserPolicy;
import ooo.klae.connex.backend.beans.ProviderCaptureWorkspacePolicy;
import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.dto.ProviderCaptureOverviewDto;
import ooo.klae.connex.backend.dto.ProviderCaptureOverviewResponse;
import ooo.klae.connex.backend.dto.ProviderCaptureUserPolicyRequest;
import ooo.klae.connex.backend.dto.ProviderCaptureWorkspacePolicyRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.DuplicateDecisionLockService;
import ooo.klae.connex.backend.services.IdentityKind;
import ooo.klae.connex.backend.services.MatchingService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Workspace ceiling, user choice, health, and disclosure contract for connected capture.
 */
@Service
@RequiredArgsConstructor
public class ProviderCapturePolicyService {
    private static final int DEFAULT_BACKFILL_DAYS = 90;
    private static final int MAX_BACKFILL_DAYS = 180;

    private final ProviderCaptureMapper captureMapper;
    private final ProviderConnectionMapper connectionMapper;
    private final ConnectedAccountProviders providers;
    private final ConnectedCaptureProperties properties;
    private final WorkspaceService workspaceService;
    private final MatchingService matchingService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final TenantWorkScope tenantWorkScope;
    private final DuplicateDecisionLockService duplicateDecisionLockService;
    private final ProviderCapturePurgeService purgeService;

    /** Returns every separately authorized provider for the current workspace user. */
    public ProviderCaptureOverviewResponse getCurrentOverview() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        List<ProviderCaptureOverviewDto> overviews = new ArrayList<>();
        for (String provider : List.of(
                ConnectedAccountProviders.GOOGLE,
                ConnectedAccountProviders.MICROSOFT)) {
            ProviderConnection connection = connection(userId, provider);
            boolean retainedData = purgeService.hasResiduals(
                workspaceId, userId, provider);
            boolean lifecycleVisible = connection != null && switch (connection.getStatus()) {
                case "revoking", "disconnecting", "purge_failed", "disconnected" -> true;
                default -> false;
            };
            if ((providers.isEnabled(provider)
                    && properties.isCaptureEnabled(provider))
                    || retainedData
                    || lifecycleVisible) {
                overviews.add(overview(
                    workspaceId, userId, provider, connection, retainedData));
            }
        }
        return new ProviderCaptureOverviewResponse(overviews);
    }

    /** Returns one provider overview after validating operator authorization. */
    public ProviderCaptureOverviewDto getCurrentOverview(String provider) {
        requireAuthorizedProvider(provider);
        return overview(
            workspaceService.getCurrentWorkspaceId(),
            workspaceService.getCurrentUserId(),
            provider);
    }

    /** Saves the current user's opt-in under the workspace ceiling. */
    public ProviderCaptureOverviewDto updateUserPolicy(
            String provider, ProviderCaptureUserPolicyRequest request) {
        requireAuthorizedProvider(provider);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        ProviderConnection connection = connection(userId, provider);
        boolean[] reset = {false};
        transaction(() -> persistUserPolicy(
            provider, request, workspaceId, userId, connection, reset));
        recordStrict(
            "provider.capture.policy",
            workspaceId,
            provider,
            request.enabled()
                ? "Enabled provider capture policy"
                : "Disabled provider capture policy",
            Map.of("provider", provider));
        return overview(workspaceId, userId, provider);
    }

    private void persistUserPolicy(
            String provider,
            ProviderCaptureUserPolicyRequest request,
            int workspaceId,
            int userId,
            ProviderConnection connection,
            boolean[] reset) {
        duplicateDecisionLockService.lockCurrentOrganization();
        if (request.enabled()) {
            workspaceService.requirePermission(
                workspaceId, userId, Permission.ACTIVITY_CREATE);
        } else {
            workspaceService.requireMember(workspaceId, userId);
        }
        if (request.enabled()
                && (connection == null
                    || (!"connected".equals(connection.getStatus())
                        && !"paused".equals(connection.getStatus())))) {
            throw new BadRequestException("Connect this provider before enabling capture");
        }
        if (request.enabled()
                && !request.calendar()
                && !request.mailInbox()
                && !request.mailSent()) {
            throw new BadRequestException("Select at least one capture stream");
        }
        ProviderCaptureWorkspacePolicy workspacePolicy =
            workspacePolicy(workspaceId, provider);
        if (request.backfillDays()
                > Math.min(MAX_BACKFILL_DAYS, workspacePolicy.getMaxBackfillDays())) {
            throw new BadRequestException("Backfill window exceeds the workspace ceiling");
        }
        if (request.includeBodies() && !workspacePolicy.isBodyCaptureAllowed()) {
            throw new BadRequestException("Message bodies are disabled by workspace policy");
        }
        ProviderCaptureUserPolicy existing =
            captureMapper.getUserPolicy(workspaceId, userId, provider);
        List<String> excludedPeople = normalizeEmails(request.excludedPeople());
        List<String> excludedConversations =
            normalizeConversationIds(request.excludedConversations());
        ProviderCaptureUserPolicy policy = userPolicy(
            workspaceId,
            userId,
            provider,
            request,
            excludedPeople,
            excludedConversations);
        reset[0] = existing != null
            && captureShapeChanged(
                existing, request, excludedPeople, excludedConversations);
        if (existing == null) {
            if (request.version() != 0) {
                throw changed();
            }
            captureMapper.insertUserPolicy(policy);
        } else if (captureMapper.updateUserPolicy(policy, request.version()) != 1) {
            throw changed();
        }
        if (reset[0]) {
            resetUserCapture(workspaceId, userId, provider);
        }
        if (connection != null) {
            ensureStreams(workspaceId, userId, provider, request, connection);
            if (effectivePolicy(
                    workspaceId, userId, provider, connection).enabled()) {
                if ("manual".equals(request.admissionMode())) {
                    captureMapper.waitManualSync(workspaceId, userId, provider);
                } else {
                    captureMapper.queueSync(workspaceId, userId, provider);
                }
            } else {
                captureMapper.pauseUserSync(workspaceId, userId, provider);
            }
        }
    }

    /** Saves the workspace administrator's restrictive provider ceiling. */
    public ProviderCaptureOverviewDto updateWorkspacePolicy(
            String provider, ProviderCaptureWorkspacePolicyRequest request) {
        requireAuthorizedProvider(provider);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        boolean[] reset = {false};
        transaction(() -> persistWorkspacePolicy(
            provider, request, workspaceId, userId, reset));
        recordStrict(
            "provider.capture.workspace_policy",
            workspaceId,
            provider,
            "Changed workspace provider capture policy",
            Map.of("provider", provider, "allowed", request.allowed()));
        return overview(workspaceId, userId, provider);
    }

    private void persistWorkspacePolicy(
            String provider,
            ProviderCaptureWorkspacePolicyRequest request,
            int workspaceId,
            int userId,
            boolean[] reset) {
        duplicateDecisionLockService.lockCurrentOrganization();
        workspaceService.requirePermission(
            workspaceId, userId, Permission.WORKSPACE_SETTINGS);
        List<String> excludedDomains = normalizeDomains(request.excludedDomains());
        ProviderCaptureWorkspacePolicy policy = new ProviderCaptureWorkspacePolicy();
        policy.setWorkspaceId(workspaceId);
        policy.setProvider(provider);
        policy.setAllowed(request.allowed());
        policy.setCalendarAllowed(request.calendar());
        policy.setMailInboxAllowed(request.mailInbox());
        policy.setMailSentAllowed(request.mailSent());
        policy.setMaxBackfillDays(request.maxBackfillDays());
        policy.setBodyCaptureAllowed(request.bodyCaptureAllowed());
        policy.setReviewRequired(request.reviewRequired());
        policy.setExcludePrivateEvents(request.excludePrivateEvents());
        policy.setExcludeInternalOnly(request.excludeInternalOnly());
        policy.setExcludedDomainsJson(objectMapper.writeValueAsString(excludedDomains));
        policy.setUpdatedByUserId(userId);
        ProviderCaptureWorkspacePolicy existing =
            captureMapper.getWorkspacePolicy(workspaceId, provider);
        reset[0] = existing != null
            && captureShapeChanged(existing, request, excludedDomains);
        if (existing == null) {
            if (request.version() != 0) {
                throw changed();
            }
            captureMapper.insertWorkspacePolicy(policy);
        } else if (captureMapper.updateWorkspacePolicy(policy, request.version()) != 1) {
            throw changed();
        }
        if (reset[0]) {
            captureMapper.deleteWorkspaceProviderActivities(workspaceId, provider);
            captureMapper.deleteWorkspaceProviderInteractions(workspaceId, provider);
            captureMapper.resetWorkspaceProviderSyncStates(workspaceId, provider);
        } else if (request.allowed()) {
            captureMapper.resumeWorkspaceSync(workspaceId, provider);
        } else {
            captureMapper.pauseWorkspaceSync(workspaceId, provider);
        }
    }

    /** Queues enabled streams for the current user. */
    public ProviderCaptureOverviewDto queueCurrent(String provider) {
        requireAuthorizedProvider(provider);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        ProviderConnection connection = connection(userId, provider);
        transaction(() -> {
            CaptureExecutionPolicy effective =
                effectivePolicy(workspaceId, userId, provider, connection);
            if (!effective.enabled()) {
                throw new BadRequestException(
                    "Capture is not active under the effective policy");
            }
            captureMapper.resetSyncGeneration(
                workspaceId, userId, provider, connection.getCredentialGeneration());
            captureMapper.queueSync(workspaceId, userId, provider);
        });
        return overview(workspaceId, userId, provider);
    }

    /** Resolves the restrictive policy snapshot used by a background page. */
    public CaptureExecutionPolicy effectivePolicy(
            int workspaceId,
            int userId,
            String provider,
            ProviderConnection connection) {
        ProviderCaptureWorkspacePolicy workspacePolicy =
            workspacePolicy(workspaceId, provider);
        ProviderCaptureUserPolicy userPolicy =
            userPolicy(workspaceId, userId, provider);
        boolean connectionReady =
            connection != null && "connected".equals(connection.getStatus());
        boolean enabled = providers.isEnabled(provider)
            && properties.isCaptureEnabled(provider)
            && workspacePolicy.isAllowed()
            && userPolicy.isEnabled()
            && connectionReady
            && workspaceService.permissionsFor(workspaceId, userId)
                .contains(Permission.ACTIVITY_CREATE);
        boolean calendarScope = connection != null
            && providers.hasCaptureScope(
                provider, connection.getGrantedScopes(), "calendar");
        boolean mailInboxScope = connection != null
            && providers.hasCaptureScope(
                provider, connection.getGrantedScopes(), "mail_inbox");
        boolean mailSentScope = connection != null
            && providers.hasCaptureScope(
                provider, connection.getGrantedScopes(), "mail_sent");
        String admissionMode = workspacePolicy.isReviewRequired()
                && "automatic".equals(userPolicy.getAdmissionMode())
            ? "review"
            : userPolicy.getAdmissionMode();
        return new CaptureExecutionPolicy(
            enabled,
            workspacePolicy.isCalendarAllowed()
                && userPolicy.isCalendarEnabled()
                && calendarScope,
            workspacePolicy.isMailInboxAllowed()
                && userPolicy.isMailInboxEnabled()
                && mailInboxScope,
            workspacePolicy.isMailSentAllowed()
                && userPolicy.isMailSentEnabled()
                && mailSentScope,
            Math.min(userPolicy.getBackfillDays(), workspacePolicy.getMaxBackfillDays()),
            workspacePolicy.isBodyCaptureAllowed() && userPolicy.isIncludeBodies(),
            admissionMode,
            true,
            workspacePolicy.isExcludeInternalOnly(),
            domains(workspacePolicy.getExcludedDomainsJson()),
            jsonValues(userPolicy.getExcludedPeopleJson()),
            jsonValues(userPolicy.getExcludedConversationsJson()),
            Math.max(1, workspacePolicy.getVersion()));
    }

    private ProviderCaptureOverviewDto overview(
            int workspaceId, int userId, String provider) {
        ProviderConnection connection = connection(userId, provider);
        return overview(
            workspaceId,
            userId,
            provider,
            connection,
            purgeService.hasResiduals(workspaceId, userId, provider));
    }

    private ProviderCaptureOverviewDto overview(
            int workspaceId,
            int userId,
            String provider,
            ProviderConnection connection,
            boolean retainedData) {
        ProviderCaptureWorkspacePolicy workspacePolicy =
            workspacePolicy(workspaceId, provider);
        ProviderCaptureUserPolicy userPolicy =
            userPolicy(workspaceId, userId, provider);
        CaptureExecutionPolicy effective =
            effectivePolicy(workspaceId, userId, provider, connection);
        List<String> restrictions = restrictions(
            workspacePolicy, userPolicy, connection, provider);
        List<ProviderCaptureOverviewDto.StreamState> streams =
            captureMapper.getSyncStates(workspaceId, userId, provider).stream()
                .map(this::stream)
                .toList();
        return new ProviderCaptureOverviewDto(
            provider,
            capturePolicy(userPolicy, workspacePolicy),
            workspaceView(workspacePolicy),
            new ProviderCaptureOverviewDto.EffectivePolicy(
                effective.enabled(),
                effective.calendar(),
                effective.mailInbox(),
                effective.mailSent(),
                effective.backfillDays(),
                effective.includeBodies(),
                effective.admissionMode(),
                restrictions),
            streams,
            captureMapper.countReviews(workspaceId, userId, provider),
            captureMapper.countPendingApprovals(workspaceId, userId, provider),
            effective.enabled() && (effective.calendar()
                || effective.mailInbox()
                || effective.mailSent()),
            retainedData,
            connection != null && "disconnected".equals(connection.getStatus()),
            disclosures(provider, effective),
            purge(connection));
    }

    private ProviderCaptureOverviewDto.CapturePolicy capturePolicy(
            ProviderCaptureUserPolicy userPolicy,
            ProviderCaptureWorkspacePolicy workspacePolicy) {
        return new ProviderCaptureOverviewDto.CapturePolicy(
            userPolicy.isEnabled(),
            userPolicy.isCalendarEnabled(),
            userPolicy.isMailInboxEnabled(),
            userPolicy.isMailSentEnabled(),
            userPolicy.getBackfillDays(),
            userPolicy.isIncludeBodies(),
            userPolicy.getAdmissionMode(),
            workspacePolicy.isReviewRequired()
                || !"automatic".equals(userPolicy.getAdmissionMode()),
            jsonValues(userPolicy.getExcludedPeopleJson()),
            jsonValues(userPolicy.getExcludedConversationsJson()),
            userPolicy.getVersion(),
            userPolicy.getUpdatedAt());
    }

    private ProviderCaptureOverviewDto.WorkspacePolicy workspaceView(
            ProviderCaptureWorkspacePolicy policy) {
        return new ProviderCaptureOverviewDto.WorkspacePolicy(
            policy.isAllowed(),
            policy.isCalendarAllowed(),
            policy.isMailInboxAllowed(),
            policy.isMailSentAllowed(),
            policy.getMaxBackfillDays(),
            policy.isBodyCaptureAllowed(),
            policy.isReviewRequired(),
            policy.isExcludePrivateEvents(),
            policy.isExcludeInternalOnly(),
            domains(policy.getExcludedDomainsJson()),
            policy.getVersion(),
            policy.getUpdatedAt());
    }

    private ProviderCaptureOverviewDto.StreamState stream(
            ProviderCaptureSyncState state) {
        return new ProviderCaptureOverviewDto.StreamState(
            state.getStream(),
            state.getStatus(),
            state.getProcessedItems(),
            state.getEstimatedItems(),
            state.getLastAttemptAt(),
            state.getLastSuccessAt(),
            state.getNextAttemptAt(),
            state.getErrorCode());
    }

    private ProviderCaptureOverviewDto.Disclosures disclosures(
            String provider, CaptureExecutionPolicy policy) {
        List<String> fields = new ArrayList<>(
            List.of("provider_source_id", "subject", "occurred_at", "participants"));
        if (policy.includeBodies()) {
            fields.add("body");
        }
        List<String> exclusions = new ArrayList<>(
            List.of("attachments", "raw_mime", "remote_images", "non_primary_calendars"));
        if (!policy.includeBodies()) {
            exclusions.add("body");
        }
        return new ProviderCaptureOverviewDto.Disclosures(
            List.of(providers.scopes(provider).split(" ")),
            fields,
            exclusions,
            List.of("workspace_activity_evidence"),
            List.of("retained_on_disconnect", "erased_on_request", "purged_on_account_deletion"));
    }

    private ProviderCaptureOverviewDto.PurgeState purge(
            ProviderConnection connection) {
        if (connection == null) {
            return new ProviderCaptureOverviewDto.PurgeState(false, "idle", null);
        }
        boolean revoking = "revoking".equals(connection.getStatus());
        boolean active = revoking
            || "disconnecting".equals(connection.getStatus());
        boolean failed = "purge_failed".equals(connection.getStatus());
        String status = "idle";
        if (active) {
            status = "disconnecting";
        } else if (failed) {
            status = "purge_failed";
        }
        return new ProviderCaptureOverviewDto.PurgeState(
            active,
            status,
            connection.getErrorCode());
    }

    private ProviderCaptureWorkspacePolicy workspacePolicy(
            int workspaceId, String provider) {
        ProviderCaptureWorkspacePolicy policy =
            captureMapper.getWorkspacePolicy(workspaceId, provider);
        if (policy != null) {
            return policy;
        }
        ProviderCaptureWorkspacePolicy defaults = new ProviderCaptureWorkspacePolicy();
        defaults.setWorkspaceId(workspaceId);
        defaults.setProvider(provider);
        defaults.setAllowed(false);
        defaults.setCalendarAllowed(true);
        defaults.setMailInboxAllowed(true);
        defaults.setMailSentAllowed(true);
        defaults.setMaxBackfillDays(DEFAULT_BACKFILL_DAYS);
        defaults.setBodyCaptureAllowed(false);
        defaults.setReviewRequired(true);
        defaults.setExcludePrivateEvents(true);
        defaults.setExcludeInternalOnly(false);
        defaults.setExcludedDomainsJson("[]");
        defaults.setVersion(0);
        return defaults;
    }

    private ProviderCaptureUserPolicy userPolicy(
            int workspaceId, int userId, String provider) {
        ProviderCaptureUserPolicy policy =
            captureMapper.getUserPolicy(workspaceId, userId, provider);
        if (policy != null) {
            return policy;
        }
        ProviderCaptureUserPolicy defaults = new ProviderCaptureUserPolicy();
        defaults.setWorkspaceId(workspaceId);
        defaults.setUserId(userId);
        defaults.setProvider(provider);
        defaults.setEnabled(false);
        defaults.setCalendarEnabled(false);
        defaults.setMailInboxEnabled(false);
        defaults.setMailSentEnabled(false);
        defaults.setBackfillDays(DEFAULT_BACKFILL_DAYS);
        defaults.setIncludeBodies(false);
        defaults.setAdmissionMode("review");
        defaults.setExcludedPeopleJson("[]");
        defaults.setExcludedConversationsJson("[]");
        defaults.setVersion(0);
        return defaults;
    }

    private ProviderCaptureUserPolicy userPolicy(
            int workspaceId,
            int userId,
            String provider,
            ProviderCaptureUserPolicyRequest request,
            List<String> excludedPeople,
            List<String> excludedConversations) {
        ProviderCaptureUserPolicy policy = new ProviderCaptureUserPolicy();
        policy.setWorkspaceId(workspaceId);
        policy.setUserId(userId);
        policy.setProvider(provider);
        policy.setEnabled(request.enabled());
        policy.setCalendarEnabled(request.calendar());
        policy.setMailInboxEnabled(request.mailInbox());
        policy.setMailSentEnabled(request.mailSent());
        policy.setBackfillDays(request.backfillDays());
        policy.setIncludeBodies(request.includeBodies());
        policy.setAdmissionMode(request.admissionMode());
        policy.setExcludedPeopleJson(
            objectMapper.writeValueAsString(excludedPeople));
        policy.setExcludedConversationsJson(
            objectMapper.writeValueAsString(excludedConversations));
        return policy;
    }

    private void ensureStreams(
            int workspaceId,
            int userId,
            String provider,
            ProviderCaptureUserPolicyRequest request,
            ProviderConnection connection) {
        if (request.calendar()) {
            captureMapper.ensureSyncState(
                workspaceId, userId, provider, "calendar",
                connection.getCredentialGeneration());
        }
        if (request.mailInbox()) {
            captureMapper.ensureSyncState(
                workspaceId, userId, provider, "mail_inbox",
                connection.getCredentialGeneration());
        }
        if (request.mailSent()) {
            captureMapper.ensureSyncState(
                workspaceId, userId, provider, "mail_sent",
                connection.getCredentialGeneration());
        }
    }

    private void ensureStreams(
            int workspaceId,
            int userId,
            String provider,
            ProviderCaptureUserPolicy policy,
            ProviderConnection connection) {
        if (policy.isCalendarEnabled()) {
            captureMapper.ensureSyncState(
                workspaceId, userId, provider, "calendar",
                connection.getCredentialGeneration());
        }
        if (policy.isMailInboxEnabled()) {
            captureMapper.ensureSyncState(
                workspaceId, userId, provider, "mail_inbox",
                connection.getCredentialGeneration());
        }
        if (policy.isMailSentEnabled()) {
            captureMapper.ensureSyncState(
                workspaceId, userId, provider, "mail_sent",
                connection.getCredentialGeneration());
        }
    }

    private void resetUserCapture(int workspaceId, int userId, String provider) {
        captureMapper.deleteProviderActivities(workspaceId, userId, provider);
        captureMapper.deleteInteractions(workspaceId, userId, provider);
        captureMapper.deleteSyncStates(workspaceId, userId, provider);
    }

    private boolean captureShapeChanged(
            ProviderCaptureUserPolicy existing,
            ProviderCaptureUserPolicyRequest request,
            List<String> excludedPeople,
            List<String> excludedConversations) {
        return existing.isCalendarEnabled() != request.calendar()
            || existing.isMailInboxEnabled() != request.mailInbox()
            || existing.isMailSentEnabled() != request.mailSent()
            || existing.getBackfillDays() != request.backfillDays()
            || existing.isIncludeBodies() != request.includeBodies()
            || !existing.getAdmissionMode().equals(request.admissionMode())
            || !jsonArray(existing.getExcludedPeopleJson()).equals(excludedPeople)
            || !jsonArray(existing.getExcludedConversationsJson())
                .equals(excludedConversations);
    }

    private boolean captureShapeChanged(
            ProviderCaptureWorkspacePolicy existing,
            ProviderCaptureWorkspacePolicyRequest request,
            List<String> normalizedDomains) {
        return existing.isCalendarAllowed() != request.calendar()
            || existing.isMailInboxAllowed() != request.mailInbox()
            || existing.isMailSentAllowed() != request.mailSent()
            || existing.getMaxBackfillDays() != request.maxBackfillDays()
            || existing.isBodyCaptureAllowed() != request.bodyCaptureAllowed()
            || existing.isReviewRequired() != request.reviewRequired()
            || existing.isExcludePrivateEvents() != request.excludePrivateEvents()
            || existing.isExcludeInternalOnly() != request.excludeInternalOnly()
            || !domains(existing.getExcludedDomainsJson()).equals(normalizedDomains);
    }

    private List<String> restrictions(
            ProviderCaptureWorkspacePolicy workspacePolicy,
            ProviderCaptureUserPolicy userPolicy,
            ProviderConnection connection,
            String provider) {
        List<String> restrictions = new ArrayList<>();
        if (!providers.isEnabled(provider)
                || !properties.isCaptureEnabled(provider)) {
            restrictions.add("operator_disabled");
        }
        if (!workspacePolicy.isAllowed()) {
            restrictions.add("workspace_disabled");
        }
        if (!userPolicy.isEnabled()) {
            restrictions.add("user_disabled");
        }
        if (connection == null) {
            restrictions.add("not_connected");
        } else if (!"connected".equals(connection.getStatus())) {
            restrictions.add(connectionRestriction(connection.getStatus()));
        }
        if (!workspacePolicy.isBodyCaptureAllowed() && userPolicy.isIncludeBodies()) {
            restrictions.add("body_capture_disabled");
        }
        if (connection != null
                && userPolicy.isCalendarEnabled()
                && !providers.hasCaptureScope(
                    provider, connection.getGrantedScopes(), "calendar")) {
            restrictions.add("missing_scope_calendar");
        }
        if (connection != null
                && (userPolicy.isMailInboxEnabled()
                    || userPolicy.isMailSentEnabled())
                && !providers.hasCaptureScope(
                    provider, connection.getGrantedScopes(), "mail_inbox")) {
            restrictions.add("missing_scope_mail");
        }
        return restrictions;
    }

    static String connectionRestriction(String status) {
        return switch (status) {
            case "paused" -> "connection_paused";
            case "error" -> "connection_error";
            case "revoking", "disconnecting" -> "connection_disconnecting";
            case "purge_failed" -> "connection_purge_failed";
            case "revoked", "disconnected" -> "not_connected";
            default -> "not_connected";
        };
    }

    private List<String> normalizeDomains(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String domain = matchingService.normalizeIdentifier(IdentityKind.DOMAIN, value)
                .orElseThrow(() -> new BadRequestException(
                    "Invalid excluded domain: " + value));
            normalized.add(domain);
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeEmails(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String email = matchingService
                .normalizeIdentifier(IdentityKind.EMAIL, value)
                .orElseThrow(() -> new BadRequestException(
                    "Invalid excluded person email: " + value));
            normalized.add(email);
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeConversationIds(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String identifier = value == null ? "" : value.strip();
            if (identifier.isEmpty() || identifier.length() > 512) {
                throw new BadRequestException(
                    "Excluded conversation identifiers must be 1 to 512 characters");
            }
            normalized.add(identifier);
        }
        return List.copyOf(normalized);
    }

    private List<String> domains(String json) {
        if (json == null) {
            return List.of();
        }
        JsonNode value = objectMapper.readTree(json);
        if (!value.isArray()) {
            return List.of();
        }
        List<String> domains = new ArrayList<>();
        for (JsonNode entry : value) {
            if (entry.isTextual()) {
                domains.add(entry.asString());
            }
        }
        return List.copyOf(domains);
    }

    private List<String> jsonValues(String json) {
        return jsonArray(json);
    }

    private List<String> jsonArray(String json) {
        if (json == null) {
            return List.of();
        }
        JsonNode value = objectMapper.readTree(json);
        if (!value.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode entry : value) {
            if (entry.isTextual()) {
                values.add(entry.asString());
            }
        }
        return List.copyOf(values);
    }

    private void requireAuthorizedProvider(String provider) {
        if (!providers.isSupported(provider)
                || !providers.isEnabled(provider)
                || !properties.isCaptureEnabled(provider)) {
            throw new ResourceNotFoundException("Capture provider is not available");
        }
    }

    private ProviderConnection connection(int userId, String provider) {
        return tenantWorkScope.unrouted(
            () -> connectionMapper.getByUserAndProvider(userId, provider));
    }

    private void transaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
            status -> work.run());
    }

    private void recordStrict(
            String action,
            int workspaceId,
            String provider,
            String summary,
            Map<String, Object> changes) {
        tenantWorkScope.unrouted(() -> {
            auditService.recordStrictIndependentScoped(
                action,
                "workspace",
                workspaceId,
                workspaceId,
                workspaceService.getOrgId(workspaceId),
                provider,
                summary,
                changes);
            return null;
        });
    }

    private static ConflictException changed() {
        return new ConflictException("Capture policy changed; reload and retry");
    }
}
