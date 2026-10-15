"use client";

import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import {
    ApiError,
    leaveOrganization,
    settlePrivilegedMfaStanding,
    type PrivilegedMfaSettleOutcome,
} from "@/app/lib/api";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { toastError } from "@/app/lib/toast";

/** What a confined account can leave to end the privilege that confines it (#1534). */
export type ConfinementExitTarget =
    | { kind: "organization"; orgId: number; name: string }
    | { kind: "workspace"; workspaceId: number; name: string };

/** Why the server refused an exit, by its stable code, or `OTHER` for anything it did not name. */
export type ConfinementExitRefusal =
    | "WORKSPACE_LAST_OWNER"
    | "WORKSPACE_RECOVERY_OWNER_REQUIRED"
    | "SOLE_NAMED_DOCUMENT_APPROVER"
    | "ORG_NOT_MEMBER"
    | "ORG_LAST_OWNER"
    | "OTHER";

const KNOWN_REFUSALS: ReadonlySet<string> = new Set<ConfinementExitRefusal>([
    "WORKSPACE_LAST_OWNER",
    "WORKSPACE_RECOVERY_OWNER_REQUIRED",
    "SOLE_NAMED_DOCUMENT_APPROVER",
    "ORG_NOT_MEMBER",
    "ORG_LAST_OWNER",
]);

/**
 * How a surface leaves a workspace, since that changes the browser's workspace selection. Both
 * resolve how the standing read that followed settled, or null when nothing was read.
 */
export type ConfinementWorkspaceExit = {
    /** Leaves the workspace and publishes the server's new selection. */
    leave: (workspaceId: number) => Promise<PrivilegedMfaSettleOutcome | null>;
    /**
     * Catches up with a workspace the account no longer belongs to, because it was removed or left
     * elsewhere: reads the server's selection and the standing again, as after a departure.
     */
    reconcile: () => Promise<PrivilegedMfaSettleOutcome | null>;
};

/** The exits in progress and refused, and the action that takes one. */
export type ConfinementExits = {
    /** The exit being taken, by {@link confinementExitKey}, or null. */
    leavingKey: string | null;
    /** The latest refusal of each exit, by {@link confinementExitKey}. */
    refusals: Readonly<Record<string, ConfinementExitRefusal>>;
    /** Takes an exit; resolves true once the server accepted it. */
    leave: (target: ConfinementExitTarget) => Promise<boolean>;
};

/** The code the server answers a workspace exit with once the account is no longer a member. */
const WORKSPACE_ALREADY_LEFT_CODE = "RESOURCE_NOT_FOUND";

/** Whether a refusal code is one of the exit refusals the server names. */
function isNamedRefusal(code: string | undefined): code is Exclude<ConfinementExitRefusal, "OTHER"> {
    return code !== undefined && KNOWN_REFUSALS.has(code);
}

/** A stable key for one exit, so its progress and refusal can be shown beside it. */
export function confinementExitKey(target: ConfinementExitTarget): string {
    return target.kind === "organization" ? `organization:${target.orgId}` : `workspace:${target.workspaceId}`;
}

/**
 * Takes the exits a confined account may always send (#1534): leaving an organization or a
 * workspace. Leaving an organization goes straight to the API, which re-reads the standing once
 * the server accepts, and then refreshes the page so roles derived from the membership, such as
 * organization navigation, drop at once. Leaving a workspace changes the browser's workspace
 * selection, so the surface supplies how; without it, only organizations can be left. A refusal
 * is kept by its stable code for the surface to explain, and the confinement stays as the server
 * left it. An organization or workspace the account had already left elsewhere counts as left: the
 * page catches up with the server as after an accepted exit. When the standing cannot be read again
 * after an exit, it is read once more, since a transient failure or a concurrent read may already
 * have caught the page up; only when that also fails is the account told the page could not update.
 * The exit counts either way, and a failure to catch up with a workspace left elsewhere is reported
 * through the shared error toast, so an expired session still leads to sign-in.
 * @param workspaceExit how this surface leaves a workspace, when it can
 * @returns the exit state and the action
 */
export function useConfinementExits(workspaceExit?: ConfinementWorkspaceExit): ConfinementExits {
    const router = useRouter();
    const t = useTranslations("AccountSecurity");
    const showApiError = useApiErrorToast("AccountSecurity");
    const [leavingKey, setLeavingKey] = useState<string | null>(null);
    const [refusals, setRefusals] = useState<Readonly<Record<string, ConfinementExitRefusal>>>({});

    const leave = useCallback(async (target: ConfinementExitTarget) => {
        const key = confinementExitKey(target);
        const forgetRefusal = () => {
            setRefusals((previous) => {
                const next = { ...previous };
                delete next[key];
                return next;
            });
        };
        const settled = async (outcome: PrivilegedMfaSettleOutcome | null) => {
            forgetRefusal();
            if (outcome === "failed" && await settlePrivilegedMfaStanding("exit") === "failed") {
                toastError(t("exitRefreshFailed"), { description: t("exitRefreshFailedDescription") });
            }
            return true;
        };
        setLeavingKey(key);
        try {
            if (target.kind === "organization") {
                const outcome = await leaveOrganization(target.orgId);
                router.refresh();
                return await settled(outcome);
            }
            if (!workspaceExit) return false;
            return await settled(await workspaceExit.leave(target.workspaceId));
        } catch (err) {
            if (target.kind === "organization" && err instanceof ApiError && err.code === "ORG_NOT_MEMBER") {
                const outcome = await settlePrivilegedMfaStanding("exit");
                router.refresh();
                return await settled(outcome);
            }
            if (target.kind === "workspace" && workspaceExit && err instanceof ApiError
                && err.code === WORKSPACE_ALREADY_LEFT_CODE) {
                try {
                    return await settled(await workspaceExit.reconcile());
                } catch (reconcileError) {
                    forgetRefusal();
                    showApiError(reconcileError, "exitRefreshFailed", "exitRefreshFailedDescription");
                    return true;
                }
            }
            const refusal: ConfinementExitRefusal = err instanceof ApiError && isNamedRefusal(err.code)
                ? err.code
                : "OTHER";
            setRefusals((previous) => ({ ...previous, [key]: refusal }));
            return false;
        } finally {
            setLeavingKey(null);
        }
    }, [router, showApiError, t, workspaceExit]);

    return { leavingKey, refusals, leave };
}
