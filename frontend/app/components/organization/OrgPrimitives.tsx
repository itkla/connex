"use client";

import { useTranslations } from "next-intl";
import { LockClosedIcon } from "@heroicons/react/24/outline";

/** Shared row-action trigger: a subtle ellipsis button that reveals on row hover. */
export const rowActionTrigger =
    "flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted/70 hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100";

export function ListCard({ children }: { children: React.ReactNode }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">{children}</ul>
    );
}

export function EmptyRow({ children }: { children: React.ReactNode }) {
    return (
        <p className="rounded-2xl border border-border bg-card px-6 py-8 text-center text-sm text-muted-foreground">
            {children}
        </p>
    );
}

/** Shown when the backend refuses (403): the user is not an org administrator. */
export function NoAccessCard() {
    const t = useTranslations("Organization");
    return (
        <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-6 py-12 text-center">
            <span aria-hidden className="grid size-10 place-items-center rounded-full bg-muted text-muted-foreground">
                <LockClosedIcon className="size-5" />
            </span>
            <div className="space-y-1">
                <p className="text-sm font-semibold text-foreground">{t("noAccessTitle")}</p>
                <p className="max-w-sm text-sm text-muted-foreground">{t("noAccessBody")}</p>
            </div>
        </div>
    );
}
