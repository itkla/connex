'use client';

import { useEffect, useState, type ComponentType } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion, type Variants } from 'motion/react';
import {
    Bars3Icon,
    CheckCircleIcon,
    HomeIcon,
    MagnifyingGlassIcon,
    PlusIcon,
} from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import { springJiggle, springSnappy, springSmooth, easeOut, instant } from '@/app/lib/motion';
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

/** Liquid-glass entrance: the pill rises and settles, then staggers its slots into place. */
const barVariants: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { ...springSmooth, staggerChildren: 0.045, delayChildren: 0.05 } },
};

/** Each slot pops in with a soft overshoot, matching the pill's liquid settle. */
const slotVariants: Variants = {
    hidden: { opacity: 0, y: 10, scale: 0.8 },
    show: { opacity: 1, y: 0, scale: 1, transition: springSnappy },
};

const slotBase =
    'group relative flex flex-1 flex-col items-center justify-center gap-1 rounded-2xl py-1 text-muted-foreground transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2 focus-visible:ring-offset-sidebar';

const indicatorWrap = 'relative flex h-7 w-12 items-center justify-center';

const labelBase = 'max-w-full truncate text-[10px] leading-none font-medium';

/**
 * The active destination's liquid-glass lens: a brand-tinted glass capsule that springs in behind the
 * icon and blurs out on exit, so navigating between destinations reads as the light re-settling rather
 * than a hard swap. Rendered inside an {@link AnimatePresence} so it can animate away.
 */
function ActiveLens({ reduce }: { reduce: boolean }) {
    return (
        <motion.span
            aria-hidden
            initial={reduce ? false : { opacity: 0, scale: 0.5, filter: 'blur(5px)' }}
            animate={{ opacity: 1, scale: 1, filter: 'blur(0px)' }}
            exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.6, filter: 'blur(5px)' }}
            transition={reduce ? instant : { default: springJiggle, filter: { duration: 0.2, ease: easeOut } }}
            className="absolute inset-0 rounded-full border border-white/25 bg-brand/25 shadow-[inset_0_1px_0_rgb(255_255_255/0.45),inset_0_-1px_2px_rgb(0_0_0/0.12)] backdrop-blur-sm"
        />
    );
}

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
            variants={reduce ? undefined : slotVariants}
            whileTap={reduce ? undefined : { scale: 0.9 }}
            transition={reduce ? instant : springSnappy}
        >
            <Link
                href={href}
                aria-label={label}
                aria-current={active ? 'page' : undefined}
                className={cn(slotBase, active ? 'text-brand-dark dark:text-brand' : 'hover:text-foreground')}
            >
                <span className={indicatorWrap}>
                    <AnimatePresence>{active && <ActiveLens key="lens" reduce={reduce} />}</AnimatePresence>
                    <Icon className="relative z-10 size-6" />
                    {badge != null && badge > 0 && (
                        <span
                            aria-hidden
                            className="absolute z-10 -top-1 right-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] leading-none font-semibold text-white ring-2 ring-sidebar"
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
            variants={reduce ? undefined : slotVariants}
            whileTap={reduce ? undefined : { scale: 0.9 }}
            transition={reduce ? instant : springSnappy}
            className={cn(slotBase, 'hover:text-foreground')}
        >
            <span className={indicatorWrap}>
                <Icon className="relative z-10 size-6" />
            </span>
            <span className={labelBase}>{caption}</span>
        </motion.button>
    );
}

/**
 * Mobile-only floating action bar spanning the viewport width (inset by the shell padding): Home ·
 * Search · New (center liquid-glass circle) · Tasks · More. Hidden at the `md` breakpoint where the
 * desktop sidebar takes over. The whole surface is an Apple-Liquid-Glass-inspired capsule (web
 * approximation): translucent, blurred, saturation-boosted, with a specular rim. "New" and "Search"
 * drive the shared Quick Create launcher and the command palette via app-shell custom events; "More"
 * opens the existing sidebar drawer. Sits below all overlay backdrops (z-30) so an open sheet/palette
 * covers it, and respects the device safe-area inset. All motion collapses under reduced-motion.
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
                initial={reduce ? false : 'hidden'}
                animate={reduce ? undefined : 'show'}
                variants={reduce ? undefined : barVariants}
                style={{
                    WebkitBackdropFilter: 'blur(14px) saturate(1.8)',
                    backdropFilter: 'url(#connex-liquid-glass) blur(1.5px) saturate(1.8)',
                }}
                className="pointer-events-auto relative flex w-full items-stretch gap-0.5 overflow-visible rounded-full border border-white/40 bg-sidebar/45 px-1.5 py-1.5 shadow-[inset_0_1.5px_0.5px_rgb(255_255_255/0.9),inset_0_-1px_1.5px_rgb(0_0_0/0.12),inset_0_0_20px_rgb(255_255_255/0.12),0_18px_44px_-12px_rgb(0_0_0/0.5),0_3px_10px_-3px_rgb(0_0_0/0.26)]"
            >
                <svg aria-hidden className="pointer-events-none absolute h-0 w-0">
                    <filter
                        id="connex-liquid-glass"
                        x="-20%"
                        y="-20%"
                        width="140%"
                        height="140%"
                        colorInterpolationFilters="sRGB"
                    >
                        <feTurbulence
                            type="fractalNoise"
                            baseFrequency="0.006 0.007"
                            numOctaves={2}
                            seed={14}
                            result="noise"
                        />
                        <feGaussianBlur in="noise" stdDeviation={1.5} result="soft" />
                        <feDisplacementMap
                            in="SourceGraphic"
                            in2="soft"
                            scale={56}
                            xChannelSelector="R"
                            yChannelSelector="G"
                        />
                    </filter>
                </svg>
                <span
                    aria-hidden
                    className="pointer-events-none absolute inset-x-8 top-px h-px rounded-full bg-gradient-to-r from-transparent via-white/60 to-transparent"
                />

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
                    variants={reduce ? undefined : slotVariants}
                    whileTap={reduce ? undefined : { scale: 0.88 }}
                    transition={reduce ? instant : springJiggle}
                    aria-label={tActions('quickCreate.trigger')}
                    className="group flex flex-1 items-center justify-center rounded-2xl focus-visible:outline-none"
                >
                    <span className="relative flex size-11 items-center justify-center overflow-hidden rounded-full border border-white/30 bg-brand/80 text-brand-foreground shadow-[inset_0_1px_1px_rgb(255_255_255/0.6),inset_0_-3px_6px_rgb(0_0_0/0.2),0_4px_12px_-5px_rgb(from_var(--color-brand)_r_g_b_/_0.45)] backdrop-blur-md transition-colors group-hover:bg-brand-hover/85 group-active:bg-brand-hover/85 group-focus-visible:ring-2 group-focus-visible:ring-brand group-focus-visible:ring-offset-2 group-focus-visible:ring-offset-sidebar">
                        <span
                            aria-hidden
                            className="pointer-events-none absolute inset-x-0 top-0 h-1/2 bg-gradient-to-b from-white/55 to-transparent"
                        />
                        <span
                            aria-hidden
                            className="pointer-events-none absolute -bottom-1/3 left-1/2 h-2/3 w-2/3 -translate-x-1/2 rounded-full bg-white/10 blur-md"
                        />
                        <PlusIcon className="relative z-10 size-6" />
                    </span>
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
