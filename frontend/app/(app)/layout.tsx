import Sidebar from "@/app/components/Sidebar";
import ContentShell from "@/app/components/ContentShell";
import { getCurrentUserFromCookie } from "@/app/lib/api";
import { headers } from "next/headers";
import { redirect } from "next/navigation";

export default async function AppLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {

    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    return (
        <ContentShell
            sidebar={
                <Sidebar
                    user={user}
                    className="w-64 bg-sidebar h-full p-6 rounded-xl border border-sidebar-border shadow-xl"
                />
            }
        >
            {children}
        </ContentShell>
    );
}
