"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import { AtSymbolIcon, EllipsisHorizontalIcon, GlobeAltIcon, TrashIcon } from "@heroicons/react/24/outline";

import {
    addWorkspaceAllowedDomain,
    getWorkspaceAllowedDomains,
    removeWorkspaceAllowedDomain,
} from "@/app/lib/api";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { useFieldErrors } from "@/app/hooks/useFieldErrors";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { toastSuccess } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import {
    EmptyRow,
    ListCard,
    TabListHeading,
    rowActionTrigger,
} from "@/app/components/settings/SettingsListPrimitives";

/**
 * The workspace's allowed email domains: who may join through an invite link without being invited
 * one by one.
 *
 * This was the third tab of `MembersPanel`'s invite strip, which gave a standing access policy no
 * address of its own — #1340 records it as a required per-scope destination, so it is now a
 * component both homes render: the legacy `/settings/members` tab, and its own deep-linkable
 * section of `/settings/workspace/people`.
 *
 * The component renders the policy and nothing about who may see it. The reads and writes behind it
 * require `WORKSPACE_SETTINGS`, which the backend enforces on every call; each home decides whether
 * to render this at all, so the consolidated page can explain a refusal in place while the legacy
 * tab keeps the admin gate it already sat behind.
 */
export default function AllowedDomainsPanel() {
    const t = useTranslations("WorkspaceMembers");
    const showApiError = useApiErrorToast("WorkspaceMembers");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const { activeWorkspaceId: workspaceId } = useWorkspace();
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const [allowedDomains, setAllowedDomains] = useState<string[]>([]);
    const [domainInput, setDomainInput] = useState("");
    const [addingDomain, setAddingDomain] = useState(false);
    const [busyDomain, setBusyDomain] = useState<string | null>(null);

    useEffect(() => {
        if (!workspaceId) return;
        let cancelled = false;
        (async () => {
            try {
                const loaded = await getWorkspaceAllowedDomains(workspaceId);
                if (!cancelled) setAllowedDomains(loaded);
            } catch (err) {
                if (!cancelled) showApiError(err, "loadFailed");
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [workspaceId, showApiError]);

    const addDomain = async () => {
        if (!workspaceId || addingDomain) return;
        resetFieldErrors();
        setAddingDomain(true);
        try {
            const updated = await addWorkspaceAllowedDomain(workspaceId, domainInput.trim());
            setAllowedDomains(updated);
            setDomainInput("");
            toastSuccess(t("domainAdded"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err) && !captureFieldErrors(err)) {
                showApiError(err, "domainAddFailed");
            }
        } finally {
            setAddingDomain(false);
        }
    };

    const removeDomain = async (domain: string) => {
        if (!workspaceId) return;
        setBusyDomain(domain);
        try {
            await removeWorkspaceAllowedDomain(workspaceId, domain);
            setAllowedDomains((prev) => prev.filter((d) => d !== domain));
            toastSuccess(t("domainRemoved"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "domainRemoveFailed");
            }
        } finally {
            setBusyDomain(null);
        }
    };

    return (
        <div className="space-y-5">
            <p className="max-w-prose text-sm text-muted-foreground">{t("domainsSubtitle")}</p>
            <form
                onSubmit={(e) => {
                    e.preventDefault();
                    addDomain();
                }}
                className="flex flex-col gap-3 sm:flex-row sm:items-start"
            >
                <div className="flex-1">
                    <InputGroup>
                        <InputGroupAddon>
                            <AtSymbolIcon />
                        </InputGroupAddon>
                        <InputGroupInput
                            value={domainInput}
                            onChange={(e) => {
                                setDomainInput(e.target.value);
                                clearError("domain");
                            }}
                            placeholder={t("domainPlaceholder")}
                            aria-label={t("domainLabel")}
                            aria-invalid={Boolean(fieldErrors.domain)}
                        />
                    </InputGroup>
                    {fieldErrors.domain && (
                        <p className="mt-1.5 text-sm text-destructive">{fieldErrors.domain}</p>
                    )}
                </div>
                <Button
                    type="submit"
                    variant="brand"
                    disabled={addingDomain || domainInput.trim().length === 0}
                    className="min-w-28"
                >
                    {addingDomain ? <Loader2Icon className="size-4 animate-spin" /> : t("addDomain")}
                </Button>
            </form>

            <div className="space-y-2">
                <TabListHeading title={t("domainsTitle")} count={allowedDomains.length} />
                {allowedDomains.length === 0 ? (
                    <EmptyRow>{t("domainsEmpty")}</EmptyRow>
                ) : (
                    <ListCard>
                        {allowedDomains.map((domain) => {
                            const busy = busyDomain === domain;
                            return (
                                <li key={domain} className="group flex items-center gap-3 px-4 py-3">
                                    <span
                                        aria-hidden
                                        className="grid size-8 shrink-0 place-items-center rounded-full bg-muted text-muted-foreground"
                                    >
                                        <GlobeAltIcon className="size-4" />
                                    </span>
                                    <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">
                                        {domain}
                                    </span>
                                    {busy ? (
                                        <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
                                    ) : (
                                        <DropdownMenu>
                                            <DropdownMenuTrigger asChild>
                                                <button
                                                    type="button"
                                                    aria-label={t("removeDomain")}
                                                    className={rowActionTrigger}
                                                >
                                                    <EllipsisHorizontalIcon className="size-5" />
                                                </button>
                                            </DropdownMenuTrigger>
                                            <DropdownMenuContent align="end" className="w-44">
                                                <DropdownMenuItem
                                                    variant="destructive"
                                                    onSelect={() => removeDomain(domain)}
                                                >
                                                    <TrashIcon className="size-4" />
                                                    {t("removeDomain")}
                                                </DropdownMenuItem>
                                            </DropdownMenuContent>
                                        </DropdownMenu>
                                    )}
                                </li>
                            );
                        })}
                    </ListCard>
                )}
            </div>
        </div>
    );
}
