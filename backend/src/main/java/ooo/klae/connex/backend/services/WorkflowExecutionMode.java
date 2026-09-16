package ooo.klae.connex.backend.services;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.services.WorkflowPrincipalLockService.LockedPrincipals;
import ooo.klae.connex.backend.services.WorkflowService.PrincipalMatchPolicy;
import ooo.klae.connex.backend.tenant.Permission;

/** Closed execution identities used to select mandatory workflow authorization paths. */
enum WorkflowExecutionMode {
    USER("user", PrincipalMatchPolicy.REDACTED_CREATOR) {
        @Override
        LockedPrincipals lockPrincipals(
                WorkflowPrincipalLockService lockService,
                int workspaceId,
                Collection<Integer> principalIds,
                AuthorizationContext context,
                boolean admitTriggerCapacity) {
            TreeSet<Integer> requiredActiveIds = new TreeSet<>();
            for (Integer userId : context.runAsUserIds()) {
                if (userId == null || userId <= 0) {
                    throw new ConflictException("Workflow run-as user is not an active workspace member");
                }
                requiredActiveIds.add(userId);
            }
            return lockService.lockUserMutation(
                workspaceId, context.actorId(), principalIds, requiredActiveIds, admitTriggerCapacity);
        }

        @Override
        void requireAuthorized(LockedPrincipals locked, AuthorizationContext context) {
            for (Integer userId : context.runAsUserIds()) {
                locked.requireExisting(userId, "Workflow run-as user is not an active workspace member");
            }
            locked.requirePermissions(Set.of(Permission.RULE_MANAGE));
        }

        @Override
        void requireWorkflowReferences(LockedPrincipals locked, Collection<Integer> principalIds) {
            locked.requireDiscoveredReferences(principalIds);
        }
    },
    SYSTEM("system", PrincipalMatchPolicy.STRICT) {
        @Override
        LockedPrincipals lockPrincipals(
                WorkflowPrincipalLockService lockService,
                int workspaceId,
                Collection<Integer> principalIds,
                AuthorizationContext context,
                boolean admitTriggerCapacity) {
            return lockService.lockSystemMutation(
                workspaceId, context.actorId(), principalIds, admitTriggerCapacity);
        }

        @Override
        void requireAuthorized(LockedPrincipals locked, AuthorizationContext context) {
            locked.requireExisting(context.actorId(), "Workflow actor account no longer exists");
            for (Integer userId : context.systemCreatorIds()) {
                locked.requireExisting(userId, "System workflow creator account no longer exists");
            }
            locked.requirePermissions(Set.of(Permission.RULE_MANAGE));
        }

        @Override
        void requireWorkflowReferences(LockedPrincipals locked, Collection<Integer> principalIds) {
            locked.requireCurrentReferences(principalIds);
        }
    };

    private final String value;
    private final PrincipalMatchPolicy principalMatchPolicy;

    WorkflowExecutionMode(String value, PrincipalMatchPolicy principalMatchPolicy) {
        this.value = value;
        this.principalMatchPolicy = principalMatchPolicy;
    }

    /** Locks the mode's current membership and authorization before any aggregate mutation. */
    abstract LockedPrincipals lockPrincipals(
        WorkflowPrincipalLockService lockService,
        int workspaceId,
        Collection<Integer> principalIds,
        AuthorizationContext context,
        boolean admitTriggerCapacity);

    /** Requires the mode's locked identities and permissions after its authorization locks. */
    abstract void requireAuthorized(LockedPrincipals locked, AuthorizationContext context);

    /** Revalidates workflow references while preserving user-mode attribution erasure. */
    abstract void requireWorkflowReferences(LockedPrincipals locked, Collection<Integer> principalIds);

    /** Returns the persisted-principal compatibility policy for activation. */
    PrincipalMatchPolicy principalMatchPolicy() {
        return principalMatchPolicy;
    }

    /** Parses the existing normalized wire values before workflow authorization or mutation. */
    static WorkflowExecutionMode parse(String value) {
        if (value == null) {
            throw new BadRequestException("Workflow execution mode must be user or system");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "user" -> USER;
            case "system" -> SYSTEM;
            default -> throw new BadRequestException("Workflow execution mode must be user or system");
        };
    }

    /** Returns the compatible wire and persistence value. */
    String value() {
        return value;
    }

    /** Carries exact required identities; null creator entries remain mandatory and fail closed. */
    record AuthorizationContext(
        int actorId,
        List<Integer> runAsUserIds,
        List<Integer> systemCreatorIds) {
        AuthorizationContext {
            runAsUserIds = runAsUserIds.stream().toList();
            systemCreatorIds = systemCreatorIds.stream().toList();
        }
    }
}
