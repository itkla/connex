"use client";

import { useTranslations } from "next-intl";
import { SparklesIcon } from "@heroicons/react/24/outline";

import {
    DropdownMenu,
    DropdownMenuCheckboxItem,
    DropdownMenuContent,
    DropdownMenuLabel,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import type { SegmentSelection } from "@/app/lib/types";

const COMPANY_SEGMENT_KEYS = ["warm_intro_available", "open_deal", "cooling", "no_activity"] as const;

/**
 * Picker for graph-aware smart segments on a records list. Toggling predicates (combined with AND)
 * drives the parent, which evaluates the active set server-side and intersects the matching ids
 * with its displayed list.
 */
export default function SegmentPicker({
    segments,
    onChange,
}: {
    segments: SegmentSelection[];
    onChange: (segments: SegmentSelection[]) => void;
}) {
    const t = useTranslations("SmartSegments");
    const activeKeys = new Set(segments.map((segment) => segment.key));

    const toggle = (key: string) => {
        if (activeKeys.has(key)) {
            onChange(segments.filter((segment) => segment.key !== key));
        } else {
            onChange([...segments, { key }]);
        }
    };

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" className="gap-1.5">
                    <SparklesIcon className="size-4" />
                    {t("title")}
                    {segments.length > 0 && (
                        <span className="flex size-5 items-center justify-center rounded-full bg-brand text-xs font-semibold text-white">
                            {segments.length}
                        </span>
                    )}
                </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-80">
                <DropdownMenuLabel>{t("title")}</DropdownMenuLabel>
                {COMPANY_SEGMENT_KEYS.map((key) => (
                    <DropdownMenuCheckboxItem
                        key={key}
                        checked={activeKeys.has(key)}
                        onCheckedChange={() => toggle(key)}
                        onSelect={(event) => event.preventDefault()}
                        className="items-start gap-2 py-2"
                    >
                        <span className="flex flex-col gap-0.5">
                            <span className="text-sm font-medium text-foreground">{t(`${key}.label`)}</span>
                            <span className="text-xs text-muted-foreground">{t(`${key}.description`)}</span>
                        </span>
                    </DropdownMenuCheckboxItem>
                ))}
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
