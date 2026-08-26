"use client";

import { useCallback, useEffect, useMemo, useRef, useState, useTransition } from "react";
import { useTranslations } from "next-intl";
import { useReducedMotion } from "motion/react";
import {
    ArchiveBoxIcon,
    ArrowPathIcon,
    ArrowUturnLeftIcon,
    LockClosedIcon,
    PencilSquareIcon,
    PlusIcon,
} from "@heroicons/react/24/outline";

import AccessDenied from "@/app/components/AccessDenied";
import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import Rise from "@/app/components/motion/Rise";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import QualificationCriteriaSkeleton from "@/app/components/settings/QualificationCriteriaSkeleton";
import { Switch } from "@/components/ui/switch";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import {
    archiveQualificationCriterion,
    createQualificationCriterion,
    getQualificationCriteria,
    restoreQualificationCriterion,
    updateQualificationCriterion,
} from "@/app/lib/api";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { usePermissionCheck, usePermissionsRefresh } from "@/app/hooks/usePermissions";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { apportionShares } from "@/app/lib/qualificationShares";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type { QualificationCriterion, QualificationDimension } from "@/app/lib/types";
import { cn } from "@/lib/utils";

const DIMENSIONS: QualificationDimension[] = ["FIT", "ENGAGEMENT"];
const DRAFT_ROW_ID = -1;

/** Narrows a select value to the closed dimension vocabulary instead of asserting it. */
function asDimension(value: string): QualificationDimension | null {
    return DIMENSIONS.find((dimension) => dimension === value) ?? null;
}

/** Reads the catalog without touching component state, so effects stay free of direct setState. */
async function readCriteria(): Promise<{ criteria?: QualificationCriterion[]; denied?: boolean }> {
    try {
        return { criteria: await getQualificationCriteria(true) };
    } catch (err) {
        if (err instanceof Error && "status" in err && (err as { status?: number }).status === 403) {
            return { denied: true };
        }
        return {};
    }
}
const DEFAULT_WEIGHT = 10;

type Draft = {
    id: number | null;
    dimension: QualificationDimension;
    label: string;
    weight: number;
    required: boolean;
    position: number;
};

/**
 * Workspace configuration for what "qualified" means (issue #559).
 *
 * <p>Weights are relative within a dimension, so a bare number tells a user nothing. Each dimension
 * carries a meter showing how its whole is divided and each row shows its resulting share; both
 * update live while a weight is typed, including the other rows' shares, because adding a criterion
 * changes everyone's denominator. Editing happens inline rather than in a dialog precisely so that
 * feedback stays visible while it is being caused.
 *
 * <p>Required criteria are not rendered as one more attribute beside weight: they block the move to
 * QUALIFIED, which is a different kind of fact, so they lead the row and say so in words.
 */
