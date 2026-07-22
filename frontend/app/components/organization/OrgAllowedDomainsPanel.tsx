"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import { AtSymbolIcon, EllipsisHorizontalIcon, GlobeAltIcon, TrashIcon } from "@heroicons/react/24/outline";

import { addOrgAllowedDomain, getOrgAllowedDomains, removeOrgAllowedDomain } from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { useFieldErrors } from "@/app/hooks/useFieldErrors";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { ApiError } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import Rise from "@/app/components/motion/Rise";
import { NoAccessCard, EmptyRow, ListCard, rowActionTrigger } from "@/app/components/organization/OrgPrimitives";

export default function OrgAllowedDomainsPanel() {
    const t = useTranslations("OrgDomains");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const { activeWorkspace } = useWorkspace();
    const orgId = activeWorkspace?.orgId ?? null;
    const { fieldErrors, setFieldErrors, clearError } = useFieldErrors();

    const [domains, setDomains] = useState<string[]>([]);
    const [loading, setLoading] = useState(true);
    const [accessDenied, setAccessDenied] = useState(false);
    const [loadError, setLoadError] = useState(false);
    const [input, setInput] = useState("");
    const [adding, setAdding] = useState(false);
    const [busy, setBusy] = useState<string | null>(null);

    useEffect(() => {
        if (!orgId) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            try {
                const loaded = await getOrgAllowedDomains(orgId);
                if (!cancelled) setDomains(loaded);
            } catch (err) {
                if (cancelled) return;
                if (err instanceof ApiError && err.status === 403) setAccessDenied(true);
                else setLoadError(true);
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [orgId]);

    async function addDomain() {
        const value = input.trim();
        if (!orgId || value.length === 0) return;
        setAdding(true);
        try {
            const updated = await addOrgAllowedDomain(orgId, value);
            setDomains(updated);
            setInput("");
            toastSuccess(t("addedToast"));
        } catch (err) {
            if (err instanceof ApiError && err.fieldErrors) setFieldErrors(err.fieldErrors);
            else if (!handlePasskeyStepUpError(err)) toastError(err instanceof Error ? err.message : String(err));
        } finally {
            setAdding(false);
        }
    }

    async function removeDomain(domain: string) {
        if (!orgId) return;
        setBusy(domain);
        try {
            await removeOrgAllowedDomain(orgId, domain);
            setDomains((prev) => prev.filter((d) => d !== domain));
            toastSuccess(t("removedToast"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                toastError(err instanceof Error ? err.message : String(err));
            }
        } finally {
            setBusy(null);
        }
    }

    if (accessDenied) return <NoAccessCard />;

    return (
        <Rise className="space-y-4">
            <div>
                <SectionHeader title={t("title")} />
                <p className="px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
            </div>

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
                            value={input}
                            onChange={(e) => {
                                setInput(e.target.value);
                                clearError("domain");
                            }}
                            placeholder={t("placeholder")}
                            aria-label={t("label")}
                            aria-invalid={Boolean(fieldErrors.domain)}
                        />
                    </InputGroup>
                    {fieldErrors.domain && <p className="mt-1.5 text-sm text-destructive">{fieldErrors.domain}</p>}
                </div>
                <Button
                    type="submit"
                    variant="brand"
                    disabled={adding || input.trim().length === 0}
                    className="min-w-28"
                >
                    {adding ? <Loader2Icon className="size-4 animate-spin" /> : t("addButton")}
                </Button>
            </form>

            {loading ? (
                <ListCard>
                    {[0, 1].map((i) => (
                        <li key={i} className="flex items-center gap-3 px-4 py-3">
                            <span className="size-8 shrink-0 animate-pulse rounded-full bg-muted" />
                            <span className="h-4 w-40 animate-pulse rounded bg-muted" />
                        </li>
                    ))}
                </ListCard>
            ) : loadError ? (
                <EmptyRow>{t("loadError")}</EmptyRow>
            ) : domains.length === 0 ? (
                <EmptyRow>{t("empty")}</EmptyRow>
            ) : (
                <ListCard>
                    {domains.map((domain) => {
                        const removing = busy === domain;
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
                                {removing ? (
                                    <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
                                ) : (
                                    <DropdownMenu>
                                        <DropdownMenuTrigger asChild>
                                            <button type="button" aria-label={t("removeDomain")} className={rowActionTrigger}>
                                                <EllipsisHorizontalIcon className="size-5" />
                                            </button>
                                        </DropdownMenuTrigger>
                                        <DropdownMenuContent align="end" className="w-44">
                                            <DropdownMenuItem variant="destructive" onSelect={() => removeDomain(domain)}>
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
        </Rise>
    );
}
