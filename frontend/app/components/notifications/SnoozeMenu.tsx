"use client";

import { BellSnoozeIcon } from "@heroicons/react/24/outline";
import { useState } from "react";
import { useTranslations } from "next-intl";

import { type SnoozePreset, type SnoozeRequest } from "@/app/lib/types";
import { Button } from "@/components/ui/button";
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuPortal,
    DropdownMenuSeparator,
    DropdownMenuSub,
    DropdownMenuSubContent,
    DropdownMenuSubTrigger,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

const PRESETS: readonly { preset: SnoozePreset; key: string }[] = [
    { preset: "later_today", key: "snoozeLaterToday" },
    { preset: "tomorrow_morning", key: "snoozeTomorrowMorning" },
    { preset: "next_week", key: "snoozeNextWeek" },
];

const MAX_HORIZON_MS = 30 * 24 * 60 * 60 * 1_000;

/**
 * Resolves the caller's IANA timezone, sent alongside every snooze request so
 * the server can anchor presets and the explicit instant to the user's day.
 */
function resolveTimezone(): string {
    return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/**
 * Formats a {@link Date} as a `datetime-local` input value (`YYYY-MM-DDTHH:mm`)
 * in the browser's local time, matching how the input reads its value back.
 */
function toDatetimeLocal(date: Date): string {
    const pad = (value: number) => String(value).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * Snooze menu for a notification row: three server presets plus a custom
 * date/time. Invokes {@link onSnooze} with the exact request body — a preset or
 * an explicit ISO-UTC instant — each paired with the caller's IANA timezone.
 */
export function SnoozeMenu({
    disabled = false,
    onSnooze,
}: {
    disabled?: boolean;
    onSnooze: (body: SnoozeRequest) => void;
}) {
    const t = useTranslations("Notifications");
    const [dialogOpenedAt, setDialogOpenedAt] = useState<number | null>(null);

    function openCustom() {
        requestAnimationFrame(() => setDialogOpenedAt(Date.now()));
    }

    return (
        <>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon-sm" aria-label={t("snooze")} disabled={disabled}>
                        <BellSnoozeIcon />
                    </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                    <SnoozeOptions onSnooze={onSnooze} onCustom={openCustom} />
                </DropdownMenuContent>
            </DropdownMenu>
            {dialogOpenedAt != null ? (
                <SnoozeCustomDialog
                    key={dialogOpenedAt}
                    open
                    now={dialogOpenedAt}
                    onOpenChange={(open) => {
                        if (!open) setDialogOpenedAt(null);
                    }}
                    onSnooze={onSnooze}
                />
            ) : null}
        </>
    );
}

function SnoozeOptions({
    onSnooze,
    onCustom,
}: {
    onSnooze: (body: SnoozeRequest) => void;
    onCustom: () => void;
}) {
    const t = useTranslations("Notifications");
    return (
        <>
            {PRESETS.map((option) => (
                <DropdownMenuItem
                    key={option.preset}
                    onSelect={() => onSnooze({ preset: option.preset, timezone: resolveTimezone() })}
                >
                    {t(option.key)}
                </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={onCustom}>{t("snoozeCustom")}</DropdownMenuItem>
        </>
    );
}

/** Nested snooze choices for an existing notification-management menu. */
export function SnoozeSubmenu({
    disabled = false,
    onSnooze,
    onCustom,
}: {
    disabled?: boolean;
    onSnooze: (body: SnoozeRequest) => void;
    onCustom: () => void;
}) {
    const t = useTranslations("Notifications");
    return (
        <DropdownMenuSub>
            <DropdownMenuSubTrigger disabled={disabled}>
                <BellSnoozeIcon />
                {t("snooze")}
            </DropdownMenuSubTrigger>
            <DropdownMenuPortal>
                <DropdownMenuSubContent>
                    <SnoozeOptions onSnooze={onSnooze} onCustom={onCustom} />
                </DropdownMenuSubContent>
            </DropdownMenuPortal>
        </DropdownMenuSub>
    );
}

/** Controlled custom-date snooze dialog that survives its originating menu closing. */
export function SnoozeCustomDialog({
    open,
    now,
    onOpenChange,
    onSnooze,
}: {
    open: boolean;
    now: number;
    onOpenChange: (open: boolean) => void;
    onSnooze: (body: SnoozeRequest) => void;
}) {
    const t = useTranslations("Notifications");
    const [customValue, setCustomValue] = useState("");
    const parsed = customValue ? new Date(customValue).getTime() : Number.NaN;
    const isEmpty = customValue.length === 0;
    const isUnparseable = !isEmpty && !Number.isFinite(parsed);
    const isPast = Number.isFinite(parsed) && parsed <= now;
    const isTooFar = Number.isFinite(parsed) && parsed > now + MAX_HORIZON_MS;
    const isInvalid = isEmpty || isUnparseable || isPast || isTooFar;
    const hint = isPast
        ? t("snoozePastHint")
        : isTooFar
            ? t("snoozeMaxHint")
            : t("snoozeCustomHint");

    function confirmCustom() {
        if (isInvalid) return;
        onSnooze({ until: new Date(customValue).toISOString(), timezone: resolveTimezone() });
        onOpenChange(false);
    }

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="sm:max-w-sm">
                <DialogHeader>
                    <DialogTitle>{t("snoozeCustomTitle")}</DialogTitle>
                    <DialogDescription>{t("snoozeCustomDescription")}</DialogDescription>
                </DialogHeader>
                <div className="grid gap-2">
                    <Label htmlFor="snooze-custom-until">{t("snoozeCustomLabel")}</Label>
                    <Input
                        id="snooze-custom-until"
                        type="datetime-local"
                        value={customValue}
                        min={toDatetimeLocal(new Date(now + 60_000))}
                        max={toDatetimeLocal(new Date(now + MAX_HORIZON_MS))}
                        onChange={(event) => setCustomValue(event.target.value)}
                        aria-invalid={!isEmpty && isInvalid}
                    />
                    <p className={isInvalid && !isEmpty ? "text-xs text-destructive" : "text-xs text-muted-foreground"}>
                        {hint}
                    </p>
                </div>
                <DialogFooter>
                    <Button variant="ghost" onClick={() => onOpenChange(false)}>
                        {t("cancel")}
                    </Button>
                    <Button disabled={isInvalid} onClick={confirmCustom}>
                        {t("snooze")}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