export default function QualificationCriteriaPanel() {
    const t = useTranslations("WorkspaceQualification");
    const showApiError = useApiErrorToast("WorkspaceQualification");
    const { activeWorkspaceId } = useWorkspace();
    const manageCheck = usePermissionCheck("WORKSPACE_SETTINGS");
    const canManage = manageCheck === "granted";
    const reduce = useReducedMotion() ?? false;

    const [criteria, setCriteria] = useState<QualificationCriterion[]>([]);
    const [loading, setLoading] = useState(true);
    const [accessDenied, setAccessDenied] = useState(false);
    const [draft, setDraft] = useState<Draft | null>(null);
    const [saving, setSaving] = useState(false);
    const [settled, setSettled] = useState<number | null>(null);
    const [showArchived, setShowArchived] = useState(false);
    const labelRef = useRef<HTMLInputElement | null>(null);

    const load = useCallback(async () => {
        const loaded = await readCriteria();
        if (loaded.denied) {
            setAccessDenied(true);
        } else if (loaded.criteria) {
            setCriteria(loaded.criteria);
            setAccessDenied(false);
        } else {
            toastError(t("loadFailed"));
        }
        setLoading(false);
    }, [t]);

    useEffect(() => {
        if (!activeWorkspaceId) return;
        let cancelled = false;
        (async () => {
            const loaded = await readCriteria();
            if (cancelled) return;
            if (loaded.denied) {
                setAccessDenied(true);
            } else if (loaded.criteria) {
                setCriteria(loaded.criteria);
                setAccessDenied(false);
            } else {
                toastError(t("loadFailed"));
            }
            setLoading(false);
        })();
        return () => {
            cancelled = true;
        };
    }, [activeWorkspaceId, t]);

    useEffect(() => {
        if (draft) labelRef.current?.focus();
    }, [draft]);

    const active = useMemo(
        () => criteria.filter((criterion) => !criterion.archivedAt),
        [criteria],
    );
    const archived = useMemo(
        () => criteria.filter((criterion) => criterion.archivedAt),
        [criteria],
    );

    /**
     * Rows for one dimension in display order — required first, because a criterion that blocks
     * qualification is the first thing a reader needs — with the draft folded in so its weight
     * participates in the shares while it is being typed.
     */
    const rowsFor = useCallback((dimension: QualificationDimension) => {
        const edited = active.map((criterion) =>
            draft && draft.id === criterion.id
                ? {
                    ...criterion,
                    label: draft.label,
                    dimension: draft.dimension,
                    weight: draft.weight,
                    required: draft.required,
                }
                : criterion);
        const existing = edited.filter((criterion) => criterion.dimension === dimension);
        const pending = draft && draft.dimension === dimension && draft.id === null
            ? [{
                id: DRAFT_ROW_ID,
                workspaceId: 0,
                label: draft.label,
                dimension,
                weight: draft.weight,
                required: draft.required,
                position: Number.MAX_SAFE_INTEGER,
            } satisfies QualificationCriterion]
            : [];
        const ordered = [...existing, ...pending].sort((a, b) => {
            if (a.required !== b.required) return a.required ? -1 : 1;
            if (a.position !== b.position) return a.position - b.position;
            return a.id - b.id;
        });
        const shares = apportionShares(ordered.map((criterion) => criterion.weight));
        return ordered.map((criterion, index) => ({ criterion, share: shares[index] ?? 0 }));
    }, [active, draft]);

    const commit = async () => {
        if (!draft || !draft.label.trim()) return;
        setSaving(true);
        try {
            const payload = {
                label: draft.label.trim(),
                dimension: draft.dimension,
                weight: draft.weight,
                required: draft.required,
                position: draft.position,
            };
            const saved = draft.id === null
                ? await createQualificationCriterion(payload)
                : await updateQualificationCriterion(draft.id, payload);
            setDraft(null);
            await load();
            setSettled(saved.id);
            window.setTimeout(() => setSettled(null), 900);
            toastSuccess(t("saved"));
        } catch (err) {
            showApiError(err, "saveFailed");
        } finally {
            setSaving(false);
        }
    };

    const archive = async (criterion: QualificationCriterion) => {
        try {
            await archiveQualificationCriterion(criterion.id);
            await load();
            toastSuccess(t("archived"));
        } catch (err) {
            showApiError(err, "archiveFailed");
        }
    };

    const restore = async (criterion: QualificationCriterion) => {
        try {
            await restoreQualificationCriterion(criterion.id);
            await load();
            toastSuccess(t("restored"));
        } catch (err) {
            showApiError(err, "restoreFailed");
        }
    };

    if (manageCheck === "unavailable") return <PermissionsUnavailableSection />;
    if (manageCheck === "denied" || accessDenied) {
        return <AccessDenied variant="inline" body={t("noAccess")} />;
    }

    if (loading) {
        return (
            <SettingsSection title={t("title")} description={t("description")}>
                <QualificationCriteriaSkeleton />
            </SettingsSection>
        );
    }

    const isFirstRun = active.length === 0 && archived.length === 0 && !draft;

    return (
        <SettingsSection
            title={t("title")}
            description={t("description")}
            action={canManage && !isFirstRun ? (
                <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setDraft({
                        id: null,
                        dimension: "FIT",
                        label: "",
                        weight: DEFAULT_WEIGHT,
                        required: false,
                        position: 0,
                    })}
                >
                    <PlusIcon className="size-4" />
                    {t("add")}
                </Button>
            ) : undefined}
        >
            {isFirstRun ? (
                <div className="rounded-2xl border border-border bg-card px-6 py-8">
                    <h3 className="text-sm font-semibold text-foreground">{t("emptyTitle")}</h3>
                    <dl className="mt-4 max-w-prose space-y-3 text-sm">
                        <div>
                            <dt className="font-medium text-foreground">{t("dimension.FIT")}</dt>
                            <dd className="text-muted-foreground">{t("emptyFit")}</dd>
                        </div>
                        <div>
                            <dt className="font-medium text-foreground">{t("dimension.ENGAGEMENT")}</dt>
                            <dd className="text-muted-foreground">{t("emptyEngagement")}</dd>
                        </div>
                        <div>
                            <dt className="font-medium text-foreground">{t("emptyRequiredTitle")}</dt>
                            <dd className="text-muted-foreground">{t("emptyRequired")}</dd>
                        </div>
                    </dl>
                    {canManage ? (
                        <Button
                            size="sm"
                            className="mt-6"
                            onClick={() => setDraft({
                                id: null,
                                dimension: "FIT",
                                label: "",
                                weight: DEFAULT_WEIGHT,
                                required: false,
                                position: 0,
                            })}
                        >
                            <PlusIcon className="size-4" />
                            {t("addFirst")}
                        </Button>
                    ) : null}
                </div>
            ) : (
                <div className="space-y-6">
                    {DIMENSIONS.map((dimension) => {
                        const rows = rowsFor(dimension);
                        const blocking = rows.filter((row) => row.criterion.required).length;
                        return (
                            <section key={dimension} className="rounded-2xl border border-border bg-card">
                                <header className="space-y-3 px-6 py-4">
                                    <div className="flex flex-wrap items-baseline justify-between gap-2">
                                        <h3 className="text-sm font-semibold text-foreground">
                                            {t(`dimension.${dimension}`)}
                                        </h3>
                                        <p className="text-xs text-muted-foreground">
                                            {blocking > 0
                                                ? t("blockingCount", { blocking, total: rows.length })
                                                : t("blockingNone")}
                                        </p>
                                    </div>
                                    <div
                                        className="flex h-1.5 gap-px overflow-hidden rounded-full bg-muted"
                                        aria-hidden="true"
                                    >
                                        {rows.map(({ criterion, share }) => (
                                            <span
                                                key={criterion.id}
                                                className={cn(
                                                    "h-full bg-primary/70 first:rounded-l-full last:rounded-r-full",
                                                    criterion.id === -1 && "bg-primary/35",
                                                    !reduce && "transition-[width] duration-200 ease-[cubic-bezier(0.23,1,0.32,1)]",
                                                )}
                                                style={{ width: `${share}%` }}
                                            />
                                        ))}
                                    </div>
                                </header>

                                <ul className="divide-y divide-border border-t border-border">
                                    {rows.length === 0 && !draft ? (
                                        <li className="px-6 py-4 text-xs text-muted-foreground">
                                            {t(`dimensionEmpty.${dimension}`)}
                                        </li>
                                    ) : null}
                                    {rows.filter(({ criterion }) => criterion.id !== DRAFT_ROW_ID).map(({ criterion, share }) => (
                                        draft && draft.id === criterion.id ? (
                                            <li key={criterion.id} className="px-6 py-4">
                                                <DraftFields
                                                    draft={draft}
                                                    saving={saving}
                                                    share={share}
                                                    labelRef={labelRef}
                                                    onChange={setDraft}
                                                    onCommit={commit}
                                                    onCancel={() => setDraft(null)}
                                                />
                                            </li>
                                        ) : (
                                            <Rise key={criterion.id}>
                                                <li
                                                    className={cn(
                                                        "group flex items-center justify-between gap-4 px-6 py-3",
                                                        settled === criterion.id && "bg-primary/5",
                                                        !reduce && "transition-colors duration-500",
                                                    )}
                                                >
                                                    <div className="flex min-w-0 items-start gap-2">
                                                        {criterion.required ? (
                                                            <LockClosedIcon
                                                                className="mt-0.5 size-4 shrink-0 text-foreground"
                                                                aria-hidden="true"
                                                            />
                                                        ) : (
                                                            <span className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                                                        )}
                                                        <div className="min-w-0">
                                                            <p className="truncate text-sm text-foreground">
                                                                {criterion.label}
                                                            </p>
                                                            {criterion.required ? (
                                                                <p className="text-xs text-muted-foreground">
                                                                    {t("blocksQualification")}
                                                                </p>
                                                            ) : null}
                                                        </div>
                                                    </div>
                                                    <div className="flex shrink-0 items-center gap-1">
                                                        <span className="text-xs tabular-nums text-muted-foreground">
                                                            {t("share", {
                                                                share,
                                                                dimension: t(`dimension.${dimension}`),
                                                            })}
                                                        </span>
                                                        {canManage ? (
                                                            <span className="flex items-center gap-1 opacity-100 [@media(hover:hover)_and_(pointer:fine)]:opacity-0 [@media(hover:hover)_and_(pointer:fine)]:group-hover:opacity-100 [@media(hover:hover)_and_(pointer:fine)]:group-focus-within:opacity-100">
                                                                <Button
                                                                    size="icon"
                                                                    variant="ghost"
                                                                    aria-label={t("edit", { label: criterion.label })}
                                                                    onClick={() => setDraft({
                                                                        id: criterion.id,
                                                                        dimension: criterion.dimension,
                                                                        label: criterion.label,
                                                                        weight: criterion.weight,
                                                                        required: criterion.required,
                                                                        position: criterion.position,
                                                                    })}
                                                                >
                                                                    <PencilSquareIcon className="size-4" />
                                                                </Button>
                                                                <Button
                                                                    size="icon"
                                                                    variant="ghost"
                                                                    aria-label={t("archive", { label: criterion.label })}
                                                                    onClick={() => void archive(criterion)}
                                                                >
                                                                    <ArchiveBoxIcon className="size-4" />
                                                                </Button>
                                                            </span>
                                                        ) : null}
                                                    </div>
                                                </li>
                                            </Rise>
                                        )
                                    ))}
                                    {draft && draft.id === null && draft.dimension === dimension ? null : null}
                                </ul>

                                {canManage && draft === null ? (
                                    <div className="border-t border-border px-6 py-3">
                                        <button
                                            type="button"
                                            className="flex items-center gap-2 text-xs text-muted-foreground transition-colors hover:text-foreground"
                                            onClick={() => setDraft({
                                                id: null,
                                                dimension,
                                                label: "",
                                                weight: DEFAULT_WEIGHT,
                                                required: false,
                                                position: 0,
                                            })}
                                        >
                                            <PlusIcon className="size-4" />
                                            {t("addTo", { dimension: t(`dimension.${dimension}`) })}
                                        </button>
                                    </div>
                                ) : null}

                                {draft && draft.id === null && draft.dimension === dimension ? (
                                    <div className="border-t border-border px-6 py-4">
                                        <DraftFields
                                            draft={draft}
                                            saving={saving}
                                            share={rows.find((row) => row.criterion.id === DRAFT_ROW_ID)?.share ?? 0}
                                            labelRef={labelRef}
                                            onChange={setDraft}
                                            onCommit={commit}
                                            onCancel={() => setDraft(null)}
                                        />
                                    </div>
                                ) : null}
                            </section>
                        );
                    })}

                    {archived.length > 0 ? (
                        <details
                            className="rounded-2xl border border-border bg-card px-6 py-4"
                            open={showArchived}
                            onToggle={(event) => setShowArchived((event.target as HTMLDetailsElement).open)}
                        >
                            <summary className="cursor-pointer text-sm text-foreground">
                                {t("archivedCount", { count: archived.length })}
                            </summary>
                            <p className="mt-2 max-w-prose text-xs text-muted-foreground">
                                {t("archivedHint")}
                            </p>
                            <ul className="mt-3 divide-y divide-border">
                                {archived.map((criterion) => (
                                    <li key={criterion.id} className="flex items-center justify-between gap-4 py-2">
                                        <span className="truncate text-sm text-muted-foreground">
                                            {criterion.label}
                                        </span>
                                        {canManage ? (
                                            <Button
                                                size="sm"
                                                variant="ghost"
                                                onClick={() => void restore(criterion)}
                                            >
                                                <ArrowUturnLeftIcon className="size-4" />
                                                {t("restore")}
                                            </Button>
                                        ) : null}
                                    </li>
                                ))}
                            </ul>
                        </details>
                    ) : null}
                </div>
            )}
        </SettingsSection>
    );
}

