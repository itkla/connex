'use client';

import { useEffect, useState, type ComponentType } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';
import {
    Bars3Icon,
    CheckCircleIcon,
    HomeIcon,
    MagnifyingGlassIcon,
    PlusIcon,
} from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import { springJiggle, instant } from '@/app/lib/motion';
import { getTaskSummary } from '@/app/lib/api';
import { useIsMobile } from '@/app/hooks/useIsMobile';

/** Dispatches the app-shell custom event that the always-mounted GlobalSearch listens for. */
function openSearch() {
    window.dispatchEvent(new CustomEvent('connex:open-search'));
}

/** Dispatches the app-shell custom event that the always-mounted QuickCreateLauncher listens for. */
function openQuickCreate() {
    window.dispatchEvent(new CustomEvent('connex:open-quick-create'));
}

function BarLink({
    href,
    label,
    Icon,
    active,
    badge,
    badgeLabel,
}: {
    href: string;
    label: string;
    Icon: ComponentType<{ className?: string }>;
    active: boolean;
    badge?: number;
    badgeLabel?: string;
}) {
    return (
        <Link
            href={href}
            aria-current={active ? 'page' : undefined}
            className={cn(
                'relative flex min-h-14 flex-1 flex-col items-center justify-center gap-1 rounded-lg text-[11px] font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand',
                active ? 'text-brand-dark' : 'text-muted-foreground',
            )}
        >
            <span className="relative">
                <Icon className="size-6" />
                {badge != null && badge > 0 && (
                    <span
                        aria-hidden
                        className="absolute -top-1.5 -right-2 min-w-4 rounded-full bg-destructive px-1 text-[10px] leading-4 font-semibold text-white"
                    >
                        {badge > 99 ? '99+' : badge}
                    </span>
                )}
            </span>
            <span className="max-w-full truncate">{label}</span>
            {badge != null && badge > 0 && badgeLabel && <span className="sr-only">{badgeLabel}</span>}
        </Link>
    );
}

function BarButton({
    label,
    Icon,
    onClick,
}: {
    label: string;
    Icon: ComponentType<{ className?: string }>;
    onClick: () => void;
}) {
    return (
        <button
            type="button"
            onClick={onClick}
            className="flex min-h-14 flex-1 flex-col items-center justify-center gap-1 rounded-lg text-[11px] font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
        >
            <Icon className="size-6" />
            <span className="max-w-full truncate">{label}</span>
        </button>
    );
}

/**
 * Mobile-only bottom action bar: Home · Search · New (prominent center) · Tasks · More. Hidden at the
 * `md` breakpoint (the desktop sidebar takes over). "New" and "Search" drive the shared Quick Create
 * launcher and the command palette via app-shell custom events; "More" opens the existing sidebar
 * drawer. Sits below all overlay backdrops (z-30) so an open sheet/palette covers it, and respects the
 * device safe-area inset.
 *
 * @param onOpenMore - opens the full sidebar drawer (workspace/account/all destinations)
 */
export default function MobileBottomBar({ onOpenMore }: { onOpenMore: () => void }) {
    const pathname = usePathname();
    const reduce = useReducedMotion() ?? false;
    const isMobile = useIsMobile();
    const tNav = useTranslations('CommonSidebar');
    const tActions = useTranslations('Actions');
    const t = useTranslations('MobileNav');

    const [attention, setAttention] = useState(0);
    useEffect(() => {
        if (!isMobile) return;
        let active = true;
        getTaskSummary()
            .then((summary) => {
                if (active) setAttention(summary.overdue + summary.dueSoon);
            })
            .catch(() => {});
        return () => {
            active = false;
        };
    }, [pathname, isMobile]);

    const onDashboard = pathname === '/dashboard';
    const onTasks = pathname === '/activity/tasks' || pathname.startsWith('/activity/tasks/');

    return (
        <nav
            aria-label={t('barLabel')}
            className="fixed inset-x-0 bottom-0 z-30 border-t border-sidebar-border bg-sidebar/95 pb-[env(safe-area-inset-bottom)] backdrop-blur md:hidden"
        >
            <div className="mx-auto flex max-w-lg items-stretch gap-0.5 px-2 py-1.5">
                <BarLink href="/dashboard" label={tNav('navDashboard')} Icon={HomeIcon} active={onDashboard} />
                <BarButton label={tActions('palette.trigger')} Icon={MagnifyingGlassIcon} onClick={openSearch} />

                <div className="flex flex-1 items-center justify-center">
                    <motion.button
                        type="button"
                        onClick={openQuickCreate}
                        whileTap={reduce ? undefined : { scale: 0.92 }}
                        transition={reduce ? instant : springJiggle}
                        aria-label={tActions('quickCreate.trigger')}
                        className="-mt-5 flex size-14 items-center justify-center rounded-full bg-brand text-brand-foreground shadow-lg ring-4 ring-sidebar transition-colors hover:bg-brand-hover focus-visible:outline-none focus-visible:ring-brand"
                    >
                        <PlusIcon className="size-7" />
                    </motion.button>
                </div>

                <BarLink
                    href="/activity/tasks"
                    label={tNav('navTasks')}
                    Icon={CheckCircleIcon}
                    active={onTasks}
                    badge={attention}
                    badgeLabel={t('tasksBadge', { count: attention })}
                />
                <BarButton label={t('more')} Icon={Bars3Icon} onClick={onOpenMore} />
            </div>
        </nav>
    );
}
