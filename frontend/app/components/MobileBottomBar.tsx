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
import { springJiggle, springSnappy, instant } from '@/app/lib/motion';
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

const slotBase =
    'group flex flex-1 flex-col items-center justify-center gap-1 rounded-2xl py-1 text-muted-foreground transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand';

const indicatorBase = 'relative flex h-7 w-12 items-center justify-center rounded-full transition-colors';

const labelBase = 'max-w-full truncate text-[10px] leading-none font-medium';

function BarLink({
    href,
    label,
    caption,
    Icon,
    active,
    reduce,
    badge,
    badgeLabel,
}: {
    href: string;
    label: string;
    caption: string;
    Icon: ComponentType<{ className?: string }>;
    active: boolean;
    reduce: boolean;
    badge?: number;
    badgeLabel?: string;
}) {
    return (
        <motion.div
            className="flex flex-1"
            whileTap={reduce ? undefined : { scale: 0.9 }}
            transition={reduce ? instant : springSnappy}
        >
            <Link
                href={href}
                aria-label={label}
                aria-current={active ? 'page' : undefined}
                className={cn(slotBase, active ? 'text-brand-dark dark:text-brand' : 'hover:text-foreground')}
            >
                <span className={cn(indicatorBase, active && 'bg-brand-light')}>
                    <Icon className="size-6" />
                    {badge != null && badge > 0 && (
                        <span
                            aria-hidden
                            className="absolute -top-1 right-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] leading-none font-semibold text-white ring-2 ring-sidebar"
                        >
                            {badge > 99 ? '99+' : badge}
                        </span>
                    )}
                </span>
                <span className={labelBase}>{caption}</span>
                {badge != null && badge > 0 && badgeLabel && <span className="sr-only">{badgeLabel}</span>}
            </Link>
        </motion.div>
    );
}

function BarButton({
    label,
    caption,
    Icon,
    onClick,
    reduce,
}: {
    label: string;
    caption: string;
    Icon: ComponentType<{ className?: string }>;
    onClick: () => void;
    reduce: boolean;
}) {
    return (
        <motion.button
            type="button"
            onClick={onClick}
            aria-label={label}
            whileTap={reduce ? undefined : { scale: 0.9 }}
            transition={reduce ? instant : springSnappy}
            className={cn(slotBase, 'hover:text-foreground')}
        >
            <span className={indicatorBase}>
                <Icon className="size-6" />
            </span>
            <span className={labelBase}>{caption}</span>
        </motion.button>
    );
}

/**
 * Mobile-only floating action bar spanning the viewport width (inset by the shell padding): Home ·
 * Search · New (brand-filled) · Tasks · More, each an icon with a super-small caption. Hidden at the
 * `md` breakpoint where the desktop sidebar takes over. "New" and "Search" drive the shared Quick Create
 * launcher and the command palette via app-shell custom events; "More" opens the existing sidebar drawer.
 * Floats above the content with a glass surface and rounded-full shape, and sits below all overlay
 * backdrops (z-30) so an open sheet/palette covers it. Respects the device safe-area inset.
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
            className="pointer-events-none fixed inset-x-0 bottom-[calc(env(safe-area-inset-bottom)+0.75rem)] z-30 px-4 md:hidden"
        >
            <motion.div
                initial={reduce ? false : { y: 16, opacity: 0 }}
                animate={{ y: 0, opacity: 1 }}
                transition={reduce ? instant : springSnappy}
                className="pointer-events-auto flex w-full items-stretch gap-0.5 rounded-full border border-sidebar-border bg-sidebar/80 px-1.5 py-1.5 shadow-[0_1px_0_0_rgb(255_255_255/0.06)_inset,0_10px_30px_-8px_rgb(0_0_0/0.35)] backdrop-blur-xl"
            >
                <BarLink
                    href="/dashboard"
                    label={tNav('navDashboard')}
                    caption={tNav('navDashboard')}
                    Icon={HomeIcon}
                    active={onDashboard}
                    reduce={reduce}
                />
                <BarButton
                    label={tActions('palette.trigger')}
                    caption={t('search')}
                    Icon={MagnifyingGlassIcon}
                    onClick={openSearch}
                    reduce={reduce}
                />

                <motion.button
                    type="button"
                    onClick={openQuickCreate}
                    whileTap={reduce ? undefined : { scale: 0.9 }}
                    transition={reduce ? instant : springJiggle}
                    aria-label={tActions('quickCreate.trigger')}
                    className="group flex flex-1 flex-col items-center justify-center gap-1 rounded-2xl py-1 text-muted-foreground transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                >
                    <span className="flex h-7 w-12 items-center justify-center rounded-full bg-brand text-brand-foreground shadow-sm transition-colors group-hover:bg-brand-hover">
                        <PlusIcon className="size-6" />
                    </span>
                    <span className={labelBase}>{tActions('quickCreate.trigger')}</span>
                </motion.button>

                <BarLink
                    href="/activity/tasks"
                    label={tNav('navTasks')}
                    caption={tNav('navTasks')}
                    Icon={CheckCircleIcon}
                    active={onTasks}
                    reduce={reduce}
                    badge={attention}
                    badgeLabel={t('tasksBadge', { count: attention })}
                />
                <BarButton
                    label={t('more')}
                    caption={t('more')}
                    Icon={Bars3Icon}
                    onClick={onOpenMore}
                    reduce={reduce}
                />
            </motion.div>
        </nav>
    );
}
