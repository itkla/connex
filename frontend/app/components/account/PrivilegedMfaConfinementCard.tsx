"use client";

import { useId, useState, type ReactNode } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import type { Passkey, PasskeyRegistrationRequirements } from "@/app/lib/types";
import type { PrivilegedMfaConfinementReason } from "@/app/lib/api";
import {
    confinementExitKey,
    type ConfinementExitRefusal,
    type ConfinementExits,
    type ConfinementExitTarget,
} from "@/app/hooks/useConfinementExits";
import { passkeyApprovalProgress } from "@/app/lib/passkeyApprovalProgress";
import { Button } from "@/components/ui/button";
import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";

const REFUSAL_KEYS: Record<ConfinementExitRefusal, string> = {
    WORKSPACE_LAST_OWNER: "exitRefusedWorkspaceLastOwner",
    WORKSPACE_RECOVERY_OWNER_REQUIRED: "exitRefusedWorkspaceRecoveryOwner",
    SOLE_NAMED_DOCUMENT_APPROVER: "exitRefusedSoleApprover",
    ORG_NOT_MEMBER: "exitRefusedOrgNotMember",
    ORG_LAST_OWNER: "exitRefusedOrgLastOwner",
    OTHER: "exitRefused",
};

/**
 * Explains why a privileged account is confined and what it can do about it (#1534): add a passkey,
 * have one approved with a code, or leave what makes it privileged. The standing and the actions
 * come from the surface; the card only lays them out, names the passkey every approval should go
 * to, and asks before each exit.
 * @param props.reason why the account is confined
 * @param props.requirements the newest decisive standing, or null before one
 * @param props.passkeys the account's passkeys, for the approval guidance
 * @param props.action the surface's main action for this reason, such as adding a passkey
 * @param props.exits the exit state and action
 * @param props.canLeaveWorkspaces whether this surface can leave a workspace
 * @param props.footer anything the surface adds below, such as a recheck
 */
export default function PrivilegedMfaConfinementCard({
    reason,
    requirements,
    passkeys,
    action,
    exits,
    canLeaveWorkspaces,
    footer,
}: {
    reason: PrivilegedMfaConfinementReason;
    requirements: PasskeyRegistrationRequirements | null;
    passkeys: Passkey[];
    action?: ReactNode;
    exits: ConfinementExits;
    canLeaveWorkspaces: boolean;
    footer?: ReactNode;
}) {
    const t = useTranslations("AccountSecurity");
    const tMembership = useTranslations("AccountInvites");
    const format = useFormatter();
    const titleId = useId();
    const [confirming, setConfirming] = useState<ConfinementExitTarget | null>(null);

    const progress = reason === "attestation"
        ? passkeyApprovalProgress(passkeys, requirements?.approvalGaps ?? [])
        : [];
    const recommended = progress[0];
    const passkeyName = (label: string | null) => label ?? t("approvalUnnamedPasskey");
    const organizations = (names: string[]) => format.list(names, { type: "conjunction" });
    const targets: ConfinementExitTarget[] = (requirements?.privilegedMemberships ?? []).flatMap((membership) => [
        ...(membership.orgRole !== null
            ? [{ kind: "organization" as const, orgId: membership.orgId, name: membership.orgName }]
            : []),
        ...(canLeaveWorkspaces
            ? membership.workspaces.map((workspace) => ({
                kind: "workspace" as const,
                workspaceId: workspace.workspaceId,
                name: workspace.workspaceName,
            }))
            : []),
    ]);

    const confirmLeave = async () => {
        if (!confirming) return;
        const target = confirming;
        setConfirming(null);
        await exits.leave(target);
    };

    return (
        <section
            aria-labelledby={titleId}
            className="rounded-2xl border border-warning/40 bg-warning/5 px-5 py-4"
        >
            <h3 id={titleId} className="text-sm font-medium text-foreground">
                {reason === "attestation" ? t("approvalNeededTitle") : t("enrollmentNeededTitle")}
            </h3>
            <p className="mt-1 max-w-prose text-sm text-muted-foreground">
                {reason === "attestation" ? t("approvalNeededDescription") : t("enrollmentNeededDescription")}
            </p>

            {recommended && (
                <p className="mt-3 max-w-prose text-sm text-foreground">
                    {t("approvalNeededUse", {
                        passkey: passkeyName(recommended.label),
                        organizations: organizations(recommended.missingOrganizations.map((org) => org.orgName)),
                    })}
                </p>
            )}
            {recommended && progress.length > 1 && (
                <>
                    <ul className="mt-2 space-y-1">
                        {progress.map((entry) => (
                            <li key={entry.credentialId} className="text-sm text-muted-foreground">
                                {t("approvalNeededPasskey", {
                                    passkey: passkeyName(entry.label),
                                    organizations: organizations(entry.missingOrganizations.map((org) => org.orgName)),
                                })}
                                {entry.credentialId === recommended.credentialId && (
                                    <span className="ml-2 text-xs font-medium text-foreground">
                                        {t("approvalNeededRecommended")}
                                    </span>
                                )}
                            </li>
                        ))}
                    </ul>
                    <p className="mt-2 max-w-prose text-sm text-muted-foreground">
                        {t("approvalNeededSamePasskey", { passkey: passkeyName(recommended.label) })}
                    </p>
                </>
            )}

            {action && <div className="mt-3 flex flex-wrap items-center gap-2">{action}</div>}

            {targets.length > 0 && (
                <div className="mt-4 border-t border-warning/30 pt-3">
                    <p className="text-sm font-medium text-foreground">{t("exitsTitle")}</p>
                    <p className="mt-1 max-w-prose text-sm text-muted-foreground">{t("exitsDescription")}</p>
                    <ul className="mt-2 space-y-2">
                        {targets.map((target) => {
                            const key = confinementExitKey(target);
                            const refusal = exits.refusals[key];
                            return (
                                <li key={key}>
                                    <Button
                                        type="button"
                                        variant="outline"
                                        size="inline"
                                        disabled={exits.leavingKey !== null}
                                        aria-busy={exits.leavingKey === key}
                                        onClick={() => setConfirming(target)}
                                    >
                                        {exits.leavingKey === key && <Loader2Icon className="size-4 animate-spin" />}
                                        {target.kind === "organization"
                                            ? t("exitLeaveOrganization", { name: target.name })
                                            : t("exitLeaveWorkspace", { name: target.name })}
                                    </Button>
                                    {refusal && (
                                        <p role="alert" className="mt-1 max-w-prose text-sm text-destructive">
                                            {t(REFUSAL_KEYS[refusal])}
                                        </p>
                                    )}
                                </li>
                            );
                        })}
                    </ul>
                </div>
            )}

            {footer}

            <Dialog
                open={confirming !== null}
                onOpenChange={(open) => {
                    if (!open) setConfirming(null);
                }}
            >
                {confirming && (
                    <DialogContent className="sm:max-w-md">
                        <DialogHeader>
                            <DialogTitle>{t("exitConfirmTitle", { name: confirming.name })}</DialogTitle>
                            <DialogDescription>
                                {confirming.kind === "organization"
                                    ? t("exitConfirmOrganization")
                                    : tMembership("leaveConfirmBody")}
                            </DialogDescription>
                        </DialogHeader>
                        <DialogFooter>
                            <DialogClose asChild>
                                <Button type="button" variant="outline">
                                    {t("cancel")}
                                </Button>
                            </DialogClose>
                            <Button type="button" variant="destructive" onClick={() => void confirmLeave()}>
                                {t("exitConfirm")}
                            </Button>
                        </DialogFooter>
                    </DialogContent>
                )}
            </Dialog>
        </section>
    );
}
