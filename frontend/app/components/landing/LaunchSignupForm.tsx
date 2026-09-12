"use client";

import Link from "next/link";
import { useRef, useState, useSyncExternalStore, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import { ArrowRightIcon, CheckCircleIcon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { subscribeToLaunch } from "@/app/lib/api";

const subscribeHydration = () => () => undefined;
const clientSnapshot = () => true;
const serverSnapshot = () => false;
type Status = "idle" | "pending" | "success" | "invalid" | "error" | "rateLimited";

/** Collects launch notification consent without creating a CRM account. */
export function LaunchSignupForm({ id }: { id: string }) {
    const t = useTranslations("CommonHome.prelaunch");
    const hydrated = useSyncExternalStore(subscribeHydration, clientSnapshot, serverSnapshot);
    const [status, setStatus] = useState<Status>("idle");
    const submitting = useRef(false);
    const failed = status === "invalid" || status === "error" || status === "rateLimited";

    async function submit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (submitting.current) return;
        const form = event.currentTarget;
        const data = new FormData(form);
        const email = data.get("email");
        const website = data.get("website");
        if (typeof email !== "string" || typeof website !== "string" || !form.reportValidity()) return;
        submitting.current = true;
        setStatus("pending");
        try {
            const result = await subscribeToLaunch(email.trim(), website);
            setStatus(result);
        } catch {
            setStatus("error");
        } finally {
            submitting.current = false;
        }
    }

    return (
        <div id={id} className="w-full max-w-xl scroll-mt-24" data-launch-signup>
            <div role="status" aria-live="polite" aria-atomic="true">
                {status === "success" && <div className="flex items-start gap-3 rounded-xl border border-brand/40 bg-background/90 p-5">
                    <CheckCircleIcon aria-hidden="true" className="mt-0.5 size-6 shrink-0 text-brand-dark dark:text-brand" />
                    <div><p className="text-lg font-semibold">{t("successTitle")}</p><p className="mt-1 text-base leading-relaxed text-muted-foreground">{t("successBody")}</p></div>
                </div>}
            </div>
            {status !== "success" && <form onSubmit={submit} aria-label={t("formLabel")} aria-busy={status === "pending"}>
                <fieldset disabled={!hydrated || status === "pending"}>
                    <label htmlFor={`${id}-email`} className="sr-only">{t("emailLabel")}</label>
                    <div className="flex flex-col gap-3 sm:flex-row">
                        <Input id={`${id}-email`} name="email" type="email" autoComplete="email" inputMode="email" required maxLength={254} placeholder={t("emailPlaceholder")} aria-invalid={status === "invalid" || undefined} aria-describedby={`${id}-privacy${failed ? ` ${id}-error` : ""}`} className="h-auto min-h-12 rounded-xl bg-background px-4 text-base shadow-none sm:flex-1 md:text-base" />
                        <Button type="submit" variant="brand" size="page" className="h-auto min-h-12 whitespace-normal px-6 py-2 text-base">
                            {t(status === "pending" ? "submitting" : "submit")}<ArrowRightIcon aria-hidden="true" className="size-4" />
                        </Button>
                    </div>
                    <div hidden aria-hidden="true"><input name="website" type="text" tabIndex={-1} autoComplete="off" /></div>
                </fieldset>
                <p id={`${id}-privacy`} className="mt-3 text-sm leading-relaxed text-foreground/80">{t.rich("privacy", { link: (chunks) => <Link href="/privacy" className="text-foreground underline underline-offset-4">{chunks}</Link> })}</p>
                {failed && <p id={`${id}-error`} role="alert" className="mt-3 text-sm leading-relaxed text-destructive">{t(status)}</p>}
                <noscript><p className="mt-3 text-sm leading-relaxed">{t("noJavaScript")}</p></noscript>
            </form>}
        </div>
    );
}
