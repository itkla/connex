"use client";

import { BellSnoozeIcon } from "@heroicons/react/24/outline";
import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

const OPTIONS = [
    { hours: 24, key: "snooze1Day" },
    { hours: 72, key: "snooze3Days" },
    { hours: 168, key: "snooze1Week" },
] as const;

/**
 * Snooze-duration menu for a notification row. Invokes {@link onSnooze} with the chosen hours.
 */
export function SnoozeMenu({ onSnooze }: { onSnooze: (hours: number) => void }) {
    const t = useTranslations("Notifications");
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon-sm" aria-label={t("snooze")}>
                    <BellSnoozeIcon />
                </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
                {OPTIONS.map((option) => (
                    <DropdownMenuItem key={option.hours} onSelect={() => onSnooze(option.hours)}>
                        {t(option.key)}
                    </DropdownMenuItem>
                ))}
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