/**
 * The inline editor. Enter commits from any field and Escape cancels, so the common path never
 * requires reaching for a button; the share reads back live from the same apportionment the list
 * uses, so what is previewed is exactly what will be stored.
 */
function DraftFields({
    draft,
    saving,
    share,
    labelRef,
    onChange,
    onCommit,
    onCancel,
}: {
    draft: Draft;
    saving: boolean;
    share: number;
    labelRef: React.RefObject<HTMLInputElement | null>;
    onChange: (draft: Draft) => void;
    onCommit: () => void;
    onCancel: () => void;
}) {
    const t = useTranslations("WorkspaceQualification");
    return (
        <div
            className="space-y-3"
            onKeyDown={(event) => {
                if (
                    event.key === "Enter"
                    && !event.shiftKey
                    && event.target instanceof HTMLInputElement
                ) {
                    event.preventDefault();
                    onCommit();
                }
                if (event.key === "Escape") {
                    event.preventDefault();
                    onCancel();
                }
            }}
        >
            <div className="flex flex-wrap items-end gap-3">
                <div className="min-w-56 flex-1 space-y-1">
                    <Label htmlFor="qualification-label">{t("labelField")}</Label>
                    <Input
                        id="qualification-label"
                        ref={labelRef}
                        value={draft.label}
                        maxLength={200}
                        placeholder={t("labelPlaceholder")}
                        onChange={(event) => onChange({ ...draft, label: event.target.value })}
                    />
                </div>
                <div className="w-24 space-y-1">
                    <Label htmlFor="qualification-weight">{t("weightField")}</Label>
                    <Input
                        id="qualification-weight"
                        type="number"
                        inputMode="numeric"
                        min={1}
                        max={100}
                        value={draft.weight}
                        onFocus={(event) => event.target.select()}
                        onChange={(event) => onChange({
                            ...draft,
                            weight: Math.min(100, Math.max(1, Number(event.target.value) || 1)),
                        })}
                    />
                </div>
                <div className="w-40 space-y-1">
                    <Label htmlFor="qualification-dimension">{t("dimensionField")}</Label>
                    <Select
                        value={draft.dimension}
                        onValueChange={(value) => {
                            const dimension = asDimension(value);
                            if (dimension) onChange({ ...draft, dimension });
                        }}
                    >
                        <SelectTrigger id="qualification-dimension" size="sm">
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                            {DIMENSIONS.map((dimension) => (
                                <SelectItem key={dimension} value={dimension}>
                                    {t(`dimension.${dimension}`)}
                                </SelectItem>
                            ))}
                        </SelectContent>
                    </Select>
                </div>
            </div>

            <p className="text-xs tabular-nums text-muted-foreground">
                {t("sharePreview", { share, dimension: t(`dimension.${draft.dimension}`) })}
            </p>

            <div className="flex flex-wrap items-center justify-between gap-3">
                <label className="flex items-center gap-2 text-sm text-foreground">
                    <Switch
                        checked={draft.required}
                        onCheckedChange={(checked) => onChange({ ...draft, required: checked })}
                    />
                    {t("requiredField")}
                </label>
                <div className="flex items-center gap-2">
                    <Button size="sm" variant="ghost" onClick={onCancel} disabled={saving}>
                        {t("cancel")}
                    </Button>
                    <Button size="sm" onClick={onCommit} disabled={saving || !draft.label.trim()}>
                        {t("save")}
                    </Button>
                </div>
            </div>
            <p className="text-xs text-muted-foreground">{t("requiredHint")}</p>
        </div>
    );
}

/** Mirrors the other settings panels' inline recovery when effective permissions cannot be read. */
function PermissionsUnavailableSection() {
    const t = useTranslations("PermissionsUnavailable");
    const refreshPermissions = usePermissionsRefresh();
    const [isRetrying, startTransition] = useTransition();

    return (
        <PermissionsUnavailable
            variant="inline"
            title={t("title")}
            body={t("sectionBody")}
            action={
                <Button
                    variant="outline"
                    size="sm"
                    onClick={() => startTransition(async () => { await refreshPermissions(); })}
                    disabled={isRetrying}
                >
                    <ArrowPathIcon
                        data-icon="inline-start"
                        className={isRetrying ? "animate-spin motion-reduce:animate-none" : undefined}
                    />
                    {isRetrying ? t("retrying") : t("retry")}
                </Button>
            }
        />
    );
}
