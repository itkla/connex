import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";

export const metadata: Metadata = { robots: { index: false, follow: false } };

export default async function AuthPage({
    params,
}: {
    params: Promise<{ action: string }>;
}) {
    const { action } = await params;

    if (action === "signup") {
        redirect("/auth/register");
    }

    notFound();
}
