"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { motion, useReducedMotion } from "motion/react";
import { ArrowRightIcon } from "@heroicons/react/20/solid";
import { Loader2Icon } from "lucide-react";

import {
    createWorkspace,
    logout,
    WorkspaceSelectionUnavailableError,
} from "@/app/lib/api";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { useSelfOwnershipOffers } from "@/app/hooks/useSelfOwnershipOffers";
import { parseInviteInput } from "@/app/lib/inviteInput";
import { toastError, toastSuccess } from "@/app/lib/toast";
import AuthBrandPanel from "@/app/components/auth/AuthBrandPanel";
import OwnershipConfinementDialog from "@/app/components/account/OwnershipConfinementDialog";
import { Button } from "@/components/ui/button";

const EASE = [0.23, 1, 0.32, 1] as const;

export default function OnboardingForm() {
    const t = useTranslations("Onboarding");
    const tOffers = useTranslations("AccountInvites");
    const format = useFormatter();
    const showApiError = useApiErrorToast("Onboarding");
    const ownershipOffers = useSelfOwnershipOffers();
    const router = useRouter();
    const reduce = useReducedMotion();
    const [name, setName] = useState("");
    const [submitting, setSubmitting] = useState(false);
    const [joinValue, setJoinValue] = useState("");

    const enter = reduce
        ? { initial: false as const }
        : {
            initial: { opacity: 0, y: 8 },
            animate: { opacity: 1, y: 0 },
            transition: { duration: 0.45, ease: EASE, delay: 0.05 },
        };

    async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
        e.preventDefault();
        const trimmed = name.trim();
        if (!trimmed) {
            toastError(t("nameRequired"));
            return;
        }
        setSubmitting(true);
        try {
            await createWorkspace(trimmed);
            toastSuccess(t("created"));
            window.location.replace("/dashboard");
        } catch (err) {
            if (err instanceof WorkspaceSelectionUnavailableError) {
                window.location.replace("/dashboard");
                return;
            }
            showApiError(err, "createFailed");
            setSubmitting(false);
        }
    }

    function handleJoin(e: React.FormEvent<HTMLFormElement>) {
        e.preventDefault();
        if (!joinValue.trim()) return;
        const parsed = parseInviteInput(joinValue);
        if (!parsed) {
            toastError(t("joinInvalid"));
            return;
        }
        router.push(parsed.href);
    }

    async function handleSignOut() {
        try {
            await logout();
        } catch {
            /* ignore: navigate to login regardless */
        }
        router.replace("/auth/login");
        router.refresh();
    }

    return (
        <div className="min-h-dvh w-full bg-white lg:grid lg:h-dvh lg:grid-cols-[1fr_1.05fr] lg:overflow-hidden">
            <div className="relative flex min-h-dvh flex-col px-6 py-10 sm:px-10 lg:h-full lg:min-h-0 lg:overflow-y-auto lg:px-14 lg:py-12">
                <button
                    type="button"
                    onClick={handleSignOut}
                    className="absolute right-6 top-6 rounded-md px-2 py-1 text-sm text-neutral-400 transition-colors hover:text-neutral-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                >
                    {t("signOut")}
                </button>

                <div className="flex flex-1 items-center justify-center">
                    <motion.div {...enter} className="w-full max-w-[400px]">
                        <h1 className="font-heading text-[clamp(1.9rem,3.5vw,2.5rem)] font-extrabold leading-[1.1] tracking-[-0.02em] text-balance text-neutral-900">
                            {t("title")}
                        </h1>
                        <p className="mt-3 text-base leading-relaxed text-neutral-500 text-pretty">
                            {t("subtitle")}
                        </p>

                        <form onSubmit={onSubmit} className="mt-8 space-y-3" noValidate>
                            <label className="block">
                                <span className="mb-1.5 block text-sm font-medium text-neutral-700">
                                    {t("nameLabel")}
                                </span>
                                <input
                                    type="text"
                                    value={name}
                                    onChange={(e) => setName(e.target.value)}
                                    placeholder={t("namePlaceholder")}
                                    autoFocus
                                    maxLength={128}
                                    className="h-12 w-full rounded-xl bg-neutral-100 px-4 text-neutral-900 ring-1 ring-black/5 transition focus:bg-white focus:outline-none focus:ring-2 focus:ring-brand"
                                />
                            </label>
                            <button
                                type="submit"
                                disabled={submitting}
                                className="flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-brand-foreground transition hover:bg-brand-hover focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2 disabled:opacity-60"
                            >
                                {submitting ? t("creating") : t("createButton")}
                                {!submitting && <ArrowRightIcon className="size-4" />}
                            </button>
                        </form>

                        <div className="mt-8 border-t border-neutral-200 pt-6">
                            <span className="block text-sm font-medium text-neutral-700">
                                {t("joinTitle")}
                            </span>
                            <form onSubmit={handleJoin} className="mt-2 flex gap-2">
                                <input
                                    type="text"
                                    value={joinValue}
                                    onChange={(e) => setJoinValue(e.target.value)}
                                    placeholder={t("joinPlaceholder")}
                                    className="h-11 flex-1 rounded-xl bg-neutral-100 px-4 text-sm text-neutral-900 ring-1 ring-black/5 transition focus:bg-white focus:outline-none focus:ring-2 focus:ring-brand"
                                />
                                <button
                                    type="submit"
                                    className="h-11 shrink-0 rounded-xl px-4 text-sm font-semibold text-neutral-700 ring-1 ring-neutral-200 transition hover:bg-neutral-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                                >
                                    {t("joinButton")}
                                </button>
                            </form>
                        </div>

                        {ownershipOffers.offers.length > 0 && (
                            <section aria-labelledby="onboarding-ownership-offers" className="mt-8 border-t border-neutral-200 pt-6">
                                <h2 id="onboarding-ownership-offers" className="text-sm font-medium text-neutral-700">
                                    {tOffers("ownershipTitle")}
                                </h2>
                                <p className="mt-1 text-sm text-neutral-500 text-pretty">{tOffers("ownershipSubtitle")}</p>
                                <ul className="mt-3 space-y-2">
                                    {ownershipOffers.offers.map((offer) => {
                                        const busy = ownershipOffers.busyOfferId === offer.id;
                                        const scope = ownershipOffers.scopeLabel(offer);
                                        const expires = format.dateTime(new Date(offer.expiresAt), { dateStyle: "medium" });
                                        return (
                                            <li
                                                key={offer.id}
                                                className="flex flex-wrap items-center gap-3 rounded-xl bg-neutral-100 px-4 py-3 ring-1 ring-black/5"
                                            >
                                                <div className="min-w-0 flex-1">
                                                    <p className="truncate text-sm font-medium text-neutral-900">
                                                        {tOffers("ownershipOfferTitle", { scope })}
                                                    </p>
                                                    <p className="truncate text-xs text-neutral-500">
                                                        {offer.offerorDisplayName
                                                            ? tOffers("ownershipOfferMeta", { name: offer.offerorDisplayName, date: expires })
                                                            : tOffers("ownershipOfferExpires", { date: expires })}
                                                    </p>
                                                </div>
                                                <Button
                                                    size="toolbar"
                                                    variant="outline"
                                                    disabled={busy}
                                                    onClick={() => ownershipOffers.decline(offer)}
                                                >
                                                    {tOffers("decline")}
                                                </Button>
                                                <Button
                                                    size="toolbar"
                                                    variant="brand"
                                                    disabled={busy}
                                                    onClick={() => ownershipOffers.accept(offer)}
                                                >
                                                    {busy ? <Loader2Icon className="size-4 animate-spin" /> : tOffers("accept")}
                                                </Button>
                                            </li>
                                        );
                                    })}
                                </ul>
                            </section>
                        )}
                    </motion.div>
                </div>
            </div>

            <AuthBrandPanel className="hidden lg:block lg:h-full" />
            <OwnershipConfinementDialog offers={ownershipOffers} />
        </div>
    );
}
