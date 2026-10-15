"use client";

import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";

import { ApiError, leaveOrganization } from "@/app/lib/api";

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

/** The exits in progress and refused, and the action that takes one. */
export type ConfinementExits = {
    /** The exit being taken, by {@link confinementExitKey}, or null. */
    leavingKey: string | null;
    /** The latest refusal of each exit, by {@link confinementExitKey}. */
    refusals: Readonly<Record<string, ConfinementExitRefusal>>;
    /** Takes an exit; resolves true once the server accepted it. */
    leave: (target: ConfinementExitTarget) => Promise<boolean>;
};

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
 * selection, so the surface supplies how; without it, only organizations can be left. A refusal is kept by its
 * stable code for the surface to explain, and the confinement stays as the server left it.
 * @param leaveWorkspace how this surface leaves a workspace, when it can
 * @returns the exit state and the action
 */
export function useConfinementExits(
    leaveWorkspace?: (workspaceId: number) => Promise<void>,
): ConfinementExits {
    const router = useRouter();
    const [leavingKey, setLeavingKey] = useState<string | null>(null);
    const [refusals, setRefusals] = useState<Readonly<Record<string, ConfinementExitRefusal>>>({});

    const leave = useCallback(async (target: ConfinementExitTarget) => {
        const key = confinementExitKey(target);
        setLeavingKey(key);
        try {
            if (target.kind === "organization") {
                await leaveOrganization(target.orgId);
                router.refresh();
            } else if (leaveWorkspace) {
                await leaveWorkspace(target.workspaceId);
            } else {
                return false;
            }
            setRefusals((previous) => {
                const next = { ...previous };
                delete next[key];
                return next;
            });
            return true;
        } catch (err) {
            const refusal: ConfinementExitRefusal = err instanceof ApiError && isNamedRefusal(err.code)
                ? err.code
                : "OTHER";
            setRefusals((previous) => ({ ...previous, [key]: refusal }));
            return false;
        } finally {
            setLeavingKey(null);
        }
    }, [leaveWorkspace, router]);

    return { leavingKey, refusals, leave };
}
