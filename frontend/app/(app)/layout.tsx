import type { Viewport } from "next";
import { Suspense } from "react";
import Sidebar from "@/app/components/Sidebar";
import SidebarFallback from "@/app/components/SidebarFallback";
import ContentShell from "@/app/components/ContentShell";
import {
    DEFAULT_CAPABILITIES,
    getCapabilities,
    getCurrentUserFromCookie,
    getEffectivePermissionsResultFromCookie,
    getMyWorkspacesFromCookie,
} from "@/app/lib/api";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { NotificationProvider } from "@/app/hooks/useNotifications";
import { NowProvider } from "@/app/hooks/useNow";
import { PermissionsProvider } from "@/app/hooks/usePermissions";
import { WorkspaceProvider } from "@/app/hooks/useWorkspace";
import { NavTrailProvider } from "@/app/hooks/useNavTrail";
import { ActionProvider } from "@/app/hooks/useActions";
import { PinnedViewsProvider } from "@/app/hooks/usePinnedViews";
import { RecentRecordsProvider } from "@/app/hooks/useRecentRecords";
import NotificationActionsBridge from "@/app/components/actions/NotificationActionsBridge";
import PreferenceActionsBridge from "@/app/components/actions/PreferenceActionsBridge";
import PinnedViewsActionsBridge from "@/app/components/actions/PinnedViewsActionsBridge";
import RecentRecordsActionsBridge from "@/app/components/actions/RecentRecordsActionsBridge";
import DraftResumeBridge from "@/app/components/DraftResumeBridge";
import { SidebarModeProvider } from "@/app/hooks/useSidebarMode";
import NavActionsBridge from "@/app/components/actions/NavActionsBridge";
import { resolveNavAccess } from "@/app/lib/navAccess";
import { requestNow } from "@/app/lib/requestClock";
import { localePreferenceFromCookieHeader, resolveLocale } from "@/i18n/config";

const SIDEBAR_SURFACE_CLASS = "bg-sidebar h-full rounded-xl border border-sidebar-border shadow-xl";

/** `viewportFit: cover` lets `env(safe-area-inset-*)` resolve to real values on notched devices, which the mobile bottom bar relies on. Scoped to the app shell so marketing/auth pages keep the default. */
export const viewport: Viewport = {
    viewportFit: "cover",
};

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

    const [capabilities, permissionsResult] = await Promise.all([
        getCapabilities(cookie ? { headers: { cookie } } : {}).catch(() => DEFAULT_CAPABILITIES),
        getEffectivePermissionsResultFromCookie(cookie),
    ]);
    const effectivePermissions = permissionsResult.ok ? permissionsResult.data : [];
    const navAccess = resolveNavAccess(capabilities, effectivePermissions);

    return (
        <NowProvider value={requestNow()}>
            <PermissionsProvider permissions={effectivePermissions}>
                <WorkspaceProvider initialWorkspaces={workspaces} initialActiveId={activeWorkspaceId}>
                    <NotificationProvider key={user.id} recipientId={user.id}>
                        <NavTrailProvider>
                            <ActionProvider user={user}>
                                <NotificationActionsBridge />
                                <PreferenceActionsBridge
                                    userLocale={resolveLocale(user.locale)}
                                    cookieLocale={localePreferenceFromCookieHeader(cookie)}
                                />
                                <DraftResumeBridge />
                                <NavActionsBridge navAccess={navAccess} />
                                <PinnedViewsProvider>
                                    <PinnedViewsActionsBridge />
                                    <RecentRecordsProvider>
                                        <RecentRecordsActionsBridge />
                                        <SidebarModeProvider>
                                            <ContentShell
                                                sidebar={
                                                    <Suspense fallback={<SidebarFallback className={SIDEBAR_SURFACE_CLASS} />}>
                                                        <Sidebar
                                                            user={user}
                                                            navAccess={navAccess}
                                                            className={SIDEBAR_SURFACE_CLASS}
                                                        />
                                                    </Suspense>
                                                }
                                            >
                                                {children}
                                            </ContentShell>
                                        </SidebarModeProvider>
                                    </RecentRecordsProvider>
                                </PinnedViewsProvider>
                            </ActionProvider>
                        </NavTrailProvider>
                    </NotificationProvider>
                </WorkspaceProvider>
            </PermissionsProvider>
        </NowProvider>
    );
}
