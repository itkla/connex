import { ConfirmEmailForm } from "@/app/auth/confirm-email/ConfirmEmailForm";

export default async function ConfirmEmailPage({
    searchParams,
}: {
    searchParams: Promise<{ token?: string }>;
}) {
    const { token } = await searchParams;
    return <ConfirmEmailForm token={token ?? null} />;
}
