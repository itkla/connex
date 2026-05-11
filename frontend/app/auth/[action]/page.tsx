import { notFound, redirect } from "next/navigation";

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
