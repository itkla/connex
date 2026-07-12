import Sidebar from "@/app/components/Sidebar";
import ContentShell from "@/app/components/ContentShell";
import { getCurrentUserFromCookie, getMyWorkspacesFromCookie } from "@/app/lib/api";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { NotificationProvider } from "@/app/hooks/useNotifications";
import { WorkspaceProvider } from "@/app/hooks/useWorkspace";
import { NavTrailProvider } from "@/app/hooks/useNavTrail";
import { ActionProvider } from "@/app/hooks/useActions";
import NotificationActionsBridge from "@/app/components/actions/NotificationActionsBridge";
import PreferenceActionsBridge from "@/app/components/actions/PreferenceActionsBridge";

export default async function AppLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {

    const headerList = await headers();
    const cookie = headerList.get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) { // Sidebar expects the user object to not be null, so even though this auth check is handled by the proxy, we need to satisfy that condition. note to hunter: leave it as-is for now
        const pathname = headerList.get('x-pathname') ?? '/dashboard';
        redirect(`/auth/login?redirect=${encodeURIComponent(pathname)}`);
    }

    const { workspaces, activeWorkspaceId } = await getMyWorkspacesFromCookie(cookie);
    if (workspaces.length === 0) {
        redirect('/onboarding');
    }

    return (
        <WorkspaceProvider initialWorkspaces={workspaces} initialActiveId={activeWorkspaceId}>
            <NotificationProvider key={user.id} recipientId={user.id}>
                <NavTrailProvider>
                    <ActionProvider user={user}>
                        <NotificationActionsBridge />
                        <PreferenceActionsBridge />
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
                    </ActionProvider>
                </NavTrailProvider>
            </NotificationProvider>
        </WorkspaceProvider>
    );
}
