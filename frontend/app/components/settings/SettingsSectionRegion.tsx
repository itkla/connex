"use client";

import { useTranslations } from "next-intl";

import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import type { PermissionCheck } from "@/app/lib/permissionState";
import { cn } from "@/lib/utils";

/**
 * One addressable region of a consolidated settings destination: the anchor a deep link resolves
 * against, and the arrival mark a reader who followed one needs in order to see which section
 * answered them.
 *
 * The mark is a background wash rather than an outline. An outline on a settings section reads as a
 * validation state; a wash that recedes reads as "here", which is all it has to say. It fades on
 * the expressive token rather than a timing of its own, so an arrival settles at the same speed as
 * everything else the page does, and disappears entirely under reduced motion.
 *
 * @param section - the slug this region is addressed by
 * @param arrived - the section the current fragment arrived at, from `useSectionArrival`
 * @param register - the ref registrar from the same hook
 */
export function SettingsSectionRegion({
    section,
    arrived,
    register,
    children,
}: {
    section: string;
    arrived: string | null;
    register: (section: string) => (element: HTMLElement | null) => void;
    children: React.ReactNode;
}) {
    return (
        <div
            id={section}
            ref={register(section)}
            tabIndex={-1}
            data-arrived={arrived === section ? "" : undefined}
            className={cn(
                "-mx-3 scroll-mt-24 rounded-2xl px-3 py-3 outline-none transition-colors duration-(--motion-expressive) ease-calm motion-reduce:transition-none",
                arrived === section ? "bg-muted/50" : "bg-transparent",
            )}
        >
            {children}
        </div>
    );
}

/**
 * The refusal posture for a section whose read the backend gates.
 *
 * #1340's rule is that a managed destination never vanishes and never teleports: a member who
 * cannot read a section still sees that the job exists here and is told who can change that, rather
 * than finding a page with a hole in it. A failed permission lookup is kept apart from a refusal,
 * because "we could not check" and "you may not" are different things to be told.
 *
 * The unresolved case says what actually failed. The shared notice's own retry copy is about
 * checking whether a *feature* is available, which is the state its first consumers were in; here
 * the lookup that failed is the viewer's permissions, so the caller passes copy naming that.
 *
 * @param check - the section's permission check, already known not to be granted
 * @param retryTitle - what to call the failure when the permission lookup itself did not resolve
 * @param retryBody - the explanation for that same case
 */
export function SectionRefusal({
    check,
    retryTitle,
    retryBody,
}: {
    check: Exclude<PermissionCheck, "granted">;
    retryTitle: string;
    retryBody: string;
}) {
    if (check === "denied") return <SettingsAvailabilityNotice variant="inline" state="ask-admin" />;
    return (
        <SettingsAvailabilityNotice
            variant="inline"
            state="retry"
            title={retryTitle}
            body={retryBody}
        />
    );
}

/**
 * The posture for a job this destination is named for but cannot yet do.
 *
 * #1340 gives three jobs a canonical home before anything exists to put in it — a consolidation
 * that quietly dropped them would leave the epic's "one destination per job" claim false. So the
 * section is real, addressable, and searchable, and says plainly that the job is not available
 * here yet, alongside the surface that does the nearest thing today.
 *
 * Deliberately not a {@link SettingsAvailabilityState}. `not-enabled` would say an operator could
 * turn this on and `managed` would say someone else already runs it; both are false, and a settings
 * page that misreports why it is empty is worse than one that admits the gap. The action is the
 * only honest thing on offer: the surface that exists.
 *
 * @param body - what belongs here, and what to do in the meantime
 * @param action - the link to the surface that serves the nearest shipped job
 */
export function SectionNotYetAvailable({
    body,
    action,
}: {
    body: string;
    action: React.ReactNode;
}) {
    const t = useTranslations("SettingsGap");
    return (
        <div className="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-border bg-card px-6 py-12 text-center">
            <p className="text-sm font-semibold text-foreground">{t("title")}</p>
            <p className="max-w-sm text-sm text-pretty text-muted-foreground">{body}</p>
            <div className="mt-1">{action}</div>
        </div>
    );
}
