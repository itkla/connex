import { headers } from "next/headers";
import { redirect } from "next/navigation";
import Link from "next/link";
import { getTranslations } from "next-intl/server";

import { getCurrentUserFromCookie, getInvitePreview } from "@/app/lib/api";
import type { InvitePreview, WorkspaceRole } from "@/app/lib/types";
import AcceptInvite from "@/app/components/invite/AcceptInvite";

function roleKey(role: WorkspaceRole): "roleOwner" | "roleAdmin" | "roleMember" {
    return role === "owner" ? "roleOwner" : role === "admin" ? "roleAdmin" : "roleMember";
}

export default async function InvitePage({ params }: { params: Promise<{ token: string }> }) {
    const { token } = await params;
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect(`/auth/login?redirect=${encodeURIComponent(`/invite/${token}`)}`);
    }

    const t = await getTranslations("InviteAccept");

    let preview: InvitePreview | null = null;
    try {
        preview = await getInvitePreview(token, { headers: { cookie: cookie ?? "" }, cache: "no-store" });
    } catch {
        preview = null;
    }

    const emailMismatch =
        preview != null && preview.email.toLowerCase() !== user.email.toLowerCase();
    const unavailable = preview == null || !preview.valid;

    return (
        <div className="grid min-h-dvh place-items-center bg-background px-6 py-12">
            <div className="w-full max-w-md rounded-2xl border border-border bg-card p-8 shadow-sm">
                {unavailable ? (
                    <div className="text-center">
                        <h1 className="text-xl font-semibold tracking-tight text-foreground">
                            {t("invalidTitle")}
                        </h1>
                        <p className="mt-2 text-sm text-muted-foreground">{t("invalidBody")}</p>
                        <Link
                            href="/dashboard"
                            className="mt-6 inline-block text-sm font-medium text-brand-dark hover:underline"
                        >
                            {t("backToApp")}
                        </Link>
                    </div>
                ) : (
                    <div className="text-center">
                        <span
                            aria-hidden
                            className="mx-auto grid size-12 place-items-center rounded-xl bg-brand-light text-lg font-semibold text-brand-dark"
                        >
                            {preview!.workspaceName.trim().charAt(0).toUpperCase()}
                        </span>
                        <h1 className="mt-4 text-xl font-semibold tracking-tight text-foreground text-balance">
                            {t("heading", { workspace: preview!.workspaceName })}
                        </h1>
                        <p className="mt-2 text-sm text-muted-foreground">
                            {t("roleLine", { role: t(roleKey(preview!.role)) })}
                        </p>
                        {preview!.invitedByLabel && (
                            <p className="mt-1 text-xs text-muted-foreground">
                                {t("invitedBy", { name: preview!.invitedByLabel })}
                            </p>
                        )}

                        {emailMismatch ? (
                            <>
                                <p className="mt-6 rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">
                                    {t("mismatchBody", { email: preview!.email, current: user.email })}
                                </p>
                                <Link
                                    href="/dashboard"
                                    className="mt-4 inline-block text-sm font-medium text-brand-dark hover:underline"
                                >
                                    {t("backToApp")}
                                </Link>
                            </>
                        ) : (
                            <div className="mt-6">
                                <AcceptInvite token={token} />
                            </div>
                        )}
                    </div>
                )}
            </div>
        </div>
    );
}
